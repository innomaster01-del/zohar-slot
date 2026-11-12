package com.zohar.slotar.crash

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zohar.slotar.Fx
import com.zohar.slotar.audio.SlotSounds
import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Wallet
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/** Play-money wallet for SKY RUSH, kept on the phone. Free refill when it runs low. */
class CrashWallet(context: Context) : Wallet {
    private val prefs = context.getSharedPreferences("zohar_crash", Context.MODE_PRIVATE)
    override var credits: Long = prefs.getLong("credits", START)
        set(v) { field = v; prefs.edit().putLong("credits", v).apply() }
    companion object { const val START = 1000L }
}

/** One of the two bet panels on the screen. */
class BetSlot(first: Long) {
    var stake by mutableLongStateOf(first)
    var on by mutableStateOf(true)
    /** Cash out by itself at this multiplier (hundredths), or 0 = by hand. */
    var auto by mutableLongStateOf(0L)
    /** What it was cashed at this round (hundredths), 0 = not (yet). */
    var cashedAt by mutableLongStateOf(0L)
    var won by mutableLongStateOf(0L)
    var riding by mutableStateOf(false)
}

/**
 * SKY RUSH: the engine and everything shown. The engine draws the crash point at take-off; the jet then flies for as
 * long as the multiplier climbs and crashes when it passes that point. Nothing on the screen changes what was drawn.
 *
 * Pad space (the same numbers as tools/3d/blackjack/crash.js): origin on the table under the middle of the pad, the
 * runway along x, take-off toward +x, +z toward the player. The flight: a roll down the runway, a climb, then a wide
 * left turn round behind the pad, so a long flight stays in the room.
 */
