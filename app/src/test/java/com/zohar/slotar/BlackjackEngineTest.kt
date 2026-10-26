package com.zohar.slotar

import com.zohar.slotar.blackjack.*
import org.junit.Assert.*
import org.junit.Test

class BlackjackEngineTest {
    private class W(override var credits: Long = 10_000) : Wallet
    /** Cards in dealing order: P1 D1(up) P2 D2(hole), then hits… */
    private fun eng(vararg cards: String, w: W = W()) = BlackjackEngine(w, SeededRng(1)).apply { stack = ArrayDeque(cards.map { Card.parse(it) }) }

    @Test fun naturalPays3to2() {
        val w = W(); val e = eng("AS", "9H", "KD", "7C", w = w); e.deal(10)
        assertEquals(Phase.DONE, e.phase); assertEquals(Outcome.BLACKJACK, e.result!!.hands[0].outcome); assertEquals(10_015, w.credits)
    }
    @Test fun dealerPeeksTenUpBlackjack() {
        val w = W(); val e = eng("9S", "KH", "9D", "AC", w = w); e.deal(20)
        assertEquals(Phase.DONE, e.phase); assertTrue(e.result!!.dealerBlackjack); assertEquals(9_980, w.credits)
    }
    @Test fun insuranceAgainstAceUpBlackjackBreaksEven() {
        val w = W(); val e = eng("9S", "AH", "9D", "KC", w = w); e.deal(20)
        assertEquals(Phase.INSURANCE, e.phase); assertTrue(e.holeHidden)
        e.insurance(true)
        assertEquals(Phase.DONE, e.phase); assertEquals(10_000, w.credits)     // lose 20, insurance 10 pays 2:1 → +30 back
    }
    @Test fun insuranceLostWhenNoBlackjackAndPlayContinues() {
        val w = W(); val e = eng("10S", "AH", "9D", "6C", "5S", w = w); e.deal(20); e.insurance(true)
        assertEquals(Phase.PLAYER, e.phase); assertEquals(9_970, w.credits)
        e.act(Action.STAND)    // dealer A6 = soft 17 stands (S17); 19 beats 17
        assertEquals(17, e.result!!.dealerTotal); assertEquals(Outcome.WIN, e.result!!.hands[0].outcome); assertEquals(10_010, w.credits)
    }
    @Test fun doubleTakesExactlyOneCard() {
        val w = W(); val e = eng("6S", "6H", "5D", "10C", "KS", "9H", w = w); e.deal(10)
        assertTrue(Action.DOUBLE in e.actions()); e.act(Action.DOUBLE)
        assertEquals(3, e.hands[0].cards.size); assertEquals(20, e.result!!.hands[0].bet)
        // dealer 16 draws 9 → bust
        assertEquals(Outcome.WIN, e.result!!.hands[0].outcome); assertEquals(10_020, w.credits)
    }
    @Test fun splitAcesOneCardEach21PaysEven() {
        val w = W(); val e = eng("AS", "9H", "AD", "8C", "KS", "5H", w = w); e.deal(10)
        e.act(Action.SPLIT)
        assertEquals(Phase.DONE, e.phase); assertEquals(2, e.hands.size)
        val r = e.result!!.hands; assertEquals(Outcome.WIN, r[0].outcome); assertEquals(20, r[0].returned)   // 21 after split = 1:1
        assertEquals(Outcome.LOSE, r[1].outcome); assertEquals(10_000, w.credits)
    }
    @Test fun splitToFourHandsAndDoubleAfterSplit() {
        val w = W(); val e = eng("8S", "6H", "8D", "10C", "8H", "3S", "8C", "2D", "2H", "3H", "4H", "KS", w = w); e.deal(10)
        e.act(Action.SPLIT); e.act(Action.SPLIT); e.act(Action.SPLIT)
        assertEquals(4, e.hands.size); assertFalse(Action.SPLIT in e.actions()); assertTrue(Action.DOUBLE in e.actions())
    }
    @Test fun surrenderReturnsHalf() {
        val w = W(); val e = eng("10S", "10H", "6D", "8C", w = w); e.deal(20)
        assertTrue(Action.SURRENDER in e.actions()); e.act(Action.SURRENDER)
        assertEquals(Outcome.SURRENDER, e.result!!.hands[0].outcome); assertEquals(9_990, w.credits)
    }
    @Test fun dealerDoesNotDrawWhenPlayerBusts() {
        val w = W(); val e = eng("10S", "6H", "6D", "10C", "KH", w = w); e.deal(10); e.act(Action.HIT)
        assertEquals(Outcome.BUST, e.result!!.hands[0].outcome); assertEquals(2, e.dealer.size)
    }
    @Test fun holeCardHiddenOnlyWhilePlaying() {
        val e = eng("10S", "6H", "7D", "10C", "KH"); e.deal(10); assertTrue(e.holeHidden); e.act(Action.STAND); assertFalse(e.holeHidden)
    }
    @Test fun illegalActionRejected() {
        val e = eng("10S", "6H", "7D", "10C"); e.deal(10)
        assertFalse(Action.SPLIT in e.actions()); try { e.act(Action.SPLIT); fail() } catch (_: IllegalArgumentException) {}
    }
    @Test fun creditsAreConservedAndHouseEdgeIsRealistic() {
        // 300k rounds of basic strategy (never insurance): every credit is accounted for, the edge is the published ~0.3–0.5 %
        val w = W(1_000_000_000); val e = BlackjackEngine(w, SeededRng(42))
        var wagered = 0L; var returned = 0L; val start = w.credits
        repeat(300_000) {
            val before = w.credits
            e.deal(10)
            if (e.phase == Phase.INSURANCE) e.insurance(false)
            while (e.phase == Phase.PLAYER) e.act(basicStrategy(e.hand.cards, e.dealer[0], e.actions()))
            val r = e.result!!
            wagered += r.hands.sumOf { it.bet }; returned += r.returned
            assertEquals(before - r.hands.sumOf { it.bet } + r.returned, w.credits)
        }
        assertEquals(start - wagered + returned, w.credits)
        val edge = 1.0 - returned.toDouble() / wagered
        println("blackjack house edge = %.3f %% over 300k rounds".format(edge * 100))
        assertTrue("edge $edge", edge > -0.004 && edge < 0.012)
    }
    @Test fun shoeHas312CardsAndReshufflesBetweenRounds() {
        val e = BlackjackEngine(W(1_000_000), SeededRng(3)); assertEquals(312, e.cardsLeft)
        var reshuffles = 0
        repeat(200) { e.deal(5); if (e.reshuffled) reshuffles++; if (e.phase == Phase.INSURANCE) e.insurance(false); while (e.phase == Phase.PLAYER) e.act(Action.STAND) }
        assertTrue(reshuffles >= 1)
    }
}

