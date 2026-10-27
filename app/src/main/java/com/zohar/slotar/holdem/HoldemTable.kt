package com.zohar.slotar.holdem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import com.zohar.slotar.Fx
import com.zohar.slotar.audio.SlotSounds
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.inverse
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import com.zohar.slotar.blackjack.BjLayout
import com.zohar.slotar.blackjack.Card
import com.zohar.slotar.blackjack.DealerRig
import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Voice
import com.zohar.slotar.blackjack.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hold'em spots on the shared table frame (tools/3d/blackjack/layout.js → hold). */
class HoldLayout(j: JSONObject) {
    private val h = j.getJSONObject("hold")
    private fun v(n: String) = h.getJSONArray(n).let { Float3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
    val dealerCards = v("dealerCards"); val board = v("board"); val boardDx = h.getDouble("boardDx").toFloat()
    val playerCards = v("playerCards"); val ante = v("ante"); val call = v("call"); val bonus = v("bonus")
}

/** Play-money wallet for the Hold'em app, kept on the phone. Free refill when it runs low. */
class HoldWallet(context: Context) : Wallet {
    private val prefs = context.getSharedPreferences("zohar_hold", Context.MODE_PRIVATE)
    override var credits: Long = prefs.getLong("credits", START)
        set(v) { field = v; prefs.edit().putLong("credits", v).apply() }
    companion object { const val START = 1000L }
}

enum class HUi { BETTING, BUSY, DECIDE }
enum class HSpot { ANTE, BONUS }

/** Casino Hold'em table: engine + everything shown on it. The engine decides first; the dealer only shows it, card by card. */
class HoldemTable(
    private val context: Context, private val engine: Engine, private val modelLoader: ModelLoader,
    private val sounds: SlotSounds, private val scope: CoroutineScope, private val onFx: (Fx) -> Unit,
    rng: Rng = SecureRng, wallet: Wallet? = null, speech: Boolean = true, private val anisotropy: Float = 8f,
) {
    private val layoutJson = JSONObject(context.assets.open("bj_layout.json").bufferedReader().readText())
    val L = BjLayout(layoutJson)
    val H = HoldLayout(layoutJson)
    val wallet: Wallet = wallet ?: HoldWallet(context)
    val game = HoldemEngine(this.wallet, rng)
    val voice = Voice(context, speech) { dealer?.talking = it }

    // ---------------- UI state ----------------
    var ui by mutableStateOf(HUi.BETTING); private set
    var credits by mutableLongStateOf(this.wallet.credits); private set
    var ante by mutableLongStateOf(0L); private set
    var bonus by mutableLongStateOf(0L); private set
    var target by mutableStateOf(HSpot.ANTE)
    var chipValue by mutableLongStateOf(25L)
    private var lastAnte = 10L; private var lastBonus = 0L
    val staked get() = ante + bonus
    /** You need room for the Call (2 × ante) on top of the ante and the bonus. */
    fun canDeal() = ui == HUi.BETTING && ante >= game.minAnte && ante * 3 + bonus <= wallet.credits
    var line by mutableStateOf("Place your ante"); private set
    var hands by mutableStateOf(""); private set                       // "You: pair of kings · Dealer: ?"
    var outcome by mutableStateOf(""); private set
    var hint by mutableStateOf<String?>(null); private set
    private var hintJob: Job? = null
    val rounds get() = roundsPlayed
    private var roundsPlayed = 0

    fun tapFelt(x: Float, z: Float): Boolean {
        if (ui != HUi.BETTING) return false
        fun near(p: Float3, r: Float) = (x - p.x) * (x - p.x) + (z - p.z) * (z - p.z) <= r * r
        val spot = when { near(H.bonus, 0.04f) -> HSpot.BONUS; near(H.ante, 0.06f) -> HSpot.ANTE; else -> return false }
        target = spot; addChip(chipValue); return true
    }

    // ---------------- scene ----------------
    var frame: Node? = null; private set
    var dealer: DealerRig? = null; private set
    private val cardPool = ArrayList<CardView>()
    private val chipPool = HashMap<Int, ArrayList<ModelNode>>()
    val cardsOnTable = ArrayList<CardView>()
    private val stacks = ArrayList<StackView>()
    private val tex = HashMap<String, Texture>()
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE).apply { if (this@HoldemTable.anisotropy > 1f) anisotropy = this@HoldemTable.anisotropy }
    private var now = 0.0
    private var job: Job? = null
    var cameraWorld: FloatArray? = null

