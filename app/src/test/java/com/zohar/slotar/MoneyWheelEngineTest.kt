package com.zohar.slotar

import com.zohar.slotar.blackjack.SeededRng
import com.zohar.slotar.blackjack.Wallet
import com.zohar.slotar.moneywheel.*
import org.junit.Assert.*
import org.junit.Test

class MoneyWheelEngineTest {
    private class W(override var credits: Long = 10_000) : Wallet
    private fun idx(seg: String) = MoneyWheel.SEGMENTS.indexOf(seg)

    @Test fun wheelMakeup() {
        assertEquals(54, MoneyWheel.SEGMENTS.size)
        assertEquals(listOf(23, 15, 7, 4, 2, 1), MoneyWheel.NUMBERS.map { MoneyWheel.count(it) })
        assertEquals(1, MoneyWheel.SEGMENTS.count { it == "2x" }); assertEquals(1, MoneyWheel.SEGMENTS.count { it == "7x" })
    }
    @Test fun exactReturns() {
        // published returns per bet (exact formula), checked against a direct sum over multiplier chains
        for (n in MoneyWheel.NUMBERS) {
            var ret = 0.0; var p = 1.0 / 54; var e = 1.0
            // chains of k multipliers: every sequence of 2x/7x of length k, then n
            fun walk(k: Int, prob: Double, mult: Double) { if (k > 10) return; ret += prob * MoneyWheel.count(n) / 54 * (1 + n * mult); walk(k + 1, prob / 54, mult * 2); walk(k + 1, prob / 54, mult * 7) }
            walk(0, 1.0, 1.0)
            assertEquals("bet $n", MoneyWheel.rtp(n), ret, 1e-9)
        }
        assertEquals(0.95342, MoneyWheel.rtp(1), 1e-5); assertEquals(0.95513, MoneyWheel.rtp(2), 1e-5)
        assertEquals(0.91239, MoneyWheel.rtp(5), 1e-5); assertEquals(0.96581, MoneyWheel.rtp(10), 1e-5)
        assertEquals(0.92735, MoneyWheel.rtp(20), 1e-5); assertEquals(0.90812, MoneyWheel.rtp(40), 1e-5)
    }
    @Test fun paysAndMultipliers() {
        val w = W(); val e = MoneyWheelEngine(w).apply { stack = ArrayDeque(listOf(idx("10"))) }
        val r = e.spin(mapOf(10 to 20L, 1 to 5L)); assertEquals(10, r.number); assertEquals(10_000L - 25 + 20 + 200, w.credits)
        val w2 = W(); val e2 = MoneyWheelEngine(w2).apply { stack = ArrayDeque(listOf(idx("2x"), idx("7x"), idx("5"))) }
        val r2 = e2.spin(mapOf(5 to 10L)); assertEquals(14, r2.multiplier); assertEquals(3, r2.spins.size)
        assertEquals(10_000L - 10 + 10 + 10 * 5 * 14, w2.credits)
    }
    @Test fun limits() {
        val e = MoneyWheelEngine(W(100))
        assertNotNull(e.validate(mapOf(3 to 10L))); assertNotNull(e.validate(mapOf(1 to 600L))); assertNotNull(e.validate(mapOf(1 to 200L)))
        assertNull(e.validate(mapOf(1 to 50L, 40 to 5L)))
    }
    @Test fun uniformSegments() {
        val e = MoneyWheelEngine(W(100_000_000), SeededRng(5)); val c = IntArray(54)
        repeat(54_000) { val r = e.spin(mapOf(1 to 5L)); for (s in r.spins) c[s.index]++ }
        val tot = c.sum().toDouble(); for (k in c) assertEquals(1.0 / 54, k / tot, 0.0016)
    }
}
