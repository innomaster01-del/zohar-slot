package com.zohar.slotar

import com.zohar.slotar.ar.VirtualHand
import org.junit.Assert.*
import org.junit.Test

class VirtualHandTest {
    // simple synthetic hand in MediaPipe world coords (metres): wrist at y=+0.08, fingers pointing up (−y)
    private fun hand(curl: Float): FloatArray {
        val w = FloatArray(63)
        fun set(i: Int, x: Float, y: Float) { w[3 * i] = x; w[3 * i + 1] = y; w[3 * i + 2] = 0f }
        set(0, 0f, 0.08f)
        set(1, -0.03f, 0.06f); set(2, -0.05f, 0.04f); set(3, -0.06f, 0.02f); set(4, -0.07f + curl * 0.06f, 0.0f + curl * 0.04f)
        val xs = floatArrayOf(-0.025f, 0f, 0.02f, 0.04f)
        for (f in 0..3) {
            val base = 5 + f * 4; val x = xs[f]
            set(base, x, 0f)                                   // knuckle
            for (j in 1..3) set(base + j, x, -0.03f * j * (1 - curl) + 0.03f * j * curl * 0.5f)   // curled fingers fold back toward the wrist
        }
        return w
    }
    private val right = floatArrayOf(1f, 0f, 0f); private val up = floatArrayOf(0f, 1f, 0f); private val back = floatArrayOf(0f, 0f, 1f)

    @Test fun openHandIsOpenFistIsClosed() {
        val v = VirtualHand()
        v.onDetection(hand(0f), floatArrayOf(0f, 1.4f, -1f), right, up, back, 0); assertFalse(v.closed)
        v.onDetection(hand(1f), floatArrayOf(0f, 1.4f, -1f), right, up, back, 50); assertTrue(v.closed)
    }

    @Test fun palmLandsWhereAskedAndHandIsSmaller() {
        val v = VirtualHand(scale = 0.7f); v.onDetection(hand(0f), floatArrayOf(0.2f, 1.4f, -1f), right, up, back, 0); v.step(1.0 / 60, 10)
        assertArrayEquals(floatArrayOf(0.2f, 1.4f, -1f), v.joints[9], 1e-4f)
        assertEquals(0.08f * 0.7f, 1.4f - v.joints[0][1], 1e-3f)     // wrist is below the palm (y up), scaled
    }

    @Test fun fistAtKnobThenDownPullsTheLever() {
        val v = VirtualHand(); val knob = floatArrayOf(0.4f, 1.4f, 0f); var t = 0L; var fired = 0
        for (k in 0..40) {
            val y = 1.4f - k * 0.006f
            v.onDetection(hand(1f), floatArrayOf(0.4f, y, 0f), right, up, back, t); repeat(2) { v.step(1.0 / 60, t); t += 16 }
            if (v.updateLever(knob, t)) fired++
        }
        assertEquals(1, fired)
    }

    @Test fun fingertipPokesAButtonOnce() {
        val v = VirtualHand(); var t = 0L
        val btn = floatArrayOf(0f, 1.4f + 0.042f * 0.7f, -1f)     // index tip (landmark 8) = palm + 3 cm·0.7 … close to it
        var presses = 0
        repeat(10) { v.onDetection(hand(0f), floatArrayOf(0f, 1.4f - 0.06f * 0.7f + 0.03f * 0.7f, -1f), right, up, back, t); v.step(1.0 / 60, t); if (v.poke("SpinButton", btn, t, 0.06f)) presses++; t += 40 }
        assertEquals(1, presses)
    }
}
