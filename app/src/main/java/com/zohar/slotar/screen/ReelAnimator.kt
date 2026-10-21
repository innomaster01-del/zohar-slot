package com.zohar.slotar.screen

import com.zohar.slotar.engine.SlotConfig
import com.zohar.slotar.engine.SpinResult
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reel motion + game-screen state machine (pure Kotlin, unit-tested). It ONLY shows engine results:
 * startSpin(result) lands every reel exactly on result.stops. The renderer draws from this state.
 * Phases: idle → spin → result (count-up, overlays) → show (win lines keep cycling, not busy) → idle.
 */
class ReelAnimator(val cfg: SlotConfig, private val listener: Listener = object : Listener {}) {
    interface Listener {
        fun onSpinStart(r: SpinResult) {}
        fun onReelStop(index: Int, hasScatter: Boolean) {}
        fun onResult(r: SpinResult, overlay: Overlay.Kind?) {}
        fun onFsOutro(r: SpinResult) {}
        fun onDone(r: SpinResult) {}
    }

    class Reel(val strip: List<String>, var pos: Double) {
        val n = strip.size
        var from = 0.0; var to = 0.0; var t = 0.0; var dur = 0.0
        var spinning = false; var antic = false
        val speed get() = if (spinning) abs((to - from) / dur) else 0.0
    }

    class Overlay(val kind: Kind, val dur: Double, val amount: Long, val title: String = "") {
        enum class Kind { FS_INTRO, FS_OUTRO, BIG, MEGA, EPIC }
        var t = 0.0
    }

