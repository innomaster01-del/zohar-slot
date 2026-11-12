package com.zohar.slotar

import com.zohar.slotar.blackjack.SeededRng
import com.zohar.slotar.blackjack.Wallet
import com.zohar.slotar.crash.*
import org.junit.Assert.*
import org.junit.Test

class CrashEngineTest {
    private class W(override var credits: Long = 10_000) : Wallet
    private class Clock { var t = 0.0; fun f() = t }
    private fun eng(w: W, c: Clock, vararg draws: Long) = CrashEngine(w, SeededRng(1), c::f).apply { if (draws.isNotEmpty()) stack = ArrayDeque(draws.toList()) }
    /** The draw r that gives exactly crash point m (the smallest such r). */
    private fun rFor(m: Long): Long { var lo = 0L; var hi = Crash.SCALE - 1; while (lo < hi) { val mid = (lo + hi) / 2; if (Crash.crashOf(mid) >= m) hi = mid else lo = mid + 1 }; return lo }

    @Test fun theDrawIsExact() {
        // the number of draws (of 10⁹) that reach m is 97·10⁹/m to within one draw: the chance is exactly 0.97 / multiplier
        for (m in listOf(101L, 150L, 200L, 333L, 1000L, 10_000L, 99_999L)) {
            val reach = Crash.SCALE - rFor(m)
            assertEquals("m=$m", 97.0 * Crash.SCALE / m, reach.toDouble(), 1.0)
        }
        // 1 − 0.97/1.01 ≈ 3.96 % of rounds crash at once, at 1.00×
        assertEquals(39_603_961L, rFor(101L))
        assertEquals(100L, Crash.crashOf(0)); assertEquals(100L, Crash.crashOf(39_603_960)); assertEquals(101L, Crash.crashOf(39_603_961))
        assertEquals(Crash.MAX, Crash.crashOf(Crash.SCALE - 1))
    }
    @Test fun theMultiplierClimbsWithTime() {
        assertEquals(100L, Crash.at(0.0)); assertEquals(200L, Crash.at(Crash.timeOf(200) + 1e-6)); assertEquals(1000L, Crash.at(Crash.timeOf(1000) + 1e-6))
        var last = 100L; var t = 0.0; while (t < 70) { val m = Crash.at(t); assertTrue(m >= last); last = m; t += 0.013 }
        assertEquals(6.3, Crash.timeOf(200), 0.05)
    }
    @Test fun cashOutPaysTheMultiplierShown() {
        val w = W(); val c = Clock(); val e = eng(w, c, rFor(350))
        e.takeOff(CrashBet(100, null), null); assertEquals(9_900L, w.credits); assertEquals(350L, e.crash)
        c.t = Crash.timeOf(212) + 1e-6; assertFalse(e.tick()); assertEquals(212L, e.current())
        assertTrue(e.cashOut(0)); assertEquals(212L, e.bets[0]!!.cashedAt); assertEquals(10_112L, w.credits)
        assertFalse("only once", e.cashOut(0))
        c.t = Crash.timeOf(351) + 1e-6; assertTrue(e.tick()); assertEquals(CrashPhase.CRASHED, e.phase); assertEquals(10_112L, w.credits)
        e.next(); assertEquals(CrashPhase.BETTING, e.phase)
    }
    @Test fun tooLateIsTooLate() {
        val w = W(); val c = Clock(); val e = eng(w, c, rFor(180))
        e.takeOff(CrashBet(50, null), CrashBet(50, 200)); c.t = Crash.timeOf(181) + 1e-6
        assertFalse(e.cashOut(0)); assertEquals(CrashPhase.CRASHED, e.phase); assertEquals(9_900L, w.credits)
        assertNull(e.bets[1]!!.cashedAt)
    }
    @Test fun autoCashOutAtItsTargetEvenIfTheFrameIsLate() {
        val w = W(); val c = Clock(); val e = eng(w, c, rFor(500))
        e.takeOff(CrashBet(100, 200), CrashBet(100, 500))
        c.t = Crash.timeOf(260); e.tick()                                   // one slow frame jumps past 2.00×: still paid at 2.00×
        assertEquals(200L, e.bets[0]!!.cashedAt); assertEquals(9_800L + 200, w.credits)
        c.t = Crash.timeOf(700); assertTrue(e.tick())                     // past the crash in one step: the 5.00× target is the crash point itself — paid
        assertEquals(500L, e.bets[1]!!.cashedAt); assertEquals(9_800L + 200 + 500, w.credits)
    }
    @Test fun nothingHappensOnTheRunway() {
        val w = W(); val c = Clock(); val e = eng(w, c, 0)
        e.takeOff(CrashBet(100, null), null, roll = 1.4)
        c.t = 1.0; assertFalse(e.tick()); assertEquals(CrashPhase.FLYING, e.phase); assertEquals(100L, e.current()); assertFalse("no cashing on the ground", e.cashOut(0))
        c.t = 1.5; assertTrue("a 1.00× crash comes just after lift-off", e.tick())
    }
    @Test fun anInstantCrashTakesBothBets() {
        val w = W(); val c = Clock(); val e = eng(w, c, 0)
        e.takeOff(CrashBet(5, 101), CrashBet(500, null)); assertTrue(e.tick()); assertEquals(100L, e.crash); assertEquals(9_495L, w.credits)
    }
    @Test fun theTopPrize() {
        val w = W(); val c = Clock(); val e = eng(w, c, Crash.SCALE - 1)
        e.takeOff(CrashBet(500, null), null); c.t = 70.0; assertTrue(e.tick())
        assertEquals(Crash.MAX, e.bets[0]!!.cashedAt); assertEquals(9_500L + 500_000, w.credits)
    }
    @Test fun limitsAndMoney() {
        val w = W(300); val c = Clock(); val e = eng(w, c)
        assertNotNull(e.validate(4)); assertNotNull(e.validate(501)); assertNotNull(e.validate(7)); assertNull(e.validate(5))
        try { e.takeOff(CrashBet(200, null), CrashBet(200, null)); fail("more than the credits") } catch (_: IllegalArgumentException) {}
        try { e.takeOff(null, null); fail("no bet") } catch (_: IllegalArgumentException) {}
        try { e.takeOff(CrashBet(100, 100), null); fail("auto must be above 1.00×") } catch (_: IllegalArgumentException) {}
        assertEquals(300L, w.credits)
    }
    @Test fun aFaultCostsThePlayerNothing() {
        val w = W(); val c = Clock(); val e = eng(w, c, rFor(900))
        e.takeOff(CrashBet(100, null), null); c.t = Crash.timeOf(150) + 1e-6; e.abandon()
        assertEquals(150L, e.bets[0]!!.cashedAt); assertEquals(10_050L, w.credits); assertEquals(CrashPhase.BETTING, e.phase)
    }
    /** Exact: the return of every target, summed over all 10⁹ draws (in closed form), is 97 % less under one credit of rounding. */
    @Test fun everyTargetReturns97() {
        for (target in listOf(101L, 110L, 150L, 200L, 250L, 333L, 500L, 1000L, 5000L, 100_000L)) {
            val stake = 100L; val pay = stake * target / 100; val reach = Crash.SCALE - rFor(target)
            val rtp = pay.toDouble() * reach / Crash.SCALE / stake
            assertEquals("target $target", 0.97, rtp, 0.0000011)
        }
    }
    /** Played through the engine with a real generator and a moving clock: three ways of playing all come back near 97 %. */
    @Test fun playedRoundsReturn97() {
        fun play(seed: Int, n: Int, how: (Int) -> CrashBet): Double {
            val w = W(100_000_000); val c = Clock(); val e = CrashEngine(w, SeededRng(seed), c::f); var staked = 0L; var back = 0L
            repeat(n) { i ->
                val b = how(i); c.t = 0.0; e.takeOff(b, null); staked += b.stake
                while (e.phase == CrashPhase.FLYING) { c.t += 0.05; e.tick() }
                back += b.returned; e.next()
                assertEquals(100_000_000L - staked + back, w.credits)
            }
            return back.toDouble() / staked
        }
        assertEquals(0.97, play(3, 400_000) { CrashBet(100, 150) }, 0.004)
        assertEquals(0.97, play(4, 400_000) { CrashBet(100, 200) }, 0.006)
        assertEquals(0.97, play(5, 400_000) { CrashBet(100, listOf(120L, 300L, 1000L)[it % 3]) }, 0.02)
    }
    @Test fun crashPointsLookRight() {
        val e = CrashEngine(W(Long.MAX_VALUE / 2), SeededRng(9), { 0.0 }); var atOnce = 0; var over2 = 0; val n = 300_000
        repeat(n) { e.takeOff(CrashBet(5, null), null); if (e.crash == 100L) atOnce++; if (e.crash >= 200) over2++; e.abandon() }
        assertEquals(1 - 0.97 / 1.01, atOnce.toDouble() / n, 0.002); assertEquals(0.485, over2.toDouble() / n, 0.004)
    }
}
