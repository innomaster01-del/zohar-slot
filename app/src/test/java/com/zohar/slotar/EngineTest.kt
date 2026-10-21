package com.zohar.slotar

import com.zohar.slotar.engine.*
import com.zohar.slotar.screen.ReelAnimator
import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    private val P = ZOHAR.pays

    @Test fun configValid() = assertEquals(emptyList<String>(), validateConfig(ZOHAR))

    @Test fun sameResultsAsTheJavaScriptEngine() {
        val lines = javaClass.classLoader!!.getResourceAsStream("vectors.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(3000, lines.size)
        for (ln in lines) {
            val f = ln.split(";")
            val stops = f[0].split(",").map { it.toInt() }.toIntArray(); val lineBet = f[1].toInt()
            val ev = evaluate(ZOHAR, gridFromStops(ZOHAR, stops), lineBet)
            val expected = if (f[2].isEmpty()) emptyList() else f[2].split(",")
            assertEquals(ln, expected, ev.lineWins.map { "${it.line}:${it.symbol}:${it.count}:${it.win}" })
            assertEquals(ln, f[3].toInt(), ev.scatter.count); assertEquals(ln, f[4].toLong(), ev.scatter.win); assertEquals(ln, f[5].toLong(), ev.baseWin)
        }
    }

    @Test fun wildRules() {
        assertEquals(Triple("K", 4, P.getValue("K")[1]), evalLine(ZOHAR, listOf("K", "W", "K", "W", "J")))
        assertEquals(Triple("CROWN", 3, P.getValue("CROWN")[0]), evalLine(ZOHAR, listOf("W", "W", "CROWN", "J", "J")))
        assertEquals(Triple("W", 3, P.getValue("W")[0]), evalLine(ZOHAR, listOf("W", "W", "W", "TEN", "J")))
        assertNull(evalLine(ZOHAR, listOf("W", "S", "S", "S", "S")))
        assertNull(evalLine(ZOHAR, listOf("Q", "K", "K", "K", "K")))
    }

    @Test fun freeSpinsStartAtNoCostWithMultiplier() {
        val trig = ZOHAR.reels.mapIndexed { i, s -> if (i < 3) s.indexOf("S") else 0 }; val quiet = ZOHAR.reels.map { 1 }
        var n = 0
        val e = SlotEngine(ZOHAR, 1000, Rng { _ -> val v = (if (n < 5) trig else quiet)[n % 5]; n++; v })
        val r1 = e.spin(); assertTrue(r1.fsStarted); assertEquals(10, r1.triggered); assertTrue(e.inFreeSpins)
        val c0 = e.credits; val r2 = e.spin()
        assertEquals("free", r2.mode); assertEquals(0L, r2.cost); assertEquals(3, r2.multiplier); assertEquals(9, e.freeLeft); assertEquals(c0 + r2.totalWin, e.credits)
        e.setBetIndex(3); assertEquals(0, e.betIndex)
    }

    @Test fun creditsAccountingAndRtp() {
        val e = SlotEngine(ZOHAR, 1_000_000_000_000L, SeededRng(11)); val c0 = e.credits; var paid = 0L; var won = 0L
        repeat(1_000_000) { i -> if (!e.inFreeSpins) e.setBetIndex(0); val r = e.spin(); paid += r.cost; won += r.totalWin }
        assertEquals(c0 - paid + won, e.credits)
        val rtp = won.toDouble() / paid
        println("Kotlin simulated RTP over 1M spins: ${"%.3f".format(rtp * 100)} %")
        assertTrue("RTP $rtp", rtp in 0.94..0.98)          // exact maths: 95.90 %
    }

    @Test fun refillOnlyWhenBroke() {
        val e = SlotEngine(ZOHAR, 5); val r = e.spin()
        assertFalse(r.ok); assertEquals(5L, e.credits); assertEquals(2005L, e.refill()); assertEquals(2005L, e.refill())
    }

    @Test fun reelsLandExactlyOnEngineResults() {
        val e = SlotEngine(ZOHAR, 1_000_000_000L, SeededRng(3)); var mismatches = 0; var done = 0
        val a = ReelAnimator(ZOHAR, object : ReelAnimator.Listener { override fun onDone(r: SpinResult) { done++ } })
        repeat(400) { i ->
            val r = e.spin(); assertTrue(a.startSpin(r, turbo = i % 3 == 0))
            if (i % 5 == 0) { a.update(0.2); a.slamStop() }
            var guard = 0
            while (a.busy && guard++ < 5000) a.update(1.0 / 30)
            assertFalse("spin $i never finished", a.busy)
            if (a.visibleGrid() != r.grid) mismatches++
            assertFalse("second spin while busy must be refused", a.busy && a.startSpin(r))
        }
        assertEquals(0, mismatches); assertEquals(400, done)
    }
}