class CrashTable(
    private val modelLoader: ModelLoader, private val sounds: SlotSounds, private val onFx: (Fx) -> Unit,
    rng: Rng = SecureRng, val wallet: Wallet,
) {
    val game = CrashEngine(wallet, rng)

    // ---------------- what the screen shows ----------------
    var credits by mutableLongStateOf(wallet.credits); private set
    val slots = listOf(BetSlot(25), BetSlot(10).also { it.on = false; it.auto = 200 })
    var chipValue by mutableLongStateOf(25L)
    /** The multiplier now (hundredths). */
    var mult by mutableLongStateOf(100L); private set
    var phase by mutableStateOf(CrashPhase.BETTING); private set
    /** Just crashed: the screen shows FLEW AWAY until the next round can be bet. */
    var crashedAt by mutableLongStateOf(0L); private set
    var message by mutableStateOf(""); private set
    val history = mutableStateListOf<Long>()
    var airborne by mutableStateOf(false); private set

    // ---------------- scene ----------------
    var frame: Node? = null; private set
    private var jet: ModelNode? = null; private var flame: ModelNode? = null; private var chase: ModelNode? = null
    private var fireball: ModelNode? = null; private var shock: ModelNode? = null
    private val shards = ArrayList<ModelNode>(); private val smoke = ArrayList<ModelNode>()
    private val dots = HashMap<Int, ArrayList<ModelNode>>()          // 0 cyan, 1 violet, 2 gold
    private class Dot(val node: ModelNode, var age: Float)
    private val live = ArrayList<Dot>()
    private var now = 0.0; private var last = 0.0
    private var engineStream = 0
    private var crashT = -1.0; private var nextRoundAt = 0.0

    // the flight state (pad space)
    private var px = START_X; private var py = PLANE_Y; private var pz = 0f; private var heading = 0f; private var vy = 0f; private var bank = 0f; private var pitch = 0f
    private var dotTimer = 0f
    private var fallVx = 0f; private var fallVy = 0f; private var fallVz = 0f; private var roll = 0f
    private val shardV = Array(SHARDS) { FloatArray(4) }
    private var boomAt = floatArrayOf(0f, 0f, 0f)
    private var rnd = 12345L
    private fun r01(): Float { rnd = rnd * 6364136223846793005L + 1442695040888963407L; return ((rnd ushr 40) and 0xFFFFFF).toFloat() / 0x1000000 }

    /** World position of the jet (for the number that floats over it), or null before the scene is ready. */
    fun jetWorld(): Position? = jet?.takeIf { it.isVisible }?.worldPosition

    fun attach(frame: Node) {
        if (this.frame === frame) return
        this.frame = frame
        fun model(name: String) = ModelNode(modelLoader.createModelInstance("models/$name.glb"), autoAnimate = false).also { frame.addChildNode(it) }
        jet = model("crash_jet")
        flame = ModelNode(modelLoader.createModelInstance("models/crash_flame.glb"), autoAnimate = false).also { jet!!.addChildNode(it); it.position = Position(-0.121f, 0f, 0f) }
        chase = model("crash_chase")
        fireball = model("crash_fireball").also { it.isVisible = false }; shock = model("crash_shock").also { it.isVisible = false }
        repeat(SHARDS) { shards.add(model("crash_shard").also { it.isVisible = false }) }
        repeat(4) { smoke.add(model("crash_smoke").also { it.isVisible = false }) }
        for ((k, n) in listOf("crash_dot_cyan", "crash_dot_violet", "crash_dot_gold").withIndex()) dots[k] = ArrayList<ModelNode>().also { l -> repeat(DOTS) { l.add(model(n).also { it.isVisible = false }) } }
        resetJet()
        Log.i("ZoharCR", "attached")
    }
    private fun resetJet() {
        px = START_X; py = PLANE_Y; pz = 0f; heading = 0f; vy = 0f; bank = 0f; pitch = 0f; roll = 0f; airborne = false
        jet?.isVisible = true; flame?.isVisible = false; place()
    }
    private fun place() {
        jet?.position = Position(px, py, pz)
        jet?.quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), deg(heading)) * Quaternion.fromAxisAngle(Float3(0f, 0f, 1f), deg(pitch)) * Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), deg(-bank + roll))
    }
    private fun deg(r: Float) = r * 57.29578f

    // ================= per frame =================
    fun update(nowSec: Double) {
        val dt = (nowSec - last).toFloat().coerceIn(0f, 0.1f); last = nowSec; now = nowSec
        if (frame == null) return
        // the chase lights run down the runway while the jet is on the ground
        chase?.let { c -> c.isVisible = !airborne && crashT < 0; val k = ((now % 1.1) / 1.1).toFloat(); c.position = Position(-0.42f + 0.84f * k, 0f, 0f) }
        if (phase == CrashPhase.FLYING) {
            // (a cash-out can also be the moment the engine finds the jet has crashed: then phase is already CRASHED there)
            val m0 = game.current()
            if (game.phase == CrashPhase.FLYING) game.tick()
            for ((i, b) in game.bets.withIndex()) { val s = slots[i]; if (b != null && b.cashedAt != null && s.cashedAt == 0L) { s.cashedAt = b.cashedAt!!; s.won = b.returned; s.riding = false; cashed(b.returned) } }
            if (game.phase != CrashPhase.FLYING) startCrash() else { mult = maxOf(m0, game.current()); fly(game.elapsed.toFloat(), dt) }
        }
        if (crashT >= 0) crashStep(dt)
        // the trail: each puff shrinks away
        val it = live.iterator()
        while (it.hasNext()) { val d = it.next(); d.age += dt; val k = 1f - d.age / DOT_LIFE; if (k <= 0f) { d.node.isVisible = false; it.remove() } else d.node.scale = Scale(k) }
        if (phase == CrashPhase.CRASHED && now >= nextRoundAt) { game.next(); phase = CrashPhase.BETTING; crashedAt = 0L; resetJet(); credits = wallet.credits; for (s in slots) { s.riding = false } }
        credits = wallet.credits
    }

    private fun fly(t: Float, dt: Float) {
        if (t < ROLL) {
            // down the runway, faster and faster
            px = START_X + 0.5f * ACC * t * t; py = PLANE_Y; pitch = if (t > ROLL - 0.35f) (t - (ROLL - 0.35f)) / 0.35f * 0.12f else 0f
            flame?.isVisible = true
        } else {
            airborne = true
            val s = t - ROLL
            val speed = 0.66f - 0.16f * (1f - exp(-s / 3f))                      // 0.66 m/s at lift-off, settling to 0.5
            val turn = TURN * smooth(s / 3.5f)                                       // into a wide left turn
            heading += turn * dt
            val dx = cos(heading) * speed * dt; val dz = -sin(heading) * speed * dt
            val y = PLANE_Y + CLIMB * (1f - exp(-s / 3.6f)) + 0.035f * sin(s * 0.9f) * smooth(s / 6f)
            vy = if (dt > 0f) (y - py) / dt else vy
            px += dx; pz += dz; py = y
            pitch = atan2(vy, speed); bank = turn * 0.9f + 0.05f * sin(s * 0.7f)
            dotTimer += dt
            if (dotTimer > 0.085f) { dotTimer = 0f; puff() }
        }
        flame?.scale = Scale(0.85f + 0.3f * r01(), 0.9f + 0.2f * r01(), 0.9f + 0.2f * r01())
        place()
        if (engineStream != 0) sounds.tune(engineStream, 0.75f + 0.28f * ln(mult / 100f).toFloat().coerceAtLeast(0f), 0.55f)
    }
    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
    private fun puff() {
        val pool = dots[if (mult < 200) 0 else if (mult < 1000) 1 else 2] ?: return
        val n = pool.firstOrNull { !it.isVisible } ?: live.firstOrNull { d -> pool.contains(d.node) }?.also { d -> live.remove(d) }?.node ?: return
        // just behind the nozzle
        val back = 0.13f; n.position = Position(px - cos(heading) * cos(pitch) * back, py - sin(pitch) * back, pz + sin(heading) * cos(pitch) * back)
        n.scale = Scale(1f); n.isVisible = true; live.add(Dot(n, 0f))
    }

    // ================= the crash =================
    private fun startCrash() {
        val r = game.last ?: return
        mult = r.crash; crashedAt = r.crash; phase = CrashPhase.CRASHED; crashT = 0.0
        history.add(0, r.crash); if (history.size > 14) history.removeAt(history.size - 1)
        sounds.stop(engineStream); engineStream = 0
        // the jet noses over and spins down for a moment before it goes up in flames
        val sp = if (airborne) 0.45f else 0.25f
        fallVx = cos(heading) * sp; fallVz = -sin(heading) * sp; fallVy = if (airborne) 0.05f else 0.3f; roll = 0f
        for (s in slots) if (s.riding) { s.riding = false; s.cashedAt = 0L; s.won = 0L }
        val won = r.returned; val staked = r.staked
        message = if (won > 0) "Round: back ${won} of ${staked}" else "Flew away at ${Crash.label(r.crash)}"
        Log.i("ZoharCR", "crash ${Crash.label(r.crash)} staked $staked back $won credits=${wallet.credits}")
        nextRoundAt = now + 3.2
    }
    private fun crashStep(dt: Float) {
        val t = crashT.toFloat(); crashT += dt
        val fall = if (airborne) 0.45f else 0.12f
        if (t < fall) {
            fallVy -= 2.8f * dt; px += fallVx * dt; py = maxOf(PLANE_Y, py + fallVy * dt); pz += fallVz * dt
            roll += 11f * dt; pitch = maxOf(-1.0f, pitch - 3f * dt); place()
            return
        }
        if (t - dt < fall) boom()
        val b = t - fall
        // fireball: blooms in a quarter of a second, then burns down
        fireball?.let { f -> if (b < 1.1f) { f.isVisible = true; val k = if (b < 0.22f) b / 0.22f * 2.8f else 2.8f * (1f - (b - 0.22f) / 0.88f); f.scale = Scale(maxOf(0.01f, k)) } else f.isVisible = false }
        shock?.let { s -> if (b < 0.55f) { s.isVisible = true; s.scale = Scale(0.4f + b / 0.55f * 6f, 1f, 0.4f + b / 0.55f * 6f) } else s.isVisible = false }
        for ((i, n) in shards.withIndex()) { val v = shardV[i]
            if (b > 2.4f) { n.isVisible = false; continue }
            v[1] -= 2.6f * dt; val p = n.position; var y = p.y + v[1] * dt
            if (y < PAD_TOP + 0.004f) { y = PAD_TOP + 0.004f; v[1] = -v[1] * 0.3f; v[0] *= 0.6f; v[2] *= 0.6f }
            n.position = Position(p.x + v[0] * dt, y, p.z + v[2] * dt)
            n.quaternion = Quaternion.fromAxisAngle(Float3(0.3f, 1f, 0.2f), (b * v[3]) % 360f)
        }
        for ((i, n) in smoke.withIndex()) { val a = b - i * 0.15f
            if (a < 0f) continue
            if (a > 3f) { n.isVisible = false; continue }
            n.isVisible = true; n.position = Position(boomAt[0] + (i - 1.5f) * 0.03f, boomAt[1] + 0.02f + a * 0.07f, boomAt[2]); n.scale = Scale(0.6f + a * 0.9f)
        }
        if (b > 3.1f) crashT = -1.0
    }
    private fun boom() {
        jet?.isVisible = false; flame?.isVisible = false
        boomAt = floatArrayOf(px, maxOf(py, PLANE_Y), pz)
        sounds.play("boom")
        fireball?.position = Position(boomAt[0], boomAt[1], boomAt[2]); shock?.position = Position(boomAt[0], boomAt[1], boomAt[2])
        for ((i, n) in shards.withIndex()) { val a = r01() * 6.283f; val up = 0.3f + r01() * 0.7f; val sp = 0.25f + r01() * 0.45f
            shardV[i][0] = cos(a) * sp; shardV[i][1] = up; shardV[i][2] = sin(a) * sp; shardV[i][3] = 300f + r01() * 600f
            n.position = Position(boomAt[0], boomAt[1], boomAt[2]); n.isVisible = true }
    }

    // ================= betting =================
    fun toggle(i: Int) { if (phase != CrashPhase.BETTING) return; slots[i].on = !slots[i].on; sounds.play("click") }
    fun addChip(i: Int) {
        if (phase != CrashPhase.BETTING) return; val s = slots[i]; val v = chipValue
        if (s.stake + v > game.maxBet) { message = "The maximum bet is ${game.maxBet}"; return }
        s.stake += v; s.on = true; message = ""; sounds.play("chip")
    }
    fun clear(i: Int) { if (phase != CrashPhase.BETTING) return; slots[i].stake = 0; slots[i].on = false; sounds.play("click") }
    /** Cycle the bet's auto cash-out: off, 1.5×, 2×, 3×, 5×, 10×. */
    fun cycleAuto(i: Int) { if (phase != CrashPhase.BETTING) return; val a = AUTOS; val s = slots[i]; s.auto = a[(a.indexOf(s.auto) + 1) % a.size]; sounds.play("click") }
    fun total() = slots.filter { it.on }.sumOf { it.stake }
    fun canTakeOff() = phase == CrashPhase.BETTING && slots.any { it.on && it.stake > 0 } && slots.filter { it.on }.all { game.validate(it.stake) == null } && total() <= wallet.credits
    fun canRefill() = phase == CrashPhase.BETTING && wallet.credits < game.minBet
    fun refill() { if (!canRefill()) return; wallet.credits = CrashWallet.START; credits = wallet.credits; message = "A thousand fresh play credits" }

    fun takeOff() {
        if (!canTakeOff()) { message = when { total() > wallet.credits -> "Not enough play credits"; else -> slots.firstOrNull { it.on }?.let { game.validate(it.stake) } ?: "Turn on a bet" }; return }
        val bs = slots.map { s -> if (s.on && s.stake > 0) CrashBet(s.stake, s.auto.takeIf { it > 0 }) else null }
        try { game.takeOff(bs[0], bs[1]) } catch (e: IllegalArgumentException) { message = e.message ?: "Can't take off"; return }
        for ((i, s) in slots.withIndex()) { s.cashedAt = 0L; s.won = 0L; s.riding = bs[i] != null }
        resetJet(); live.forEach { it.node.isVisible = false }; live.clear(); crashT = -1.0
        for (n in shards + smoke) n.isVisible = false; fireball?.isVisible = false; shock?.isVisible = false
        phase = CrashPhase.FLYING; mult = 100; crashedAt = 0; message = ""; credits = wallet.credits
        sounds.play("jet_takeoff", 0.9f); engineStream = sounds.loop("jet_loop", 0.55f, 0.75f)
        Log.i("ZoharCR", "take off: ${bs.map { it?.let { b -> "${b.stake}@${b.auto ?: "-"}" } }} credits=${wallet.credits}")
    }
    fun cashOut(i: Int) {
        val s = slots[i]; if (!s.riding) return
        if (game.cashOut(i)) { val b = game.bets[i]!!; s.cashedAt = b.cashedAt!!; s.won = b.returned; s.riding = false; cashed(b.returned); credits = wallet.credits }
    }
    private fun cashed(amount: Long) {
        sounds.play("cash_out"); onFx(Fx.COINS)
        if (mult >= 1000) { sounds.play("big_win"); onFx(Fx.BIG_WIN) }
        Log.i("ZoharCR", "cashed $amount at ${Crash.label(mult)}")
    }

    fun close() { game.abandon(); sounds.stop(engineStream) }

    companion object {
        const val START_X = -0.34f; const val PLANE_Y = 0.046f; const val PAD_TOP = 0.018f
        const val ROLL = 1.4f; const val ACC = 0.469f; const val CLIMB = 0.78f; const val TURN = 0.45f
        const val DOTS = 40; const val DOT_LIFE = 3.4f; const val SHARDS = 10
        val AUTOS = listOf(0L, 150L, 200L, 300L, 500L, 1000L)
    }
}
