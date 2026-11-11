package com.zohar.slotar

import com.zohar.slotar.craps.DiceMath
import org.junit.Assert.*
import org.junit.Test

class DiceMathTest {
    /** The face the engine drew is the face that looks up: each value's normal, turned the way the table turns the die, points straight up. */
    @Test fun everyValueEndsFaceUp() {
        for (v in 1..6) {
            val (axis, deg) = DiceMath.faceUp(v); val n = DiceMath.turn(DiceMath.normal(v), axis, deg)
            assertEquals("value $v x", 0f, n[0], 1e-5f); assertEquals("value $v y", 1f, n[1], 1e-5f); assertEquals("value $v z", 0f, n[2], 1e-5f)
            // and no other face does
            for (o in 1..6) if (o != v) assertTrue(DiceMath.turn(DiceMath.normal(o), axis, deg)[1] < 0.5f)
        }
    }
    @Test fun oppositeFacesAddUpToSeven() {
        for (v in 1..6) { val a = DiceMath.normal(v); val b = DiceMath.normal(7 - v); for (i in 0..2) assertEquals(0f, a[i] + b[i], 0f) }
    }
    /** The flight ends exactly where the die is to lie, never dips under the felt, and has stopped tumbling by its last landing. */
    @Test fun flightEndsAtRest() {
        var s = 3; val r = { s = (s * 16807L % 2147483647L).toInt(); (s % 1000) / 1000f }
        repeat(200) {
            val rest = floatArrayOf(-0.42f + 0.84f * r(), 0.8095f, 0.37f + 0.11f * r())
            val wall = floatArrayOf((if (r() > 0.5f) 1 else -1) * (0.5f + 0.24f * r()), 0.84f + 0.07f * r(), 0.03f)
            val legs = DiceMath.flight(floatArrayOf(0f, 1.0f, 1.02f), wall, rest, r)
            val end = DiceMath.duration(legs)
            assertTrue(end in 1.2..1.8)
            val p = DiceMath.position(legs, end + 1); for (i in 0..2) assertEquals(rest[i], p[i], 1e-5f)
            var t = 0.0; while (t < end) { val q = DiceMath.position(legs, t); assertTrue("under the felt at $t", q[1] >= 0.8095f - 1e-4f); assertTrue(q[2] >= 0.029f); t += 0.01 }
            assertEquals(1f, DiceMath.tumble(legs, 0.0), 0f); assertEquals(0f, DiceMath.tumble(legs, legs.last().t0), 0f); assertEquals(0f, DiceMath.tumble(legs, end), 0f)
        }
    }
}
