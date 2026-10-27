package com.zohar.slotar.holdem

import com.zohar.slotar.blackjack.Card
import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Wallet

/**
 * Casino Hold'em, play money (the version most online casinos run). THE ENGINE DECIDES, THE TABLE ONLY SHOWS.
 * One 52-card deck, shuffled every round. Player: Ante (+ optional AA Bonus) → 2 cards each, 3 community cards →
 * Call (2 × Ante) or Fold → turn + river, dealer reveals. Dealer qualifies with a pair of 4s or better.
 *  - Dealer does not qualify: Ante pays by the table below, Call pushes.
 *  - Dealer qualifies: player wins → Ante by the table + Call 1:1; tie → both push; dealer wins → both lose.
 * Ante table: royal flush 100, straight flush 20, four of a kind 10, full house 3, flush 2, straight or less 1.
 * AA Bonus (player's 2 cards + flop, settled even after a fold): royal 100, straight flush 50, quads 40, full house 30,
 * flush 20, straight / trips / two pair / pair of aces 7.
 */

// ---------------- hand evaluation ----------------
enum class Cat(val label: String) { HIGH("High card"), PAIR("Pair"), TWO_PAIR("Two pair"), TRIPS("Three of a kind"), STRAIGHT("Straight"), FLUSH("Flush"), FULL_HOUSE("Full house"), QUADS("Four of a kind"), STRAIGHT_FLUSH("Straight flush"), ROYAL("Royal flush") }
data class HandValue(val cat: Cat, val score: Long, val best: List<Card>) : Comparable<HandValue> {
    override fun compareTo(other: HandValue) = score.compareTo(other.score)
    /** Rank of the pair for a one-pair hand (2..14), else 0. */
    val pairRank get() = if (cat == Cat.PAIR) ((score shr 16) and 0xF).toInt() else 0
}
object Poker {
    private fun hi(c: Card) = if (c.rank == 1) 14 else c.rank
    /** Exact 5-card value. Score = category, then the ranks that decide ties, most important first (4 bits each). */
    fun eval5(cs: List<Card>): HandValue {
        require(cs.size == 5)
        val r = cs.map { hi(it) }.sortedDescending()
        val flush = cs.all { it.suit == cs[0].suit }
        val distinct = r.distinct()
        var straightHigh = 0
        if (distinct.size == 5) { if (r[0] - r[4] == 4) straightHigh = r[0]; if (r == listOf(14, 5, 4, 3, 2)) straightHigh = 5 }
        // group by count, then by rank: e.g. full house = [trips rank, pair rank]
        val groups = r.groupingBy { it }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
        val counts = groups.map { it.value }; val order = groups.map { it.key }
        val cat = when {
            straightHigh > 0 && flush -> if (straightHigh == 14) Cat.ROYAL else Cat.STRAIGHT_FLUSH
            counts[0] == 4 -> Cat.QUADS
            counts[0] == 3 && counts[1] == 2 -> Cat.FULL_HOUSE
            flush -> Cat.FLUSH
            straightHigh > 0 -> Cat.STRAIGHT
            counts[0] == 3 -> Cat.TRIPS
            counts[0] == 2 && counts[1] == 2 -> Cat.TWO_PAIR
            counts[0] == 2 -> Cat.PAIR
            else -> Cat.HIGH
        }
        val keys = if (straightHigh > 0) listOf(straightHigh) else order
        var score = cat.ordinal.toLong()
        for (i in 0 until 5) score = (score shl 4) or (keys.getOrNull(i) ?: 0).toLong()
        return HandValue(cat, score, cs)
    }
    /** Best five of up to seven cards. */
    fun best(cs: List<Card>): HandValue {
        if (cs.size == 5) return eval5(cs)
        require(cs.size == 7)
        var best: HandValue? = null
        for (a in 0 until 7) for (b in a + 1 until 7) {          // leave out two of the seven
            val v = eval5(cs.filterIndexed { i, _ -> i != a && i != b }); if (best == null || v > best) best = v
        }
        return best!!
    }
}

object HoldemPays {
    fun ante(c: Cat) = when (c) { Cat.ROYAL -> 100; Cat.STRAIGHT_FLUSH -> 20; Cat.QUADS -> 10; Cat.FULL_HOUSE -> 3; Cat.FLUSH -> 2; else -> 1 }
    /** AA Bonus on the first five cards; null = no win. */
    fun bonus(v: HandValue): Int? = when (v.cat) {
        Cat.ROYAL -> 100; Cat.STRAIGHT_FLUSH -> 50; Cat.QUADS -> 40; Cat.FULL_HOUSE -> 30; Cat.FLUSH -> 20
        Cat.STRAIGHT, Cat.TRIPS, Cat.TWO_PAIR -> 7
        Cat.PAIR -> if (v.pairRank == 14) 7 else null
        else -> null
    }
    fun qualifies(v: HandValue) = v.cat > Cat.PAIR || (v.cat == Cat.PAIR && v.pairRank >= 4)
}

enum class HPhase { IDLE, DECIDE, DONE }
enum class HOutcome { WIN, PUSH, LOSE, FOLD, DEALER_NOT_QUALIFIED }
data class HoldemResult(
    val outcome: HOutcome, val player: HandValue?, val dealer: HandValue?, val dealerQualifies: Boolean,
    val ante: Long, val call: Long, val anteReturned: Long, val callReturned: Long,
    val bonus: Long, val bonusReturned: Long, val bonusHand: HandValue?,
) { val returned get() = anteReturned + callReturned + bonusReturned; val staked get() = ante + call + bonus }

