package com.zohar.slotar.bonus

import com.zohar.slotar.blackjack.Card
import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.hilo.HiLoCall

/**
 * The three bonus mini-games. Each is one short round that ends with a bonus percentage: the game that called it adds
 * that percentage of the win it was called on. THE ENGINE DECIDES, THE SCENE ONLY SHOWS: the dice, the next card and
 * the ball's way down are drawn here before anything moves.
 *
 * What each is worth on average (exact, checked in the unit test), so the game that calls it can count it in its own return:
 *   Dice      133.89 %  — two dice, the total picks the bonus (rare totals pay most)
 *   Plinko    148.05 %  — 8 rows, the pocket picks the bonus
 *   Hi-Lo     about 100 % whichever way is called — a right call pays 100 % × cards left ÷ cards that win, a wrong call nothing
 */
enum class BonusKind(val title: String) { HILO("Higher or Lower"), DICE("Dice"), PLINKO("Plinko") }

/** The outcome of one mini-game: `pct` percent of `base` is the bonus. */
data class BonusResult(val kind: BonusKind, val pct: Int, val base: Long) { val bonus get() = base * pct / 100 }

object BonusDice {
    /** Bonus % for the totals 2 … 12 (the same numbers are in layout.js → bonus.dicePct). */
    val TABLE = intArrayOf(500, 300, 200, 100, 50, 20, 50, 100, 200, 300, 500)
    fun pct(sum: Int): Int { require(sum in 2..12); return TABLE[sum - 2] }
    fun mean(): Double { var s = 0; for (a in 1..6) for (b in 1..6) s += pct(a + b); return s / 36.0 }
    fun roll(rng: Rng = SecureRng): Pair<Int, Int> = (rng.next(6) + 1) to (rng.next(6) + 1)
}

object BonusPlinko {
    const val ROWS = 8
    /** Bonus % of the pockets, edge … middle (the same numbers are printed under the pockets: layout.js → bonus.plinko.pct). */
    val HALF = intArrayOf(2000, 500, 200, 100, 50)
    fun pct(pocket: Int): Int { require(pocket in 0..ROWS); return HALF[minOf(pocket, ROWS - pocket)] }
    fun ways(k: Int): Int { var c = 1L; for (i in 0 until k) c = c * (ROWS - i) / (i + 1); return c.toInt() }
    fun mean(): Double { var s = 0L; for (k in 0..ROWS) s += ways(k).toLong() * pct(k); return s / 256.0 }
    /** The ball's way down: true = right, one fair turn a row. */
    fun path(rng: Rng = SecureRng): List<Boolean> = List(ROWS) { rng.next(2) == 1 }
}

/** One call of higher-or-lower on a fresh 52-card deck (ace low, king high, the same rank is a wrong call). */
class BonusHiLo(rng: Rng = SecureRng, first: Card? = null, next: Card? = null) {
    data class Offer(val call: HiLoCall, val wins: Int, val left: Int, val pct: Int)
    private val deck = ArrayList<Card>(52).apply {
        for (s in 0..3) for (r in 1..13) add(Card(r, s))
        for (i in size - 1 downTo 1) { val j = rng.next(i + 1); val t = this[i]; this[i] = this[j]; this[j] = t }
        // tests may fix the two cards; they are moved to the top, so the deck stays a whole deck
        next?.let { remove(it); add(it) }; first?.let { remove(it); add(it) }
    }
    val first: Card = deck.removeAt(deck.size - 1)
    /** The card that will be turned: fixed from the start, shown only after the call. */
    private val nextCard: Card = deck[deck.size - 1]
    var called: HiLoCall? = null; private set
    /** The calls that can win, with what a right one pays: 100 % × cards left ÷ cards that win, rounded down. */
    fun offers(): List<Offer> = HiLoCall.entries.mapNotNull { c ->
        val k = deck.count { if (c == HiLoCall.HIGHER) it.rank > first.rank else it.rank < first.rank }
        if (k == 0) null else Offer(c, k, deck.size, 100 * deck.size / k)
    }
    fun offer(c: HiLoCall) = offers().firstOrNull { it.call == c }
    /** Make the call: returns the card turned and the bonus % (0 on a wrong call). */
    fun guess(c: HiLoCall): Pair<Card, Int> {
        check(called == null) { "already called" }
        val o = offer(c) ?: throw IllegalArgumentException("${c.label} cannot win from ${first}")
        called = c
        val right = if (c == HiLoCall.HIGHER) nextCard.rank > first.rank else nextCard.rank < first.rank
        return nextCard to (if (right) o.pct else 0)
    }
}
