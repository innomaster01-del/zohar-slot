package com.zohar.slotar.blackjack

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

/** Table frame (metres) shared with the 3D models: see tools/3d/blackjack/layout.js. */
class BjLayout(private val j: JSONObject) {
    private fun v(n: String) = j.getJSONArray(n).let { Float3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
    val feltY = j.getDouble("feltY").toFloat(); val dealerZ = j.getDouble("dealerZ").toFloat()
    val shoe = v("shoe"); val discard = v("discard"); val rack = v("rack"); val dealerCards = v("dealerCards")
    val playerCards = v("playerCards"); val playerBet = v("playerBet"); val insurance = v("insurance")
    val splitDx = j.getDouble("splitDx").toFloat(); val cardW = j.getDouble("cardW").toFloat(); val chipH = j.getDouble("chipH").toFloat()
    companion object { fun load(c: Context) = BjLayout(JSONObject(c.assets.open("bj_layout.json").bufferedReader().readText())) }
}

/** Play-money wallet for the blackjack app, kept on the phone. Free refill when it runs low. */
class BjWallet(context: Context) : Wallet {
    private val prefs = context.getSharedPreferences("zohar_bj", Context.MODE_PRIVATE)
    override var credits: Long = prefs.getLong("credits", START)
        set(v) { field = v; prefs.edit().putLong("credits", v).apply() }
    companion object { const val START = 1000L }
}

enum class Ui { BETTING, BUSY, INSURANCE, PLAYER }

/**
 * The table: engine + every visible thing on it (cards, chips, the dealer's moves and words). The engine decides
 * first; the choreography only shows what already happened, card by card, in the real dealing order.
 */
class BlackjackTable(
    private val context: Context, private val engine: Engine, private val modelLoader: ModelLoader,
    private val sounds: SlotSounds, private val scope: CoroutineScope, private val onFx: (Fx) -> Unit,
    rng: Rng = SecureRng, wallet: Wallet? = null,
) {
    val L = BjLayout.load(context)
    val wallet: Wallet = wallet ?: BjWallet(context)
    val game = BlackjackEngine(this.wallet, rng)
    val voice = Voice(context) { dealer?.talking = it }

    // ---------------- UI state ----------------
    var ui by mutableStateOf(Ui.BETTING); private set
    var credits by mutableLongStateOf(this.wallet.credits); private set
    var bet by mutableLongStateOf(0L); private set
    var lastBet = 25L; private set
    var line by mutableStateOf("Place your bet"); private set          // what the dealer says (subtitle)
    var totals by mutableStateOf(""); private set
    var actions by mutableStateOf(listOf<Action>()); private set
    var outcome by mutableStateOf(""); private set
    var insuranceCost by mutableLongStateOf(0L); private set
    var evenMoney by mutableStateOf(false); private set
    val rounds get() = roundsPlayed
    private var roundsPlayed = 0

    // ---------------- scene ----------------
    var frame: Node? = null; private set
    var dealer: DealerRig? = null; private set
    private val cardPool = ArrayList<CardView>()
    private val chipPool = HashMap<Int, ArrayList<ModelNode>>()
    val cardsOnTable = ArrayList<CardView>()
    private val stacks = ArrayList<StackView>()
    private val tex = HashMap<String, Texture>()
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE).apply { anisotropy = 8f }
    private var now = 0.0
    private var job: Job? = null
    var cameraWorld: FloatArray? = null

