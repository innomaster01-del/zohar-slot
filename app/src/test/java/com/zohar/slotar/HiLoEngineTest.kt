package com.zohar.slotar

import com.zohar.slotar.blackjack.Card
import com.zohar.slotar.blackjack.SeededRng
import com.zohar.slotar.blackjack.Wallet
import com.zohar.slotar.hilo.*
import org.junit.Assert.*
import org.junit.Test

class HiLoEngineTest {
    private class W(override var credits: Long = 10_000) : Wallet
    /** Cards in the order they are turned: the first card, then one per call. */
    private fun eng(w: W, vararg cards: String) = HiLoEngine(w, SeededRng(1)).apply { stack = ArrayDeque(cards.map { Card.parse(it) }) }

    @Test fun rightCallGrowsThePotAtTrueOdds() {
        val w = W(); val e = eng(w, "7S", "KH")
        assertEquals(Card.parse("7S"), e.start(100)); assertEquals(9_900L, w.credits)
        assertFalse(e.canTake()); assertEquals(0L, e.pot)                       // nothing to take before the first right call
        val hi = e.offer(HiLoCall.HIGHER)!!; val lo = e.offer(HiLoCall.LOWER)!!
        assertEquals(24, hi.wins); assertEquals(24, lo.wins); assertEquals(51, hi.left)     // 8…K and A…6, 4 of each, 51 cards left
        assertEquals(206L, hi.pot)                                              // 100 × 0.97 × 51 / 24 = 206.125
        val s = e.guess(HiLoCall.HIGHER)
        assertTrue(s.right); assertEquals(206L, s.pot); assertEquals(206L, e.pot); assertEquals(9_900L, w.credits)
        val r = e.take()
        assertTrue(r.taken); assertEquals(206L, r.returned); assertEquals(10_106L, w.credits); assertEquals(HiLoPhase.DONE, e.phase)
    }
    @Test fun wrongCallLosesTheStake() {
        val w = W(); val e = eng(w, "7S", "3H"); e.start(50)
        val s = e.guess(HiLoCall.HIGHER)
        assertFalse(s.right); assertEquals(HiLoPhase.DONE, e.phase); assertEquals(9_950L, w.credits)
        assertEquals(0L, e.result!!.returned); assertFalse(e.result!!.taken); assertFalse(e.canTake())
    }
    @Test fun sameRankIsWrongBothWays() {
        for (c in HiLoCall.entries) { val w = W(); val e = eng(w, "9S", "9D"); e.start(10); assertFalse(e.guess(c).right); assertEquals(9_990L, w.credits) }
    }
    @Test fun aLostPotIsGoneToo() {
        val w = W(); val e = eng(w, "7S", "KH", "QD", "KC"); e.start(100)
        assertTrue(e.guess(HiLoCall.HIGHER).right); assertTrue(e.guess(HiLoCall.LOWER).right); assertTrue(e.pot > 206)
        assertFalse(e.guess(HiLoCall.LOWER).right)                              // king after queen
        assertEquals(9_900L, w.credits); assertEquals(0L, e.pot)
    }
    @Test fun aceIsLowKingIsHighAndImpossibleCallsAreNotOffered() {
        val k = eng(W(), "KS"); k.start(10)
        assertNull(k.offer(HiLoCall.HIGHER)); assertEquals(48, k.offer(HiLoCall.LOWER)!!.wins)
        val a = eng(W(), "AS"); a.start(10)
        assertNull(a.offer(HiLoCall.LOWER)); assertEquals(48, a.offer(HiLoCall.HIGHER)!!.wins)
        try { a.guess(HiLoCall.LOWER); fail("an impossible call was accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun oddsFollowTheCardsAlreadyOut() {
        // after 7♠ then 8♥ the deck has 50 cards: three 8s left, so 20 are higher than 8 and 27 lower
        val e = eng(W(), "7S", "8H"); e.start(100); e.guess(HiLoCall.HIGHER)
        val hi = e.offer(HiLoCall.HIGHER)!!; val lo = e.offer(HiLoCall.LOWER)!!
        assertEquals(50, hi.left); assertEquals(20, hi.wins); assertEquals(27, lo.wins)
        assertEquals(515L, hi.pot)                                              // 97 × 51/24 × 50/20 = 515.3125
        assertEquals(381L, lo.pot)                                              // 97 × 51/24 × 50/27 = 381.71…
    }
    @Test fun limits() {
        val w = W(300); val e = HiLoEngine(w, SeededRng(3))
        assertNotNull(e.validate(4)); assertNotNull(e.validate(501)); assertNotNull(e.validate(400)); assertNull(e.validate(300))
        e.start(300); assertNotNull(e.validate(5))                              // no second stake while the round is on
        try { e.take(); fail("took a pot before any right call") } catch (_: IllegalStateException) {}
    }
    @Test fun topPrizeAndLadderLength() {
        // a pot never passes the top prize: the call that would is not offered
        val e = eng(W(), "2S", "AH"); e.start(500)                              // 500 × 0.97 × 51 / 4 = 6183
        assertEquals(6_183L, e.offer(HiLoCall.LOWER)!!.pot); e.guess(HiLoCall.LOWER)
        assertNull(e.offer(HiLoCall.LOWER))                                     // ace: nothing lower
        assertEquals(6_578L, e.offer(HiLoCall.HIGHER)!!.pot)                    // × 50 / 47
        e.stack = ArrayDeque(listOf(Card.parse("QC"))); assertTrue(e.guess(HiLoCall.HIGHER).right)
        assertNull(e.offer(HiLoCall.HIGHER))                                    // only 4 kings in 49: the pot would pass 10,000
        assertEquals(7_674L, e.offer(HiLoCall.LOWER)!!.pot)                     // 42 of 49 are lower: 6578.45… × 49 / 42
        // the ladder has 11 steps; after the last one there is nothing to call and the pot is taken
        val w = W(); val l = eng(w, "AS", "KS", "AH", "KH", "AD", "KD", "AC", "KC", "2S", "QS", "2H", "QH"); l.start(5)
        for (i in 0 until 11) { assertFalse(l.mustTake); assertTrue(l.guess(if (i % 2 == 0) HiLoCall.HIGHER else HiLoCall.LOWER).right) }
        assertEquals(11, l.steps); assertTrue(l.offers().isEmpty()); assertTrue(l.mustTake)
        val pot = l.pot; assertEquals(pot, l.take().returned); assertEquals(9_995L + pot, w.credits)
    }
    @Test fun everyRoundKeepsItsRules() {
        val rng = SeededRng(11); val pick = SeededRng(5)
        repeat(20_000) {
            val w = W(1_000_000); val e = HiLoEngine(w, rng); e.start(500)
            while (e.phase == HiLoPhase.GUESS) {
                val os = e.offers()
                for (o in os) { assertTrue(o.pot <= e.maxPot); assertTrue(o.wins in 1..o.left); assertTrue(o.pot >= e.pot) }
                if (os.isEmpty()) { assertTrue(e.mustTake); e.take(); break }
                if (e.canTake() && pick.next(4) == 0) { e.take(); break }
                val before = e.pot; val s = e.guess(os[pick.next(os.size)].call)
                if (s.right) assertTrue(e.pot >= before) else assertEquals(999_500L, w.credits)
            }
            assertTrue(e.steps <= e.maxSteps); assertEquals(e.cards.size, e.cards.toSet().size)        // no card twice
            assertEquals(1_000_000L - 500 + e.result!!.returned, w.credits)
        }
    }
    /** One call, then take: the exact return over every ordered pair of cards, through the engine itself. */
    @Test fun exactReturnOfOneCall() {
        val all = (0..3).flatMap { s -> (1..13).map { Card(it, s) } }
        for (prefer in HiLoCall.entries) {
            var staked = 0L; var back = 0L
            for (a in all) for (b in all) if (a != b) {
                val w = W(); val e = HiLoEngine(w, SeededRng(1)).apply { stack = ArrayDeque(listOf(a, b)) }; e.start(100)
                val call = e.offer(prefer)?.call ?: e.offers().first().call
                if (e.guess(call).right) e.take()
                staked += 100; back += w.credits - 9_900
            }
            val rtp = back.toDouble() / staked
            assertTrue("$prefer $rtp", rtp <= 0.97 && rtp > 0.965)              // 97 % less the rounding down to whole credits
        }
    }
    /** Longer ladders, three ways of playing: all come back to the same 97 %. */
    @Test fun returnIsTheSameHoweverYouPlay() {
        fun play(seed: Int, rounds: Int, stopAfter: Int, likelier: Boolean): Double {
            val rng = SeededRng(seed); val pick = SeededRng(seed + 100); var back = 0L
            repeat(rounds) {
                val w = W(1_000_000); val e = HiLoEngine(w, rng); e.start(100)
                while (e.phase == HiLoPhase.GUESS) {
                    val os = e.offers()
                    if (os.isEmpty() || e.steps >= stopAfter) { e.take(); break }
                    e.guess((if (likelier) os.maxByOrNull { it.wins }!! else os[pick.next(os.size)]).call)
                }
                back += w.credits - 999_900
            }
            return back / (rounds * 100.0)
        }
        assertEquals(0.97, play(21, 400_000, 1, likelier = true), 0.006)
        assertEquals(0.97, play(22, 400_000, 3, likelier = true), 0.012)
        assertEquals(0.97, play(23, 400_000, 2, likelier = false), 0.02)
    }
    @Test fun anAbandonedRoundCostsThePlayerNothing() {
        val w = W(); val e = eng(w, "7S", "KH"); e.start(100)
        assertEquals(100L, e.abandon()!!.returned); assertEquals(10_000L, w.credits); assertEquals(HiLoPhase.DONE, e.phase)      // no call yet: stake back
        assertNull(e.abandon())
        val w2 = W(); val e2 = eng(w2, "7S", "KH"); e2.start(100); e2.guess(HiLoCall.HIGHER)
        assertEquals(206L, e2.abandon()!!.returned); assertEquals(10_106L, w2.credits)                                            // a pot already won is paid
    }
    @Test fun aStackedCardLeavesTheDeck() {
        val e = eng(W(), "KS"); e.start(10)
        assertEquals(51, e.offer(HiLoCall.LOWER)!!.left); assertEquals(48, e.offer(HiLoCall.LOWER)!!.wins)
    }
}