class SideBetTest {
    private val types = (1..13).flatMap { r -> (0..3).map { s -> com.zohar.slotar.blackjack.Card(r, s) } }
    private val D = 6

    @org.junit.Test fun perfectPairsExactRtp() {
        var ev = 0.0; var n = 0.0
        for (a in types) for (b in types) {
            val w = D.toDouble() * (D - if (a == b) 1 else 0)
            val p = com.zohar.slotar.blackjack.SidePays.pairs(a, b)
            ev += w * (if (p != null) p.second + 1 else 0); n += w
        }
        org.junit.Assert.assertEquals(292.0 / 311.0, ev / n, 1e-12)        // 93.89 %
    }

    @org.junit.Test fun plus3ExactRtp() {
        var ev = 0.0; var n = 0.0
        for (a in types) for (b in types) for (c in types) {
            val wb = D - (if (b == a) 1 else 0); val wc = D - (if (c == a) 1 else 0) - (if (c == b) 1 else 0)
            if (wb <= 0 || wc <= 0) continue
            val w = D.toDouble() * wb * wc
            val p = com.zohar.slotar.blackjack.SidePays.plus3(a, b, c)
            ev += w * (if (p != null) p.second + 1 else 0); n += w
        }
        org.junit.Assert.assertEquals(0.953790, ev / n, 1e-6)              // 95.38 %
    }

    @org.junit.Test fun handsAreRecognised() {
        val C = com.zohar.slotar.blackjack.Card::parse
        val P = com.zohar.slotar.blackjack.SidePays
        org.junit.Assert.assertEquals(25, P.pairs(C("QH"), C("QH"))!!.second)
        org.junit.Assert.assertEquals(12, P.pairs(C("QH"), C("QD"))!!.second)
        org.junit.Assert.assertEquals(6, P.pairs(C("QH"), C("QS"))!!.second)
        org.junit.Assert.assertNull(P.pairs(C("QH"), C("KH")))
        org.junit.Assert.assertEquals(10, P.plus3(C("QS"), C("KH"), C("AD"))!!.second)
        org.junit.Assert.assertEquals(10, P.plus3(C("AS"), C("2H"), C("3D"))!!.second)
        org.junit.Assert.assertNull(P.plus3(C("KS"), C("AH"), C("2D")))
        org.junit.Assert.assertEquals(40, P.plus3(C("9C"), C("10C"), C("JC"))!!.second)
        org.junit.Assert.assertEquals(100, P.plus3(C("7D"), C("7D"), C("7D"))!!.second)
        org.junit.Assert.assertEquals(5, P.plus3(C("2D"), C("9D"), C("KD"))!!.second)
    }

    @org.junit.Test fun sideBetsArePaidAtTheDeal() {
        val w = object : com.zohar.slotar.blackjack.Wallet { override var credits = 1000L }
        val e = com.zohar.slotar.blackjack.BlackjackEngine(w, com.zohar.slotar.blackjack.SeededRng(3))
        val C = com.zohar.slotar.blackjack.Card::parse
        e.stack = ArrayDeque(listOf(C("8H"), C("9H"), C("8H"), C("5C")))   // player 8♥ 8♥, dealer 9♥ (up)
        e.deal(10, pairs = 5, plus3 = 5)
        // 1000 - 20 + perfect pair 5×26 + 21+3 (8♥ 8♥ 9♥ = flush) 5×6
        org.junit.Assert.assertEquals(1000L - 20 + 130 + 30, w.credits)
    }
}