    fun attach(frame: Node, dealerNode: ModelNode) {
        if (this.frame === frame && dealer?.node === dealerNode) return
        this.frame = frame; dealer = DealerRig(engine, dealerNode)
        Log.i("ZoharBJ", "attached dealerOk=${dealer?.ok}")
        scope.launch { delay(900); dealer?.play("Welcome", now); delay(600); say("Welcome to the table. Place your bet.") }
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
        if (ui != Ui.BETTING) return
        val nb = bet + v
        if (nb > game.rules.maxBet) { say("Table maximum is ${game.rules.maxBet}."); return }
        if (nb > wallet.credits) { sounds.play("no_credits"); line = "Not enough play credits"; return }
        bet = nb; sounds.play("chip"); showBetStack()
    }
    fun clearBet() { if (ui != Ui.BETTING) return; bet = 0; showBetStack(); sounds.play("chips_stack") }
    fun rebet() { if (ui != Ui.BETTING) return; bet = min(lastBet, wallet.credits); showBetStack(); sounds.play("chips_stack") }
    fun canRefill() = ui == Ui.BETTING && wallet.credits + bet < game.rules.minBet
    fun refill() { if (!canRefill()) return; wallet.credits = BjWallet.START; credits = wallet.credits; say("Here are a thousand fresh play credits. Good luck.") }
    private var betStack: StackView? = null
    private fun showBetStack() {
        betStack?.let { removeStack(it) }; betStack = null
        if (bet > 0) betStack = stack(bet, Float3(L.playerBet.x, L.feltY, L.playerBet.z))
        credits = wallet.credits - bet
    }

    // ================= the round =================
    fun deal() {
        if (ui != Ui.BETTING || bet < game.rules.minBet) { if (bet in 1 until game.rules.minBet) say("Minimum bet is ${game.rules.minBet}."); return }
        seq {
            lastBet = bet
            game.deal(bet)
            roundsPlayed++
            credits = wallet.credits; outcome = ""; totals = ""
            if (game.reshuffled) { sounds.play("shuffle"); say("Fresh shoe. Good luck.") } else say("Good luck.")
            handStacks.clear(); handStacks.add(betStack!!); betStack = null
            val p = game.hands[0].cards; val d = game.dealer
            dealTo(p[0], playerSpot(0, 1, 0), faceUp = true, hand = 0)
            dealTo(d[0], dealerSpot(0), faceUp = true, toDealer = true)
            dealTo(p[1], playerSpot(0, 1, 1), faceUp = true, hand = 0)
            dealTo(d[1], dealerSpot(1), faceUp = false, toDealer = true)
            showTotals()
            when (game.phase) {
                Phase.INSURANCE -> {
                    insuranceCost = game.insuranceCost(); evenMoney = isBlackjack(game.hands[0].cards)
                    say(if (evenMoney) "Blackjack against an ace. Even money?" else "Dealer shows an ace. Insurance?")
                    ui = Ui.INSURANCE; return@seq
                }
                Phase.DONE -> { finishRound(); return@seq }
                else -> { if (game.dealer[0].isAce || game.dealer[0].value == 10) { dealer?.play("Nod", now); say("No blackjack.", flush = false) }; playerTurn() }
            }
        }
    }

    fun insurance(take: Boolean) = seq {
        if (ui != Ui.INSURANCE) return@seq
        var insStack: StackView? = null
        if (take && game.canInsure()) { game.insurance(true); insStack = stack(game.insuranceBet, Float3(L.insurance.x - 0.06f, L.feltY, L.insurance.z)); sounds.play("chip") } else game.insurance(false)
        credits = wallet.credits - (game.result?.returned ?: 0)
        dealer?.play("Nod", now); wait(1.2)
        if (game.phase == Phase.DONE) {
            if (insStack != null) insStackHolder = insStack
            finishRound()
        } else {
            say("No blackjack.")
            insStack?.let { s -> collectStack(s) }
            playerTurn()
        }
    }
    private var insStackHolder: StackView? = null

