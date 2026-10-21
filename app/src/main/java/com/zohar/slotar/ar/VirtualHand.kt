package com.zohar.slotar.ar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One Euro filter (Casiez et al.): smooth when the hand is still, responsive when it moves fast. */
class OneEuro(private val minCutoff: Double = 1.2, private val beta: Double = 0.05, private val dCutoff: Double = 1.0) {
    private var x = Double.NaN; private var dx = 0.0
    private fun alpha(cutoff: Double, dt: Double): Double { val tau = 1.0 / (2 * PI * cutoff); return 1.0 / (1.0 + tau / dt) }
    fun filter(v: Double, dt: Double): Double {
        if (x.isNaN() || dt <= 0) { x = v; return v }
        val d = (v - x) / dt
        dx += alpha(dCutoff, dt) * (d - dx)
        val cutoff = minCutoff + beta * abs(dx)
        x += alpha(cutoff, dt) * (v - x)
        return x
    }
    fun reset() { x = Double.NaN; dx = 0.0 }
}

/**
 * Turns the tracked real hand into a small virtual hand standing at the machine: 21 joints in WORLD space.
 * Shape comes from MediaPipe world landmarks (metres, hand-centred), position from where the palm is on screen,
 * pushed out along the camera ray to the depth of the lever knob, scaled down to suit the machine.
 * Pure maths (unit-tested); the renderer and the game read [joints], [grabbing], [pull], [pokeTip].
 */
class VirtualHand(private val scale: Float = 0.7f) {
    val joints = Array(21) { FloatArray(3) }
    var visible = false; private set
    /** Hand is closed (fist) or pinching — hysteresis so it does not flicker. */
    var closed = false; private set
    var grabbing = false; private set
    /** 0..1 lever throw while grabbing. */
    var pull = 0f; private set
    private val target = Array(21) { FloatArray(3) }
    private val filters = Array(63) { OneEuro() }
    private var lastSeenMs = 0L
    private var grabStartY = 0f
    private var lastPokeMs = HashMap<String, Long>()
    private var rearmAt = 0L

    /**
     * New detection. world = 63 MediaPipe world coords; palm = world point where the palm should sit (on the camera ray);
     * camRight/camUp/camBack = camera axes in world space (camera looks along −back).
     */
    fun onDetection(world: FloatArray, palm: FloatArray, camRight: FloatArray, camUp: FloatArray, camBack: FloatArray, nowMs: Long) {
        if (world.size < 63) return
        val cx = world[27]; val cy = world[28]; val cz = world[29]          // landmark 9 (middle-finger knuckle) = palm centre
        for (i in 0 until 21) {
            // MediaPipe: x right, y down, z away from the camera → camera space (x right, y up, z back)
            val lx = (world[3 * i] - cx) * scale; val ly = -(world[3 * i + 1] - cy) * scale; val lz = -(world[3 * i + 2] - cz) * scale
            for (k in 0..2) target[i][k] = palm[k] + camRight[k] * lx + camUp[k] * ly + camBack[k] * lz
        }
        // closed hand: fingertips near the wrist relative to hand size; pinch: thumb tip near index tip
        val size = dist(world, 0, 9).coerceAtLeast(1e-4f)
        val curl = (dist(world, 8, 0) + dist(world, 12, 0) + dist(world, 16, 0) + dist(world, 20, 0)) / (4 * size)
        val pinch = dist(world, 4, 8) / size
        closed = if (closed) (curl < 1.45f || pinch < 0.45f) else (curl < 1.2f || pinch < 0.28f)
        if (!visible) for (i in 0 until 21) for (k in 0..2) { filters[i * 3 + k].reset(); joints[i][k] = target[i][k] }
        visible = true; lastSeenMs = nowMs
    }

    /** Per render frame: glide joints to the latest detection; hide after 0.4 s without a hand. */
    fun step(dt: Double, nowMs: Long) {
        if (visible && nowMs - lastSeenMs > 400) { visible = false; release() }
        if (!visible) return
        for (i in 0 until 21) for (k in 0..2) joints[i][k] = filters[i * 3 + k].filter(target[i][k].toDouble(), dt).toFloat()
    }

    /**
     * Lever: grab when the closed hand is at the knob (or a fingertip touches it); while grabbing, the lever follows the palm
     * down. Returns true once when pulled far enough (spin).
     */
    private var armed = true
    fun updateLever(knob: FloatArray, nowMs: Long, throwM: Float = 0.16f, fireAt: Float = 0.6f): Boolean {
        if (!visible) return false
        val palm = joints[9]; val tip = joints[8]
        // after a pull the hand must let go (open, or move away) before it can grab again
        if (!armed) { if (!closed || d3(palm, knob) > 0.2f || nowMs > rearmAt) armed = true else return false }
        if (!grabbing) {
            if ((closed && d3(palm, knob) < 0.13f) || d3(tip, knob) < 0.05f) { grabbing = true; grabStartY = palm[1]; pull = 0f }
            return false
        }
        if (d3(palm, knob) > 0.45f) { release(); return false }
        pull = min(1f, max(0f, (grabStartY - palm[1]) / throwM))
        if (pull >= fireAt) { grabbing = false; armed = false; rearmAt = nowMs + 2500; return true }
        return false
    }

    /** Index fingertip pressing a button (world centre); one press per 0.8 s per button. */
    fun poke(name: String, button: FloatArray, nowMs: Long, radius: Float = 0.035f): Boolean {
        if (!visible || grabbing) return false
        if (d3(joints[8], button) > radius) return false
        val last = lastPokeMs[name] ?: 0L
        if (nowMs - last < 800) return false
        lastPokeMs[name] = nowMs; return true
    }

    fun release() { grabbing = false; pull = 0f }

    private fun dist(w: FloatArray, a: Int, b: Int): Float { val dx = w[3 * a] - w[3 * b]; val dy = w[3 * a + 1] - w[3 * b + 1]; val dz = w[3 * a + 2] - w[3 * b + 2]; return sqrt(dx * dx + dy * dy + dz * dz) }
    private fun d3(a: FloatArray, b: FloatArray): Float { val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]; return sqrt(dx * dx + dy * dy + dz * dz) }

    companion object {
        /** Bones as joint index pairs (wrist, thumb, 4 fingers, palm edge). */
        val BONES = intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8, 5, 9, 9, 10, 10, 11, 11, 12, 9, 13, 13, 14, 14, 15, 15, 16, 13, 17, 17, 18, 18, 19, 19, 20, 0, 17)
        @Suppress("unused") private fun ease(x: Double) = 1 - exp(-x)
    }
}
