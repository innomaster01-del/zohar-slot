package com.zohar.slotar

import com.zohar.slotar.blackjack.SeededRng
import com.zohar.slotar.blackjack.Wallet
import com.zohar.slotar.craps.*
import org.junit.Assert.*
import org.junit.Test

class CrapsEngineTest {
    private class W(override var credits: Long = 10_000) : Wallet
    private fun eng(w: W, vararg dice: Pair<Int, Int>) = CrapsEngine(w, SeededRng(1)).apply { stack = ArrayDeque(dice.toList()) }

    @Test fun comeOutNaturalsWin() {
        for (d in listOf(3 to 4, 6 to 1, 5 to 6)) {
            val w = W(); val e = eng(w, d); val r = e.roll(100, 0)
            assertEquals(PassOutcome.NATURAL, r.pass); assertTrue(r.passSettled); assertEquals(200L, r.passReturned)
            assertEquals(10_100L, w.credits); assertNull(e.point); assertEquals(0L, e.passStake); assertEquals(100L, r.net)
        }
    }
    @Test fun comeOutCrapsLoses() {
        for (d in listOf(1 to 1, 1 to 2, 6 to 6)) {
            val w = W(); val e = eng(w, d); val r = e.roll(100, 0)
            assertEquals(PassOutcome.CRAPS, r.pass); assertEquals(9_900L, w.credits); assertNull(e.point); assertEquals(-100L, r.net)
        }
    }
    @Test fun pointIsSetThenMade() {
        val w = W(); val e = eng(w, 2 to 4, 3 to 5, 1 to 1, 5 to 1)
        val r1 = e.roll(50, 0)
        assertEquals(PassOutcome.POINT_SET, r1.pass); assertEquals(6, e.point); assertEquals(50L, e.passStake); assertEquals(9_950L, w.credits)
        assertFalse(r1.passSettled); assertEquals(0L, r1.net)                  // the bet rides: nothing won or lost yet
        assertEquals(PassOutcome.NONE, e.roll(0, 0).pass)                       // 8: no decision
        assertEquals(PassOutcome.NONE, e.roll(0, 0).pass)                       // 2 is only craps on the come-out
        assertEquals(6, e.point); assertEquals(9_950L, w.credits)
        val r = e.roll(0, 0)
        assertEquals(PassOutcome.POINT_MADE, r.pass); assertEquals(100L, r.passReturned); assertEquals(10_050L, w.credits)
        assertNull(e.point); assertEquals(0L, e.passStake)
    }
    @Test fun sevenOutLosesTheLine() {
        val w = W(); val e = eng(w, 4 to 4, 5 to 6, 3 to 4)
        e.roll(25, 0); assertEquals(8, e.point)
        assertEquals(PassOutcome.NONE, e.roll(0, 0).pass)                       // 11 wins only on the come-out
        val r = e.roll(0, 0)
        assertEquals(PassOutcome.SEVEN_OUT, r.pass); assertEquals(9_975L, w.credits); assertNull(e.point); assertEquals(-25L, r.net)
    }
    @Test fun theLineBetIsLockedWhileThePointIsOn() {
        val w = W(); val e = eng(w, 2 to 2, 3 to 3, 2 to 2)
        e.roll(100, 0)                                                          // point 4
        e.roll(500, 0)                                                          // whatever is asked now, the line keeps its 100
        assertEquals(100L, e.passStake); assertEquals(9_900L, w.credits)
        assertEquals(200L, e.roll(0, 0).passReturned); assertEquals(10_100L, w.credits)
    }
    @Test fun fieldPaysByTheTable() {
        fun field(d: Pair<Int, Int>): Long { val e = eng(W(), 3 to 3, d); e.roll(5, 0); return e.roll(0, 100).fieldReturned }      // what comes back for 100 on the field
        assertEquals(300L, field(1 to 1))                                       // 2 pays double: stake + 200
        assertEquals(400L, field(6 to 6))                                       // 12 pays triple
        for (d in listOf(1 to 2, 1 to 3, 4 to 5, 4 to 6, 5 to 6)) assertEquals("$d", 200L, field(d))
        for (d in listOf(1 to 4, 2 to 4, 4 to 4)) assertEquals("$d", 0L, field(d))
        assertEquals(listOf(2, 1, 1, null, null, null, null, 1, 1, 1, 3), (2..12).map { fieldPays(it) })
    }
    @Test fun fieldAndLineOnTheSameRoll() {
        val w = W(); val e = eng(w, 5 to 6); val r = e.roll(100, 50)          // 11: line wins, field wins
        assertEquals(200L, r.passReturned); assertEquals(100L, r.fieldReturned); assertEquals(10_150L, w.credits); assertEquals(150L, r.net)
        val w2 = W(); val e2 = eng(w2, 3 to 4); val r2 = e2.roll(100, 50)      // 7: line wins, field loses
        assertEquals(10_050L, w2.credits); assertEquals(50L, r2.net); assertFalse(r2.fieldWon)
    }
    @Test fun limits() {
        val w = W(120); val e = CrapsEngine(w, SeededRng(2))
        assertNotNull(e.validate(0, 0)); assertNotNull(e.validate(0, 50))       // the shooter must be on the line
        assertNotNull(e.validate(4, 0)); assertNotNull(e.validate(501, 0)); assertNotNull(e.validate(100, 25)); assertNotNull(e.validate(5, 3))
        assertNull(e.validate(100, 20)); assertNull(e.validate(5, 0))
        try { e.roll(0, 0); fail("rolled without a line bet") } catch (_: IllegalArgumentException) {}
        assertEquals(120L, w.credits)
    }
    /** Exact odds from the rules themselves: every one of the 36 throws, and the race between the point and the seven. */
    @Test fun exactOdds() {
        val ways = IntArray(13); for (a in 1..6) for (b in 1..6) ways[a + b]++
        var field = 0.0; for (s in 2..12) field += ways[s] / 36.0 * (fieldPays(s)?.let { 1.0 + it } ?: 0.0)
        assertEquals(35.0 / 36, field, 1e-12)                                   // house edge 2.78 %
        var win = (ways[7] + ways[11]) / 36.0
        for (p in listOf(4, 5, 6, 8, 9, 10)) win += ways[p] / 36.0 * ways[p] / (ways[p] + ways[7])
        assertEquals(244.0 / 495, win, 1e-12)                                   // 49.29 %: house edge 1.41 %
    }
    /** The engine's own dice, a million line bets and a million field bets. */
    @Test fun measuredReturns() {
        val w = W(1_000_000_000_000); val e = CrapsEngine(w, SeededRng(31))
        var lineBets = 0L; var lineBack = 0L; var fieldBack = 0L; var rolls = 0L; val seen = IntArray(7)
        while (lineBets < 1_000_000) {
            val r = e.roll(100, 100); rolls++; seen[r.d1]++; seen[r.d2]++
            fieldBack += r.fieldReturned
            if (r.passSettled) { lineBets++; lineBack += r.passReturned }
        }
        assertEquals(2 * 244.0 / 495, lineBack / (lineBets * 100.0), 0.004)
        assertEquals(35.0 / 36, fieldBack / (rolls * 100.0), 0.004)
        for (f in 1..6) assertEquals(1.0 / 6, seen[f] / (2.0 * rolls), 0.002)   // every face as often as the others
        assertEquals(1_000_000_000_000L - rolls * 100 - lineBets * 100 + lineBack + fieldBack - e.passStake, w.credits)
    }
}