    fun attach(frame: Node, dealerNode: ModelNode?) {
        if (this.frame === frame && dealer?.node === dealerNode) return
        this.frame = frame; dealer = dealerNode?.let { DealerRig(engine, it) }
        Log.i("ZoharHE", "attached dealerOk=${dealer?.ok}")
        // opening, like a real dealer starting a shift: clear the hands (palms, then backs, to the camera), then invite the player
        scope.launch {
            delay(900)
            if (dealer?.has("ClearHands") == true) { dealer?.play("ClearHands", now); delay(3100) }
            dealer?.play("Welcome", now); delay(500); say("Welcome to Casino Hold'em. Place your ante.")
        }
        idleJob?.cancel(); idleJob = scope.launch { idleWatch() }
    }

    // ================= per frame =================
    fun update(nowSec: Double, dt: Float) {
        now = nowSec
        val f = frame ?: return
        val inv = runCatching { inverse(f.worldTransform) }.getOrNull()
        dealer?.let { d ->
            d.lookAt = cameraWorld
            d.update(now, dt)
        }
        // the dealer's hand in table space (cards ride in it while he carries them)
        fun hand(left: Boolean): Float3? = dealer?.handWorld(left)?.let { w -> inv?.let { m -> (m * dev.romainguy.kotlin.math.Float4(w[0], w[1], w[2], 1f)).xyz } }
        for (c in cardsOnTable) c.update(now, ::hand)
        for (s in stacks) s.update(now)
    }


    // ================= betting =================
    fun addChip(v: Long) {
        touched()
        if (ui != HUi.BETTING) return
        when (target) {
            HSpot.ANTE -> {
                if (ante + v > game.maxAnte) { say("Ante maximum is ${game.maxAnte}."); return }
                if ((ante + v) * 3 + bonus > wallet.credits) { sounds.play("no_credits"); line = "Keep room for the call: 3 × the ante"; return }
                ante += v
            }
            HSpot.BONUS -> {
                if (bonus + v > game.maxBonus) { say("The AA Bonus goes up to ${game.maxBonus}."); return }
                if (ante * 3 + bonus + v > wallet.credits) { sounds.play("no_credits"); line = "Not enough play credits"; return }
                bonus += v
            }
        }
        sounds.play("chip"); showStacks()
    }
    fun clearBet() { if (ui != HUi.BETTING) return; touched(); ante = 0; bonus = 0; showStacks(); sounds.play("chips_stack") }
    fun rebet() {
        if (ui != HUi.BETTING) return
        ante = lastAnte.coerceAtMost(wallet.credits / 3); if (ante < game.minAnte) ante = 0
        bonus = if (ante * 3 + lastBonus <= wallet.credits) lastBonus else 0
        showStacks(); sounds.play("chips_stack")
    }
    fun canRefill() = ui == HUi.BETTING && wallet.credits < game.minAnte * 3
    fun refill() { if (!canRefill()) return; wallet.credits = HoldWallet.START; credits = wallet.credits; say("Here are a thousand fresh play credits. Good luck.") }
    private var anteStack: StackView? = null
    private var bonusStack: StackView? = null
    private var callStack: StackView? = null
    private fun showStacks() {
        anteStack?.let { removeStack(it) }; anteStack = null
        bonusStack?.let { removeStack(it) }; bonusStack = null
        if (ante > 0) anteStack = stack(ante, Float3(H.ante.x, L.feltY, H.ante.z))
        if (bonus > 0) bonusStack = stack(bonus, Float3(H.bonus.x, L.feltY, H.bonus.z))
        credits = wallet.credits - staked
    }