    fun act(a: Action) = seq {
        if (ui != Ui.PLAYER || a !in game.actions()) return@seq
        val idx = game.active; val h = game.hand; val nHands = game.hands.size
        when (a) {
            Action.HIT -> { sounds.play("click"); game.act(a); dealTo(h.cards.last(), playerSpot(idx, game.hands.size, h.cards.size - 1), true, hand = idx) }
            Action.STAND -> { game.act(a); dealer?.play("Nod", now); wait(0.5) }
            Action.DOUBLE -> {
                game.act(a); addToStack(handStacks[idx], h.bet / 2); sounds.play("chips_stack"); wait(0.4)
                dealTo(h.cards.last(), playerSpot(idx, nHands, h.cards.size - 1), true, sideways = true, hand = idx)
            }
            Action.SURRENDER -> { game.act(a); say("Surrender. Half your bet back."); dealer?.play("Nod", now); wait(0.8) }
            Action.SPLIT -> {
                game.act(a)
                val n = game.hands.size
                // the second card slides to the new hand, a second bet goes next to it
                val moving = cardsOnTable.first { it.hand == idx && it.indexInHand == 1 }
                for (c in cardsOnTable) if (c.hand > idx && c.owner == Owner.PLAYER) c.hand++
                moving.hand = idx + 1; moving.indexInHand = 0
                handStacks.add(idx + 1, stack(h.bet, betSpot(idx + 1, n)))
                relayoutPlayer(n); sounds.play("chips_stack"); wait(0.7)
                dealTo(game.hands[idx].cards[1], playerSpot(idx, n, 1), true, hand = idx)
                dealTo(game.hands[idx + 1].cards[1], playerSpot(idx + 1, n, 1), true, hand = idx + 1)
            }
        }
        showTotals()
        if (game.phase == Phase.DONE) finishRound() else playerTurn()
    }

    private fun playerTurn() {
        actions = game.actions(); val h = game.hand
        val t = h.total
        line = when {
            game.hands.size > 1 -> "Hand ${game.active + 1}: ${t.total}${if (t.soft && t.total < 21) " (soft)" else ""}"
            else -> "You have ${if (t.soft && t.total < 21) "soft " else ""}${t.total}"
        }
        if (game.hands.size > 1) say("Hand ${game.active + 1}. ${t.total}.") else say("${if (t.soft && t.total < 21) "Soft " else ""}${t.total}.")
        ui = Ui.PLAYER
    }

    private suspend fun Seq.finishRound() {
        ui = Ui.BUSY; actions = emptyList()
        val r = game.result ?: return
        // dealer reveals and plays
        val hole = cardsOnTable.firstOrNull { it.owner == Owner.DEALER && it.indexInHand == 1 }
        if (hole != null && !hole.faceUp) { clip("Flip"); waitUntil(0.35); hole.flipUp(now, 0.4); sounds.play("card_flip"); wait(0.9) }
        val shown = cardsOnTable.count { it.owner == Owner.DEALER }
        for (i in shown until game.dealer.size) dealTo(game.dealer[i], dealerSpot(i), true, toDealer = true)
        showTotals()
        val dt = r.dealerTotal
        when {
            r.dealerBlackjack -> say("Dealer has blackjack.")
            dt > 21 -> say("Dealer busts with $dt!")
            game.dealer.size > 2 || r.hands.any { it.outcome != Outcome.BLACKJACK } -> say("Dealer has $dt.")
        }
        wait(1.1)
        // insurance side bet
        insStackHolder?.let { s -> if (r.insuranceReturned > 0) { payStack(s, r.insuranceBet * 2); say("Insurance pays two to one.", flush = false) } else collectStack(s); insStackHolder = null; wait(1.0) }
        // settle each hand, the dealer's way: take the losers, pay the winners
        var anyWin = false; var bigWin = false
        r.hands.forEachIndexed { i, hr ->
            val s = handStacks.getOrNull(i) ?: return@forEachIndexed
            when (hr.outcome) {
                Outcome.LOSE, Outcome.BUST -> { clip("Collect"); waitUntil(0.45); collectStack(s); wait(1.0) }
                Outcome.SURRENDER -> { clip("Collect"); waitUntil(0.45); collectStack(s, keep = hr.returned); wait(1.0) }
                Outcome.PUSH -> { dealer?.play("Nod", now); say("Push.", flush = false); wait(0.9) }
                Outcome.WIN, Outcome.BLACKJACK -> {
                    anyWin = true; if (hr.outcome == Outcome.BLACKJACK || hr.bet >= 100) bigWin = true
                    clip("Pay"); waitUntil(0.6); payStack(s, hr.returned - hr.bet); sounds.play("chips_stack"); wait(1.0)
                }
            }
        }
        val net = r.returned - r.hands.sumOf { it.bet } - r.insuranceBet
        outcome = when { net > 0 -> "YOU WIN ${net}"; net == 0L -> "PUSH"; else -> "LOSE ${-net}" }
        Log.i("ZoharBJ", "round ${roundsPlayed}: player=${game.hands.map { it.cards }} dealer=${game.dealer} -> ${r.hands.map { it.outcome }} net=$net credits=${wallet.credits}")
        if (r.hands.any { it.outcome == Outcome.BLACKJACK }) { dealer?.smile = 1f; dealer?.play("Clap", now); onFx(Fx.COINS); onFx(Fx.BIG_WIN); sounds.play("big_win"); say("Blackjack! Congratulations.") }
        else if (anyWin) { dealer?.smile = 0.8f; onFx(Fx.COINS); sounds.play("win_small"); say(if (bigWin) "Very nice. Winner!" else "Winner.") }
        else if (net < 0) { dealer?.smile = 0.15f; say(if (r.hands.any { it.outcome == Outcome.BUST }) "Too many. Better luck next hand." else "Sorry. Next hand.") }
        credits = wallet.credits
        wait(1.6)
        // winnings go to the player, cards to the discard holder
        for (s in handStacks) takeHome(s)
        clip("Clear"); waitUntil(0.45)
        for (c in cardsOnTable) c.toDiscard(now, Float3(L.discard.x, L.feltY + 0.02f, L.discard.z)); sounds.play("card_slide")
        wait(1.0)
        for (c in cardsOnTable) release(c); cardsOnTable.clear()
        for (s in handStacks) removeStack(s); handStacks.clear()
        dealer?.smile = 0f; totals = ""
        bet = 0; ui = Ui.BETTING; line = "Place your bet"
        if (wallet.credits >= lastBet) rebet()
    }

