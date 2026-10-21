package com.zohar.slotar

import com.zohar.slotar.ar.LeverGesture
import org.junit.Assert.*
import org.junit.Test

class LeverGestureTest {
    @Test fun grabAndPullDownFiresOnce() {
        val g = LeverGesture(); var t = 0L; var fired = 0
        fun s(x: Float, y: Float) { if (g.onPointer(x, y, 500f, 300f, 60f, 200f, t)) fired++; t += 66 }
        s(900f, 900f); s(510f, 310f); s(505f, 305f)                  // arrive on the knob → grabbed after dwell
        assertEquals(LeverGesture.State.GRABBED, g.state)
        s(505f, 360f); assertTrue(g.pull in 0.2f..0.35f)            // lever follows the finger
        s(505f, 440f); assertEquals(1, fired)                         // past 60 % of the throw → one spin
        s(505f, 470f); s(505f, 480f); assertEquals(1, fired)          // no double fire during cooldown
    }
    @Test fun passingOverTheKnobWithoutPullingDoesNothing() {
        val g = LeverGesture(); var t = 0L; var fired = 0
        for (x in listOf(300f, 400f, 500f, 600f, 700f)) { if (g.onPointer(x, 300f, 500f, 300f, 60f, 200f, t)) fired++; t += 66 }
        assertEquals(0, fired); assertEquals(LeverGesture.State.FREE, g.state)
    }
    @Test fun slidingAwayReleases() {
        val g = LeverGesture(); var t = 0L
        repeat(3) { g.onPointer(500f, 300f, 500f, 300f, 60f, 200f, t); t += 66 }
        g.onPointer(900f, 330f, 500f, 300f, 60f, 200f, t)
        assertEquals(LeverGesture.State.FREE, g.state)
    }
}