    // ================= the round =================
    fun deal() {
        touched()
        if (!canDeal()) { if (ante in 1 until game.minAnte) say("Minimum ante is ${game.minAnte}."); return }
        seq {
            lastAnte = ante; lastBonus = bonus
            game.deal(ante, bonus)
            roundsPlayed++
            credits = wallet.credits - game.bonusReturned; outcome = ""; hands = ""; hint = null
            sounds.play("shuffle"); say("Good luck.")
            val p = game.player; val d = game.dealer
            dealTo(p[0], playerSpot(0), true, Owner.PLAYER)
            dealTo(d[0], dealerSpot(0), false, Owner.DEALER)
            dealTo(p[1], playerSpot(1), true, Owner.PLAYER)
            dealTo(d[1], dealerSpot(1), false, Owner.DEALER)
            wait(0.3); say("The flop.", flush = false)
            for (i in 0 until 3) dealTo(game.board[i], boardSpot(i), true, Owner.BOARD)
            val v = Poker.eval5(p + game.board)
            hands = "You: ${describe(v)}"
            // AA Bonus is settled on the flop
            bonusStack?.let { s ->
                bonusStack = null
                if (game.bonusReturned > 0) {
                    val pays = HoldemPays.bonus(game.bonusHand!!)!!
                    clip("Pay"); waitUntil(0.6); payStack(s, game.bonusReturned - game.bonus); sounds.play("win_small"); onFx(Fx.COINS)
                    say("AA Bonus: ${describe(game.bonusHand!!)}! $pays to 1.", flush = false); wait(1.3)
                    takeHome(s); wait(0.8); removeStack(s)
                } else { clip("Collect"); waitUntil(0.45); collectStack(s); wait(0.4); removeStack(s) }
                credits = wallet.credits
            }
            say(if (v.cat >= Cat.PAIR) "You have ${describe(v)}. Call or fold?" else "${describe(v)}. Call or fold?")
            decide()
        }
    }

    private fun decide() {
        ui = HUi.DECIDE
        // the hint: deal out the unknown cards a few thousand times in the background
        val p = game.player.toList(); val f = game.board.take(3)
        hintJob?.cancel(); hintJob = scope.launch {
            val v = withContext(Dispatchers.Default) { holdemCallValue(p, f, 2000) }
            hint = if (v > -1.0) "Call  (worth ${"%+.2f".format(v)} antes; fold = −1)" else "Fold  (call worth ${"%+.2f".format(v)} antes)"
        }
    }

    fun call() {
        touched()
        if (ui != HUi.DECIDE || !game.canCall()) return
        seq {
            hintJob?.cancel(); hint = null
            game.call()
            val r = game.result!!
            sounds.play("chips_stack")
            callStack = stack(r.call, Float3(H.call.x, L.feltY, H.call.z + 0.25f)).also { it.moveTo(now, Float3(H.call.x, L.feltY, H.call.z), 0.45) }
            credits = wallet.credits - r.returned + r.bonusReturned
            say("Call.", flush = false); wait(0.6)
            dealTo(game.board[3], boardSpot(3), true, Owner.BOARD)
            dealTo(game.board[4], boardSpot(4), true, Owner.BOARD)
            showdown()
        }
    }

    fun fold() {
        touched()
        if (ui != HUi.DECIDE) return
        seq {
            hintJob?.cancel(); hint = null
            game.fold()
            say("Fold.")
            anteStack?.let { s -> clip("Collect"); waitUntil(0.45); collectStack(s); removeStack(s); anteStack = null }
            // like a live table, the rest of the cards are shown
            dealTo(game.board[3], boardSpot(3), true, Owner.BOARD)
            dealTo(game.board[4], boardSpot(4), true, Owner.BOARD)
            revealDealer()
            val r = game.result!!
            hands = "You: ${describe(r.player!!)}  ·  Dealer: ${describe(r.dealer!!)}"
            outcome = "FOLD −${r.ante}"
            say("Dealer had ${describe(r.dealer)}.", flush = false)
            wait(2.0); clearTable()
        }
    }

