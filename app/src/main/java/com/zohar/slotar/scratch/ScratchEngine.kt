package com.zohar.slotar.scratch

import com.zohar.slotar.engine.CryptoRng
import com.zohar.slotar.engine.Rng

/**
 * ZOHAR AR Scratch Cards — game maths (provider-grade: fixed, published odds, identical for every player).
 * The outcome of a card is drawn when the card is bought; scratching only reveals it.
 *
 * Per card:  prize (× price) from [PRIZE_WEIGHTS]  ×  Super Scratch multiplier from [MULT_WEIGHTS]
 *            + an independent 3-star bonus (weight [FREE_CARD_WEIGHT]) that awards one free card of the same tier.
 * RTP = E[prize·mult] / (1 − pFree). Tiers differ only in the weight of the smallest prize → published RTP per tier.
 * PLAY MONEY in this app: credits cannot be bought or cashed out.
 */
enum class Tier(val label: String, val price: Long, val rtpTarget: Double, val smallWinWeight: Int) {
    SILVER("SILVER", 1, 0.94, 116_667),
    GOLD("GOLD", 5, 0.95, 123_546),
    PLATINUM("PLATINUM", 20, 0.96, 130_426);
}

interface Wallet { var credits: Long }

data class ScratchCard(
    val tier: Tier,
    val cells: List<Long>,            // 9 amounts shown in the 3×3 grid (credits)
    val prizeMultiple: Int,           // 0 = no win, else prize × price
    val winCells: List<Int>,          // the 3 matching cells (empty when no win)
    val multiplier: Int,              // Super Scratch box
    val stars: List<Boolean>,         // 3 bonus spots
    val freeCard: Boolean,
    val totalWin: Long,               // prize × price × multiplier (0 when no win)
    val paidWithFreeCard: Boolean,
)

object ScratchMath {
    const val TOTAL = 1_000_000
    /** prize (× price) → weight per million; the "1×" weight is per tier ([Tier.smallWinWeight]). */
    val PRIZES = intArrayOf(1, 2, 5, 10, 20, 50, 100, 500)
    private val FIXED = mapOf(2 to 70_000, 5 to 25_000, 10 to 8_000, 20 to 3_000, 50 to 1_000, 100 to 400, 500 to 70)
    fun prizeWeights(t: Tier): Map<Int, Int> = FIXED + (1 to t.smallWinWeight)
    val MULT_WEIGHTS = linkedMapOf(1 to 80, 2 to 12, 3 to 4, 5 to 3, 10 to 1)      // per 100
    const val FREE_CARD_WEIGHT = 30_000                                                 // per million (3 %)

    fun pWin(t: Tier) = prizeWeights(t).values.sum().toDouble() / TOTAL
    fun expectedMult(): Double = MULT_WEIGHTS.entries.sumOf { (m, w) -> m * w.toDouble() } / 100.0
    /** Exact return to player (fraction of price), counting free cards as a geometric series. */
    fun rtp(t: Tier): Double {
        val e = prizeWeights(t).entries.sumOf { (v, w) -> v * w.toDouble() } / TOTAL * expectedMult()
        return e / (1.0 - FREE_CARD_WEIGHT.toDouble() / TOTAL)
    }
    fun topWin(t: Tier) = PRIZES.max().toLong() * MULT_WEIGHTS.keys.max() * t.price
}

class ScratchEngine(private val wallet: Wallet, private val rng: Rng = CryptoRng()) {
    private val freeCards = HashMap<Tier, Int>()
    var cardsBought = 0L; private set
    var totalPaid = 0L; private set
    var totalWon = 0L; private set

    fun freeCardsFor(t: Tier) = freeCards[t] ?: 0
    /** Promotional free card (e.g. daily play-money card). */
    fun grantFreeCard(t: Tier) { freeCards[t] = freeCardsFor(t) + 1 }
    fun canBuy(t: Tier) = freeCardsFor(t) > 0 || wallet.credits >= t.price

    /** Buys (or redeems a free card) and returns the card with its outcome already decided; null if not enough credits. */
    fun buy(t: Tier): ScratchCard? {
        val free = freeCardsFor(t) > 0
        if (!free && wallet.credits < t.price) return null
        if (free) freeCards[t] = freeCardsFor(t) - 1 else { wallet.credits -= t.price; totalPaid += t.price }
        cardsBought++
        val card = generate(t, free)
        if (card.freeCard) freeCards[t] = freeCardsFor(t) + 1
        wallet.credits += card.totalWin; totalWon += card.totalWin
        return card
    }

    fun generate(t: Tier, paidWithFreeCard: Boolean = false): ScratchCard {
        // 1) outcome
        var r = rng.next(ScratchMath.TOTAL); var prize = 0
        for ((v, w) in ScratchMath.prizeWeights(t).entries.sortedBy { it.key }) { if (r < w) { prize = v; break }; r -= w }
        var m = rng.next(100); var mult = 1
        for ((k, w) in ScratchMath.MULT_WEIGHTS) { if (m < w) { mult = k; break }; m -= w }
        val free = rng.next(ScratchMath.TOTAL) < ScratchMath.FREE_CARD_WEIGHT
        // 2) a grid that shows exactly that outcome: the prize 3 times, every other amount at most twice
        val amounts = ScratchMath.PRIZES.map { it * t.price }
        val cells = MutableList(9) { 0L }
        val winCells = ArrayList<Int>()
        val order = (0 until 9).toMutableList().also { shuffle(it) }
        val counts = HashMap<Long, Int>()
        if (prize > 0) { val v = prize * t.price; repeat(3) { val c = order.removeAt(0); cells[c] = v; winCells.add(c) }; counts[v] = 3 }
        for (c in order) {
            val choices = amounts.filter { (counts[it] ?: 0) < 2 }
            val v = choices[rng.next(choices.size)]; cells[c] = v; counts[v] = (counts[v] ?: 0) + 1
        }
        // 3) bonus spots: 3 stars only with the free card, otherwise 0–2 stars
        val stars = if (free) listOf(true, true, true) else {
            val n = rng.next(3); val s = MutableList(3) { false }; val idx = (0 until 3).toMutableList().also { shuffle(it) }; repeat(n) { s[idx[it]] = true }; s
        }
        val win = if (prize > 0) prize * t.price * mult else 0L
        return ScratchCard(t, cells, prize, winCells.sorted(), mult, stars, free, win, paidWithFreeCard)
    }

    private fun shuffle(l: MutableList<Int>) { for (i in l.size - 1 downTo 1) { val j = rng.next(i + 1); val x = l[i]; l[i] = l[j]; l[j] = x } }
}
