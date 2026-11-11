package com.zohar.slotar.hilo

import com.zohar.slotar.blackjack.Card
import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Wallet
import java.math.BigInteger

/**
 * Higher or Lower, play money. THE ENGINE DECIDES, THE TABLE ONLY SHOWS: one 52-card deck is shuffled when the round
 * starts, so every card of the round is fixed before the first one is turned.
 *
 * The round is a ladder. The first card is turned; the player says whether the next card is HIGHER or LOWER. A right
 * call grows the pot and the player may take it or go on; a wrong call (the same rank is wrong too) loses the stake.
 * Ace is the lowest card, king the highest.
 *
 * Pay: pot = stake × RTP × Π (cards left ÷ cards that win), rounded down to a whole credit only when it is paid. Every
 * step is paid at its true odds from the cards still in the deck, and the house keeps its 3 % once, on the first step.
 * So the return is the same 97 % whichever way the player calls and whenever he stops (less the rounding, under one
 * credit a round). A call that cannot win, or whose pot would pass the table's top prize, is not offered.
 */
enum class HiLoCall(val label: String) { HIGHER("Higher"), LOWER("Lower") }
enum class HiLoPhase { IDLE, GUESS, DONE }

/** One call on offer: `wins` of the `left` cards still in the deck make it right; `pot` is what the pot becomes. */
data class HiLoOffer(val call: HiLoCall, val wins: Int, val left: Int, val pot: Long) {
    val chance get() = wins.toDouble() / left
}
data class HiLoStep(val call: HiLoCall, val from: Card, val card: Card, val right: Boolean, val pot: Long)
data class HiLoResult(val stake: Long, val returned: Long, val cards: List<Card>, val steps: Int, val taken: Boolean)

class HiLoEngine(private val wallet: Wallet, private val rng: Rng = SecureRng) {
    val minBet = 5L; val maxBet = 500L
    /** The most one round pays. A call that would take the pot past it is not offered: the player takes the pot. */
    val maxPot = 10_000L
    /** The longest ladder: the table has room for this many cards after the first. */
    val maxSteps = 11
    val rtpNum = 97L; val rtpDen = 100L

    var phase = HiLoPhase.IDLE; private set
    var stake = 0L; private set
    /** The cards turned so far this round; the last one is the card to beat. */
    val cards = ArrayList<Card>()
    val current get() = cards.last()
    val steps get() = cards.size - 1
    var result: HiLoResult? = null; private set
    private var deck = ArrayList<Card>()
    // the pot as an exact fraction; it only becomes whole credits when it is shown or paid
    private var potNum = BigInteger.ZERO; private var potDen = BigInteger.ONE
    /** For tests: the next cards to come out (top first). They are taken out of the deck, so the odds stay true. */
    var stack: ArrayDeque<Card>? = null

    /** What the player holds now, in whole credits (0 before the first right call: the stake is still at risk). */
    val pot: Long get() = if (steps == 0) 0L else potNum.divide(potDen).toLong()

    fun validate(bet: Long): String? = when {
        phase == HiLoPhase.GUESS -> "The round is still on."
        bet < minBet -> "The minimum bet is $minBet."
        bet > maxBet -> "The table maximum is $maxBet."
        bet > wallet.credits -> "Not enough play credits."
        else -> null
    }

    /** Take the stake, shuffle a fresh deck, turn the first card. */
    fun start(bet: Long): Card {
        validate(bet)?.let { throw IllegalArgumentException(it) }
        wallet.credits -= bet; stake = bet
        deck = ArrayList(52); for (s in 0..3) for (r in 1..13) deck.add(Card(r, s))
        for (i in deck.size - 1 downTo 1) { val j = rng.next(i + 1); val t = deck[i]; deck[i] = deck[j]; deck[j] = t }
        cards.clear(); cards.add(draw())
        potNum = BigInteger.valueOf(bet * rtpNum); potDen = BigInteger.valueOf(rtpDen)
        phase = HiLoPhase.GUESS; result = null
        return current
    }
    private fun draw(): Card {
        val s = stack?.removeFirstOrNull() ?: return deck.removeAt(deck.size - 1)
        require(deck.remove(s)) { "stacked card $s is not in the deck" }
        return s
    }

    /** The calls the player may make now. Empty = nothing more to call: take the pot. */
    fun offers(): List<HiLoOffer> {
        if (phase != HiLoPhase.GUESS || steps >= maxSteps) return emptyList()
        val r = current.rank; val n = deck.size
        return HiLoCall.entries.mapNotNull { c ->
            val k = deck.count { if (c == HiLoCall.HIGHER) it.rank > r else it.rank < r }
            if (k == 0) return@mapNotNull null
            val p = potNum.multiply(BigInteger.valueOf(n.toLong())).divide(potDen.multiply(BigInteger.valueOf(k.toLong())))
            if (p > BigInteger.valueOf(maxPot)) null else HiLoOffer(c, k, n, p.toLong())
        }
    }
    fun offer(call: HiLoCall) = offers().firstOrNull { it.call == call }
    /** The player must take the pot: there is one, and no call is on offer. */
    val mustTake get() = phase == HiLoPhase.GUESS && steps > 0 && offers().isEmpty()

    /** Turn the next card. A wrong call ends the round and the stake is gone. */
    fun guess(call: HiLoCall): HiLoStep {
        check(phase == HiLoPhase.GUESS) { "no round" }
        val o = offer(call) ?: throw IllegalArgumentException("${call.label} is not on offer")
        val from = current; val next = draw(); cards.add(next)
        val right = if (call == HiLoCall.HIGHER) next.rank > from.rank else next.rank < from.rank
        if (right) {
            potNum = potNum.multiply(BigInteger.valueOf(o.left.toLong())); potDen = potDen.multiply(BigInteger.valueOf(o.wins.toLong()))
            val g = potNum.gcd(potDen); potNum = potNum.divide(g); potDen = potDen.divide(g)
        } else {
            potNum = BigInteger.ZERO; potDen = BigInteger.ONE
            phase = HiLoPhase.DONE; result = HiLoResult(stake, 0, cards.toList(), steps, taken = false)
        }
        return HiLoStep(call, from, next, right, if (right) pot else 0)
    }

    fun canTake() = phase == HiLoPhase.GUESS && steps > 0
    /**
     * The table could not go on showing the round (a fault, not a choice the player has): nothing is taken from him.
     * A pot already won is paid; with no call made yet the stake goes back.
     */
    fun abandon(): HiLoResult? {
        if (phase != HiLoPhase.GUESS) return null
        if (canTake()) return take()
        wallet.credits += stake; phase = HiLoPhase.DONE
        return HiLoResult(stake, stake, cards.toList(), 0, taken = false).also { result = it }
    }
    /** Take the pot: it is paid, rounded down to a whole credit, and the round ends. */
    fun take(): HiLoResult {
        check(canTake()) { "nothing to take" }
        val pay = pot
        wallet.credits += pay
        phase = HiLoPhase.DONE
        return HiLoResult(stake, pay, cards.toList(), steps, taken = true).also { result = it }
    }
}