    private suspend fun Seq.revealDealer() {
        clip("Flip"); waitUntil(0.35)
        for (c in cardsOnTable) if (c.owner == Owner.DEALER && !c.faceUp) c.flipUp(now, 0.4)
        sounds.play("card_flip"); wait(0.9)
    }

    private suspend fun Seq.showdown() {
        val r = game.result!!
        revealDealer()
        hands = "You: ${describe(r.player!!)}  ·  Dealer: ${describe(r.dealer!!)}"
        say(if (r.dealerQualifies) "Dealer has ${describe(r.dealer)}." else "Dealer has ${describe(r.dealer)}. Dealer does not qualify.", flush = false)
        wait(1.4)
        val a = anteStack; val c = callStack; anteStack = null; callStack = null
        val antePays = HoldemPays.ante(r.player.cat)
        when (r.outcome) {
            HOutcome.LOSE -> {
                clip("Collect"); waitUntil(0.45); a?.let { collectStack(it) }; c?.let { collectStack(it) }; wait(0.5)
                dealer?.smile = 0.15f; say("Dealer wins. Next hand.")
            }
            HOutcome.PUSH -> { dealer?.play("Nod", now); say("Same hand. Push.") ; wait(1.0) }
            HOutcome.DEALER_NOT_QUALIFIED -> {
                clip("Pay"); waitUntil(0.6); a?.let { payStack(it, r.anteReturned - r.ante) }; sounds.play("chips_stack"); onFx(Fx.COINS)
                dealer?.smile = 0.8f; say("Ante pays $antePays to 1. Your call is returned."); wait(1.2)
            }
            HOutcome.WIN -> {
                clip("Pay"); waitUntil(0.6); a?.let { payStack(it, r.anteReturned - r.ante) }; c?.let { payStack(it, r.callReturned - r.call) }
                sounds.play("chips_stack"); onFx(Fx.COINS); if (antePays >= 10) { onFx(Fx.BIG_WIN); sounds.play("big_win"); dealer?.play("Clap", now) }
                dealer?.smile = 1f; say(if (antePays > 1) "You win with ${describe(r.player)}! Ante pays $antePays to 1." else "You win!"); wait(1.4)
            }
            HOutcome.FOLD -> {}
        }
        val net = r.returned - r.staked
        outcome = when { net > 0 -> "YOU WIN $net"; net == 0L -> "PUSH"; else -> "LOSE ${-net}" }
        Log.i("ZoharHE", "round $roundsPlayed: ${r.outcome} player=${r.player.cat} dealer=${r.dealer.cat} q=${r.dealerQualifies} net=$net credits=${wallet.credits}")
        credits = wallet.credits
        wait(1.6)
        listOfNotNull(a, c).forEach { takeHome(it) }
        wait(0.7); listOfNotNull(a, c).forEach { removeStack(it) }
        clearTable()
    }

    private suspend fun Seq.clearTable() {
        clip("Clear"); waitUntil(0.45)
        for (cv in cardsOnTable) cv.toDiscard(now, Float3(L.discard.x, L.feltY + 0.02f, L.discard.z)); sounds.play("card_slide")
        wait(1.0)
        for (cv in cardsOnTable) release(cv); cardsOnTable.clear()
        anteStack?.let { removeStack(it) }; anteStack = null; callStack?.let { removeStack(it) }; callStack = null
        dealer?.smile = 0f; hands = ""
        ante = 0; bonus = 0; credits = wallet.credits; ui = HUi.BETTING; line = "Place your ante"
        if (wallet.credits >= lastAnte * 3 + lastBonus) rebet()
    }

    private fun recover() {
        when (game.phase) {
            HPhase.DECIDE -> decide()
            else -> {
                for (c in cardsOnTable) release(c); cardsOnTable.clear()
                for (s in stacks.toList()) removeStack(s); anteStack = null; bonusStack = null; callStack = null
                ante = 0; bonus = 0; credits = wallet.credits; ui = HUi.BETTING; line = "Place your ante"
            }
        }
    }

