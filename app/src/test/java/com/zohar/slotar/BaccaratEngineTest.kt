package com.zohar.slotar

import com.zohar.slotar.baccarat.*
import com.zohar.slotar.blackjack.Card
import com.zohar.slotar.blackjack.SeededRng
import com.zohar.slotar.blackjack.Wallet
import org.junit.Assert.*
import org.junit.Test

class BaccaratEngineTest {
    private class W(override var credits: Long = 10_000) : Wallet
    /** Cards in dealing order: P1 B1 P2 B2, then third cards. */
    private fun eng(w: W, vararg cards: String) = BaccaratEngine(w, SeededRng(1)).apply { stack = ArrayDeque(cards.map { Card.parse(it) }) }

    /** Exact 8-deck odds, walking every card order through the engine's own drawing rule (bankerDraws). */
    @Test fun exactEightDeckOdds() {
        val c = IntArray(10) { if (it == 0) 128 else 32 }; val tot = 416
        var pw = 0.0; var bw = 0.0; var tw = 0.0
        fun score(pt: Int, bt: Int, w: Double) { if (pt > bt) pw += w else if (bt > pt) bw += w else tw += w }
        for (a in 0..9) { val pa = c[a] / tot.toDouble(); c[a]--
        for (b in 0..9) { val pb = c[b] / (tot - 1.0); c[b]--
        for (a2 in 0..9) { val pa2 = c[a2] / (tot - 2.0); c[a2]--
        for (b2 in 0..9) { val pb2 = c[b2] / (tot - 3.0); c[b2]--
            val w = pa * pb * pa2 * pb2; val p2 = (a + a2) % 10; val bt2 = (b + b2) % 10; val n = tot - 4.0
            if (p2 >= 8 || bt2 >= 8) score(p2, bt2, w)
            else if (p2 <= 5) {
                for (x in 0..9) { val px = c[x] / n; c[x]--; val pt = (p2 + x) % 10
                    if (bankerDraws(bt2, x)) for (y in 0..9) score(pt, (bt2 + y) % 10, w * px * c[y] / (n - 1)) else score(pt, bt2, w * px)
                    c[x]++ }
            } else if (bankerDraws(bt2, null)) { for (y in 0..9) score(p2, (bt2 + y) % 10, w * c[y] / n) } else score(p2, bt2, w)
            c[b2]++ }; c[a2]++ }; c[b]++ }; c[a]++ }
        assertEquals(0.458597, bw, 1e-6); assertEquals(0.446247, pw, 1e-6); assertEquals(0.095156, tw, 1e-6)
        assertEquals(0.0106, pw - 0.95 * bw, 1e-4)          // banker bet house edge 1.06 %
        assertEquals(0.0124, bw - pw, 1e-4)                 // player bet 1.24 %
        assertEquals(0.1436, 1 - 9 * tw, 1e-4)              // tie 14.36 %
    }
    @Test fun naturalStops() {
        val w = W(); val r = eng(w, "9S", "5H", "KD", "2C").deal(mapOf(BacBet.PLAYER to 10L))
        assertEquals(2, r.player.size); assertEquals(2, r.banker.size); assertEquals(BacWinner.PLAYER, r.winner); assertEquals(10_010L, w.credits)
    }
    @Test fun thirdCardRules() {
        // player 3 (A+2) draws a 5 → 8; banker 4 (2+2) draws on a player third card of 5 → K (0) → 4
        val w = W(); val r = eng(w, "AS", "2H", "2D", "2C", "5S", "KH").deal(mapOf(BacBet.BANKER to 20L))
        assertEquals(3, r.player.size); assertEquals(3, r.banker.size); assertEquals(8, r.pTotal); assertEquals(4, r.bTotal)
        assertEquals(BacWinner.PLAYER, r.winner); assertEquals(10_000L - 20, w.credits)
        // banker 6 stands when the player's third card is an 8
        val r2 = eng(W(), "AS", "3H", "2D", "3C", "8S", "9H").deal(mapOf(BacBet.TIE to 5L))
        assertEquals(2, r2.banker.size)
    }
    @Test fun bankerCommissionAndTiePush() {
        val w = W(); eng(w, "2S", "9H", "3D", "KC").deal(mapOf(BacBet.BANKER to 100L))          // banker natural 9
        assertEquals(10_000L + 95, w.credits)
        val w2 = W(); val r = eng(w2, "4S", "5H", "4D", "3C", "KS", "KH").deal(mapOf(BacBet.PLAYER to 10L, BacBet.TIE to 10L))   // 8 v 8
        assertEquals(BacWinner.TIE, r.winner); assertEquals(10_000L - 20 + 10 + 90, w2.credits)
    }
    @Test fun pairsPayElevenToOne() {
        val w = W(); eng(w, "7S", "QH", "7D", "QC").deal(mapOf(BacBet.PLAYER to 5L, BacBet.PPAIR to 5L, BacBet.BPAIR to 5L))
        // player 4 (7+7), banker 0 (Q+Q): player draws, banker draws; both pairs pay 11:1 regardless of the coup
        assertEquals(true, w.credits >= 10_000L - 15 + 60 + 60)
    }
    @Test fun limits() {
        val e = BaccaratEngine(W(100), SeededRng(2))
        assertNotNull(e.validate(mapOf(BacBet.PPAIR to 10L)))                 // a side bet alone is not enough
        assertNotNull(e.validate(mapOf(BacBet.PLAYER to 600L)))
        assertNull(e.validate(mapOf(BacBet.BANKER to 25L, BacBet.BPAIR to 5L)))
    }
}
