package com.zohar.slotar.plinko

import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Wallet

/**
 * Plinko, play money. THE ENGINE DECIDES, THE BOARD ONLY SHOWS: the ball's whole way down is drawn here before it is
 * let go — one fair left-or-right for each of the 12 rows of pegs — and the ball on the board follows exactly that way.
 * The pocket it lands in is the number of times it went right (0 = far left … 12 = far right).
 *
 * Each pocket pays the stake times its multiplier. Three boards to choose from; they differ in how the prizes are
 * spread (how often and how big), not in what comes back over time: each returns just under 97 %.
 * Multipliers are kept in fifths (×5), and bets are multiples of 5, so every prize is a whole number of credits.
 */
enum class PlinkoRisk(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

object Plinko {
    const val ROWS = 12
    const val POCKETS = ROWS + 1
    // edge … centre, in fifths: 60 = ×12, 1 = ×0.2
    private val HALF = mapOf(
        PlinkoRisk.LOW to intArrayOf(60, 16, 8, 8, 6, 5, 1),
        PlinkoRisk.MEDIUM to intArrayOf(130, 56, 22, 10, 6, 2, 2),
        PlinkoRisk.HIGH to intArrayOf(755, 122, 42, 10, 3, 1, 1),
    )
    /** The multiplier of pocket k, in fifths. */
    fun fifths(risk: PlinkoRisk, k: Int): Int { require(k in 0..ROWS); return HALF.getValue(risk)[minOf(k, ROWS - k)] }
    fun label(fifths: Int): String = if (fifths % 5 == 0) "${fifths / 5}" else "${fifths / 5}.${fifths % 5 * 2}"
    /** How many of the 4096 ways down end in pocket k. */
    fun ways(k: Int): Int { var c = 1L; for (i in 0 until k) c = c * (ROWS - i) / (i + 1); return c.toInt() }
    const val ALL_WAYS = 4096
    fun rtp(risk: PlinkoRisk): Double { var s = 0L; for (k in 0..ROWS) s += ways(k).toLong() * fifths(risk, k); return s / 5.0 / ALL_WAYS }
}

/** One ball: `path[r]` is true where it went right at row r. */
data class PlinkoDrop(val path: List<Boolean>, val risk: PlinkoRisk, val stake: Long, val fifths: Int, val returned: Long) {
    val pocket get() = path.count { it }
    val net get() = returned - stake
}

class PlinkoEngine(private val wallet: Wallet, private val rng: Rng = SecureRng) {
    val minBet = 5L; val maxBet = 500L
    var last: PlinkoDrop? = null; private set
    /** For tests: the ways down of the next balls. */
    var stack: ArrayDeque<List<Boolean>>? = null

    fun validate(bet: Long): String? = when {
        bet < minBet -> "The minimum bet is $minBet."
        bet > maxBet -> "The maximum bet is $maxBet."
        bet % 5 != 0L -> "Bets go in steps of 5."
        bet > wallet.credits -> "Not enough play credits."
        else -> null
    }

    fun drop(bet: Long, risk: PlinkoRisk): PlinkoDrop {
        validate(bet)?.let { throw IllegalArgumentException(it) }
        wallet.credits -= bet
        val path = stack?.removeFirstOrNull() ?: List(Plinko.ROWS) { rng.next(2) == 1 }
        require(path.size == Plinko.ROWS)
        val f = Plinko.fifths(risk, path.count { it })
        val back = bet / 5 * f
        wallet.credits += back
        return PlinkoDrop(path, risk, bet, f, back).also { last = it }
    }
}
