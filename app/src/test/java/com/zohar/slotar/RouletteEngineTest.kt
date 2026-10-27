package com.zohar.slotar

import com.zohar.slotar.blackjack.SeededRng
import com.zohar.slotar.blackjack.Wallet
import com.zohar.slotar.roulette.*
import org.junit.Assert.*
import org.junit.Test

class RouletteEngineTest {
    private class W(override var credits: Long = 10_000) : Wallet
    private val g = RouletteGrid(gx0 = 0f, gz0 = 0f, cw = 1f, ch = 1f, dh = 1f, eh = 1f)     // unit cells for easy maths

    @Test fun wheelHasEveryNumberOnce() {
        assertEquals((0..36).toSet(), Wheel.ORDER.toSet()); assertEquals(37, Wheel.ORDER.size)
        assertEquals(18, (1..36).count { Wheel.isRed(it) })
        // reds and blacks alternate round the wheel
        for (i in 1 until 36) assertNotEquals(Wheel.isRed(Wheel.ORDER[i]), Wheel.isRed(Wheel.ORDER[i + 1]))
    }
    @Test fun tapsResolveLikeADealerReadsChips() {
        assertEquals(listOf(1), g.resolve(0.5f, 2.5f)!!.numbers)              // bottom-left cell = 1
        assertEquals(listOf(36), g.resolve(11.5f, 0.5f)!!.numbers)
        assertEquals(listOf(0), g.resolve(-0.5f, 1.5f)!!.numbers)
        assertEquals(listOf(1, 4), g.resolve(1.02f, 2.5f)!!.numbers)           // split across a vertical line
        assertEquals(listOf(1, 2), g.resolve(0.5f, 2.02f)!!.numbers)           // split across a horizontal line
        assertEquals(listOf(1, 2, 4, 5), g.resolve(0.98f, 2.02f)!!.numbers)    // corner
        assertEquals(listOf(4, 5, 6), g.resolve(1.5f, 2.95f)!!.numbers)        // street
        assertEquals(listOf(4, 5, 6, 7, 8, 9), g.resolve(1.98f, 2.95f)!!.numbers)   // six line
        assertEquals(listOf(0, 2), g.resolve(0.05f, 1.5f)!!.numbers)           // split with zero
        assertEquals(listOf(0, 1, 2), g.resolve(0.05f, 2.02f)!!.numbers)       // trio
        assertEquals(listOf(0, 1, 2, 3), g.resolve(0.05f, 2.95f)!!.numbers)    // first four
        assertEquals("Column 3", g.resolve(12.5f, 0.5f)!!.name); assertTrue(3 in g.resolve(12.5f, 0.5f)!!.numbers)
        assertEquals("2nd 12", g.resolve(5.5f, 3.5f)!!.name)
        assertEquals("Red", g.resolve(4.5f, 4.5f)!!.name); assertEquals("19-36", g.resolve(11.9f, 4.5f)!!.name)
        assertNull(g.resolve(-0.5f, 4.5f))
    }
    @Test fun everyBetReturnsExactly36Over37() {
        val spots = HashSet<RSpot>()
        var x = -0.98f; while (x < 13f) { var z = 0.01f; while (z < 5f) { g.resolve(x, z)?.let { spots.add(it) }; z += 0.04f }; x += 0.04f }
        assertTrue(spots.size > 140)
        for (s in spots) {
            assertTrue(s.name, s.numbers.size in setOf(1, 2, 3, 4, 6, 12, 18)); assertEquals(s.name, s.numbers.size, s.numbers.toSet().size)
            var ret = 0L
            for (n in 0..36) { val w = W(); val e = RouletteEngine(w).apply { stack = ArrayDeque(listOf(n)) }; ret += e.spin(mapOf(s to 36L)).returned }
            assertEquals(s.name, 36L * 36, ret)                // 37 spins of 36 → 36 × 36 back: 36/37
        }
    }
    @Test fun paysAndLimits() {
        val w = W(); val e = RouletteEngine(w).apply { stack = ArrayDeque(listOf(17, 0)) }
        val r = e.spin(mapOf(g.straight(17) to 10L, g.even(2) to 20L))           // 17 is black
        assertEquals(17, r.number); assertEquals(10_000L - 30 + 360, w.credits)
        val r2 = e.spin(mapOf(g.even(2) to 20L, g.straight(0) to 5L)); assertEquals(0, r2.number); assertEquals(180L, r2.returned)
        assertNotNull(e.validate(mapOf(g.straight(5) to 200L)))
        assertNull(e.validate(mapOf(g.dozen(0) to 500L)))
        assertNotNull(RouletteEngine(W(50)).validate(mapOf(g.dozen(0) to 100L)))
    }
    @Test fun uniformDraw() {
        val e = RouletteEngine(W(10_000_000), SeededRng(3)); val c = IntArray(37)
        repeat(37_000) { c[e.spin(mapOf(g.straight(0) to 5L)).number]++ }
        for (k in c) assertEquals(1000.0, k.toDouble(), 140.0)
    }
}
