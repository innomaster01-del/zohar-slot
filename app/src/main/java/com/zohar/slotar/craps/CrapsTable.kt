package com.zohar.slotar.craps

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.filament.Engine
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
import kotlin.math.sin

/** Craps spots on the tub table (tools/3d/blackjack/layout.js → craps). */
class CrapsLayout(j: JSONObject) {
    private val c = j.getJSONObject("craps")
    private fun f(n: String) = c.getDouble(n).toFloat()
    private fun v(n: String) = c.getJSONArray(n).let { Float3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
    private fun two(n: String) = c.getJSONArray(n).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() }
    val numbers = c.getJSONArray("numbers").let { a -> List(a.length()) { a.getInt(it) } }
    val pointZ = f("pointZ"); val pointX0 = f("pointX0"); val pointDx = f("pointDx"); val pointD = f("pointD")
    val field = v("field"); val fieldW = f("fieldW"); val fieldD = f("fieldD"); val fieldChips = v("fieldChips")
    val pass = v("pass"); val passW = f("passW"); val passD = f("passD"); val passSideZ = f("passSideZ"); val passChips = v("passChips")
    val puckOff = v("puckOff"); val die = f("die"); val throwFrom = v("throwFrom"); val wallZ = f("wallZ")
    val hitX = two("hitX"); val hitY = two("hitY")
    private val rest = c.getJSONObject("rest")
    val restX = rest.getDouble("x").toFloat(); val restZ0 = rest.getDouble("z0").toFloat(); val restZ1 = rest.getDouble("z1").toFloat()
    val seatEye = v("seatEye"); val zNear = f("zNear"); val wallT = f("wallT")
    /** Where the puck sits when the point is `n`: at the top of that number's box. */
    fun puckOn(n: Int) = Float3(pointX0 + numbers.indexOf(n) * pointDx, field.y, pointZ - pointD / 2 + 0.042f)
}

/** Play-money wallet for the Craps app, kept on the phone. Free refill when it runs low. */
class CrapsWallet(context: Context) : Wallet {
    private val prefs = context.getSharedPreferences("zohar_craps", Context.MODE_PRIVATE)
    override var credits: Long = prefs.getLong("credits", START)
        set(v) { field = v; prefs.edit().putLong("credits", v).apply() }
    companion object { const val START = 1000L }
}

enum class CUi { BETTING, BUSY }

/** What the stickman calls for a roll, the way it is called at a table. */
fun stickCall(r: CrapsRoll): String {
    val s = r.sum; val hard = if (r.hard && s in listOf(4, 6, 8, 10)) ", the hard way" else ""
    return when (r.pass) {
        PassOutcome.NATURAL -> if (s == 7) "Seven, winner seven! Pay the line." else "Yo eleven! Winner, pay the line."
        PassOutcome.CRAPS -> when (s) { 2 -> "Two, craps, aces. Line away."; 3 -> "Three, craps, ace deuce. Line away."; else -> "Twelve, craps, boxcars. Line away." }
        PassOutcome.POINT_SET -> "$s$hard. The point is $s."
        PassOutcome.POINT_MADE -> "$s$hard, winner! The point is made. Pay the line."
        PassOutcome.SEVEN_OUT -> "Seven out. Line away."
        PassOutcome.NONE -> when (s) { 11 -> "Yo eleven."; 2 -> "Two, aces."; 3 -> "Three, ace deuce."; 12 -> "Twelve, boxcars."; else -> "$s$hard." }
    }
}

/**
 * Craps table: engine + everything shown on it. The engine draws both dice before they leave the hand; the throw on
 * the table is laid out so that they come to rest on exactly those faces.
 */