    // ================= words =================
    private fun rankName(r: Int, plural: Boolean) = when (r) { 14 -> if (plural) "aces" else "ace"; 13 -> if (plural) "kings" else "king"; 12 -> if (plural) "queens" else "queen"; 11 -> if (plural) "jacks" else "jack"; else -> if (plural) "${r}s" else "$r" }
    fun describe(v: HandValue): String {
        val k0 = ((v.score shr 16) and 0xF).toInt(); val k1 = ((v.score shr 12) and 0xF).toInt()
        return when (v.cat) {
            Cat.ROYAL -> "a royal flush"; Cat.STRAIGHT_FLUSH -> "a straight flush"; Cat.QUADS -> "four ${rankName(k0, true)}"
            Cat.FULL_HOUSE -> "a full house"; Cat.FLUSH -> "a flush"; Cat.STRAIGHT -> "a straight"
            Cat.TRIPS -> "three ${rankName(k0, true)}"; Cat.TWO_PAIR -> "two pair, ${rankName(k0, true)} and ${rankName(k1, true)}"
            Cat.PAIR -> "a pair of ${rankName(k0, true)}"; Cat.HIGH -> "${rankName(k0, false)} high"
        }
    }

    // ================= layout =================
    data class Spot(val pos: Float3, val yaw: Float, val lift: Float)
    fun playerSpot(k: Int) = Spot(Float3(H.playerCards.x + (k - 0.5f) * 0.072f, L.feltY, H.playerCards.z), 0f, k * 0.0004f)
    fun dealerSpot(k: Int) = Spot(Float3(H.dealerCards.x + (k - 0.5f) * 0.072f, L.feltY, H.dealerCards.z), 0f, k * 0.0004f)
    fun boardSpot(i: Int) = Spot(Float3(H.board.x + (i - 2) * H.boardDx, L.feltY, H.board.z), 0f, 0f)

    // ================= cards =================
    enum class Owner { PLAYER, DEALER, BOARD }
    private fun obtainCard(card: Card, owner: Owner): CardView {
        val f = frame!!
        val cv = cardPool.removeLastOrNull() ?: CardView(ModelNode(modelLoader.createModelInstance("models/bj_card.glb"), autoAnimate = false)).also { f.addChildNode(it.node); it.bindBack(texture("back")) }
        cv.node.isVisible = false
        cv.bindFace(texture(card.id)); cv.card = card; cv.owner = owner
        return cv
    }
    private fun release(c: CardView) { c.node.isVisible = false; c.card = null; cardPool.add(c) }

