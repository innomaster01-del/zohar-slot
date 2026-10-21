package com.zohar.slotar.ar

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * "Grab the knob and pull down" — works for a real fingertip seen by the camera (HandTracker) and for a finger
 * dragged on the screen. Pure logic (unit-tested). Coordinates are screen pixels.
 *  - grab: the pointer stays on the knob (within grabRadius) for [dwell] samples
 *  - pull: the lever follows the pointer down; past [fire] of the throw it fires one spin and springs back
 */
class LeverGesture(private val dwell: Int = 2, private val fire: Float = 0.6f) {
    enum class State { FREE, GRABBED, COOLDOWN }
    var state = State.FREE; private set
    /** 0 = rest, 1 = fully pulled (drawn lever angle follows this). */
    var pull = 0f; private set
    private var near = 0
    private var grabY = 0f
    private var lastSeen = 0L
    private var cooldownUntil = 0L

    /** Feed one pointer sample. knob = projected knob centre, throwPx = on-screen length of the lever. Returns true when it fires. */
    fun onPointer(x: Float, y: Float, knobX: Float, knobY: Float, grabRadius: Float, throwPx: Float, nowMs: Long): Boolean {
        lastSeen = nowMs
        if (state == State.COOLDOWN) { if (nowMs < cooldownUntil) return false; state = State.FREE; near = 0 }
        val d = hypot(x - knobX, y - knobY)
        when (state) {
            State.FREE -> {
                if (d <= grabRadius) { if (++near >= dwell) { state = State.GRABBED; grabY = y } } else near = 0
            }
            State.GRABBED -> {
                // let go if the finger slides far sideways or goes back up past the grab point
                if (x !in (knobX - grabRadius * 3)..(knobX + grabRadius * 3) || y < grabY - grabRadius * 2) { release(); return false }
                pull = min(1f, max(0f, (y - grabY) / max(1f, throwPx)))
                if (pull >= fire) { state = State.COOLDOWN; cooldownUntil = nowMs + 900; return true }
            }
            State.COOLDOWN -> {}
        }
        return false
    }

    /** Pointer gone (hand lost / finger lifted). */
    fun onLost(nowMs: Long) { if (state == State.GRABBED && nowMs - lastSeen > 350) release() }
    fun release() { if (state == State.GRABBED) state = State.FREE; near = 0 }
    /** Spring the lever back toward rest; call every frame. */
    fun relax(dt: Float) { if (state != State.GRABBED) pull = max(0f, pull - dt * 3.2f) }
    /** Tap on the knob: full automatic pull. */
    fun autoPull(nowMs: Long) { pull = 1f; state = State.COOLDOWN; cooldownUntil = nowMs + 900 }
}