class HoldemEngine(private val wallet: Wallet, private val rng: Rng = SecureRng, val minAnte: Long = 5, val maxAnte: Long = 200, val maxBonus: Long = 100) {
    var phase = HPhase.IDLE; private set
    val player = ArrayList<Card>(); val dealer = ArrayList<Card>(); val board = ArrayList<Card>()
    var ante = 0L; private set; var bonus = 0L; private set
    var result: HoldemResult? = null; private set
    /** AA Bonus is decided by the flop: known (and paid) as soon as the first five cards are out. */
    var bonusHand: HandValue? = null; private set
    var bonusReturned = 0L; private set
    /** For tests: the next cards to come out (top first). */
    var stack: ArrayDeque<Card>? = null
    private var deck = ArrayList<Card>()

    private fun shuffle() {
        deck = ArrayList(); for (s in 0..3) for (r in 1..13) deck.add(Card(r, s))
        for (i in deck.size - 1 downTo 1) { val j = rng.next(i + 1); val t = deck[i]; deck[i] = deck[j]; deck[j] = t }
    }
    private fun draw(): Card { stack?.removeFirstOrNull()?.let { c -> deck.remove(c); return c }; return deck.removeAt(deck.size - 1) }

    fun deal(ante: Long, bonus: Long = 0) {
        check(phase == HPhase.IDLE || phase == HPhase.DONE) { "round in progress" }
        require(ante in minAnte..maxAnte) { "ante $minAnte–$maxAnte" }
        require(bonus in 0..maxBonus) { "AA bonus up to $maxBonus" }
        require(ante * 3 + bonus <= wallet.credits) { "you need 3 × the ante (for the call) plus the bonus" }
        shuffle()
        wallet.credits -= ante + bonus
        this.ante = ante; this.bonus = bonus; result = null; bonusHand = null; bonusReturned = 0
        player.clear(); dealer.clear(); board.clear()
        player.add(draw()); dealer.add(draw()); player.add(draw()); dealer.add(draw())
        repeat(3) { board.add(draw()) }
        // AA Bonus: player's two + the flop
        if (bonus > 0) {
            val v = Poker.eval5(player + board); bonusHand = v
            HoldemPays.bonus(v)?.let { p -> bonusReturned = bonus * (p + 1); wallet.credits += bonusReturned }
        }
        phase = HPhase.DECIDE
    }

    val callCost get() = ante * 2
    fun canCall() = phase == HPhase.DECIDE && wallet.credits >= callCost

    fun fold() {
        check(phase == HPhase.DECIDE)
        repeat(2) { board.add(draw()) }                        // shown afterwards, like a live table
        val pv = Poker.best(player + board); val dv = Poker.best(dealer + board)
        result = HoldemResult(HOutcome.FOLD, pv, dv, HoldemPays.qualifies(dv), ante, 0, 0, 0, bonus, bonusReturned, bonusHand)
        phase = HPhase.DONE
    }

    fun call() {
        check(canCall())
        val call = callCost; wallet.credits -= call
        repeat(2) { board.add(draw()) }
        val pv = Poker.best(player + board); val dv = Poker.best(dealer + board)
        val q = HoldemPays.qualifies(dv)
        val (o, a, c) = when {
            !q -> Triple(HOutcome.DEALER_NOT_QUALIFIED, ante * (HoldemPays.ante(pv.cat) + 1), call)
            pv > dv -> Triple(HOutcome.WIN, ante * (HoldemPays.ante(pv.cat) + 1), call * 2)
            pv.score == dv.score -> Triple(HOutcome.PUSH, ante, call)
            else -> Triple(HOutcome.LOSE, 0L, 0L)
        }
        wallet.credits += a + c
        result = HoldemResult(o, pv, dv, q, ante, call, a, c, bonus, bonusReturned, bonusHand)
        phase = HPhase.DONE
    }
}

/**
 * The hint: what a Call is worth, in antes, found by dealing the unknown cards (turn, river, the dealer's two) many
 * times from what is left of the deck. Fold costs exactly 1 ante, so Call is right when its value is above −1.
 * With enough samples this is the optimal decision (about 2.2 % house edge on the ante with perfect play).
 */
fun holdemCallValue(player: List<Card>, flop: List<Card>, samples: Int = 1500, rng: Rng = SecureRng): Double {
    val known = (player + flop).toSet()
    val rest = ArrayList<Card>(); for (s in 0..3) for (r in 1..13) Card(r, s).let { if (it !in known) rest.add(it) }
    var total = 0.0
    repeat(samples) {
        // partial Fisher–Yates: four random cards = turn, river, dealer's two
        for (i in 0 until 4) { val j = i + rng.next(rest.size - i); val t = rest[i]; rest[i] = rest[j]; rest[j] = t }
        val board = flop + listOf(rest[0], rest[1]); val dealer = listOf(rest[2], rest[3])
        val pv = Poker.best(player + board); val dv = Poker.best(dealer + board)
        total += when {
            !HoldemPays.qualifies(dv) -> HoldemPays.ante(pv.cat).toDouble()
            pv > dv -> HoldemPays.ante(pv.cat) + 2.0
            pv.score == dv.score -> 0.0
            else -> -3.0
        }
    }
    return total / samples
}
fun holdemShouldCall(player: List<Card>, flop: List<Card>, samples: Int = 1500, rng: Rng = SecureRng) = holdemCallValue(player, flop, samples, rng) > -1.0
