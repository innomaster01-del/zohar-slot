package com.zohar.slotar.baccarat

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

/** Baccarat spots on the shared table frame (tools/3d/blackjack/layout.js → bacc). */
class BacLayout(j: JSONObject) {
    private val h = j.getJSONObject("bacc")
    private fun v(n: String) = h.getJSONArray(n).let { Float3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
    val playerHand = v("playerHand"); val bankerHand = v("bankerHand"); val cardDx = h.getDouble("cardDx").toFloat(); val thirdDx = h.getDouble("thirdDx").toFloat()
    private val spots = mapOf(BacBet.PLAYER to v("player"), BacBet.BANKER to v("banker"), BacBet.TIE to v("tie"), BacBet.PPAIR to v("ppair"), BacBet.BPAIR to v("bpair"))
    fun bet(b: BacBet) = spots.getValue(b)
}

/** Play-money wallet for the Baccarat app, kept on the phone. Free refill when it runs low. */
class BacWallet(context: Context) : Wallet {
    private val prefs = context.getSharedPreferences("zohar_bac", Context.MODE_PRIVATE)
    override var credits: Long = prefs.getLong("credits", START)
        set(v) { field = v; prefs.edit().putLong("credits", v).apply() }
    companion object { const val START = 1000L }
}

enum class BUi { BETTING, BUSY, SQUEEZE }

/** Baccarat table: engine + everything shown on it. The engine decides the whole coup first; the dealer only shows it, card by card. */
class BaccaratTable(
    private val context: Context, private val engine: Engine, private val modelLoader: ModelLoader,
    private val sounds: SlotSounds, private val scope: CoroutineScope, private val onFx: (Fx) -> Unit,
    rng: Rng = SecureRng, wallet: Wallet? = null, speech: Boolean = true, private val anisotropy: Float = 8f,
) {
    private val layoutJson = JSONObject(context.assets.open("bj_layout.json").bufferedReader().readText())
    val L = BjLayout(layoutJson)
    val B = BacLayout(layoutJson)
    val wallet: Wallet = wallet ?: BacWallet(context)
    val game = BaccaratEngine(this.wallet, rng)
    val voice = Voice(context, speech) { dealer?.talking = it }

    // ---------------- UI state ----------------
    var ui by mutableStateOf(BUi.BETTING); private set
    var credits by mutableLongStateOf(this.wallet.credits); private set
    val bets = androidx.compose.runtime.mutableStateMapOf<BacBet, Long>()
    var target by mutableStateOf(BacBet.PLAYER)
    var chipValue by mutableLongStateOf(25L)
    var squeeze by mutableStateOf(true)
    private var lastBets: Map<BacBet, Long> = mapOf(BacBet.PLAYER to 10L)
    val staked get() = bets.values.sum()
    fun canDeal() = ui == BUi.BETTING && game.validate(bets.toMap()) == null
    var line by mutableStateOf("Place your bets"); private set
    var totals by mutableStateOf(""); private set
    var outcome by mutableStateOf(""); private set
    val history = androidx.compose.runtime.mutableStateListOf<BacWinner>()
    var squeezeCard by mutableStateOf<Card?>(null); private set
    var squeezeLabel by mutableStateOf(""); private set
    private var squeezeDone: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val rounds get() = roundsPlayed
    private var roundsPlayed = 0

    fun tapFelt(x: Float, z: Float): Boolean {
        if (ui != BUi.BETTING) return false
        fun inside(b: BacBet): Boolean { val p = B.bet(b); val (rx, rz) = when (b) { BacBet.PLAYER, BacBet.BANKER -> 0.065f to 0.065f; BacBet.TIE -> 0.075f to 0.04f; else -> 0.042f to 0.042f }
            val dx = (x - p.x) / rx; val dz = (z - p.z) / rz; return dx * dx + dz * dz <= 1f }
        val spot = BacBet.entries.firstOrNull { inside(it) } ?: return false
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
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE).apply { if (this@BaccaratTable.anisotropy > 1f) anisotropy = this@BaccaratTable.anisotropy }
    private var now = 0.0
    private var job: Job? = null
    var cameraWorld: FloatArray? = null

    fun attach(frame: Node, dealerNode: ModelNode?) {
        if (this.frame === frame && dealer?.node === dealerNode) return
        this.frame = frame; dealer = dealerNode?.let { DealerRig(engine, it) }
        Log.i("ZoharBA", "attached dealerOk=${dealer?.ok}")
        // opening, like a real dealer starting a shift: clear the hands (palms, then backs, to the camera), then invite the player
        scope.launch {
            delay(900)
            if (dealer?.has("ClearHands") == true) { dealer?.play("ClearHands", now); delay(3100) }
            dealer?.play("Welcome", now); delay(500); say("Welcome to Baccarat. Place your bets.")
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
        if (ui != BUi.BETTING) return
        val cur = bets[target] ?: 0L
        val max = if (target == BacBet.PLAYER || target == BacBet.BANKER) game.maxMain else game.maxSide
        if (cur + v > max) { say("${target.label} goes up to $max."); return }
        if (staked + v > wallet.credits) { sounds.play("no_credits"); line = "Not enough play credits"; return }
        bets[target] = cur + v
        sounds.play("chip"); showStacks()
    }
    fun clearBet() { if (ui != BUi.BETTING) return; touched(); bets.clear(); showStacks(); sounds.play("chips_stack") }
    fun rebet() {
        if (ui != BUi.BETTING) return
        bets.clear(); if (lastBets.values.sum() <= wallet.credits) bets.putAll(lastBets)
        showStacks(); sounds.play("chips_stack")
    }
    fun canRefill() = ui == BUi.BETTING && wallet.credits < game.minBet
    fun refill() { if (!canRefill()) return; wallet.credits = BacWallet.START; credits = wallet.credits; say("Here are a thousand fresh play credits. Good luck.") }
    private val stacksBy = HashMap<BacBet, StackView>()
    private fun showStacks() {
        for (s in stacksBy.values) removeStack(s); stacksBy.clear()
        for ((b, a) in bets) if (a > 0) { val p = B.bet(b); stacksBy[b] = stack(a, Float3(p.x, L.feltY, p.z)) }
        credits = wallet.credits - staked
    }

    // ================= the coup =================
    fun deal() {
        touched()
        if (!canDeal()) { game.validate(bets.toMap())?.let { line = it; say(it) }; return }
        seq {
            val stakes = bets.filterValues { it > 0 }
            lastBets = stakes
            val r = game.deal(stakes)
            roundsPlayed++
            credits = wallet.credits - r.returned; outcome = ""; totals = ""
            if (game.reshuffled && roundsPlayed > 1) { sounds.play("shuffle"); say("Fresh shoe."); wait(1.0) }
            say("No more bets.", flush = false)
            // the biggest bet squeezes its side, like at a real table (Player when even)
            val sq: Owner? = when { !squeeze -> null; (stakes[BacBet.BANKER] ?: 0) > (stakes[BacBet.PLAYER] ?: 0) -> Owner.BANKER; else -> Owner.PLAYER }
            val pv = ArrayList<CardView>(); val bv = ArrayList<CardView>()
            pv += dealTo(r.player[0], handSpot(Owner.PLAYER, 0), sq != Owner.PLAYER, Owner.PLAYER)
            bv += dealTo(r.banker[0], handSpot(Owner.BANKER, 0), sq != Owner.BANKER, Owner.BANKER)
            pv += dealTo(r.player[1], handSpot(Owner.PLAYER, 1), sq != Owner.PLAYER, Owner.PLAYER)
            bv += dealTo(r.banker[1], handSpot(Owner.BANKER, 1), sq != Owner.BANKER, Owner.BANKER)
            if (sq != null) {
                val side = if (sq == Owner.PLAYER) "Player" else "Banker"
                val cs = if (sq == Owner.PLAYER) r.player else r.banker; val vs = if (sq == Owner.PLAYER) pv else bv
                say("Your squeeze. $side cards.")
                squeezeOne(vs[0], cs[0], "$side · first card"); squeezeOne(vs[1], cs[1], "$side · second card")
            }
            val p2 = bacTotal(r.player.take(2)); val b2 = bacTotal(r.banker.take(2))
            totals = "Player $p2  ·  Banker $b2"
            say(when {
                p2 >= 8 && b2 >= 8 -> "Natural $p2 for the Player, natural $b2 for the Banker."
                p2 >= 8 -> "Natural $p2 for the Player."
                b2 >= 8 -> "Natural $b2 for the Banker."
                else -> "Player $p2. Banker $b2."
            })
            wait(1.5)
            if (r.player.size == 3) {
                say("Player draws.")
                val cv = dealTo(r.player[2], thirdSpot(Owner.PLAYER), sq != Owner.PLAYER, Owner.PLAYER)
                if (sq == Owner.PLAYER) squeezeOne(cv, r.player[2], "Player · third card")
                totals = "Player ${r.pTotal}  ·  Banker $b2"; say("Player ${r.pTotal}.", flush = false); wait(1.0)
            } else if (!r.natural) { say("Player stands on $p2.", flush = false); wait(1.0) }
            if (r.banker.size == 3) {
                say("Banker draws.")
                val cv = dealTo(r.banker[2], thirdSpot(Owner.BANKER), sq != Owner.BANKER, Owner.BANKER)
                if (sq == Owner.BANKER) squeezeOne(cv, r.banker[2], "Banker · third card")
                say("Banker ${r.bTotal}.", flush = false); wait(1.0)
            } else if (!r.natural) { say("Banker stands on $b2.", flush = false); wait(1.0) }
            totals = "Player ${r.pTotal}  ·  Banker ${r.bTotal}"
            history.add(r.winner); if (history.size > 24) history.removeAt(0)
            val pairs = listOfNotNull("Player pair".takeIf { r.playerPair }, "Banker pair".takeIf { r.bankerPair })
            say(when (r.winner) {
                BacWinner.PLAYER -> "Player wins, ${r.pTotal} over ${r.bTotal}."
                BacWinner.BANKER -> "Banker wins, ${r.bTotal} over ${r.pTotal}."
                BacWinner.TIE -> "Tie, ${r.pTotal} each."
            } + (if (pairs.isNotEmpty()) " " + pairs.joinToString(" and ") + "." else ""))
            wait(1.3)
            settle(r)
        }
    }

    /** Hand the card to the player's finger: the overlay peels it, then the card on the table turns over. */
    private suspend fun Seq.squeezeOne(cv: CardView, card: Card, label: String) {
        val d = kotlinx.coroutines.CompletableDeferred<Unit>(); squeezeDone = d
        squeezeLabel = label; squeezeCard = card; ui = BUi.SQUEEZE
        d.await()
        squeezeCard = null; ui = BUi.BUSY
        cv.flipUp(now, 0.35); sounds.play("card_flip"); wait(0.5)
    }
    fun squeezed() { squeezeDone?.complete(Unit) }

    private suspend fun Seq.settle(r: BacResult) {
        val lost = stacksBy.filterKeys { (r.returns[it] ?: 0L) == 0L }
        val won = stacksBy.filterKeys { (r.returns[it] ?: 0L) > (r.stakes[it] ?: 0L) }
        val kept = stacksBy.filterKeys { it !in lost && it !in won }                 // Player / Banker bets returned on a tie
        if (lost.isNotEmpty()) { clip("Collect"); waitUntil(0.45); for (s in lost.values) collectStack(s); wait(0.3) }
        if (won.isNotEmpty()) {
            clip("Pay"); waitUntil(0.6)
            for ((b, s) in won) payStack(s, r.returns.getValue(b) - r.stakes.getValue(b))
            sounds.play("chips_stack"); onFx(Fx.COINS)
            if (BacBet.TIE in won || BacBet.PPAIR in won || BacBet.BPAIR in won) { onFx(Fx.BIG_WIN); sounds.play("big_win"); dealer?.play("Clap", now) }
            dealer?.smile = 1f
            if (BacBet.BANKER in won) say("Banker pays less five percent.", flush = false)
        } else if (kept.isNotEmpty()) { dealer?.play("Nod", now); say("Player and Banker bets stay with you.", flush = false) }
        else dealer?.smile = 0.15f
        val net = r.returned - r.staked
        outcome = when { net > 0 -> "YOU WIN $net"; net == 0L -> "PUSH"; else -> "LOSE ${-net}" }
        Log.i("ZoharBA", "coup $roundsPlayed: ${r.winner} P=${r.pTotal} B=${r.bTotal} net=$net credits=${wallet.credits}")
        credits = wallet.credits
        wait(1.6)
        for (s in won.values + kept.values) takeHome(s)
        wait(0.8)
        for (s in stacksBy.values) removeStack(s); stacksBy.clear()
        clearTable()
    }

    private suspend fun Seq.clearTable() {
        clip("Clear"); waitUntil(0.45)
        for (cv in cardsOnTable) cv.toDiscard(now, Float3(L.discard.x, L.feltY + 0.02f, L.discard.z)); sounds.play("card_slide")
        wait(1.0)
        for (cv in cardsOnTable) release(cv); cardsOnTable.clear()
        dealer?.smile = 0f
        bets.clear(); credits = wallet.credits; ui = BUi.BETTING; line = "Place your bets"
        if (wallet.credits >= lastBets.values.sum()) rebet()
    }

    private fun recover() {
        squeezeCard = null
        for (c in cardsOnTable) release(c); cardsOnTable.clear()
        for (s in stacks.toList()) removeStack(s); stacksBy.clear()
        bets.clear(); credits = wallet.credits; ui = BUi.BETTING; line = "Place your bets"
    }

    // ================= layout =================
    data class Spot(val pos: Float3, val yaw: Float, val lift: Float)
    private fun handOf(o: Owner) = if (o == Owner.PLAYER) B.playerHand else B.bankerHand
    fun handSpot(o: Owner, k: Int) = handOf(o).let { h -> Spot(Float3(h.x + (k - 0.5f) * B.cardDx, L.feltY, h.z), 0f, k * 0.0004f) }
    /** The third card lies sideways on the outside of its hand. */
    fun thirdSpot(o: Owner) = handOf(o).let { h -> Spot(Float3(h.x + (if (o == Owner.PLAYER) -1f else 1f) * B.thirdDx, L.feltY, h.z), (PI / 2).toFloat(), 0.0008f) }

    // ================= cards =================
    enum class Owner { PLAYER, BANKER }
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
        ui = BUi.BUSY
        job = scope.launch {
            try { Seq().block() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("ZoharBA", "sequence failed", e)
                recover()
            }
            // a sequence must always hand the table back to the player; never leave it BUSY
            if (ui == BUi.BUSY) { Log.w("ZoharBA", "sequence ended BUSY in phase ${game.phase}: recovering"); recover() }
        }
    }
    inner class Seq {
        private var mark = 0.0
        suspend fun wait(sec: Double) { delay((sec * 1000).toLong()) }
        fun clip(name: String) { dealer?.play(name, now); mark = now }
        /** wait until `sec` after the last clip started */
        suspend fun waitUntil(sec: Double) { val t = mark + sec - now; if (t > 0) delay((t * 1000).toLong()) }
        suspend fun dealTo(card: Card, spot: Spot, faceUp: Boolean, owner: Owner): CardView {
            val d = dealer
            val clip = when { spot.pos.x < -0.09f -> "DealPlayerL"; spot.pos.x > 0.09f -> "DealPlayerR"; else -> "DealPlayer" }
            d?.play(clip, now); mark = now
            val cv = obtainCard(card, owner)
            cv.deal(now, Float3(L.shoe.x, L.feltY + 0.012f, L.shoe.z), spot, faceUp, spot.yaw)
            cardsOnTable.add(cv)
            waitUntil(0.82); sounds.play("card_slide", 0.8f)
            waitUntil(1.0)
            return cv
        }
        suspend fun say(text: String, flush: Boolean = true) = this@BaccaratTable.say(text, flush)
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
            if (ui == BUi.BETTING && t - lastTouch > 180_000) {
                dealer?.play(if (idleLine % 2 == 0) "Talk" else "Shrug", now); dealer?.smile = 0.7f
                say(idleLines[idleLine % idleLines.size]); idleLine++
                lastTouch = t                                                  // next joke after another 3 minutes
                delay(4000); dealer?.smile = 0f
            }
        }
    }
    private fun texture(id: String): Texture = tex.getOrPut(id) {
        Log.i("ZoharBA", "step texture $id")
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
        return pool.removeLastOrNull() ?: run { Log.i("ZoharBA", "step chip model $v"); ModelNode(modelLoader.createModelInstance("models/bj_chip_$v.glb"), autoAnimate = false).also { frame!!.addChildNode(it) } }
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

    fun close() { idleJob?.cancel(); voice.close(); job?.cancel(); for (t in tex.values) runCatching { engine.destroyTexture(t) } }

    companion object {
        fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        fun lerp(a: Float3, b: Float3, u: Float) = Float3(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u)
        @Suppress("unused") private fun near(a: Float, b: Float) = abs(a - b) < 1e-4f
    }
}