    // ================= choreography helpers =================
    /** Coroutine runner: the whole sequence runs on the main thread, frame updates animate in between. */
    private fun seq(block: suspend Seq.() -> Unit) {
        if (job?.isActive == true) return
        ui = Ui.BUSY
        job = scope.launch { try { Seq().block() } catch (e: Exception) { Log.e("ZoharBJ", "sequence failed", e); ui = Ui.BETTING } }
    }
    inner class Seq {
        private var mark = 0.0
        suspend fun wait(sec: Double) { delay((sec * 1000).toLong()) }
        fun clip(name: String) { dealer?.play(name, now); mark = now }
        /** wait until `sec` after the last clip started */
        suspend fun waitUntil(sec: Double) { val t = mark + sec - now; if (t > 0) delay((t * 1000).toLong()) }
        suspend fun dealTo(card: Card, spot: Spot, faceUp: Boolean, toDealer: Boolean = false, sideways: Boolean = false, hand: Int = 0) {
            val d = dealer
            val clip = when { toDealer -> "DealDealer"; spot.pos.x < -0.08f -> "DealPlayerL"; spot.pos.x > 0.08f -> "DealPlayerR"; else -> "DealPlayer" }
            d?.play(clip, now); mark = now
            val cv = obtainCard(card, if (toDealer) Owner.DEALER else Owner.PLAYER, hand)
            cv.deal(now, Float3(L.shoe.x, L.feltY + 0.012f, L.shoe.z), spot, faceUp, if (sideways) PI.toFloat() / 2 else spot.yaw)
            cardsOnTable.add(cv)
            waitUntil(0.82); sounds.play("card_slide", 0.8f)
            waitUntil(1.0)
        }
        suspend fun say(text: String, flush: Boolean = true) = this@BlackjackTable.say(text, flush)
        suspend fun collectStack(s: StackView, keep: Long = 0) {
            val pos = Float3(L.rack.x + (s.base.x - L.rack.x) * 0.2f, L.feltY - 0.01f, L.rack.z)
            if (keep > 0) { s.moveTo(now, pos, 0.55); delay(600); s.hide(); val back = stack(keep, s.base0); handStacksReplace(s, back) }
            else { s.moveTo(now, pos, 0.6); delay(650); s.hide() }
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
    private fun handStacksReplace(old: StackView, new: StackView) { val i = handStacks.indexOf(old); if (i >= 0) handStacks[i] = new }

    private val handStacks = ArrayList<StackView>()
    private fun say(text: String, flush: Boolean = true) { line = text; voice.say(text, flush) }
    private fun showTotals() {
        val ps = game.hands.joinToString(" / ") { h -> val t = h.total; if (isBlackjack(h.cards) && game.hands.size == 1) "BJ" else "${if (t.soft && t.total < 21) "soft " else ""}${t.total}" }
        val d = if (game.holeHidden) game.dealer[0].let { if (it.isAce) "A" else "${it.value}" } else "${total(game.dealer).total}"
        totals = "You $ps  ·  Dealer $d"
    }

    // ================= layout =================
    data class Spot(val pos: Float3, val yaw: Float, val lift: Float)
    private fun handX(i: Int, n: Int) = L.playerCards.x + (i - (n - 1) / 2f) * L.splitDx
    fun playerSpot(hand: Int, nHands: Int, k: Int) = Spot(Float3(handX(hand, nHands) + k * 0.017f, L.feltY, L.playerCards.z - k * 0.024f), 0f, k * 0.0005f)
    fun betSpot(hand: Int, nHands: Int) = Float3(handX(hand, nHands), L.feltY, L.playerBet.z)
    fun dealerSpot(k: Int) = Spot(Float3(L.dealerCards.x + (if (k == 0) 0f else 0.016f + k * (L.cardW + 0.006f) - L.cardW), L.feltY, L.dealerCards.z), 0f, k * 0.0005f)
    private fun relayoutPlayer(n: Int) {
        for (c in cardsOnTable) if (c.owner == Owner.PLAYER) c.moveTo(now, playerSpot(c.hand, n, c.indexInHand))
        handStacks.forEachIndexed { i, s -> s.moveTo(now, betSpot(i, n), 0.45) }
    }

    // ================= cards =================
    enum class Owner { PLAYER, DEALER }
    private fun obtainCard(card: Card, owner: Owner, hand: Int): CardView {
        val f = frame!!
        val cv = cardPool.removeLastOrNull() ?: CardView(ModelNode(modelLoader.createModelInstance("models/bj_card.glb"), autoAnimate = false)).also { f.addChildNode(it.node); it.bindBack(texture("back")) }
        cv.node.isVisible = false
        cv.bindFace(texture(card.id)); cv.card = card; cv.owner = owner
        cv.hand = if (owner == Owner.PLAYER) hand else 0
        cv.indexInHand = if (owner == Owner.DEALER) cardsOnTable.count { it.owner == Owner.DEALER } else cardsOnTable.count { it.owner == Owner.PLAYER && it.hand == cv.hand }
        return cv
    }
    private fun release(c: CardView) { c.node.isVisible = false; c.card = null; cardPool.add(c) }

    private fun texture(id: String): Texture = tex.getOrPut(id) {
        val src = context.assets.open("cards/$id.jpg").use { BitmapFactory.decodeStream(it) }
        // the card mesh comes from three.js (UV v-up): upload the rows flipped
        val m = android.graphics.Matrix().apply { setScale(1f, -1f); postTranslate(0f, src.height.toFloat()) }
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).copy(Bitmap.Config.ARGB_8888, false)
        val levels = 1 + (Math.log(max(bmp.width, bmp.height).toDouble()) / Math.log(2.0)).toInt()
        val t = Texture.Builder().width(bmp.width).height(bmp.height).levels(levels).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8).build(engine)
        TextureHelper.setBitmap(engine, t, 0, bmp); t.generateMipmaps(engine)
        t
    }

    inner class CardView(val node: ModelNode) {
        var card: Card? = null; var owner = Owner.PLAYER; var hand = 0; var indexInHand = 0
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
        return pool.removeLastOrNull() ?: ModelNode(modelLoader.createModelInstance("models/bj_chip_$v.glb"), autoAnimate = false).also { frame!!.addChildNode(it) }
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

    fun close() { voice.close(); job?.cancel(); for (t in tex.values) runCatching { engine.destroyTexture(t) } }

    companion object {
        fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        fun lerp(a: Float3, b: Float3, u: Float) = Float3(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u)
        @Suppress("unused") private fun near(a: Float, b: Float) = abs(a - b) < 1e-4f
    }
}