    class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var a: Float, val va: Float, var life: Float, val r: Float)

    enum class Phase { IDLE, SPIN, RESULT, SHOW }

    val reels = cfg.reels.mapIndexed { i, s -> Reel(s, ((i * 7) % s.size).toDouble()) }
    var busy = false; private set
    var result: SpinResult? = null; private set
    var phase = Phase.IDLE; private set
    var phaseT = 0.0; private set
    var winShown = 0.0; private set
    var lineCycle = 0; private set
    var overlay: Overlay? = null; private set
    val particles = ArrayList<Particle>()
    private var lineT = 0.0
    private var winTarget = 0L
    private val rnd = java.util.Random()
    /** Something visible changed since the last draw. */
    var dirty = true

    fun startSpin(res: SpinResult, turbo: Boolean = false): Boolean {
        if (busy || !res.ok) return false
        result = res; busy = true; phase = Phase.SPIN; phaseT = 0.0; winShown = 0.0; winTarget = res.totalWin; lineCycle = 0; overlay = null
        var scattersSoFar = 0; var extra = 0.0
        reels.forEachIndexed { i, r ->
            // anticipation: once 2 scatters have landed, later reels spin longer with a glow (they can still miss)
            val anticip = scattersSoFar >= cfg.fsTrigger - 1
            if (anticip) extra += if (turbo) 0.25 else 0.9
            r.antic = anticip && !turbo
            if (res.grid.any { row -> row[i] == cfg.scatter }) scattersSoFar++
            val turns = (if (turbo) 1 else 2) + i + (if (anticip) 2 else 0)
            val d = (((r.pos - res.stops[i]) % r.n) + r.n) % r.n           // steps down to the target stop
            r.from = r.pos; r.to = r.pos - d - turns * r.n                    // decreasing pos = symbols move down
            r.dur = (if (turbo) 0.35 else 0.75) + i * (if (turbo) 0.08 else 0.17) + extra
            r.t = 0.0; r.spinning = true
        }
        listener.onSpinStart(res); dirty = true; return true
    }

    fun slamStop() {
        if (phase != Phase.SPIN) return
        reels.forEachIndexed { i, r -> if (r.spinning) r.dur = min(r.dur, r.t + 0.12 + 0.03 * i) }
    }

    /** Advance by dt seconds. Returns true when the picture changed (redraw + texture upload needed). */
    fun update(dtIn: Double): Boolean {
        val dt = min(dtIn, 0.1); phaseT += dt
        val res = result
        if (phase == Phase.SPIN && res != null) {
            var all = true
            reels.forEachIndexed { i, r ->
                if (!r.spinning) return@forEachIndexed
                all = false; r.t = min(r.dur, r.t + dt)
                val x = r.t / r.dur
                r.pos = r.from + (r.to - r.from) * (if (x < 1) outBack(x, 0.6) else 1.0)
                if (x >= 1) {
                    r.spinning = false; r.pos = (((r.to.roundToInt() % r.n) + r.n) % r.n).toDouble(); r.antic = false
                    listener.onReelStop(i, res.grid.any { row -> row[i] == cfg.scatter })
                }
            }
            dirty = true
            if (all) {
                phase = Phase.RESULT; phaseT = 0.0
                val x = res.totalWin.toDouble() / res.totalBet
                overlay = when {
                    res.fsStarted -> Overlay(Overlay.Kind.FS_INTRO, 2.6, res.triggered.toLong())
                    x >= 80 -> Overlay(Overlay.Kind.EPIC, 6.0, res.totalWin)
                    x >= 40 -> Overlay(Overlay.Kind.MEGA, 4.5, res.totalWin)
                    x >= 15 -> Overlay(Overlay.Kind.BIG, 3.2, res.totalWin)
                    else -> null
                }
                listener.onResult(res, overlay?.kind)
                if (overlay?.kind in BIG_KINDS) burst(60)
            }
        }
        if (phase == Phase.SHOW && res != null) {
            if (res.lineWins.size > 1) { lineT += dt; if (lineT > 0.9) { lineT = 0.0; lineCycle = (lineCycle + 1) % (res.lineWins.size + 1); dirty = true } }
            if (floor(phaseT * 8) != floor((phaseT - dt) * 8)) dirty = true
        }
        if (phase == Phase.RESULT && res != null) {
            val ov = overlay
            if (winShown < winTarget) {
                val span = if (ov != null && ov.kind in BIG_KINDS) ov.dur * 0.7 else 0.8
                winShown = min(winTarget.toDouble(), winShown + max(1.0, winTarget * dt / span)); dirty = true
            }
            if (res.lineWins.size > 1) { lineT += dt; if (lineT > 0.9) { lineT = 0.0; lineCycle = (lineCycle + 1) % (res.lineWins.size + 1); dirty = true } }
            if ((res.lineWins.isNotEmpty() || res.scatter.win > 0) && floor(phaseT * 8) != floor((phaseT - dt) * 8)) dirty = true
            val settled = ov == null && winShown >= winTarget && phaseT > (if (res.totalWin > 0) 1.0 else 0.15)
            if (settled || (ov != null && ov.t >= ov.dur && winShown >= winTarget)) {
                if (ov?.kind != Overlay.Kind.FS_OUTRO && res.fsEnded) {
                    overlay = Overlay(Overlay.Kind.FS_OUTRO, 2.8, res.fsTotal); burst(40); listener.onFsOutro(res)
                } else {
                    overlay = null; busy = false; phase = if (res.totalWin > 0) Phase.SHOW else Phase.IDLE; phaseT = 0.0; dirty = true
                    listener.onDone(res)
                }
            }
        }
        overlay?.let { it.t += dt; dirty = true }
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next(); p.vy += 900f * dt.toFloat(); p.x += p.vx * dt.toFloat(); p.y += p.vy * dt.toFloat(); p.a += p.va * dt.toFloat(); p.life -= dt.toFloat()
            if (p.life <= 0f || p.y > 1.1f * SCREEN_H) it.remove()
        }
        if (particles.isNotEmpty()) dirty = true
        return dirty
    }

    private fun burst(n: Int) {
        repeat(n) {
            particles.add(Particle(SCREEN_W / 2f + (rnd.nextFloat() - 0.5f) * SCREEN_W * 0.3f, SCREEN_H * 0.44f, (rnd.nextFloat() - 0.5f) * 900f,
                -300f - rnd.nextFloat() * 700f, rnd.nextFloat() * 6.28f, (rnd.nextFloat() - 0.5f) * 12f, 1.6f + rnd.nextFloat() * 1.2f, 8f + rnd.nextFloat() * 10f))
        }
    }

    /** What is ACTUALLY on the reels now (tests compare it with result.grid). */
    fun visibleGrid(): List<List<String>> = (0 until cfg.rows).map { row ->
        reels.map { r -> r.strip[(((r.pos.roundToInt() + row) % r.n) + r.n) % r.n] }
    }

    companion object {
        const val SCREEN_W = 720
        const val SCREEN_H = 1200
        val BIG_KINDS = setOf(Overlay.Kind.BIG, Overlay.Kind.MEGA, Overlay.Kind.EPIC)
        fun outBack(x: Double, s: Double = 1.2): Double { val y = x - 1; return 1 + (s + 1) * y * y * y + s * y * y }
    }
}
