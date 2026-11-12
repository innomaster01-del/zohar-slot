package com.zohar.slotar.hilo

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
import kotlin.math.sin
import com.zohar.slotar.blackjack.BjLayout
import com.zohar.slotar.blackjack.Card
import com.zohar.slotar.blackjack.DealerRig
import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Voice
import com.zohar.slotar.blackjack.Wallet

/** Higher or Lower spots on the shared table frame (tools/3d/blackjack/layout.js → hilo). */
class HiLoLayout(j: JSONObject) {
    private val h = j.getJSONObject("hilo")
    private fun v(n: String) = h.getJSONArray(n).let { Float3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
    val row = v("row"); val rowDx = h.getDouble("rowDx").toFloat(); val places = h.getInt("places")
    val bet = v("bet"); val pot = v("pot"); val lower = v("lower"); val higher = v("higher")
    val zoneX = h.getJSONArray("zone").getDouble(0).toFloat(); val zoneZ = h.getJSONArray("zone").getDouble(1).toFloat()
}

/** Play-money wallet for the Higher or Lower app, kept on the phone. Free refill when it runs low. */
class HiLoWallet(context: Context) : Wallet {
    private val prefs = context.getSharedPreferences("zohar_hilo", Context.MODE_PRIVATE)
    override var credits: Long = prefs.getLong("credits", START)
        set(v) { field = v; prefs.edit().putLong("credits", v).apply() }
    companion object { const val START = 1000L }
}

enum class HUi { BETTING, GUESS, BUSY }

fun cardName(c: Card) = listOf("Ace", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Jack", "Queen", "King")[c.rank - 1] +
    " of " + listOf("spades", "hearts", "diamonds", "clubs")[c.suit]

/**
 * Higher or Lower table: engine + everything shown on it. The engine shuffles the deck when the round starts, so each
 * card is decided before the dealer turns it; the table only shows it.
 */
class HiLoTable(
    private val context: Context, private val engine: Engine, private val modelLoader: ModelLoader,
    private val sounds: SlotSounds, private val scope: CoroutineScope, private val onFx: (Fx) -> Unit,
    rng: Rng = SecureRng, wallet: Wallet? = null, speech: Boolean = true, private val anisotropy: Float = 8f,
) {
    private val layoutJson = JSONObject(context.assets.open("bj_layout.json").bufferedReader().readText())
    val L = BjLayout(layoutJson)
    val H = HiLoLayout(layoutJson)
    val wallet: Wallet = wallet ?: HiLoWallet(context)
    val game = HiLoEngine(this.wallet, rng)
    val voice = Voice(context, speech) { dealer?.talking = it }

    // ---------------- UI state ----------------
    var ui by mutableStateOf(HUi.BETTING); private set
    var credits by mutableLongStateOf(this.wallet.credits); private set
    /** The player's chips standing on the table in front of him: always worth exactly `credits`. */
    val chips = com.zohar.slotar.chips.BankView(context, "hilo", modelLoader, dev.romainguy.kotlin.math.Float3(0.38f, 0.80f, 0.55f))
    var bet by mutableLongStateOf(0L); private set
    var chipValue by mutableLongStateOf(25L)
    private var lastBet = 25L
    fun canDeal() = ui == HUi.BETTING && game.validate(bet) == null
    var line by mutableStateOf("Place your bet"); private set
    var outcome by mutableStateOf(""); private set
    /** What the player holds now and what each call would make it (read from the engine after every card). */
    var pot by mutableLongStateOf(0L); private set
    var offers by mutableStateOf<List<HiLoOffer>>(emptyList()); private set
    var canTake by mutableStateOf(false); private set
    var cardNo by mutableStateOf(0); private set
    fun offer(c: HiLoCall) = offers.firstOrNull { it.call == c }
    val rounds get() = roundsPlayed
    private var roundsPlayed = 0
    private fun refresh() { pot = game.pot; offers = game.offers(); canTake = game.canTake(); cardNo = game.cards.size }

    /** A tap on the felt: the bet circle while betting; LOWER / HIGHER / the pot while the round is on. */
    fun tapFelt(x: Float, z: Float): Boolean {
        fun round(p: Float3, r: Float) = (x - p.x) * (x - p.x) + (z - p.z) * (z - p.z) <= r * r
        fun zone(p: Float3) = abs(x - p.x) <= H.zoneX && abs(z - p.z) <= H.zoneZ
        when (ui) {
            HUi.BETTING -> if (round(H.bet, 0.065f)) { addChip(chipValue); return true }
            HUi.GUESS -> when {
                zone(H.lower) -> { call(HiLoCall.LOWER); return true }
                zone(H.higher) -> { call(HiLoCall.HIGHER); return true }
                round(H.pot, 0.07f) -> { take(); return true }
            }
            HUi.BUSY -> {}
        }
        return false
    }

    // ---------------- scene ----------------
    var frame: Node? = null; private set
    var dealer: DealerRig? = null; private set
    private val cardPool = ArrayList<CardView>()
    private val chipPool = HashMap<Int, ArrayList<ModelNode>>()
    val cardsOnTable = ArrayList<CardView>()
    private val stacks = ArrayList<StackView>()
    private val tex = HashMap<String, Texture>()
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE).apply { if (this@HiLoTable.anisotropy > 1f) anisotropy = this@HiLoTable.anisotropy }
    private var now = 0.0
    private var job: Job? = null
    var cameraWorld: FloatArray? = null

    fun attach(frame: Node, dealerNode: ModelNode?) {
        chips.attach(frame)
        if (this.frame === frame && dealer?.node === dealerNode) return
        this.frame = frame; dealer = dealerNode?.let { DealerRig(engine, it) }
        Log.i("ZoharHL", "attached dealerOk=${dealer?.ok}")
        scope.launch {
            delay(900)
            if (dealer?.has("ClearHands") == true) { dealer?.play("ClearHands", now); delay(3100) }
            dealer?.play("Welcome", now); delay(500); say("Welcome to Higher or Lower. Place your bet.")
        }
        idleJob?.cancel(); idleJob = scope.launch { idleWatch() }
    }

    // ================= per frame =================
    fun update(nowSec: Double, dt: Float) {
        chips.update(dt, credits)
        now = nowSec
        val f = frame ?: return
        val inv = runCatching { inverse(f.worldTransform) }.getOrNull()
        dealer?.let { d -> d.lookAt = cameraWorld; d.update(now, dt) }
        // the dealer's hand in table space (cards ride in it while he carries them)
        fun hand(left: Boolean): Float3? = dealer?.handWorld(left)?.let { w -> inv?.let { m -> (m * dev.romainguy.kotlin.math.Float4(w[0], w[1], w[2], 1f)).xyz } }
        for (c in cardsOnTable) c.update(now, ::hand)
        for (s in stacks) s.update(now)
    }

    // ================= betting =================
    private var betStack: StackView? = null
    private var potStack: StackView? = null
    private val betPos get() = Float3(H.bet.x, L.feltY, H.bet.z)
    private val potPos get() = Float3(H.pot.x, L.feltY, H.pot.z)
    fun addChip(v: Long) {
        touched()
        if (ui != HUi.BETTING) return
        if (bet + v > game.maxBet) { say("The table maximum is ${game.maxBet}."); return }
        if (bet + v > wallet.credits) { sounds.play("no_credits"); line = "Not enough play credits"; return }
        bet += v; outcome = ""
        sounds.play("chip"); showBet()
    }
    fun clearBet() { if (ui != HUi.BETTING) return; touched(); bet = 0; showBet(); sounds.play("chips_stack") }
    private fun rebet() {
        if (ui != HUi.BETTING) return
        bet = if (lastBet <= wallet.credits) lastBet else 0L
        showBet(); if (bet > 0) sounds.play("chips_stack")
    }
    fun canRefill() = ui == HUi.BETTING && wallet.credits < game.minBet
    fun refill() { if (!canRefill()) return; wallet.credits = HiLoWallet.START; credits = wallet.credits; say("Here are a thousand fresh play credits. Good luck.") }
    private fun showBet() {
        betStack?.let { removeStack(it) }; betStack = if (bet > 0) stack(bet, betPos) else null
        credits = wallet.credits - bet
    }

    // ================= the round =================
    /** Row place k (0 = the first card), left to right. */
    fun rowSpot(k: Int) = Spot(Float3(H.row.x + k * H.rowDx, L.feltY, H.row.z), 0f, 0.0003f)

    fun deal() {
        touched()
        if (!canDeal()) { game.validate(bet)?.let { line = it; say(it) }; return }
        seq {
            lastBet = bet
            val first = game.start(bet)
            roundsPlayed++; outcome = ""; credits = wallet.credits
            say("No more bets.", flush = false)
            // the stake goes into the pot circle: it is what the round is played for
            betStack?.let { it.moveTo(now, potPos, 0.5); potStack = it }; betStack = null; sounds.play("chips_stack", 0.7f)
            dealTo(first, rowSpot(0))
            refresh()
            say("${cardName(first)}. " + ask())
            ui = HUi.GUESS
        }
    }
    private fun ask() = when {
        game.offer(HiLoCall.HIGHER) == null && game.offer(HiLoCall.LOWER) == null -> "Take the pot."
        game.offer(HiLoCall.HIGHER) == null -> "It can only go lower" + (if (game.canTake()) ", or take the pot." else ".")
        game.offer(HiLoCall.LOWER) == null -> "It can only go higher" + (if (game.canTake()) ", or take the pot." else ".")
        game.canTake() -> "Higher, lower, or take the pot?"
        else -> "Higher or lower?"
    }

    fun call(c: HiLoCall) {
        touched()
        if (ui != HUi.GUESS) return
        if (game.offer(c) == null) { say(if (game.steps >= game.maxSteps) "The row is full. Take the pot." else "${c.label} is not on offer from this card."); return }
        seq {
            val s = game.guess(c)
            say("${c.label}.", flush = false)
            dealTo(s.card, rowSpot(game.steps))
            wait(0.25)
            if (s.right) {
                clip("Pay"); waitUntil(0.6)
                potStack?.let { removeStack(it) }; potStack = stack(s.pot, potPos); sounds.play("chips_stack")
                dealer?.smile = 0.6f
                refresh()
                Log.i("ZoharHL", "round $roundsPlayed step ${game.steps}: ${s.from} → ${s.card} ${c.label} right pot=${s.pot}")
                if (game.mustTake) {
                    say("${cardName(s.card)}. ${c.label} it is. That is as far as this table goes: the pot is yours.")
                    wait(1.2); settleTake()
                } else {
                    say("${cardName(s.card)}. ${c.label} it is. The pot is ${s.pot}. " + ask())
                    ui = HUi.GUESS
                }
            } else {
                refresh()
                say("${cardName(s.card)}. " + (if (s.card.rank == s.from.rank) "The same rank" else if (c == HiLoCall.HIGHER) "Lower" else "Higher") + ". The house takes the pot.")
                Log.i("ZoharHL", "round $roundsPlayed step ${game.steps}: ${s.from} → ${s.card} ${c.label} wrong, lost ${game.stake} credits=${wallet.credits}")
                wait(0.9)
                clip("Collect"); waitUntil(0.45); potStack?.let { collectStack(it) }
                dealer?.smile = 0.15f
                outcome = "LOSE ${game.stake}"; credits = wallet.credits
                wait(1.2)
                clearTable()
            }
        }
    }

    fun take() {
        touched()
        if (ui != HUi.GUESS || !game.canTake()) return
        seq { settleTake() }
    }
    private suspend fun Seq.settleTake() {
        val r = game.take()
        refresh()
        say("You take ${r.returned}.")
        sounds.play("chips_stack"); onFx(Fx.COINS)
        if (r.returned >= r.stake * 10) { onFx(Fx.BIG_WIN); sounds.play("big_win"); dealer?.play("Clap", now) } else dealer?.play("Nod", now)
        dealer?.smile = 1f
        outcome = "YOU WIN ${r.returned - r.stake}"
        Log.i("ZoharHL", "round $roundsPlayed: took ${r.returned} after ${r.steps} right calls, stake ${r.stake} credits=${wallet.credits}")
        credits = wallet.credits
        wait(1.4)
        potStack?.let { takeHome(it) }
        wait(0.8)
        clearTable()
    }

    private suspend fun Seq.clearTable() {
        clip("Clear"); waitUntil(0.45)
        for (cv in cardsOnTable) cv.toDiscard(now, Float3(L.discard.x, L.feltY + 0.02f, L.discard.z)); sounds.play("card_slide")
        wait(1.0)
        reset()
        rebet()
    }
    /** Everything off the table, back to betting. */
    private fun reset() {
        for (c in cardsOnTable) release(c); cardsOnTable.clear()
        for (s in stacks.toList()) removeStack(s); betStack = null; potStack = null
        dealer?.smile = 0f
        bet = 0; credits = wallet.credits; pot = 0; offers = emptyList(); canTake = false; cardNo = 0
        ui = HUi.BETTING; line = "Place your bet"
    }
    /**
     * A sequence broke in the middle. If the engine's round is still on, the player loses nothing by it: the pot so far
     * is paid if there is one, otherwise the stake comes back. Then the table starts clean.
     */
    private fun recover() {
        game.abandon()?.let { Log.w("ZoharHL", "round abandoned: ${it.returned} back to the player") }
        reset()
    }

    // ================= layout =================
    data class Spot(val pos: Float3, val yaw: Float, val lift: Float)

    // ================= cards =================
    private fun obtainCard(card: Card): CardView {
        val f = frame!!
        val cv = cardPool.removeLastOrNull() ?: CardView(ModelNode(modelLoader.createModelInstance("models/bj_card.glb"), autoAnimate = false)).also { f.addChildNode(it.node); it.bindBack(texture("back")) }
        cv.node.isVisible = false
        cv.bindFace(texture(card.id)); cv.card = card
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
                Log.e("ZoharHL", "sequence failed", e)
                recover()
            }
            // a sequence must always hand the table back to the player; never leave it BUSY
            if (ui == HUi.BUSY) { Log.w("ZoharHL", "sequence ended BUSY: recovering"); recover() }
        }
    }
    inner class Seq {
        private var mark = 0.0
        suspend fun wait(sec: Double) { delay((sec * 1000).toLong()) }
        fun clip(name: String) { dealer?.play(name, now); mark = now }
        /** wait until `sec` after the last clip started */
        suspend fun waitUntil(sec: Double) { val t = mark + sec - now; if (t > 0) delay((t * 1000).toLong()) }
        suspend fun dealTo(card: Card, spot: Spot, faceUp: Boolean = true): CardView {
            val d = dealer
            val clip = when { spot.pos.x < -0.09f -> "DealPlayerL"; spot.pos.x > 0.09f -> "DealPlayerR"; else -> "DealPlayer" }
            d?.play(clip, now); mark = now
            val cv = obtainCard(card)
            cv.deal(now, Float3(L.shoe.x, L.feltY + 0.012f, L.shoe.z), spot, faceUp, spot.yaw)
            cardsOnTable.add(cv)
            waitUntil(0.82); sounds.play("card_slide", 0.8f)
            waitUntil(1.0)
            return cv
        }
        suspend fun say(text: String, flush: Boolean = true) = this@HiLoTable.say(text, flush)
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
        "These cards are getting cold... I'm going to start guessing them myself.",
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
        Log.i("ZoharHL", "step texture $id")
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
        var card: Card? = null
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
        return pool.removeLastOrNull() ?: run { Log.i("ZoharHL", "step chip model $v"); ModelNode(modelLoader.createModelInstance("models/bj_chip_$v.glb"), autoAnimate = false).also { frame!!.addChildNode(it) } }
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
