package com.zohar.slotar

import com.zohar.slotar.blackjack.SeededRng
import com.zohar.slotar.chips.ChipBank
import org.junit.Assert.*
import org.junit.Test

class ChipBankTest {
    @Test fun aNewBankIsTheFewestChips() {
        val b = ChipBank(); b.follow(1_000)
        assertEquals(1, b.counts[1000L]); assertEquals(1_000L, b.total)
        val c = ChipBank(); c.follow(7_630)
        assertEquals(mapOf(5L to 1, 25L to 1, 100L to 1, 500L to 1, 1000L to 2, 5000L to 1), c.counts.toMap()); assertEquals(7_630L, c.total)
    }
    @Test fun aLossPaysWithSmallChipsAndMakesChange() {
        val b = ChipBank(); b.follow(1_000)                 // one yellow 1,000
        b.follow(975)                                       // a 25 bet: the 1,000 is changed
        assertEquals(975L, b.total); assertEquals(0, b.counts[1000L]); assertEquals(1, b.counts[500L]); assertEquals(4, b.counts[100L]); assertEquals(3, b.counts[25L])
        b.follow(950); assertEquals(2, b.counts[25L])     // a second 25 bet comes from a green chip
    }
    @Test fun aWinAddsChips() {
        val b = ChipBank(); b.follow(500); b.follow(625)
        assertEquals(1, b.counts[500L]); assertEquals(1, b.counts[100L]); assertEquals(1, b.counts[25L])
    }
    @Test fun breakingAChip() {
        val b = ChipBank(); b.follow(5_000)
        assertTrue(b.breakChip(5000)); assertEquals(5, b.counts[1000L]); assertEquals(5_000L, b.total)
        assertTrue(b.breakChip(1000)); assertEquals(2, b.counts[500L])
        assertTrue(b.breakChip(500)); assertEquals(5, b.counts[100L])
        assertTrue(b.breakChip(100)); assertEquals(4, b.counts[25L])
        assertTrue(b.breakChip(25)); assertEquals(5, b.counts[5L])
        assertFalse(b.breakChip(5)); assertFalse(b.breakChip(5000))
        assertEquals(5_000L, b.total); assertEquals(listOf(1000L, 500L, 100L, 25L), b.breakable())
    }
    @Test fun oddCreditsAndSaving() {
        val b = ChipBank(); b.follow(1_003); assertEquals(1_000L, b.total); assertEquals(3L, b.remainder(1_003))
        val c = ChipBank.load(b.save()); assertEquals(b.counts.toMap(), c.counts.toMap())
        assertEquals(0L, ChipBank.load("garbage").total)
    }
    /** Whatever happens, the chips are always worth exactly the credits, and never negative. */
    @Test fun alwaysExact() {
        val r = SeededRng(4); val b = ChipBank(); var credits = 1_000L; b.follow(credits)
        repeat(100_000) {
            when (r.next(4)) {
                0 -> credits += 5L * (1 + r.next(200))
                1 -> credits = maxOf(0L, credits - 5L * (1 + r.next(100)))
                2 -> b.breakable().let { l -> if (l.isNotEmpty()) b.breakChip(l[r.next(l.size)]) }
                else -> credits += 5L * r.next(3000) - 5L * r.next(3000).coerceAtMost((credits / 5).toInt())
            }
            if (credits < 0) credits = 0
            b.follow(credits)
            assertEquals(credits, b.total); assertTrue(b.counts.values.all { it >= 0 })
        }
    }
}