    // ================= choreography helpers =================
    /** Coroutine runner: the whole sequence runs on the main thread, frame updates animate in between. */
    private fun seq(block: suspend Seq.() -> Unit) {
        if (job?.isActive == true) return
        ui = HUi.BUSY
        job = scope.launch {
            try { Seq().block() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("ZoharHE", "sequence failed", e)
                recover()
            }
            // a sequence must always hand the table back to the player; never leave it BUSY
            if (ui == HUi.BUSY) { Log.w("ZoharHE", "sequence ended BUSY in phase ${game.phase}: recovering"); recover() }
        }
    }
    inner class Seq {
        private var mark = 0.0
        suspend fun wait(sec: Double) { delay((sec * 1000).toLong()) }
        fun clip(name: String) { dealer?.play(name, now); mark = now }
        /** wait until `sec` after the last clip started */
        suspend fun waitUntil(sec: Double) { val t = mark + sec - now; if (t > 0) delay((t * 1000).toLong()) }
        suspend fun dealTo(card: Card, spot: Spot, faceUp: Boolean, owner: Owner) {
            val d = dealer
            val clip = when { owner == Owner.DEALER -> "DealDealer"; spot.pos.x < -0.09f -> "DealPlayerL"; spot.pos.x > 0.09f -> "DealPlayerR"; else -> "DealPlayer" }
            d?.play(clip, now); mark = now
            val cv = obtainCard(card, owner)
            cv.deal(now, Float3(L.shoe.x, L.feltY + 0.012f, L.shoe.z), spot, faceUp, spot.yaw)
            cardsOnTable.add(cv)
            waitUntil(0.82); sounds.play("card_slide", 0.8f)
            waitUntil(1.0)
        }
        suspend fun say(text: String, flush: Boolean = true) = this@HoldemTable.say(text, flush)
        suspend fun collectStack(s: StackView) {
            val pos = Float3(L.rack.x + (s.base.x - L.rack.x) * 0.2f, L.feltY - 0.01f, L.rack.z)
            s.moveTo(now, pos, 0.6); delay(650); s.hide()
            sounds.play("chips_stack", 0.6f)
        }
        suspend fun payStack(s: StackView, win: Long) {
            if (win <= 0) return
            val from = Float3(L.rack.x - 0.1f, L.feltY, L.rack.z + 0.04f)
            val pay = stack(win, from); pay.moveTo(now, Float3(s.base.x + 0.05f, L.feltY, s.base.z), 0.6); s.extra = pay
            delay(650)
        }
        fun takeHome(s: StackView) {
            val home = Float3(s.base.x, L.feltY, s.base.z + 0.28f)
            if (s.visible) s.moveTo(now, home, 0.7); s.extra?.let { if (it.visible) it.moveTo(now, home, 0.7) }
        }
    }
    var lineAt by mutableLongStateOf(0L); private set                 // when the dealer last spoke (for the speech bubble)
    private fun say(text: String, flush: Boolean = true) { line = text; lineAt = android.os.SystemClock.uptimeMillis(); voice.say(text, flush) }

