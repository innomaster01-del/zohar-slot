package com.zohar.slotar.craps

import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Wallet

/**
 * Craps, play money: the Pass Line and the Field, by the rules of a casino table. THE ENGINE DECIDES, THE TABLE ONLY
 * SHOWS: both dice are drawn here before they leave the hand; the throw on the table lands on those faces.
 *
 * Pass Line. The come-out roll: 7 or 11 wins even money, 2, 3 or 12 ("craps") loses, any other total becomes the
 * point. Then the dice are rolled until the point comes again (the line wins even money) or a 7 comes first (it
 * loses). The bet stays on the line until then: it cannot be taken down or changed once a point is on.
 * Chance to win 244/495 = 49.29 %, house edge 1.41 %.
 *
 * Field. One roll, any roll: 3, 4, 9, 10, 11 pay even money, 2 pays double, 12 pays triple; 5, 6, 7, 8 lose.
 * Returns 35/36: house edge 2.78 %.
 */
enum class CrapsBet(val label: String) { PASS("Pass Line"), FIELD("Field") }
enum class PassOutcome { NONE, NATURAL, CRAPS, POINT_SET, POINT_MADE, SEVEN_OUT }

/** What the Field pays on a total, to one (null = it loses). */
fun fieldPays(sum: Int): Int? = when (sum) { 2 -> 2; 12 -> 3; 3, 4, 9, 10, 11 -> 1; else -> null }

data class CrapsRoll(
    val d1: Int, val d2: Int, val pointBefore: Int?, val pointAfter: Int?, val pass: PassOutcome,
    val passStake: Long, val passReturned: Long, val fieldStake: Long, val fieldReturned: Long,
) {
    val sum get() = d1 + d2
    val hard get() = d1 == d2
    val comeOut get() = pointBefore == null
    /** The Pass Line bet was settled by this roll (won or lost); otherwise it is still on the line. */
    val passSettled get() = pass == PassOutcome.NATURAL || pass == PassOutcome.CRAPS || pass == PassOutcome.POINT_MADE || pass == PassOutcome.SEVEN_OUT
    val passWon get() = pass == PassOutcome.NATURAL || pass == PassOutcome.POINT_MADE
    val fieldWon get() = fieldStake > 0 && fieldReturned > 0
    /** Credits won or lost on the bets this roll settled (a line bet still riding is neither). */
    val net get() = (if (passSettled) passReturned - passStake else 0L) + fieldReturned - fieldStake
}

class CrapsEngine(private val wallet: Wallet, private val rng: Rng = SecureRng) {
    val minBet = 5L; val maxBet = 500L
    /** The point, while one is on; null = the next roll is a come-out roll. */
    var point: Int? = null; private set
    /** What rides on the Pass Line while a point is on (already taken from the wallet). */
    var passStake = 0L; private set
    var last: CrapsRoll? = null; private set
    /** For tests: the next throws, as (first die, second die). */
    var stack: ArrayDeque<Pair<Int, Int>>? = null

    /** Can the dice be thrown with these bets? `pass` is only read on a come-out roll; with a point on, the line bet is the one riding. */
    fun validate(pass: Long, field: Long): String? {
        if (field != 0L && field < minBet) return "The minimum bet is $minBet."
        if (field > maxBet) return "The table maximum is $maxBet."
        if (point == null) {
            if (pass < minBet) return "The shooter needs at least $minBet on the Pass Line."
            if (pass > maxBet) return "The table maximum is $maxBet."
            if (pass + field > wallet.credits) return "Not enough play credits."
        } else if (field > wallet.credits) return "Not enough play credits."
        return null
    }

    fun roll(pass: Long, field: Long): CrapsRoll {
        validate(pass, field)?.let { throw IllegalArgumentException(it) }
        val before = point
        if (before == null) { wallet.credits -= pass; passStake = pass }
        wallet.credits -= field
        val (a, b) = stack?.removeFirstOrNull() ?: ((rng.next(6) + 1) to (rng.next(6) + 1))
        require(a in 1..6 && b in 1..6)
        val sum = a + b; val stake = passStake
        val outcome = when {
            before == null && (sum == 7 || sum == 11) -> PassOutcome.NATURAL
            before == null && (sum == 2 || sum == 3 || sum == 12) -> PassOutcome.CRAPS
            before == null -> PassOutcome.POINT_SET
            sum == before -> PassOutcome.POINT_MADE
            sum == 7 -> PassOutcome.SEVEN_OUT
            else -> PassOutcome.NONE
        }
        val passBack = if (outcome == PassOutcome.NATURAL || outcome == PassOutcome.POINT_MADE) stake * 2 else 0L
        val fieldBack = fieldPays(sum)?.let { field * (1 + it) } ?: 0L
        wallet.credits += passBack + fieldBack
        when (outcome) {
            PassOutcome.POINT_SET -> point = sum
            PassOutcome.NONE -> {}
            else -> { point = null; passStake = 0 }
        }
        return CrapsRoll(a, b, before, point, outcome, stake, passBack, field, fieldBack).also { last = it }
    }
}
