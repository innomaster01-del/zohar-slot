package com.zohar.slotar

import com.zohar.slotar.blackjack.Card
import com.zohar.slotar.blackjack.SeededRng
import com.zohar.slotar.bonus.*
import com.zohar.slotar.hilo.HiLoCall
import com.zohar.slotar.plinko.PlinkoGeo
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BonusEngineTest {
    @Test fun diceBonus() {
        assertEquals(500, BonusDice.pct(2)); assertEquals(20, BonusDice.pct(7)); assertEquals(500, BonusDice.pct(12)); assertEquals(100, BonusDice.pct(9))
        for (s in 2..12) assertEquals(BonusDice.pct(s), BonusDice.pct(14 - s))
        assertEquals(4820 / 36.0, BonusDice.mean(), 1e-9)                         // 133.89 %
        val rng = SeededRng(5); val seen = IntArray(7); repeat(60_000) { val (a, b) = BonusDice.roll(rng); seen[a]++; seen[b]++ }
        for (f in 1..6) assertEquals(1.0 / 6, seen[f] / 120_000.0, 0.005)
    }
    @Test fun plinkoBonus() {
        assertEquals(256, (0..8).sumOf { BonusPlinko.ways(it) }); assertEquals(70, BonusPlinko.ways(4))
        assertEquals(2000, BonusPlinko.pct(0)); assertEquals(50, BonusPlinko.pct(4)); assertEquals(500, BonusPlinko.pct(7))
        assertEquals(37900 / 256.0, BonusPlinko.mean(), 1e-9)                     // 148.05 %
        assertEquals(8, BonusPlinko.path(SeededRng(1)).size)
    }
    @Test fun resultIsAPercentageOfTheBase() {
        assertEquals(250L, BonusResult(BonusKind.DICE, 50, 500).bonus); assertEquals(0L, BonusResult(BonusKind.HILO, 0, 500).bonus); assertEquals(2000L, BonusResult(BonusKind.PLINKO, 2000, 100).bonus)
    }
    @Test fun hiLoPaysTrueOdds() {
        val g = BonusHiLo(SeededRng(1), first = Card.parse("7S"), next = Card.parse("KH"))
        assertEquals(Card.parse("7S"), g.first)
        val hi = g.offer(HiLoCall.HIGHER)!!; assertEquals(24, hi.wins); assertEquals(51, hi.left); assertEquals(212, hi.pct)      // 100 × 51 / 24
        val (card, pct) = g.guess(HiLoCall.HIGHER); assertEquals(Card.parse("KH"), card); assertEquals(212, pct)
        try { g.guess(HiLoCall.LOWER); fail("called twice") } catch (_: IllegalStateException) {}
        assertEquals(0, BonusHiLo(SeededRng(1), Card.parse("7S"), Card.parse("KH")).guess(HiLoCall.LOWER).second)
        assertEquals(0, BonusHiLo(SeededRng(1), Card.parse("7S"), Card.parse("7H")).guess(HiLoCall.HIGHER).second)                  // the same rank is wrong
        val k = BonusHiLo(SeededRng(1), first = Card.parse("KS")); assertNull(k.offer(HiLoCall.HIGHER)); assertEquals(106, k.offer(HiLoCall.LOWER)!!.pct)   // 100 × 51 / 48
        try { k.guess(HiLoCall.HIGHER); fail("a call that cannot win was taken") } catch (_: IllegalArgumentException) {}
    }
    /** Whichever way is called, the bonus is worth the same: exact, over every first card and every next card. */
    @Test fun hiLoIsWorthTheSameEitherWay() {
        val all = (0..3).flatMap { s -> (1..13).map { Card(it, s) } }
        for (prefer in HiLoCall.entries) {
            var sum = 0L; var n = 0
            for (a in all) for (b in all) if (a != b) {
                val g = BonusHiLo(SeededRng(1), a, b); val call = g.offer(prefer)?.call ?: g.offers().first().call
                sum += g.guess(call).second; n++
            }
            val mean = sum / n.toDouble()
            assertTrue("$prefer $mean", mean <= 100.0 && mean > 99.0)             // 100 % less the rounding down
        }
    }
    @Test fun hiLoDealsFromAWholeDeck() {
        val rng = SeededRng(9); val firsts = IntArray(14)
        repeat(26_000) { val g = BonusHiLo(rng); firsts[g.first.rank]++; val o = g.offers().first(); assertNotEquals(g.first, g.guess(o.call).first) }
        for (r in 1..13) assertEquals(1.0 / 13, firsts[r] / 26_000.0, 0.01)
    }
    /** The numbers drawn on the models are the engine's numbers, and the ball on the triangle ends over the engine's pocket on all 256 ways. */
    @Test fun whatIsShownIsWhatIsPaid() {
        val f = listOf("src/blackjack/assets/bj_layout.json", "app/src/blackjack/assets/bj_layout.json").map { File(it) }.first { it.exists() }
        val b = JSONObject(f.readText()).getJSONObject("bonus"); val p = b.getJSONObject("plinko")
        assertEquals(BonusDice.TABLE.toList(), b.getJSONArray("dicePct").let { a -> List(a.length()) { a.getInt(it) } })
        assertEquals(BonusPlinko.HALF.toList(), p.getJSONArray("pct").let { a -> List(a.length()) { a.getInt(it) } })
        assertEquals(BonusPlinko.ROWS, p.getInt("rows"))
        fun d(n: String) = p.getDouble(n).toFloat()
        val geo = PlinkoGeo(p.getInt("rows"), d("dx"), d("dy"), d("topY"), d("pegR"), d("ballR"), d("dropY"), d("cupY"))
        for (bits in 0 until 256) {
            val path = List(8) { (bits shr it) and 1 == 1 }; val legs = geo.legs(path); val end = geo.position(legs, geo.duration(legs) + 1)
            assertEquals(geo.pocketX(path.count { it }), end[0], 1e-4f); assertEquals(d("cupY"), end[1], 1e-4f)
        }
    }
}
