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

class HandLeverTest {
    @Test fun handOverKnobThenMoveDownFires() {
        val g = com.zohar.slotar.ar.LeverGesture(); val h = com.zohar.slotar.ar.HandLever(g); var t = 0L; var fired = 0
        // knob at (500,300); a big hand box covering it
        fun s(palmY: Float, box: FloatArray = floatArrayOf(350f, 150f, 700f, 650f)) { if (h.onHand(box, palmY, 200f, 500f, 300f, 60f, 200f, 2000f, t)) fired++; t += 40 }
        s(400f); s(400f); org.junit.Assert.assertEquals(com.zohar.slotar.ar.LeverGesture.State.GRABBED, g.state)
        s(450f); s(520f); s(600f)          // hand moves down 200 px = 0.1 of 2000 px screen → past 60 % of the throw
        org.junit.Assert.assertEquals(1, fired)
    }
    @Test fun handAwayFromKnobDoesNothing() {
        val g = com.zohar.slotar.ar.LeverGesture(); val h = com.zohar.slotar.ar.HandLever(g); var t = 0L; var fired = 0
        repeat(10) { i -> if (h.onHand(floatArrayOf(0f, 900f, 200f, 1300f), 1000f + i * 40, 900f, 500f, 300f, 60f, 200f, 2000f, t)) fired++; t += 40 }
        org.junit.Assert.assertEquals(0, fired); org.junit.Assert.assertEquals(com.zohar.slotar.ar.LeverGesture.State.FREE, g.state)
    }
}
