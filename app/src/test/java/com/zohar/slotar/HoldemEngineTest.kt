package com.zohar.slotar

import com.zohar.slotar.blackjack.Card
import com.zohar.slotar.blackjack.SeededRng
import com.zohar.slotar.blackjack.Wallet
import com.zohar.slotar.holdem.*
import org.junit.Assert.*
import org.junit.Test

class HoldemEngineTest {
    private fun c(vararg s: String) = s.map { Card.parse(it) }
    private class W(override var credits: Long = 10_000) : Wallet
    /** Cards in dealing order: P1 D1 P2 D2, flop ×3, turn, river. */
    private fun eng(w: W, vararg cards: String) = HoldemEngine(w, SeededRng(1)).apply { stack = ArrayDeque(cards.map { Card.parse(it) }) }

    @Test fun categories() {
        assertEquals(Cat.ROYAL, Poker.eval5(c("AS", "KS", "QS", "JS", "10S")).cat)
        assertEquals(Cat.STRAIGHT_FLUSH, Poker.eval5(c("9H", "8H", "7H", "6H", "5H")).cat)
        assertEquals(Cat.STRAIGHT, Poker.eval5(c("AS", "2H", "3D", "4C", "5S")).cat)        // the wheel
        assertEquals(Cat.HIGH, Poker.eval5(c("KS", "AH", "2D", "3C", "4S")).cat)            // no wrap-around
        assertEquals(Cat.FULL_HOUSE, Poker.eval5(c("7S", "7H", "7D", "2C", "2S")).cat)
        assertEquals(Cat.TWO_PAIR, Poker.eval5(c("7S", "7H", "2D", "2C", "KS")).cat)
        assertEquals(4, Poker.eval5(c("4S", "4H", "AD", "9C", "2S")).pairRank)
    }
    @Test fun ordering() {
        assertTrue(Poker.eval5(c("2H", "4H", "6H", "8H", "10H")) > Poker.eval5(c("AS", "KH", "QD", "JC", "10S")))   // flush > straight
        assertTrue(Poker.eval5(c("KS", "KH", "5D", "5C", "AS")) > Poker.eval5(c("KD", "KC", "5H", "5S", "QS")))      // kicker
        assertTrue(Poker.eval5(c("6S", "5H", "4D", "3C", "2S")) > Poker.eval5(c("AS", "2H", "3D", "4C", "5H")))       // 6-high straight beats the wheel
        assertEquals(0, Poker.eval5(c("KS", "KH", "5D", "5C", "AS")).compareTo(Poker.eval5(c("KD", "KC", "5H", "5S", "AH"))))
        assertEquals(Cat.FLUSH, Poker.best(c("AH", "KH", "2H", "7H", "9H", "9S", "9D")).cat)
    }
    @Test fun sevenCardFrequencies() {
        val rng = SeededRng(9); val n = 30_000; val count = IntArray(Cat.entries.size)
        val deck = (1..13).flatMap { r -> (0..3).map { s -> Card(r, s) } }.toMutableList()
        repeat(n) {
            for (i in 0 until 7) { val j = i + rng.next(52 - i); val t = deck[i]; deck[i] = deck[j]; deck[j] = t }
            count[Poker.best(deck.subList(0, 7).toList()).cat.ordinal]++
        }
        assertEquals(0.4382, count[Cat.PAIR.ordinal] / n.toDouble(), 0.01)        // published 7-card odds
        assertEquals(0.2350, count[Cat.TWO_PAIR.ordinal] / n.toDouble(), 0.01)
        assertEquals(0.1741, count[Cat.HIGH.ordinal] / n.toDouble(), 0.01)
    }
    @Test fun aaBonusExactReturn() {
        // every 5-card hand once: the AA Bonus returns 93.74 % (house edge 6.26 %)
        val deck = (1..13).flatMap { r -> (0..3).map { s -> Card(r, s) } }
        var ret = 0L; var n = 0L
        for (a in 0 until 52) for (b in a + 1 until 52) for (cc in b + 1 until 52) for (d in cc + 1 until 52) for (e in d + 1 until 52) {
            val v = Poker.eval5(listOf(deck[a], deck[b], deck[cc], deck[d], deck[e])); n++
            HoldemPays.bonus(v)?.let { ret += it + 1 }
        }
        assertEquals(2_598_960L, n); assertEquals(2_436_308L, ret)
    }
    @Test fun dealerDoesNotQualifyAntePaysCallPushes() {
        val w = W(); val e = eng(w, "AH", "2C", "KH", "3D", "QH", "9S", "8D", "7H", "5S")     // player A-K-Q-9-8 high, dealer 2-3 (no 4s)
        e.deal(10); assertEquals(HPhase.DECIDE, e.phase); e.call()
        val r = e.result!!
        assertEquals(HOutcome.DEALER_NOT_QUALIFIED, r.outcome)
        assertEquals(10_000L - 30 + 20 + 20, w.credits)     // ante 1:1 (+20 back), call returned (20)
    }
    @Test fun flushWinsPaysTwoToOnePlusCall() {
        val w = W(); val e = eng(w, "AH", "KS", "9H", "KD", "2H", "5H", "QH", "JD", "3S")      // player ♥ flush, dealer pair of kings
        e.deal(10); e.call()
        assertEquals(HOutcome.WIN, e.result!!.outcome); assertEquals(Cat.FLUSH, e.result!!.player!!.cat)
        assertEquals(10_000L - 30 + 30 + 40, w.credits)     // ante 2:1 → 30, call 1:1 → 40
    }
    @Test fun dealerWinsTakesBoth() {
        val w = W(); val e = eng(w, "2H", "AS", "7C", "AD", "KH", "9S", "4D", "JC", "8S")
        e.deal(10); e.call(); assertEquals(HOutcome.LOSE, e.result!!.outcome); assertEquals(10_000L - 30, w.credits)
    }
    @Test fun tiePushes() {
        val w = W(); val e = eng(w, "2C", "3S", "4C", "5D", "AH", "KH", "QH", "JH", "10S")    // board straight A-K-Q-J-10 plays for both
        e.deal(10); e.call(); assertEquals(HOutcome.PUSH, e.result!!.outcome); assertEquals(10_000L, w.credits)
    }
    @Test fun foldLosesAnteButBonusStillPays() {
        val w = W(); val e = eng(w, "AH", "2C", "AS", "3D", "7C", "9S", "JD", "KH", "QS")      // pocket aces → AA Bonus 7:1
        e.deal(10, bonus = 5)
        assertEquals(Cat.PAIR, e.bonusHand!!.cat); assertEquals(40L, e.bonusReturned)
        e.fold(); assertEquals(HOutcome.FOLD, e.result!!.outcome)
        assertEquals(10_000L - 15 + 40, w.credits)
    }
    @Test fun needsRoomForTheCall() {
        val w = W(25); val e = HoldemEngine(w, SeededRng(1))
        assertThrows(IllegalArgumentException::class.java) { e.deal(10) }    // 3 × 10 > 25
        e.deal(5)
    }
    @Test fun hintCallsStrongFoldsWeak() {
        val rng = SeededRng(4)
        assertTrue(holdemShouldCall(c("AS", "AH"), c("7C", "2D", "9S"), 800, rng))
        assertTrue(holdemShouldCall(c("QH", "JH"), c("2H", "7H", "KC"), 800, rng))         // flush draw
        assertFalse(holdemShouldCall(c("3S", "2H"), c("KC", "QD", "9S"), 800, rng))        // nothing
    }
}
