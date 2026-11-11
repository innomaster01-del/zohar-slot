package com.zohar.slotar

import com.zohar.slotar.blackjack.SeededRng
import com.zohar.slotar.blackjack.Wallet
import com.zohar.slotar.plinko.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PlinkoEngineTest {
    private class W(override var credits: Long = 10_000) : Wallet
    private fun way(s: String) = s.map { it == 'R' }
    private val geo = PlinkoGeo(12, 0.07f, 0.075f, 1.70f, 0.007f, 0.016f, 1.80f, 0.736f)

    @Test fun pocketIsTheNumberOfRights() {
        val w = W(); val e = PlinkoEngine(w, SeededRng(1)).apply { stack = ArrayDeque(listOf(way("LLLLLLLLLLLL"), way("RRRRRRRRRRRR"), way("RLRLRLRLRLRL"))) }
        val a = e.drop(100, PlinkoRisk.HIGH); assertEquals(0, a.pocket); assertEquals(755, a.fifths); assertEquals(15_100L, a.returned); assertEquals(25_000L, w.credits)
        val b = e.drop(100, PlinkoRisk.LOW); assertEquals(12, b.pocket); assertEquals(1_200L, b.returned)
        val c = e.drop(100, PlinkoRisk.MEDIUM); assertEquals(6, c.pocket); assertEquals(40L, c.returned); assertEquals(-60L, c.net)
        assertEquals(25_000L - 100 + 1_200 - 100 + 40, w.credits)
    }
    @Test fun boardsAreSymmetricAndPayWholeCredits() {
        for (r in PlinkoRisk.entries) for (k in 0..12) {
            assertEquals(Plinko.fifths(r, k), Plinko.fifths(r, 12 - k))
            if (k < 6) assertTrue(Plinko.fifths(r, k) >= Plinko.fifths(r, k + 1))            // prizes grow toward the edges
        }
        assertEquals("12", Plinko.label(60)); assertEquals("0.2", Plinko.label(1)); assertEquals("1.6", Plinko.label(8)); assertEquals("24.4", Plinko.label(122)); assertEquals("151", Plinko.label(755))
        val w = W(1_000_000); val e = PlinkoEngine(w, SeededRng(2))
        for (bet in listOf(5L, 25L, 130L, 500L)) for (r in PlinkoRisk.entries) { val d = e.drop(bet, r); assertEquals(bet * d.fifths, d.returned * 5) }
    }
    /** Exact return of each board: every way down counted. */
    @Test fun exactReturns() {
        assertEquals(4096, (0..12).sumOf { Plinko.ways(it) }); assertEquals(924, Plinko.ways(6)); assertEquals(1, Plinko.ways(0)); assertEquals(12, Plinko.ways(11))
        for (r in PlinkoRisk.entries) { val x = Plinko.rtp(r); assertTrue("$r $x", x <= 0.97 && x > 0.9685) }
    }
    @Test fun limits() {
        val w = W(120); val e = PlinkoEngine(w, SeededRng(3))
        assertNotNull(e.validate(0)); assertNotNull(e.validate(3)); assertNotNull(e.validate(12)); assertNotNull(e.validate(505)); assertNotNull(e.validate(125)); assertNull(e.validate(120))
        try { e.drop(7, PlinkoRisk.LOW); fail("took a bet that is not a step of 5") } catch (_: IllegalArgumentException) {}
        assertEquals(120L, w.credits)
    }
    /** The engine's own balls: left and right equally often at every row, pockets as often as the count of ways says, returns as computed. */
    @Test fun measured() {
        val w = W(1_000_000_000_000); val e = PlinkoEngine(w, SeededRng(41)); val n = 600_000
        val rights = IntArray(12); val pockets = IntArray(13); val back = LongArray(3)
        repeat(n) { i -> val d = e.drop(100, PlinkoRisk.entries[i % 3]); d.path.forEachIndexed { r, right -> if (right) rights[r]++ }; pockets[d.pocket]++; back[i % 3] += d.returned }
        for (r in 0 until 12) assertEquals(0.5, rights[r] / n.toDouble(), 0.003)
        for (k in 2..10) assertEquals(Plinko.ways(k) / 4096.0, pockets[k] / n.toDouble(), 0.003)
        assertEquals(Plinko.rtp(PlinkoRisk.LOW), back[0] / (n / 3 * 100.0), 0.01); assertEquals(Plinko.rtp(PlinkoRisk.MEDIUM), back[1] / (n / 3 * 100.0), 0.03)
    }
    /** What the board shows: on every one of the 4096 ways the ball meets a real peg at each row and comes to rest over the engine's pocket. */
    @Test fun theBallFollowsThePath() {
        for (bits in 0 until 4096) {
            val path = List(12) { (bits shr it) and 1 == 1 }
            val legs = geo.legs(path); assertEquals(13, legs.size)
            var rights = 0
            for (r in 0 until 12) {
                val l = legs[r + 1]; val i = geo.pegIndex(rights)
                assertTrue(i in 1..r + 1)                                                    // never the two outer pegs of a row
                assertEquals(geo.pegX(r, i), l.x0, 1e-4f); assertEquals(geo.pegY(r) + 0.023f, l.y0, 1e-4f)
                assertEquals(if (path[r]) 0.035f else -0.035f, l.x1 - l.x0, 1e-4f)
                if (path[r]) rights++
            }
            val end = geo.position(legs, geo.duration(legs) + 1)
            assertEquals(geo.pocketX(rights), end[0], 1e-4f); assertEquals(0.736f, end[1], 1e-4f)
            assertTrue(abs(end[0]) <= 0.42f + 1e-4f)
        }
        // on the way the ball never passes through a peg: at each landing it sits exactly on top of one
        val legs = geo.legs(List(12) { it % 2 == 0 }); var t = 0.0; var lowest = 9f
        while (t < geo.duration(legs)) { val p = geo.position(legs, t); assertTrue(p[1] <= 1.80f + 1e-4f); lowest = minOf(lowest, p[1]); t += 0.005 }
        assertTrue(lowest >= 0.736f - 1e-3f)
    }
}
