package com.zohar.slotar.moneywheel

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
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
import kotlin.math.floor
import kotlin.math.sin

/** Money wheel stage on the shared frame (tools/3d/blackjack/layout.js → mw). */
class MwLayout(j: JSONObject) {
    private val m = j.getJSONObject("mw")
    private fun v(n: String) = m.getJSONArray(n).let { Float3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
    val wheel = v("wheel"); val flapper = v("flapper"); val host = v("host"); val hostYaw = m.getDouble("hostYaw").toFloat()
    val deskY = m.getJSONObject("desk").getDouble("y").toFloat(); val deskZ = m.getJSONObject("desk").getDouble("z").toFloat()
    val spots = m.getJSONArray("spots").let { a -> (0 until a.length()).map { a.getDouble(it).toFloat() } }
    val spotZ = m.getDouble("spotZ").toFloat(); val spotR = m.getDouble("spotR").toFloat(); val front = m.getDouble("front").toFloat()
}

/** Play-money wallet for the Money Wheel app, kept on the phone. Free refill when it runs low. */
class MwWallet(context: Context) : Wallet {
    private val prefs = context.getSharedPreferences("zohar_mw", Context.MODE_PRIVATE)
    override var credits: Long = prefs.getLong("credits", START)
        set(v) { field = v; prefs.edit().putLong("credits", v).apply() }
    companion object { const val START = 1000L }
}

enum class MUi { BETTING, BUSY }

/**
 * The money wheel show: engine + everything shown. The engine draws every spin first; the host pulls the wheel and the
 * wheel's deceleration is worked out so the flapper stops on exactly that segment.
 */
class MoneyWheelTable(
    private val context: Context, private val engine: Engine, private val modelLoader: ModelLoader,
    private val sounds: SlotSounds, private val scope: CoroutineScope, private val onFx: (Fx) -> Unit,
    private val rng: Rng = SecureRng, wallet: Wallet? = null, speech: Boolean = true,
) {
    private val layoutJson = JSONObject(context.assets.open("bj_layout.json").bufferedReader().readText())
    val L = BjLayout(layoutJson)
    val M = MwLayout(layoutJson)
    val tapY get() = M.deskY
    val wallet: Wallet = wallet ?: MwWallet(context)
    val game = MoneyWheelEngine(this.wallet, rng)
    val voice = Voice(context, speech, female = true) { dealer?.talking = it }

    // ---------------- UI state ----------------
    var ui by mutableStateOf(MUi.BETTING); private set
    var credits by mutableLongStateOf(this.wallet.credits); private set
    val bets = mutableStateMapOf<Int, Long>()
    var chipValue by mutableLongStateOf(25L)
    private val placed = ArrayList<Pair<Int, Long>>()
    private var lastBets: Map<Int, Long> = emptyMap()
    val staked get() = bets.values.sum()
    fun canSpin() = ui == MUi.BETTING && game.validate(bets.toMap()) == null
    var line by mutableStateOf("Place your bets"); private set
    var outcome by mutableStateOf(""); private set
    var multiplier by mutableIntStateOf(1); private set              // shown while multipliers stack up
    var landed by mutableStateOf<String?>(null); private set          // the segment the wheel just stopped on
    val history = mutableStateListOf<String>()
    private var rounds = 0

    fun tapFelt(x: Float, z: Float): Boolean {
        if (ui != MUi.BETTING) return false
        val i = M.spots.indexOfFirst { sx -> (x - sx) * (x - sx) + (z - M.spotZ) * (z - M.spotZ) <= M.spotR * M.spotR * 1.4f }
        if (i < 0) return false
        bet(MoneyWheel.NUMBERS[i]); return true
    }

    // ---------------- scene ----------------
    var frame: Node? = null; private set
    var dealer: DealerRig? = null; private set
    private var wheelNode: ModelNode? = null
    private var flapperNode: ModelNode? = null
    private var bulbsA: MaterialInstance? = null
    private var bulbsB: MaterialInstance? = null
    private val chipPool = HashMap<Int, ArrayList<ModelNode>>()
    private val stacks = ArrayList<StackView>()
    private var now = 0.0
    private var job: Job? = null
    var cameraWorld: FloatArray? = null

    fun attach(frame: Node, hostNode: ModelNode?) {
        if (this.frame === frame && dealer?.node === hostNode) return
        this.frame = frame; dealer = hostNode?.let { DealerRig(engine, it) }
        Log.i("ZoharMW", "attached hostOk=${dealer?.ok}")
        fun add(file: String) = runCatching { ModelNode(modelLoader.createModelInstance(file), autoAnimate = false).also { frame.addChildNode(it) } }.getOrNull()
        wheelNode = add("models/mw_wheel.glb"); flapperNode = add("models/mw_flapper.glb")
        wheelNode?.let { n ->
            val asset = n.modelInstance.asset; val rm = engine.renderableManager
            fun mat(name: String) = asset.getFirstEntityByName(name).takeIf { it != 0 && rm.hasComponent(it) }?.let { rm.getMaterialInstanceAt(rm.getInstance(it), 0) }
            bulbsA = mat("BulbsA"); bulbsB = mat("BulbsB")
        }
        scope.launch {
            delay(800)
            dealer?.play("Welcome", now); delay(600)
            say("Hi, and welcome to the ZOHAR Money Wheel! Place your bets on the numbers, and I'll spin.")
        }
        idleJob?.cancel(); idleJob = scope.launch { idleWatch() }
    }

    // ================= the wheel =================
    private val n = MoneyWheel.SEGMENTS.size
    private val step = 2 * PI / n
    private var angle = 0.0                     // clockwise, seen from the player
    private var spinFrom = 0.0; private var spinTo = 0.0; private var spinT0 = -1.0; private var spinDur = 8.0
    private var lastPeg = 0
    private var flap = 0f; private var flapVel = 0f
    private var lastTick = 0.0
    var lights = Lights.IDLE
    enum class Lights { IDLE, CHASE, WIN, MULTI }

    /** Spin so that segment [index] stops under the flapper. Returns the duration (s). */
    private fun spinTo(index: Int): Double {
        val off = (rng.next(1000) / 1000.0 - 0.5) * 0.6 * step               // somewhere inside the segment, not always the middle
        val target = -index * step + off
        var d = (target - angle) % (2 * PI); if (d < 0) d += 2 * PI
        d += 2 * PI * (2 + rng.next(2))                                       // 2–3 whole turns more
        spinFrom = angle; spinTo = angle + d; spinT0 = now; spinDur = 6.5 + d / (2 * PI) * 0.45
        return spinDur
    }
    private fun updateWheel(dt: Float) {
        if (spinT0 >= 0) {
            val u = ((now - spinT0) / spinDur).coerceIn(0.0, 1.0)
            val e = 1 - (1 - u) * (1 - u) * (1 - u)                                 // fast start, long slow-down
            angle = spinFrom + (spinTo - spinFrom) * e
            if (u >= 1.0) spinT0 = -1.0
        }
        val w = M.wheel
        wheelNode?.transform = Transform(position = Position(w.x, w.y, w.z), quaternion = Quaternion.fromAxisAngle(Float3(0f, 0f, 1f), Math.toDegrees(-angle).toFloat()))
        // the flapper rides over the pegs: pegs sit on the segment lines; at the top the wheel shows angle −angle
        val ph = ((-angle / step - 0.5) % 1.0 + 1.0) % 1.0
        val e = if (ph < 0.5) ph else ph - 1.0                                    // signed distance to the nearest peg, in segments
        val bump = (1.0 - abs(e) / 0.42).coerceIn(0.0, 1.0)
        val target = (0.55 * bump * bump).toFloat()                                // the passing peg pushes the tip to the right
        flapVel += ((target - flap) * 180f - flapVel * 14f) * dt; flap += flapVel * dt
        if (bump > 0.9) flap = maxOf(flap, target)
        flapperNode?.transform = Transform(position = Position(M.flapper.x, M.flapper.y, M.flapper.z), quaternion = Quaternion.fromAxisAngle(Float3(0f, 0f, 1f), Math.toDegrees(flap.toDouble()).toFloat()))
        val peg = floor(-angle / step - 0.5).toInt()
        if (peg != lastPeg) { lastPeg = peg; if (now - lastTick > 0.045) { lastTick = now; sounds.play("click", 0.55f, 1.25f) } }
        // bulbs
        val on = floatArrayOf(1.0f, 0.82f, 0.5f); val off = floatArrayOf(0.10f, 0.07f, 0.04f)
        val (a, b) = when (lights) {
            Lights.IDLE -> { val k = ((now / 0.7).toInt() % 2 == 0); if (k) on to off else off to on }
            Lights.CHASE -> { val k = ((now / 0.09).toInt() % 2 == 0); if (k) on to off else off to on }
            Lights.WIN -> { val k = ((now / 0.16).toInt() % 2 == 0); if (k) on to on else off to off }
            Lights.MULTI -> { val k = ((now / 0.06).toInt() % 2 == 0); if (k) on to off else off to on }
        }
        val s = when (lights) { Lights.WIN, Lights.MULTI -> 4f; Lights.CHASE -> 3f; else -> 2.2f }
        runCatching { bulbsA?.setParameter("emissiveFactor", a[0] * s, a[1] * s, a[2] * s); bulbsB?.setParameter("emissiveFactor", b[0] * s, b[1] * s, b[2] * s) }
    }
    val spinning get() = spinT0 >= 0

    // ================= per frame =================
    fun update(nowSec: Double, dt: Float) {
        now = nowSec
        frame ?: return
        dealer?.let { d -> d.lookAt = cameraWorld; d.update(now, dt) }
        updateWheel(dt)
        for (s in stacks) s.update(now)
    }

    // ================= betting =================
    fun bet(num: Int) {
        touched()
        if (ui != MUi.BETTING) return
        val v = chipValue; val cur = bets[num] ?: 0L
        if (cur + v > game.maxBet) { say("Up to ${game.maxBet} on $num."); return }
        if (staked + v > game.maxTotal) { say("The maximum is ${game.maxTotal} a spin."); return }
        if (staked + v > wallet.credits) { sounds.play("no_credits"); line = "Not enough play credits"; return }
        bets[num] = cur + v; placed.add(num to v); sounds.play("chip"); showStack(num)
    }
    fun undo() { if (ui != MUi.BETTING) return; touched(); val (k, v) = placed.removeLastOrNull() ?: return; val left = (bets[k] ?: 0L) - v; if (left > 0) bets[k] = left else bets.remove(k); showStack(k); sounds.play("chip") }
    fun clearBets() { if (ui != MUi.BETTING) return; touched(); bets.clear(); placed.clear(); for (k in stacksBy.keys.toList()) showStack(k); sounds.play("chips_stack") }
    fun canRebet() = ui == MUi.BETTING && bets.isEmpty() && lastBets.isNotEmpty()
    fun rebet() {
        if (!canRebet()) return; touched()
        if (lastBets.values.sum() > wallet.credits) { line = "Not enough play credits for the same bets"; return }
        bets.putAll(lastBets); placed.clear(); for ((k, v) in lastBets) placed.add(k to v); for (k in lastBets.keys) showStack(k); sounds.play("chips_stack")
    }
    fun canRefill() = ui == MUi.BETTING && wallet.credits < game.minBet && bets.isEmpty()
    fun refill() { if (!canRefill()) return; wallet.credits = MwWallet.START; credits = wallet.credits; say("Here are a thousand fresh play credits. Have fun!") }

    private val stacksBy = HashMap<Int, StackView>()
    private fun spotPos(num: Int) = Float3(M.spots[MoneyWheel.NUMBERS.indexOf(num)], M.deskY, M.spotZ)
    private fun showStack(num: Int) {
        stacksBy.remove(num)?.let { removeStack(it) }
        val a = bets[num] ?: 0L
        if (a > 0) stacksBy[num] = stack(a, spotPos(num))
        credits = wallet.credits - staked
    }

    // ================= the spin =================
    fun spin() {
        touched()
        if (!canSpin()) { game.validate(bets.toMap())?.let { line = it; say(it) }; return }
        seq {
            val stakes = bets.filterValues { it > 0 }; lastBets = stakes
            val r = game.spin(stakes)                                  // every spin of this round is decided here
            rounds++; outcome = ""; landed = null; multiplier = 1
            credits = wallet.credits - r.returned
            Log.i("ZoharMW", "round $rounds decided ${r.spins.map { it.segment }}")
            say(listOf("Here we go!", "Good luck, everyone!", "Let's see where it stops!", "Round and round it goes!")[rounds % 4])
            var mult = 1
            for ((i, s) in r.spins.withIndex()) {
                clip("SpinWheel"); waitUntil(0.85)
                val dur = spinTo(s.index); lights = Lights.CHASE
                wait(1.2); if (i == 0) say("No more bets.", flush = false)
                wait(dur - 1.2 + 0.25)
                landed = s.segment
                val m = s.multiplier
                if (m != null) {
                    mult *= m; multiplier = mult; lights = Lights.MULTI; onFx(Fx.BIG_WIN); sounds.play("free_spins")
                    clip("Excited"); say(if (mult == m) "$m times multiplier! We spin again, and every win is multiplied!" else "Another one! That's ${mult} times now! Spin again!")
                    history.add(0, s.segment)
                    wait(2.6); lights = Lights.CHASE
                } else {
                    history.add(0, s.segment); if (history.size > 14) history.removeAt(history.size - 1)
                    say(if (mult > 1) "${s.segment}, times $mult!" else "${s.segment}!", flush = true)
                    wait(1.0)
                }
            }
            settle(r)
        }
    }

    private suspend fun Seq.settle(r: MwResult) {
        val won = stacksBy.filterKeys { (r.returns[it] ?: 0L) > 0 }
        val lost = stacksBy.filterKeys { it !in won.keys }
        if (lost.isNotEmpty()) { for (s in lost.values) collectStack(s); wait(0.4) }
        val net = r.returned - r.staked
        if (won.isNotEmpty()) {
            lights = Lights.WIN
            for ((k, s) in won) payStack(s, r.returns.getValue(k) - r.stakes.getValue(k))
            sounds.play("chips_stack"); onFx(Fx.COINS)
            val big = net >= r.staked * 10
            if (big) { onFx(Fx.BIG_WIN); sounds.play("big_win"); clip("Cheer"); dealer?.smile = 1f; say("Wow! What a win! Congratulations!", flush = false) }
            else { sounds.play("win_small"); clip("Clap"); dealer?.smile = 0.9f; say("You win! Well done!", flush = false) }
        } else {
            clip(if (rounds % 2 == 0) "Shrug" else "Talk"); dealer?.smile = 0.3f
            say(listOf("Not this time. The wheel is waiting for you.", "So close! Another spin?", "Next time, maybe!")[rounds % 3], flush = false)
        }
        outcome = when { net > 0 -> "YOU WIN ${net}"; net == 0L -> "EVEN"; else -> "LOSE ${-net}" }
        credits = wallet.credits
        Log.i("ZoharMW", "round $rounds: ${r.number} x${r.multiplier} net=$net credits=${wallet.credits}")
        wait(2.4)
        for (s in won.values) takeHome(s)
        wait(0.8)
        for (s in stacksBy.values) removeStack(s); stacksBy.clear()
        bets.clear(); placed.clear(); multiplier = 1; lights = Lights.IDLE; dealer?.smile = 0f
        credits = wallet.credits; ui = MUi.BETTING; line = "Place your bets"
    }

    private fun recover() {
        for (s in stacks.toList()) removeStack(s); stacksBy.clear()
        bets.clear(); placed.clear(); multiplier = 1; lights = Lights.IDLE
        credits = wallet.credits; ui = MUi.BETTING; line = "Place your bets"
    }

    // ================= choreography =================
    private fun seq(block: suspend Seq.() -> Unit) {
        if (job?.isActive == true) return
        ui = MUi.BUSY
        job = scope.launch {
            try { Seq().block() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("ZoharMW", "sequence failed", e); recover()
            }
            if (ui == MUi.BUSY) recover()
        }
    }
    inner class Seq {
        private var mark = 0.0
        suspend fun wait(sec: Double) { if (sec > 0) delay((sec * 1000).toLong()) }
        fun clip(name: String) { dealer?.play(name, now); mark = now }
        suspend fun waitUntil(sec: Double) { val t = mark + sec - now; if (t > 0) delay((t * 1000).toLong()) }
        fun say(text: String, flush: Boolean = true) = this@MoneyWheelTable.say(text, flush)
        suspend fun collectStack(s: StackView) { s.moveTo(now, Float3(s.base.x * 0.3f, M.deskY, M.deskZ - 0.3f), 0.6); delay(620); s.hide(); sounds.play("chips_stack", 0.5f) }
        suspend fun payStack(s: StackView, win: Long) {
            if (win <= 0) return
            val pay = stack(win, Float3(s.base.x, M.deskY, M.deskZ - 0.3f)); pay.moveTo(now, Float3(s.base.x + 0.05f, M.deskY, s.base.z + 0.03f), 0.6); s.extra = pay
            delay(420)
        }
        fun takeHome(s: StackView) {
            val home = Float3(s.base.x, M.deskY, M.front + 0.15f)
            if (s.visible) s.moveTo(now, home, 0.7); s.extra?.let { if (it.visible) it.moveTo(now, home, 0.7) }
        }
    }
    var lineAt by mutableLongStateOf(0L); private set
    private fun say(text: String, flush: Boolean = true) { line = text; lineAt = android.os.SystemClock.uptimeMillis(); voice.say(text, flush) }

    // ---- if nobody plays for 3 minutes, the host makes a light joke (never pressure to bet) ----
    private var idleJob: Job? = null
    private var lastTouch = android.os.SystemClock.uptimeMillis()
    private var idleLine = 0
    fun touched() { lastTouch = android.os.SystemClock.uptimeMillis() }
    private val idleLines = listOf(
        "The wheel is getting dizzy just standing here!",
        "Take your time. I'll keep the lights warm.",
        "Fun fact: this wheel has fifty-four segments. I counted. Twice.",
        "I promise the wheel doesn't bite. The flapper might tickle.",
    )
    private suspend fun idleWatch() {
        while (true) {
            delay(5000)
            val t = android.os.SystemClock.uptimeMillis()
            if (ui == MUi.BETTING && t - lastTouch > 180_000) {
                dealer?.play(listOf("Laugh", "Hair", "Talk", "Shrug")[idleLine % 4], now); dealer?.smile = 0.8f
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

    fun close() { idleJob?.cancel(); voice.close(); job?.cancel() }

    companion object {
        fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        fun lerp(a: Float3, b: Float3, u: Float) = Float3(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u)
    }
}
