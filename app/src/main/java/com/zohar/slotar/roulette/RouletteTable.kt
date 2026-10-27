package com.zohar.slotar.roulette

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import com.zohar.slotar.Fx
import com.zohar.slotar.audio.SlotSounds
import com.zohar.slotar.blackjack.BjLayout
import com.zohar.slotar.blackjack.DealerRig
import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Voice
import com.zohar.slotar.blackjack.Wallet
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Roulette spots on the shared table frame (tools/3d/blackjack/layout.js → roul). */
class RouLayout(j: JSONObject) {
    private val r = j.getJSONObject("roul")
    private fun v(n: String) = r.getJSONArray(n).let { Float3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
    private fun f(n: String) = r.getDouble(n).toFloat()
    val wheel = v("wheel"); val rack = v("rack")
    val k = f("k"); val dealer = r.getJSONArray("dealer").let { Float3(it.getDouble(0).toFloat(), 0f, it.getDouble(1).toFloat()) }
    val frontZ = r.getJSONObject("table").let { (it.getDouble("zf") + it.getDouble("rail")).toFloat() }
    val race = r.getJSONObject("race").let { c -> Racetrack(c.getDouble("cx").toFloat(), c.getDouble("cz").toFloat(), c.getDouble("hl").toFloat(), c.getDouble("rOut").toFloat(), c.getDouble("rIn").toFloat()) }
    val trackR = f("trackR"); val trackY = f("trackY"); val pocketR = f("pocketR"); val pocketY = f("pocketY"); val numberR = f("numberR"); val ballR = f("ballR")
    val grid = r.getJSONObject("grid").let { g -> RouletteGrid(g.getDouble("gx0").toFloat(), g.getDouble("gz0").toFloat(), g.getDouble("cw").toFloat(), g.getDouble("ch").toFloat(), g.getDouble("dh").toFloat(), g.getDouble("eh").toFloat()) }
}

/** Play-money wallet for the Roulette app, kept on the phone. Free refill when it runs low. */
class RouWallet(context: Context) : Wallet {
    private val prefs = context.getSharedPreferences("zohar_roul", Context.MODE_PRIVATE)
    override var credits: Long = prefs.getLong("credits", START)
        set(v) { field = v; prefs.edit().putLong("credits", v).apply() }
    companion object { const val START = 1000L }
}

enum class RUi { BETTING, BUSY }

/**
 * European roulette table: engine + everything shown on it. The engine draws the number first; then the dealer spins the
 * rotor and sends the ball, and the ball's path is worked out so that it drops into exactly that pocket.
 */
class RouletteTable(
    private val context: Context, private val engine: Engine, private val modelLoader: ModelLoader,
    private val sounds: SlotSounds, private val scope: CoroutineScope, private val onFx: (Fx) -> Unit,
    rng: Rng = SecureRng, wallet: Wallet? = null, speech: Boolean = true,
) {
    private val layoutJson = JSONObject(context.assets.open("bj_layout.json").bufferedReader().readText())
    val L = BjLayout(layoutJson)
    val R = RouLayout(layoutJson)
    val grid get() = R.grid
    val wallet: Wallet = wallet ?: RouWallet(context)
    val game = RouletteEngine(this.wallet, rng)
    val voice = Voice(context, speech) { dealer?.talking = it }

    // ---------------- UI state ----------------
    var ui by mutableStateOf(RUi.BETTING); private set
    var credits by mutableLongStateOf(this.wallet.credits); private set
    val bets = mutableStateMapOf<RSpot, Long>()
    var chipValue by mutableLongStateOf(25L)
    private val placed = ArrayList<List<Pair<RSpot, Long>>>()    // for Undo (a racetrack bet is one group)
    private var lastBets: Map<RSpot, Long> = emptyMap()
    val staked get() = bets.values.sum()
    fun canSpin() = ui == RUi.BETTING && game.validate(bets.toMap()) == null
    var line by mutableStateOf("Place your bets"); private set
    var lastBet by mutableStateOf(""); private set                       // "Split 8-11 · 25 · pays 17 to 1"
    var outcome by mutableStateOf(""); private set
    var result by mutableStateOf<Int?>(null); private set
    val history = mutableStateListOf<Int>()
    private var roundsPlayed = 0

    /** A tap on the felt → the bet a dealer would read from a chip put down there. */
    fun tapFelt(x: Float, z: Float): Boolean {
        if (ui != RUi.BETTING) return false
        grid.resolve(x, z)?.let { place(listOf(it to chipValue), null); return true }
        R.race.resolve(x, z)?.let { betRace(it); return true }
        return false
    }

    // ---------------- scene ----------------
    var frame: Node? = null; private set
    var dealer: DealerRig? = null; private set
    private val chipPool = HashMap<Int, ArrayList<ModelNode>>()
    private val stacks = ArrayList<StackView>()
    private var rotorNode: ModelNode? = null
    private var ballNode: ModelNode? = null
    private var dollyNode: ModelNode? = null
    private var now = 0.0
    private var job: Job? = null
    var cameraWorld: FloatArray? = null

    fun attach(frame: Node, dealerNode: ModelNode?, tableNode: ModelNode?) {
        if (this.frame === frame && dealer?.node === dealerNode) return
        this.frame = frame; dealer = dealerNode?.let { DealerRig(engine, it) }
        Log.i("ZoharRO", "attached dealerOk=${dealer?.ok}")
        fun add(file: String) = runCatching { ModelNode(modelLoader.createModelInstance(file), autoAnimate = false).also { frame.addChildNode(it) } }.getOrNull()
        rotorNode = add("models/roul_rotor.glb"); ballNode = add("models/roul_ball.glb"); dollyNode = add("models/roul_dolly.glb")?.also { it.isVisible = false }
        tableNode?.let { setupMarquee(it) }
        scope.launch {
            delay(900)
            if (dealer?.has("ClearHands") == true) { dealer?.play("ClearHands", now); delay(3100) }
            dealer?.play("Welcome", now); delay(500); say("Welcome to European roulette. Place your bets.")
        }
        idleJob?.cancel(); idleJob = scope.launch { idleWatch() }
    }

    // ================= the wheel =================
    // rotor: turns slowly all the time; a flick from the dealer adds speed that dies away (angle has a closed form, so the
    // ball can be aimed at a pocket seconds ahead)
    private val wIdle = 0.5; private val wKick = 2.6; private val tauR = 9.0
    private var rotorBase = 0.0; private var rotorT0 = -1.0
    private var rotorIdleAngle = 0.0
    private fun rotorAt(t: Double): Double = if (rotorT0 < 0) rotorIdleAngle else { val s = t - rotorT0; rotorBase + wIdle * s + (wKick - wIdle) * tauR * (1 - exp(-s / tauR)) }
    // ball: launched on the track at angle beta0, slows (w·e^(−t/τ)), leaves the track at T1, bounces into the pocket by T2
    private val T1 = 6.5; private val T2 = 8.7; private val tauB = 4.0
    private var ballT0 = -1e9; private var beta0 = 0.0; private var wb0 = 14.0; private var target = 0; private var wrap = 0.0
    private val step = 2 * PI / 37
    private fun phi(n: Int) = -Wheel.pocketIndex(n) * step
    private fun ballFree(s: Double) = beta0 - wb0 * tauB * (1 - exp(-s / tauB))
    private fun inPocket(t: Double) = rotorAt(t) + phi(target)
    /** Height of the bowl / rotor surface at radius r (modelled at 0.62 m, scaled by k). */
    private fun surfY(r: Float): Float = R.k * surf0(r / R.k)
    private fun surf0(r: Float): Float = when {
        r > 0.205f -> -0.006f + 0.018f * ((r - 0.205f) / 0.078f).coerceIn(0f, 1f).pow(1.15f)
        r > 0.1935f -> -0.006f
        r > 0.163f -> -0.0062f + 0.0033f * (r - 0.163f) / 0.0282f
        else -> -0.0122f
    }

    /** Kick the rotor and send the ball so that it lands in [number]. Returns the seconds until it is in the pocket. */
    private fun launch(number: Int, ballDelay: Double): Double {
        rotorBase = rotorAt(now); if (rotorT0 < 0) rotorBase = rotorIdleAngle; rotorT0 = now
        target = number; ballT0 = now + ballDelay
        // the ball leaves the dealer's left hand on the near side of the track, going clockwise
        val start = kotlin.math.atan2((R.dealer.x - R.wheel.x).toDouble(), (R.dealer.z - R.wheel.z).toDouble())
        val c = tauB * (1 - exp(-T2 / tauB))
        val land = inPocket(ballT0 + T2)
        // total travel D = start − land (+ whole turns) so that the starting speed is about 14 rad/s
        var d = start - land; while (d < 0) d += 2 * PI
        while (d / c < 12.5) d += 2 * PI
        beta0 = start; wb0 = d / c
        wrap = land - ballFree(T2)                // whole turns between the free path and the pocket, so the blend never spins extra
        return ballDelay + T2
    }

    private fun updateWheel(dt: Float) {
        if (rotorT0 < 0) rotorIdleAngle += wIdle * dt
        val a = rotorAt(now)
        val w = R.wheel
        rotorNode?.transform = Transform(position = Position(w.x, w.y, w.z), quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), Math.toDegrees(a).toFloat()))
        val b = ballNode ?: return
        val s = now - ballT0
        val ang: Double; val r: Float; val y: Float
        when {
            s < 0 -> { ang = beta0; r = R.trackR; y = R.trackY; b.isVisible = true }                       // in the dealer's hand just before the throw
            s < T1 -> { ang = ballFree(s); r = R.trackR; y = R.trackY; b.isVisible = true }
            s < T2 -> {
                val u = ((s - T1) / (T2 - T1)).toFloat()
                val k = smooth(u).toDouble()
                ang = ballFree(s) + (inPocket(now) - wrap - ballFree(s)) * k + 0.18 * sin(u * 9.0) * (1 - u) * (1 - u)
                val ru = smooth((u * 1.3f).coerceAtMost(1f))
                r = R.trackR + (R.pocketR - R.trackR) * ru
                val hop = 0.011f * abs(sin(u * 3.4f * PI.toFloat())) * (1 - u) * (1 - u)
                y = maxOf(surfY(r) + R.ballR, R.pocketY) + hop
                b.isVisible = true
            }
            else -> { ang = inPocket(now); r = R.pocketR; y = R.pocketY; b.isVisible = true }
        }
        b.transform = Transform(position = Position(w.x + r * sin(ang).toFloat(), w.y + y, w.z + r * cos(ang).toFloat()))
    }

    // ================= per frame =================
    fun update(nowSec: Double, dt: Float) {
        now = nowSec
        frame ?: return
        dealer?.let { d -> d.lookAt = cameraWorld; d.update(now, dt) }
        updateWheel(dt)
        for (s in stacks) s.update(now)
        updateDolly()
    }

    // ================= betting =================
    /** Put chips down: one spot, or a racetrack bet's several spots, all or nothing. */
    private fun place(items: List<Pair<RSpot, Long>>, name: String?) {
        touched()
        if (ui != RUi.BETTING) return
        val add = HashMap<RSpot, Long>(); for ((sp, v) in items) add[sp] = (add[sp] ?: 0L) + v
        for ((sp, v) in add) { val max = if (sp.inside) game.maxInside else game.maxOutside; if ((bets[sp] ?: 0L) + v > max) { say("${sp.name} goes up to $max."); return } }
        val total = add.values.sum()
        if (staked + total > game.maxTotal) { say("Table maximum is ${game.maxTotal} a spin."); return }
        if (staked + total > wallet.credits) { sounds.play("no_credits"); line = "Not enough play credits"; return }
        for ((sp, v) in add) bets[sp] = (bets[sp] ?: 0L) + v
        placed.add(items)
        lastBet = if (name != null) "$name · ${items.size} chips · $total" else items[0].first.let { sp -> "${sp.name} · ${bets[sp]} · pays ${sp.pays} to 1" }
        sounds.play(if (items.size > 1) "chips_stack" else "chip"); for (sp in add.keys) showStack(sp)
    }
    fun bet(s: RSpot) = place(listOf(s to chipValue), null)
    /** A racetrack bet: the dealer puts the chips for it on the layout. */
    fun betRace(rb: RaceBet) {
        place(rb.pieces.map { (ns, m) -> grid.spotFor(ns) to chipValue * m }, rb.name)
        if (ui == RUi.BETTING && rb.pieces.size > 1) { dealer?.play("Nod", now); say(rb.name + ".") }
    }
    fun undo() {
        if (ui != RUi.BETTING) return; touched()
        val group = placed.removeLastOrNull() ?: return
        for ((sp, v) in group) { val left = (bets[sp] ?: 0L) - v; if (left > 0) bets[sp] = left else bets.remove(sp); showStack(sp) }
        sounds.play("chip"); lastBet = ""
    }
    fun clearBets() { if (ui != RUi.BETTING) return; touched(); bets.clear(); placed.clear(); refreshStacks(); sounds.play("chips_stack"); lastBet = "" }
    fun rebet() {
        if (ui != RUi.BETTING || lastBets.isEmpty()) return
        touched()
        if (lastBets.values.sum() > wallet.credits) { line = "Not enough play credits for the same bets"; return }
        bets.clear(); placed.clear(); bets.putAll(lastBets); placed.add(lastBets.map { it.key to it.value })
        refreshStacks(); sounds.play("chips_stack")
    }
    fun canRebet() = ui == RUi.BETTING && bets.isEmpty() && lastBets.isNotEmpty()
    fun canRefill() = ui == RUi.BETTING && wallet.credits < game.minBet && bets.isEmpty()
    fun refill() { if (!canRefill()) return; wallet.credits = RouWallet.START; credits = wallet.credits; say("Here are a thousand fresh play credits. Good luck.") }

    private val stacksBy = HashMap<RSpot, StackView>()
    private fun showStack(s: RSpot) {
        stacksBy.remove(s)?.let { removeStack(it) }
        val a = bets[s] ?: 0L
        if (a > 0) stacksBy[s] = stack(a, Float3(s.x, L.feltY, s.z))
        credits = wallet.credits - staked
    }
    private fun refreshStacks() {
        for (st in stacksBy.values) removeStack(st); stacksBy.clear()
        for (s in bets.keys) showStack(s)
        credits = wallet.credits - staked
    }

    // ================= the spin =================
    fun spin() {
        touched()
        if (!canSpin()) { game.validate(bets.toMap())?.let { line = it; say(it) }; return }
        seq {
            val stakes = bets.filterValues { it > 0 }
            lastBets = stakes
            val r = game.spin(stakes)                              // decided here, before the ball is thrown
            roundsPlayed++; outcome = ""; result = null
            credits = wallet.credits - r.returned
            hideDolly()
            Log.i("ZoharRO", "spin $roundsPlayed decided ${r.number}")
            clip("Spin")
            // the rotor flick lands at 0.5 s into the clip, the ball leaves the hand at 1.25 s
            waitUntil(0.5)
            val total = launch(r.number, 0.75)
            sounds.play("ball_roll", 0.9f)
            wait(3.6); say("No more bets.")
            wait(total - 3.6 - (T2 - T1) - 0.1); sounds.play("ball_drop", 1f)
            wait(T2 - T1 + 0.5)
            result = r.number
            history.add(0, r.number); if (history.size > 12) history.removeAt(history.size - 1)
            paintMarquee()
            val n = r.number
            say(if (n == 0) "Zero." else "$n, ${Wheel.color(n)}, ${if (n % 2 == 0) "even" else "odd"}.")
            wait(1.0)
            // the dolly goes on the winning number
            clip("DealPlayer"); waitUntil(0.6); showDolly(n); wait(0.8)
            settle(r)
        }
    }

    private suspend fun Seq.settle(r: RResult) {
        val lost = stacksBy.filterKeys { (r.returns[it] ?: 0L) == 0L }
        val won = stacksBy.filterKeys { (r.returns[it] ?: 0L) > 0L }
        if (lost.isNotEmpty()) { clip("Collect"); waitUntil(0.45); for (s in lost.values) collectStack(s); wait(0.3) }
        if (won.isNotEmpty()) {
            clip("Pay"); waitUntil(0.6)
            for ((b, s) in won) payStack(s, r.returns.getValue(b) - r.stakes.getValue(b))
            sounds.play("chips_stack"); onFx(Fx.COINS)
            val best = won.keys.minOf { it.numbers.size }
            if (best == 1) { onFx(Fx.BIG_WIN); sounds.play("big_win"); dealer?.play("Clap", now); say("Straight up! 35 to 1.", flush = false) }
            else if (best <= 4) { sounds.play("win_small"); say("Winner on the inside.", flush = false) }
            dealer?.smile = 1f
        } else dealer?.smile = 0.15f
        val net = r.returned - r.staked
        outcome = when { net > 0 -> "YOU WIN $net"; net == 0L -> "EVEN"; else -> "LOSE ${-net}" }
        Log.i("ZoharRO", "spin $roundsPlayed: ${r.number} net=$net credits=${wallet.credits}")
        credits = wallet.credits
        wait(1.8)
        for (s in won.values) takeHome(s)
        wait(0.8)
        for (s in stacksBy.values) removeStack(s); stacksBy.clear()
        hideDolly(); dealer?.smile = 0f
        bets.clear(); placed.clear(); credits = wallet.credits; lastBet = ""
        ui = RUi.BETTING; line = "Place your bets"
    }

    private fun recover() {
        for (s in stacks.toList()) removeStack(s); stacksBy.clear()
        bets.clear(); placed.clear(); credits = wallet.credits; ui = RUi.BETTING; line = "Place your bets"; hideDolly()
    }

    // ================= dolly =================
    private var dollyFrom = Float3(); private var dollyTo = Float3(); private var dollyT0 = -1.0
    private fun showDolly(n: Int) {
        val s = grid.straight(n); val d = dollyNode ?: return
        dollyFrom = Float3(R.dealer.x + 0.05f, L.feltY + 0.12f, 0.30f); dollyTo = Float3(s.x, L.feltY + L.chipH * (stacksBy[s]?.chips?.size ?: 0), s.z); dollyT0 = now
        d.isVisible = true; sounds.play("click", 0.7f)
    }
    private fun hideDolly() { dollyNode?.isVisible = false; dollyT0 = -1.0 }
    private fun updateDolly() {
        val d = dollyNode ?: return; if (dollyT0 < 0) return
        val u = smooth(((now - dollyT0) / 0.5).toFloat())
        val p = lerp(dollyFrom, dollyTo, u).let { it.copy(y = it.y + 0.04f * sin(u * PI.toFloat())) }
        d.transform = Transform(position = Position(p.x, p.y, p.z))
    }

    // ================= the number board =================
    private var marqueeTex: Texture? = null
    private var marqueeMat: com.google.android.filament.MaterialInstance? = null
    private val marqueeSampler = TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private fun setupMarquee(tableNode: ModelNode) {
        runCatching {
            val asset = tableNode.modelInstance.asset; val rm = engine.renderableManager
            val e = asset.getFirstEntityByName("MarqueeScreen"); if (e == 0 || !rm.hasComponent(e)) return
            marqueeMat = rm.getMaterialInstanceAt(rm.getInstance(e), 0)
            marqueeTex = Texture.Builder().width(256).height(400).levels(1).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8).build(engine)
            paintMarquee()
        }.onFailure { Log.w("ZoharRO", "marquee", it) }
    }
    private fun paintMarquee() {
        val t = marqueeTex ?: return; val m = marqueeMat ?: return
        val bmp = Bitmap.createBitmap(256, 400, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        c.drawColor(android.graphics.Color.rgb(5, 7, 10))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        p.color = android.graphics.Color.rgb(233, 196, 106); p.textSize = 34f; c.drawText("ZOHAR", 128f, 42f, p)
        p.color = android.graphics.Color.argb(120, 233, 196, 106); c.drawRect(20f, 54f, 236f, 56f, p)
        history.forEachIndexed { i, n ->
            if (i > 9) return@forEachIndexed
            val y = 100f + i * 30.5f + (if (i == 0) 6f else 12f)
            val x = when { n == 0 -> 128f; Wheel.isRed(n) -> 190f; else -> 66f }
            p.color = when { n == 0 -> android.graphics.Color.rgb(40, 210, 110); Wheel.isRed(n) -> android.graphics.Color.rgb(255, 60, 70); else -> android.graphics.Color.WHITE }
            p.textSize = if (i == 0) 48f else 28f
            c.drawText(n.toString(), x, y, p)
        }
        if (history.isEmpty()) { p.color = android.graphics.Color.WHITE; p.textSize = 26f; c.drawText("ROULETTE", 128f, 120f, p); p.textSize = 18f; p.color = android.graphics.Color.rgb(127, 224, 168); c.drawText("PLAY MONEY", 128f, 150f, p) }
        // the screen is a three.js plane: upload the rows flipped
        val mtx = android.graphics.Matrix().apply { setScale(1f, -1f); postTranslate(0f, 400f) }
        val flipped = Bitmap.createBitmap(bmp, 0, 0, 256, 400, mtx, true)
        runCatching { TextureHelper.setBitmap(engine, t, 0, flipped); m.setParameter("baseColorMap", t, marqueeSampler) }
    }

    // ================= choreography helpers =================
    private fun seq(block: suspend Seq.() -> Unit) {
        if (job?.isActive == true) return
        ui = RUi.BUSY
        job = scope.launch {
            try { Seq().block() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("ZoharRO", "sequence failed", e); recover()
            }
            if (ui == RUi.BUSY) { Log.w("ZoharRO", "sequence ended BUSY: recovering"); recover() }
        }
    }
    inner class Seq {
        private var mark = 0.0
        suspend fun wait(sec: Double) { if (sec > 0) delay((sec * 1000).toLong()) }
        fun clip(name: String) { dealer?.play(name, now); mark = now }
        suspend fun waitUntil(sec: Double) { val t = mark + sec - now; if (t > 0) delay((t * 1000).toLong()) }
        fun say(text: String, flush: Boolean = true) = this@RouletteTable.say(text, flush)
        suspend fun collectStack(s: StackView) {
            s.moveTo(now, Float3(R.rack.x, L.feltY + 0.01f, R.rack.z + 0.04f), 0.7); delay(720); s.hide()
            sounds.play("chips_stack", 0.6f)
        }
        suspend fun payStack(s: StackView, win: Long) {
            if (win <= 0) return
            val pay = stack(win, Float3(R.rack.x + 0.1f, L.feltY, R.rack.z + 0.06f)); pay.moveTo(now, Float3(s.base.x + 0.035f, L.feltY, s.base.z + 0.02f), 0.7); s.extra = pay
            delay(450)
        }
        fun takeHome(s: StackView) {
            val home = Float3(s.base.x * 0.6f, L.feltY, 1.08f)
            if (s.visible) s.moveTo(now, home, 0.7); s.extra?.let { if (it.visible) it.moveTo(now, home, 0.7) }
        }
    }
    var lineAt by mutableLongStateOf(0L); private set
    private fun say(text: String, flush: Boolean = true) { line = text; lineAt = android.os.SystemClock.uptimeMillis(); voice.say(text, flush) }

    // ---- if nobody plays for 3 minutes, the dealer makes a light joke (never pressure to bet) ----
    private var idleJob: Job? = null
    private var lastTouch = android.os.SystemClock.uptimeMillis()
    private var idleLine = 0
    fun touched() { lastTouch = android.os.SystemClock.uptimeMillis() }
    private val idleLines = listOf(
        "The ball is getting bored... it keeps asking me which number.",
        "Take your time. I'll just keep the wheel warm.",
        "Don't worry, the chips don't bite. Mostly.",
        "I've polished this wheel twice now. It's very shiny.",
    )
    private suspend fun idleWatch() {
        while (true) {
            delay(5000)
            val t = android.os.SystemClock.uptimeMillis()
            if (ui == RUi.BETTING && t - lastTouch > 180_000) {
                dealer?.play(if (idleLine % 2 == 0) "Talk" else "Shrug", now); dealer?.smile = 0.7f
                say(idleLines[idleLine % idleLines.size]); idleLine++
                lastTouch = t
                delay(4000); dealer?.smile = 0f
            }
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
        for (d in DENOMS) while (left >= d && chips.size < 20) { chips.add(d.toInt() to chipNode(d.toInt())); left -= d }
        val s = StackView(chips, base); stacks.add(s); s.layout(); return s
    }
    private fun removeStack(s: StackView) { s.extra?.let { removeStack(it) }; s.extra = null; for ((v, n) in s.chips) { n.isVisible = false; chipPool.getOrPut(v) { ArrayList() }.add(n) }; stacks.remove(s) }

    inner class StackView(val chips: List<Pair<Int, ModelNode>>, var base: Float3) {
        var extra: StackView? = null
        var visible = true; private set
        private var from = base; private var to = base; private var t0 = -1.0; private var dur = 0.5
        fun layout() {
            chips.forEachIndexed { i, (_, n) ->
                val j = (i * 2654435761L % 1000) / 1000f - 0.5f
                n.isVisible = visible
                n.transform = Transform(position = Position(base.x + j * 0.0014f, base.y + i * L.chipH, base.z + ((i * 40503L % 1000) / 1000f - 0.5f) * 0.0014f),
                    quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), i * 37f))
            }
        }
        fun moveTo(now: Double, p: Float3, d: Double) { from = base; to = p; t0 = now; dur = d }
        fun hide() { visible = false; for ((_, n) in chips) n.isVisible = false; extra?.hide() }
        fun update(now: Double) {
            if (t0 < 0) return
            val u = smooth(((now - t0) / dur).toFloat())
            base = lerp(from, to, u).let { it.copy(y = it.y + 0.03f * sin(u * PI.toFloat())) }
            if (now - t0 >= dur) { base = to; t0 = -1.0 }
            layout()
        }
    }

    fun close() { idleJob?.cancel(); voice.close(); job?.cancel(); marqueeTex?.let { runCatching { engine.destroyTexture(it) } } }

    companion object {
        fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        fun lerp(a: Float3, b: Float3, u: Float) = Float3(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u)
        @Suppress("unused") private fun r2(x: Float) = (x * 100).roundToInt() / 100f
    }
}