class CrapsTable(
    private val context: Context, private val engine: Engine, private val modelLoader: ModelLoader,
    private val sounds: SlotSounds, private val scope: CoroutineScope, private val onFx: (Fx) -> Unit,
    rng: Rng = SecureRng, wallet: Wallet? = null, speech: Boolean = true,
) {
    private val layoutJson = JSONObject(context.assets.open("bj_layout.json").bufferedReader().readText())
    val L = BjLayout(layoutJson)
    val C = CrapsLayout(layoutJson)
    val wallet: Wallet = wallet ?: CrapsWallet(context)
    val game = CrapsEngine(this.wallet, rng)
    val voice = Voice(context, speech) { dealer?.talking = it }
    // only how the throw looks comes from here (where it strikes the wall, where it lies, how it tumbles), never the faces
    private val look = java.util.Random()
    private fun rnd() = look.nextFloat()

    // ---------------- UI state ----------------
    var ui by mutableStateOf(CUi.BETTING); private set
    var credits by mutableLongStateOf(this.wallet.credits); private set
    val bets = androidx.compose.runtime.mutableStateMapOf<CrapsBet, Long>()
    var target by mutableStateOf(CrapsBet.PASS)
    var chipValue by mutableLongStateOf(25L)
    private var lastPass = 25L
    var point by mutableStateOf<Int?>(null); private set
    /** The last throw, once the dice have come to rest (the big dice on the screen show it). */
    var shown by mutableStateOf<CrapsRoll?>(null); private set
    val history = androidx.compose.runtime.mutableStateListOf<Int>()
    var line by mutableStateOf("Place your bets"); private set
    var outcome by mutableStateOf(""); private set
    val rolls get() = rollsThrown
    private var rollsThrown = 0
    /** What a throw would put at risk now: the field bet, and the line bet when it is not riding yet. */
    private val uncommitted get() = (bets[CrapsBet.FIELD] ?: 0L) + (if (game.point == null) bets[CrapsBet.PASS] ?: 0L else 0L)
    fun canRoll() = ui == CUi.BETTING && game.validate(bets[CrapsBet.PASS] ?: 0L, bets[CrapsBet.FIELD] ?: 0L) == null
    val passLocked get() = point != null

    /** A tap on the felt: the Pass Line (along the player's side and up both ends) or the Field. */
    fun tapFelt(x: Float, z: Float): Boolean {
        if (ui != CUi.BETTING) return false
        val hw = C.passW / 2
        val onLine = (abs(x) <= hw && abs(z - C.pass.z) <= C.passD / 2 + 0.01f) || (abs(abs(x) - (hw - C.passD / 2)) <= C.passD / 2 + 0.01f && z in C.passSideZ..C.pass.z)
        val onField = abs(x - C.field.x) <= C.fieldW / 2 && abs(z - C.field.z) <= C.fieldD / 2
        val spot = when { onLine -> CrapsBet.PASS; onField -> CrapsBet.FIELD; else -> return false }
        target = spot; addChip(chipValue); return true
    }

    // ---------------- scene ----------------
    var frame: Node? = null; private set
    var dealer: DealerRig? = null; private set
    private val chipPool = HashMap<Int, ArrayList<ModelNode>>()
    private val stacks = ArrayList<StackView>()
    private val stacksBy = HashMap<CrapsBet, StackView>()
    val dice = ArrayList<DieView>()
    private var puck: PuckView? = null
    private var now = 0.0
    private var job: Job? = null
    var cameraWorld: FloatArray? = null

    fun attach(frame: Node, dealerNode: ModelNode?) {
        if (this.frame === frame && dealer?.node === dealerNode) return
        val first = this.frame == null
        this.frame = frame; dealer = dealerNode?.let { DealerRig(engine, it) }
        Log.i("ZoharCR", "attached dealerOk=${dealer?.ok}")
        if (first) {
            repeat(2) { i ->
                val n = ModelNode(modelLoader.createModelInstance("models/craps_die.glb"), autoAnimate = false); frame.addChildNode(n)
                // before the first throw the dice wait on the felt in front of the stickman, showing a seven
                dice.add(DieView(n).also { it.place(Float3(-0.03f + i * 0.06f, L.feltY + C.die / 2, 0.40f), if (i == 0) 3 else 4, 20f + i * 50f) })
            }
            val pn = ModelNode(modelLoader.createModelInstance("models/craps_puck.glb"), autoAnimate = false); frame.addChildNode(pn)
            puck = PuckView(pn).also { it.set(null, 0.0, jump = true) }
            showStacks()
        }
        scope.launch {
            delay(900)
            if (dealer?.has("ClearHands") == true) { dealer?.play("ClearHands", now); delay(3100) }
            dealer?.play("Welcome", now); delay(500); say("Welcome to the craps table. New shooter coming out. Place your bets.")
        }
        idleJob?.cancel(); idleJob = scope.launch { idleWatch() }
    }

    // ================= per frame =================
    fun update(nowSec: Double, dt: Float) {
        now = nowSec
        frame ?: return
        dealer?.let { d -> d.lookAt = cameraWorld; d.update(now, dt) }
        for (d in dice) d.update(now)
        puck?.update(now)
        for (s in stacks) s.update(now)
    }

    // ================= betting =================
    fun addChip(v: Long) {
        touched()
        if (ui != CUi.BETTING) return
        if (target == CrapsBet.PASS && passLocked) { say("The line bet stays as it is while the point is on."); return }
        val cur = bets[target] ?: 0L
        if (cur + v > game.maxBet) { say("${target.label} goes up to ${game.maxBet}."); return }
        if (uncommitted + v > wallet.credits) { sounds.play("no_credits"); line = "Not enough play credits"; return }
        bets[target] = cur + v; outcome = ""
        sounds.play("chip"); showStacks()
    }
    /** Take down what may be taken down: the field always, the line only before a point is on. */
    fun clearBets() {
        if (ui != CUi.BETTING) return; touched()
        bets.remove(CrapsBet.FIELD); if (!passLocked) bets.remove(CrapsBet.PASS)
        showStacks(); sounds.play("chips_stack")
    }
    fun canClear() = ui == CUi.BETTING && ((bets[CrapsBet.FIELD] ?: 0L) > 0 || (!passLocked && (bets[CrapsBet.PASS] ?: 0L) > 0))
    fun canRefill() = ui == CUi.BETTING && !passLocked && wallet.credits < game.minBet
    fun refill() { if (!canRefill()) return; wallet.credits = CrapsWallet.START; credits = wallet.credits; say("Here are a thousand fresh play credits. Good luck.") }
    private fun spot(b: CrapsBet) = (if (b == CrapsBet.PASS) C.passChips else C.fieldChips).let { Float3(it.x, L.feltY, it.z) }
    private fun showStacks() {
        if (frame == null) return
        for (s in stacksBy.values) removeStack(s); stacksBy.clear()
        for ((b, a) in bets) if (a > 0) stacksBy[b] = stack(a, spot(b))
        credits = wallet.credits - uncommitted
    }

    // ================= the throw =================
    /** Throw the dice. `side` −1 / +1 = toward the left / right section of the far wall (0 = either). */
    fun roll(side: Int = 0) {
        touched()
        val pass = bets[CrapsBet.PASS] ?: 0L; val field = bets[CrapsBet.FIELD] ?: 0L
        if (ui != CUi.BETTING) return
        game.validate(pass, field)?.let { line = it; say(it); return }
        seq {
            if (game.point == null) lastPass = pass
            val r = game.roll(pass, field)                              // both dice are decided here, before they fly
            rollsThrown++; outcome = ""; shown = null
            credits = wallet.credits - r.passReturned - r.fieldReturned // winnings show when they are paid
            if (r.comeOut) say("Coming out.", flush = false)
            sounds.play("dice_shake"); wait(0.45)
            val t = throwDice(r, if (side != 0) side else if (rnd() < 0.5f) -1 else 1)
            wait(t + 0.2)
            shown = r; history.add(r.sum); if (history.size > 14) history.removeAt(0)
            say(stickCall(r))
            Log.i("ZoharCR", "roll $rollsThrown: ${r.d1}+${r.d2}=${r.sum} ${r.pass} point ${r.pointBefore}→${r.pointAfter} line ${r.passStake}→${r.passReturned} field ${r.fieldStake}→${r.fieldReturned} credits=${wallet.credits}")
            wait(0.9)
            settle(r)
        }
    }

    /** Lay out both flights and start them; returns how long the longer one takes. The faces are the engine's. */
    private fun throwDice(r: CrapsRoll, side: Int): Double {
        val y = L.feltY + C.die / 2
        val hx = side * (C.hitX.first + (C.hitX.second - C.hitX.first) * rnd())
        val rx = (-C.restX + 2 * C.restX * rnd()).coerceIn(-C.restX + 0.06f, C.restX - 0.06f); val rz = C.restZ0 + (C.restZ1 - C.restZ0) * rnd()
        var longest = 0.0
        dice.forEachIndexed { i, d ->
            val sgn = if (i == 0) -1f else 1f
            val from = Float3(C.throwFrom.x + sgn * 0.025f, C.throwFrom.y, C.throwFrom.z)
            val wall = Float3(hx + sgn * (0.02f + 0.03f * rnd()), L.feltY + C.hitY.first + (C.hitY.second - C.hitY.first) * rnd(), C.wallZ + C.die / 2 + 0.008f)
            val rest = Float3(rx + sgn * (0.028f + 0.03f * rnd()), y, (rz + sgn * 0.03f * (rnd() - 0.5f)).coerceIn(C.restZ0, C.restZ1))
            val legs = DiceMath.flight(floatArrayOf(from.x, from.y, from.z), floatArrayOf(wall.x, wall.y, wall.z), floatArrayOf(rest.x, rest.y, rest.z), ::rnd)
            d.fly(now + i * 0.03, legs, if (i == 0) r.d1 else r.d2, 360f * rnd(), Float3(rnd() - 0.5f, rnd() - 0.5f, rnd() - 0.5f), 900f + 700f * rnd())
            longest = maxOf(longest, DiceMath.duration(legs) + i * 0.03)
            // the sounds of this die: the wall once for both, then each landing a little softer
            scope.launch {
                var at = 0.0
                legs.forEachIndexed { k, l ->
                    val wait = l.t0 + (if (i == 1) 0.03 else 0.0) - at; if (wait > 0) delay((wait * 1000).toLong()); at += maxOf(wait, 0.0)
                    if (k == 1 && i == 0) sounds.play("dice_wall")
                    if (k in 2..4) sounds.play("dice_hit", 0.9f - 0.22f * (k - 2), 0.95f + 0.1f * rnd())
                }
            }
        }
        return longest
    }

    private suspend fun Seq.settle(r: CrapsRoll) {
        val fieldStack = stacksBy[CrapsBet.FIELD]; val lineStack = stacksBy[CrapsBet.PASS]
        val lineLost = r.pass == PassOutcome.CRAPS || r.pass == PassOutcome.SEVEN_OUT
        val lost = listOfNotNull(fieldStack.takeIf { r.fieldStake > 0 && !r.fieldWon }, lineStack.takeIf { lineLost })
        if (lost.isNotEmpty()) { clip("Collect"); waitUntil(0.45); for (s in lost) collectStack(s); wait(0.3) }
        val won = listOfNotNull((fieldStack to (r.fieldReturned - r.fieldStake)).takeIf { r.fieldWon && fieldStack != null }, (lineStack to (r.passReturned - r.passStake)).takeIf { r.passWon && lineStack != null })
        if (won.isNotEmpty()) {
            clip("Pay"); waitUntil(0.6)
            for ((s, win) in won) payStack(s!!, win)
            sounds.play("chips_stack"); onFx(Fx.COINS)
            if (r.fieldWon && (r.sum == 2 || r.sum == 12)) { onFx(Fx.BIG_WIN); sounds.play("big_win"); dealer?.play("Clap", now) }
            dealer?.smile = 1f
        } else if (lost.isNotEmpty()) dealer?.smile = 0.15f
        // the puck follows the point
        if (r.pointAfter != r.pointBefore) { puck?.set(r.pointAfter, now); sounds.play("click"); wait(0.5) }
        point = game.point
        val net = r.net
        outcome = when {
            net > 0 -> "YOU WIN $net"
            net < 0 -> "LOSE ${-net}"
            r.pass == PassOutcome.POINT_SET -> "POINT IS ${r.sum}"
            else -> ""
        }
        credits = wallet.credits
        if (won.isNotEmpty()) { wait(1.2); for ((s, _) in won) takeHome(s!!); wait(0.8) }
        // what was settled leaves the felt; a line bet still riding stays where it is
        bets.remove(CrapsBet.FIELD); if (r.passSettled) bets.remove(CrapsBet.PASS)
        dealer?.smile = 0f
        // a new come-out: the same line bet goes back up, like the last one (take it down with Clear)
        if (r.passSettled && lastPass in game.minBet..wallet.credits) bets[CrapsBet.PASS] = lastPass
        showStacks()
        line = if (game.point == null) "Coming out: place your bets" else "The point is ${game.point}"
        ui = CUi.BETTING
    }

    /** A sequence broke in the middle: the engine has already settled the roll, so show the table as the engine has it. */
    private fun recover() {
        bets.remove(CrapsBet.FIELD); if (game.point == null) bets.remove(CrapsBet.PASS) else bets[CrapsBet.PASS] = game.passStake
        point = game.point; puck?.set(game.point, now, jump = true)
        game.last?.let { r -> dice.forEachIndexed { i, d -> d.place(d.pos, if (i == 0) r.d1 else r.d2, d.yaw) }; shown = r }
        showStacks(); ui = CUi.BETTING; line = "Place your bets"
    }

    // ================= choreography helpers =================
    private fun seq(block: suspend Seq.() -> Unit) {
        if (job?.isActive == true) return
        ui = CUi.BUSY
        job = scope.launch {
            try { Seq().block() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("ZoharCR", "sequence failed", e)
                recover()
            }
            if (ui == CUi.BUSY) { Log.w("ZoharCR", "sequence ended BUSY: recovering"); recover() }
        }
    }
    inner class Seq {
        private var mark = 0.0
        suspend fun wait(sec: Double) { delay((sec * 1000).toLong()) }
        fun clip(name: String) { dealer?.play(name, now); mark = now }
        suspend fun waitUntil(sec: Double) { val t = mark + sec - now; if (t > 0) delay((t * 1000).toLong()) }
        suspend fun say(text: String, flush: Boolean = true) = this@CrapsTable.say(text, flush)
        suspend fun collectStack(s: StackView) {
            val pos = Float3(L.rack.x + (s.base.x - L.rack.x) * 0.2f, L.feltY - 0.01f, L.rack.z)
            s.moveTo(now, pos, 0.7); delay(750); s.hide()
            sounds.play("chips_stack", 0.6f)
        }
        suspend fun payStack(s: StackView, win: Long) {
            if (win <= 0) return
            val from = Float3(L.rack.x - 0.1f, L.feltY, L.rack.z + 0.04f)
            val pay = stack(win, from); pay.moveTo(now, Float3(s.base.x + 0.05f, L.feltY, s.base.z), 0.7); s.extra = pay
            delay(750)
        }
        fun takeHome(s: StackView) {
            val home = Float3(s.base.x, L.feltY, C.zNear - 0.05f)
            if (s.visible) s.moveTo(now, home, 0.6); s.extra?.let { if (it.visible) it.moveTo(now, home, 0.6) }
        }
    }
    var lineAt by mutableLongStateOf(0L); private set                 // when the stickman last spoke (for the speech bubble)
    private fun say(text: String, flush: Boolean = true) { line = text; lineAt = android.os.SystemClock.uptimeMillis(); voice.say(text, flush) }

    // ---- if nobody plays for 3 minutes, the stickman makes a light joke (never pressure to bet) ----
    private var idleJob: Job? = null
    private var lastTouch = android.os.SystemClock.uptimeMillis()
    private var idleLine = 0
    fun touched() { lastTouch = android.os.SystemClock.uptimeMillis() }
    private val idleLines = listOf(
        "The dice are getting lonely. I might roll them myself.",
        "Take your time. I'll just straighten the chips again.",
        "No hurry. These dice have nowhere else to be.",
        "I've polished this stick twice now.",
    )
    private suspend fun idleWatch() {
        while (true) {
            delay(5000)
            val t = android.os.SystemClock.uptimeMillis()
            if (ui == CUi.BETTING && t - lastTouch > 180_000) {
                dealer?.play(if (idleLine % 2 == 0) "Talk" else "Shrug", now); dealer?.smile = 0.7f
                say(idleLines[idleLine % idleLines.size]); idleLine++
                lastTouch = t
                delay(4000); dealer?.smile = 0f
            }
        }
    }

    // ================= dice =================
    /** One die. At rest it shows `value`; in flight it follows its legs and tumbles less and less until the last landing. */
    inner class DieView(val node: ModelNode) {
        var value = 1; private set
        var pos = Float3(); private set
        var yaw = 0f; private set
        private var legs: List<DiceMath.Leg>? = null
        private var t0 = 0.0; private var axis = Float3(1f, 0f, 0f); private var spin = 0f
        private fun rest(): Quaternion { val (a, deg) = DiceMath.faceUp(value); return Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), yaw) * Quaternion.fromAxisAngle(if (a == 'x') Float3(1f, 0f, 0f) else Float3(0f, 0f, 1f), deg) }
        fun place(p: Float3, v: Int, yawDeg: Float) { legs = null; pos = p; value = v; yaw = yawDeg; node.transform = Transform(position = Position(p.x, p.y, p.z), quaternion = rest()) }
        fun fly(start: Double, path: List<DiceMath.Leg>, v: Int, yawDeg: Float, spinAxis: Float3, spinDeg: Float) {
            legs = path; t0 = start; value = v; yaw = yawDeg; spin = spinDeg
            val l = kotlin.math.sqrt(spinAxis.x * spinAxis.x + spinAxis.y * spinAxis.y + spinAxis.z * spinAxis.z).coerceAtLeast(1e-3f)
            axis = Float3(spinAxis.x / l, spinAxis.y / l, spinAxis.z / l)
        }
        fun update(now: Double) {
            val path = legs ?: return
            val t = (now - t0).coerceAtLeast(0.0)
            val p = DiceMath.position(path, t); pos = Float3(p[0], p[1], p[2])
            val left = DiceMath.tumble(path, t)
            val q = if (left > 0f) rest() * Quaternion.fromAxisAngle(axis, spin * left) * Quaternion.fromAxisAngle(Float3(axis.z, axis.x, axis.y), spin * 0.6f * left) else rest()
            node.transform = Transform(position = Position(pos.x, pos.y, pos.z), quaternion = q)
            if (t >= DiceMath.duration(path)) legs = null
        }
    }

    // ================= the puck =================
    /** ON (white face up) on the point's number, OFF (turned over, black) in its corner. */
    inner class PuckView(val node: ModelNode) {
        private var from = Float3(); private var to = Float3(); private var t0 = -1.0; private var on = false; private var wasOn = false
        fun set(point: Int?, now: Double, jump: Boolean = false) {
            wasOn = on; on = point != null
            to = if (point != null) C.puckOn(point) else Float3(C.puckOff.x, L.feltY, C.puckOff.z)
            if (jump) { from = to; t0 = -1.0; apply(to, on) } else { from = cur; t0 = now }
        }
        private var cur = Float3()
        private fun apply(p: Float3, faceOn: Boolean) {
            cur = p
            // turned over, the puck's top is 12 mm lower than its origin: lift it so it still lies on the felt
            node.transform = if (faceOn) Transform(position = Position(p.x, p.y, p.z)) else Transform(position = Position(p.x, p.y + 0.012f, p.z), quaternion = Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), 180f))
        }
        fun update(now: Double) {
            if (t0 < 0) return
            val u = smooth(((now - t0) / 0.45).toFloat())
            val p = lerp(from, to, u).let { it.copy(y = it.y + 0.05f * sin(u * PI.toFloat())) }
            apply(p, if (u < 0.5f) wasOn else on)
            if (now - t0 >= 0.45) { t0 = -1.0; apply(to, on) }
        }
    }

    // ================= chips =================
    private val DENOMS = longArrayOf(500, 100, 25, 5)
    private fun chipNode(v: Int): ModelNode {
        val pool = chipPool.getOrPut(v) { ArrayList() }
        return pool.removeLastOrNull() ?: run { Log.i("ZoharCR", "step chip model $v"); ModelNode(modelLoader.createModelInstance("models/bj_chip_$v.glb"), autoAnimate = false).also { frame!!.addChildNode(it) } }
    }
    fun stack(amount: Long, base: Float3): StackView {
        val chips = ArrayList<Pair<Int, ModelNode>>()
        var left = amount
        for (d in DENOMS) while (left >= d && chips.size < 24) { chips.add(d.toInt() to chipNode(d.toInt())); left -= d }
        val s = StackView(chips, base); stacks.add(s); s.layout(); return s
    }
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

    fun close() { idleJob?.cancel(); voice.close(); job?.cancel() }

    companion object {
        fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        fun lerp(a: Float3, b: Float3, u: Float) = Float3(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u)
    }
}
