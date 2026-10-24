package com.zohar.slotar

import com.zohar.slotar.engine.SeededRng
import com.zohar.slotar.scratch.*
import org.junit.Assert.*
import org.junit.Test

class ScratchEngineTest {
    private class W(override var credits: Long) : Wallet

    @Test fun publishedRtpPerTier() {
        for (t in Tier.entries) {
            val r = ScratchMath.rtp(t)
            println("${t.label}: RTP ${"%.4f".format(r * 100)} %  hit 1 in ${"%.2f".format(1 / ScratchMath.pWin(t))}  top win ${ScratchMath.topWin(t)}")
            assertEquals("${t.label} RTP", t.rtpTarget, r, 0.0005)
        }
    }

    @Test fun everyCardShowsExactlyItsOutcome() {
        val e = ScratchEngine(W(0), SeededRng(7))
        repeat(200_000) { i ->
            val t = Tier.entries[i % 3]; val c = e.generate(t)
            val counts = c.cells.groupingBy { it }.eachCount()
            if (c.prizeMultiple > 0) {
                val v = c.prizeMultiple * t.price
                assertEquals(3, counts[v]); assertEquals(1, counts.values.count { it >= 3 })    // one triple only
                assertEquals(c.winCells, c.cells.indices.filter { c.cells[it] == v })
                assertEquals(v * c.multiplier, c.totalWin)
            } else { assertTrue("no triple on a losing card", counts.values.all { it <= 2 }); assertEquals(0L, c.totalWin) }
            assertEquals(c.freeCard, c.stars.all { it })
            assertTrue(c.cells.all { it in ScratchMath.PRIZES.map { p -> p * t.price } })
        }
    }

    @Test fun simulatedRtpMatchesTheMaths() {
        for (t in Tier.entries) {
            val w = W(1_000_000_000_000L); val e = ScratchEngine(w, SeededRng(11L + t.ordinal)); val c0 = w.credits
            repeat(1_000_000) { e.buy(t) }
            assertEquals(c0 - e.totalPaid + e.totalWon, w.credits)
            val r = e.totalWon.toDouble() / e.totalPaid
            println("${t.label} simulated RTP over 1M cards: ${"%.3f".format(r * 100)} %")
            assertEquals(t.rtpTarget, r, if (t == Tier.SILVER) 0.02 else 0.025)
        }
    }

    @Test fun freeCardIsRedeemedBeforePaying() {
        val w = W(100); val e = ScratchEngine(w, SeededRng(3))
        var guard = 0
        while (e.freeCardsFor(Tier.GOLD) == 0 && guard++ < 10_000) { w.credits = 100; e.buy(Tier.GOLD) }
        val before = w.credits; val c = e.buy(Tier.GOLD)!!
        assertTrue(c.paidWithFreeCard); assertEquals(before + c.totalWin, w.credits)
    }

    @Test fun cannotBuyWithoutCredits() { val e = ScratchEngine(W(3)); assertNull(e.buy(Tier.GOLD)); assertNotNull(e.buy(Tier.SILVER)) }
}
