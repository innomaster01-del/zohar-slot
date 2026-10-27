package com.zohar.slotar.moneywheel

import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Wallet

/**
 * Money wheel (big wheel game show), play money. THE ENGINE DECIDES, THE WHEEL ONLY SHOWS: every spin's segment is drawn
 * here (uniform over the 54 segments) before the host touches the wheel.
 * Segments: 1 ×23, 2 ×15, 5 ×7, 10 ×4, 20 ×2, 40 ×1, 2x ×1, 7x ×1. A bet on a number wins when the wheel stops on it and
 * pays that number to 1. A multiplier segment pays nothing by itself: the wheel spins again and the next win is multiplied
 * (multipliers that land in a row multiply together).
 */
object MoneyWheel {
    /** Segment order on the wheel, clockwise from the top (same list as tools/3d/blackjack/mw.js). */
    val SEGMENTS = listOf("40", "1", "5", "2", "10", "1", "2", "1", "1", "20", "5", "2", "1", "2", "1", "1", "2", "1", "7x", "10", "5", "2", "1", "1", "2", "5", "1", "2", "1", "1", "2", "1", "10", "5", "1", "2", "2x", "20", "1", "2", "1", "5", "1", "2", "1", "10", "2", "1", "1", "5", "2", "1", "2", "1")
    val NUMBERS = listOf(1, 2, 5, 10, 20, 40)
    fun count(n: Int) = SEGMENTS.count { it == n.toString() }
    fun multiplier(seg: String): Int? = if (seg.endsWith("x")) seg.dropLast(1).toInt() else null

    /**
     * Exact return of a bet on [n] (per unit staked). Each spin: a number with probability c/54, a multiplier 2x or 7x with
     * 1/54 each. Over all the multiplier chains: P(final = n) = c/52 and E[product of multipliers; final = n] = c/54 · 1/(1 − 9/54).
     */
    fun rtp(n: Int): Double { val c = count(n).toDouble(); val stop = c / 52.0; val mult = c / 54.0 / (1 - 9.0 / 54.0); return stop + n * mult }
}

data class MwSpin(val index: Int) { val segment get() = MoneyWheel.SEGMENTS[index]; val multiplier get() = MoneyWheel.multiplier(segment) }
data class MwResult(val spins: List<MwSpin>, val number: Int, val multiplier: Int, val stakes: Map<Int, Long>, val returns: Map<Int, Long>) {
    val staked get() = stakes.values.sum(); val returned get() = returns.values.sum()
}

class MoneyWheelEngine(private val wallet: Wallet, private val rng: Rng = SecureRng) {
    val minBet = 5L; val maxBet = 500L; val maxTotal = 2000L
    /** For tests: the next segment indexes to come out. */
    var stack: ArrayDeque<Int>? = null

    fun validate(stakes: Map<Int, Long>): String? {
        if (stakes.keys.any { it !in MoneyWheel.NUMBERS } || stakes.values.any { it < 0 }) return "bad bet"
        val t = stakes.values.sum()
        if (t < minBet) return "Place at least $minBet."
        stakes.entries.firstOrNull { it.value > maxBet }?.let { return "Up to $maxBet on ${it.key}." }
        if (t > maxTotal) return "Table maximum is $maxTotal a spin."
        if (t > wallet.credits) return "Not enough play credits."
        return null
    }

    fun spin(stakes: Map<Int, Long>): MwResult {
        validate(stakes)?.let { throw IllegalArgumentException(it) }
        val bets = stakes.filterValues { it > 0 }
        wallet.credits -= bets.values.sum()
        val spins = ArrayList<MwSpin>(); var mult = 1
        while (true) {
            val s = MwSpin(stack?.removeFirstOrNull() ?: rng.next(MoneyWheel.SEGMENTS.size)); spins.add(s)
            val m = s.multiplier
            if (m != null && spins.size < 12) { mult *= m; continue }
            if (m != null) continue                           // (never in practice: 12 multipliers in a row) keep spinning
            val n = s.segment.toInt()
            val ret = bets.mapValues { (k, a) -> if (k == n) a + a * k * mult else 0L }
            wallet.credits += ret.values.sum()
            return MwResult(spins, n, mult, bets, ret)
        }
    }
}