    // ---- if nobody plays for 3 minutes, the dealer makes a light joke (never pressure to bet) ----
    private var idleJob: Job? = null
    private var lastTouch = android.os.SystemClock.uptimeMillis()
    private var idleLine = 0
    fun touched() { lastTouch = android.os.SystemClock.uptimeMillis() }
    private val idleLines = listOf(
        "These cards are getting cold... I'm going to start talking to the shoe.",
        "Take your time. I'll just practice my shuffle.",
        "Don't worry, the chips don't bite. Mostly.",
        "I've counted these chips twice now. Still the same number.",
    )
    private suspend fun idleWatch() {
        while (true) {
            delay(5000)
            val t = android.os.SystemClock.uptimeMillis()
            if (ui == HUi.BETTING && t - lastTouch > 180_000) {
                dealer?.play(if (idleLine % 2 == 0) "Talk" else "Shrug", now); dealer?.smile = 0.7f
                say(idleLines[idleLine % idleLines.size]); idleLine++
                lastTouch = t                                                  // next joke after another 3 minutes
                delay(4000); dealer?.smile = 0f
            }
        }
    }
    private fun texture(id: String): Texture = tex.getOrPut(id) {
        Log.i("ZoharHE", "step texture $id")
        val src = context.assets.open("cards/$id.jpg").use { BitmapFactory.decodeStream(it) }
        // the card mesh comes from three.js (UV v-up): upload the rows flipped
        val m = android.graphics.Matrix().apply { setScale(1f, -1f); postTranslate(0f, src.height.toFloat()) }
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).copy(Bitmap.Config.ARGB_8888, false)
        val levels = 1 + (Math.log(max(bmp.width, bmp.height).toDouble()) / Math.log(2.0)).toInt()
        val t = Texture.Builder().width(bmp.width).height(bmp.height).levels(levels).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8).build(engine)
        // mip levels made on the CPU (sharp cards at a glancing angle); no glGenerateMipmap needed
        var lv = bmp
        for (i in 0 until levels) {
            if (i > 0) lv = Bitmap.createScaledBitmap(lv, max(1, lv.width / 2), max(1, lv.height / 2), true)
            TextureHelper.setBitmap(engine, t, i, lv)
        }
        t
    }


    inner class CardView(val node: ModelNode) {
        var card: Card? = null; var owner = Owner.PLAYER
        var faceUp = true; private set
        private val asset = node.modelInstance.asset
        private val rm = engine.renderableManager
        private fun mat(name: String) = asset.getFirstEntityByName(name).takeIf { it != 0 && rm.hasComponent(it) }?.let { rm.getMaterialInstanceAt(rm.getInstance(it), 0) }
        fun bindFace(t: Texture) { runCatching { mat("CardFace")?.setParameter("baseColorMap", t, sampler) } }
        fun bindBack(t: Texture) { runCatching { mat("CardBack")?.setParameter("baseColorMap", t, sampler) } }
        // tween
        private var mode = 0                        // 0 still, 1 deal, 2 move, 3 flip
        private var t0 = 0.0; private var dur = 0.4
        private var from = Float3(); private var to = Float3(); private var yaw0 = 0f; private var yaw = 0f
        private var roll0 = 0f; private var roll1 = 0f; private var lift = 0f
        private var pos = Float3(); private var curYaw = 0f; private var curRoll = 0f
        fun deal(now: Double, shoe: Float3, spot: Spot, up: Boolean, yaw: Float) {
            mode = 1; t0 = now; from = shoe; to = spot.pos.copy(y = spot.pos.y + spot.lift + 0.0003f); yaw0 = -0.35f; this.yaw = yaw
            faceUp = up; roll0 = PI.toFloat(); roll1 = if (up) 0f else PI.toFloat(); lift = spot.lift
            pos = shoe; curRoll = roll0; curYaw = yaw0; apply()
        }
        fun moveTo(now: Double, spot: Spot) { mode = 2; t0 = now; dur = 0.45; from = pos; to = spot.pos.copy(y = spot.pos.y + spot.lift + 0.0003f); yaw0 = curYaw; yaw = spot.yaw; roll0 = curRoll; roll1 = curRoll }
        fun flipUp(now: Double, d: Double) { mode = 3; t0 = now; dur = d; from = pos; to = pos; yaw0 = curYaw; yaw = curYaw; roll0 = curRoll; roll1 = 0f; faceUp = true }
        fun toDiscard(now: Double, p: Float3) { mode = 2; t0 = now; dur = 0.55; from = pos; to = p; yaw0 = curYaw; yaw = PI.toFloat() / 2; roll0 = curRoll; roll1 = PI.toFloat() }
        fun update(now: Double, hand: (Boolean) -> Float3?) {
            when (mode) {
                1 -> {
                    // DealX clip timing: reach the shoe 0.30 s, pull 0.45, carry to the target 0.80, slide off the fingers until 1.02
                    val t = now - t0
                    node.isVisible = t >= 0.33
                    if (t < 0.45) { pos = from; curRoll = roll0; curYaw = yaw0 }
                    else if (t < 0.80) {
                        val h = hand(true)
                        val u = smooth(((t - 0.45) / 0.35).toFloat())
                        val base = lerp(from, Float3(to.x, to.y, to.z - 0.12f), u)
                        pos = if (h != null) lerp(base, Float3(h.x, max(L.feltY + 0.006f, h.y - 0.02f), h.z), 0.7f * sin(u * PI.toFloat()).coerceAtLeast(0f) + 0.3f * u).let { Float3(it.x, max(L.feltY + 0.004f, it.y), it.z) } else base
                        curYaw = yaw0 + (yaw - yaw0) * u; curRoll = roll0
                    } else if (t < 1.02) {
                        val u = smooth(((t - 0.80) / 0.22).toFloat())
                        val start = cachedSlideFrom ?: pos.also { cachedSlideFrom = it }
                        pos = lerp(start, to, u).let { it.copy(y = it.y + 0.03f * sin(u * PI.toFloat()) * (if (roll1 != roll0) 1f else 0.2f)) }
                        curRoll = roll0 + (roll1 - roll0) * u; curYaw = yaw
                    } else { pos = to; curRoll = roll1; curYaw = yaw; mode = 0; cachedSlideFrom = null }
                    apply()
                }
                2, 3 -> {
                    val u = smooth(((now - t0) / dur).toFloat())
                    pos = lerp(from, to, u).let { if (mode == 3) it.copy(y = it.y + 0.05f * sin(u * PI.toFloat())) else it.copy(y = it.y + 0.01f * sin(u * PI.toFloat())) }
                    curYaw = yaw0 + (yaw - yaw0) * u; curRoll = roll0 + (roll1 - roll0) * u
                    if (now - t0 >= dur) { pos = to; curRoll = roll1; curYaw = yaw; mode = 0 }
                    node.isVisible = true; apply()
                }
            }
        }
        private var cachedSlideFrom: Float3? = null
        private fun apply() {
            val q = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), Math.toDegrees(curYaw.toDouble()).toFloat()) * Quaternion.fromAxisAngle(Float3(0f, 0f, 1f), Math.toDegrees(curRoll.toDouble()).toFloat())
            node.transform = Transform(position = Position(pos.x, pos.y, pos.z), quaternion = q)
        }
    }


    // ================= chips =================
    private val DENOMS = longArrayOf(500, 100, 25, 5)
    private fun chipNode(v: Int): ModelNode {
        val pool = chipPool.getOrPut(v) { ArrayList() }
        return pool.removeLastOrNull() ?: run { Log.i("ZoharHE", "step chip model $v"); ModelNode(modelLoader.createModelInstance("models/bj_chip_$v.glb"), autoAnimate = false).also { frame!!.addChildNode(it) } }
    }
    fun stack(amount: Long, base: Float3): StackView {
        val chips = ArrayList<Pair<Int, ModelNode>>()
        var left = amount
        for (d in DENOMS) while (left >= d && chips.size < 24) { chips.add(d.toInt() to chipNode(d.toInt())); left -= d }
        val s = StackView(chips, base); stacks.add(s); s.layout(); return s
    }
    private fun addToStack(s: StackView, amount: Long) { val extra = stack(amount, Float3(s.base.x + 0.045f, L.feltY, s.base.z)); s.extra = extra }
    private fun removeStack(s: StackView) { s.extra?.let { removeStack(it) }; s.extra = null; for ((v, n) in s.chips) { n.isVisible = false; chipPool.getOrPut(v) { ArrayList() }.add(n) }; stacks.remove(s) }

    inner class StackView(val chips: List<Pair<Int, ModelNode>>, var base: Float3) {
        val base0 = base
        var extra: StackView? = null
        var visible = true; private set
        private var from = base; private var to = base; private var t0 = -1.0; private var dur = 0.5
        fun layout() {
            chips.forEachIndexed { i, (_, n) ->
                val j = (i * 2654435761L % 1000) / 1000f - 0.5f
                n.isVisible = visible
                n.transform = Transform(position = Position(base.x + j * 0.0016f, base.y + i * L.chipH, base.z + ((i * 40503L % 1000) / 1000f - 0.5f) * 0.0016f),
                    quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), i * 37f))
            }
        }
        fun moveTo(now: Double, p: Float3, d: Double) { from = base; to = p; t0 = now; dur = d }
        fun hide() { visible = false; for ((_, n) in chips) n.isVisible = false; extra?.hide() }
        fun update(now: Double) {
            if (t0 < 0) return
            val u = smooth(((now - t0) / dur).toFloat())
            base = lerp(from, to, u).let { it.copy(y = it.y + 0.02f * sin(u * PI.toFloat())) }
            if (now - t0 >= dur) { base = to; t0 = -1.0 }
            layout()
        }
    }

    fun close() { idleJob?.cancel(); hintJob?.cancel(); voice.close(); job?.cancel(); for (t in tex.values) runCatching { engine.destroyTexture(t) } }

    companion object {
        fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        fun lerp(a: Float3, b: Float3, u: Float) = Float3(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u)
        @Suppress("unused") private fun near(a: Float, b: Float) = abs(a - b) < 1e-4f
    }
}

