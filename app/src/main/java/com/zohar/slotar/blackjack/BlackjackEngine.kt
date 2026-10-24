package com.zohar.slotar.blackjack

import java.security.SecureRandom

/**
 * Casino blackjack, play money. THE ENGINE DECIDES, THE TABLE ONLY SHOWS: every card comes out of a shuffled shoe here
 * before any animation. Rules (shown on the felt and in the odds panel): 6 decks, dealer stands on all 17s, blackjack
 * pays 3:2, dealer peeks with an ace or ten up, double on any two cards, double after split, split to 4 hands, split aces
 * get one card each, insurance pays 2:1 (offered with an ace up), late surrender. Reshuffle at 75 % only between rounds.
 */
data class Card(val rank: Int, val suit: Int) {         // rank 1 = ace … 13 = king; suit 0 ♠ 1 ♥ 2 ♦ 3 ♣
    val value get() = if (rank >= 10) 10 else rank
    val isAce get() = rank == 1
    val id get() = RANKS[rank - 1] + SUITS[suit]       // "AS", "10H", "KD" … = asset name
    override fun toString() = RANK_LABELS[rank - 1] + "♠♥♦♣"[suit]
    companion object {
        val RANKS = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
        val RANK_LABELS = RANKS
        val SUITS = listOf("S", "H", "D", "C")
        fun parse(s: String): Card { val r = RANKS.indexOf(s.dropLast(1)) + 1; val su = SUITS.indexOf(s.takeLast(1)); require(r > 0 && su >= 0) { s }; return Card(r, su) }
    }
}

data class Total(val total: Int, val soft: Boolean)
fun total(cards: List<Card>): Total {
    var t = 0; var aces = 0
    for (c in cards) { t += c.value; if (c.isAce) aces++ }
    val soft = aces > 0 && t + 10 <= 21
    return Total(if (soft) t + 10 else t, soft)
}
fun isBlackjack(cards: List<Card>) = cards.size == 2 && total(cards).total == 21

data class Rules(
    val decks: Int = 6, val dealerHitsSoft17: Boolean = false, val bjNum: Int = 3, val bjDen: Int = 2,
    val doubleAfterSplit: Boolean = true, val maxHands: Int = 4, val resplitAces: Boolean = false, val hitSplitAces: Boolean = false,
    val lateSurrender: Boolean = true, val insurance: Boolean = true, val dealerPeeks: Boolean = true, val penetration: Double = 0.75,
    val minBet: Long = 5, val maxBet: Long = 500,
)

interface Wallet { var credits: Long }

class Hand(val cards: MutableList<Card>, var bet: Long) {
    var done = false; var doubled = false; var split = false; var splitAces = false; var surrendered = false
    val total get() = total(cards)
}

enum class Phase { IDLE, INSURANCE, PLAYER, DONE }
enum class Action { HIT, STAND, DOUBLE, SPLIT, SURRENDER }
enum class Outcome { BLACKJACK, WIN, PUSH, LOSE, BUST, SURRENDER }
data class HandResult(val outcome: Outcome, val bet: Long, val returned: Long, val total: Int)
data class RoundResult(val hands: List<HandResult>, val dealerTotal: Int, val dealerBlackjack: Boolean, val insuranceBet: Long, val insuranceReturned: Long, val returned: Long)

/** rng(n) → uniform in [0, n). */
fun interface Rng { fun next(n: Int): Int }
object SecureRng : Rng { private val r = SecureRandom(); override fun next(n: Int) = r.nextInt(n) }
/** Deterministic sfc32 (same as the JS engine's seededRng) — tests and replays only. */
class SeededRng(seed: Int) : Rng {
    private var a = 0x9e3779b9.toInt(); private var b = 0x243f6a88; private var c = 0xb7e15162.toInt(); private var d = seed
    private fun step(): Int { var t = a + b; a = b xor (b ushr 9); b = c + (c shl 3); c = (c shl 21) or (c ushr 11); d += 1; t += d; c += t; return t }
    init { repeat(15) { step() } }
    override fun next(n: Int): Int { val limit = (4294967296L / n) * n; var x: Long; do { x = step().toLong() and 0xffffffffL } while (x >= limit); return (x % n).toInt() }
}

class BlackjackEngine(private val wallet: Wallet, private val rng: Rng = SecureRng, val rules: Rules = Rules()) {
    var phase = Phase.IDLE; private set
    private var shoe = ArrayList<Card>(); private var cut = 0; var dealtFromShoe = 0; private set
    var reshuffled = false; private set
    val dealer = ArrayList<Card>()
    val hands = ArrayList<Hand>()
    var active = 0; private set
    var insuranceBet = 0L; private set
    var result: RoundResult? = null; private set
    /** For tests: the next cards to come out (top first). */
    var stack: ArrayDeque<Card>? = null

    init { newShoe() }
    val cardsLeft get() = shoe.size
    private fun newShoe() {
        shoe = ArrayList(); for (d in 0 until rules.decks) for (s in 0..3) for (r in 1..13) shoe.add(Card(r, s))
        for (i in shoe.size - 1 downTo 1) { val j = rng.next(i + 1); val t = shoe[i]; shoe[i] = shoe[j]; shoe[j] = t }   // Fisher–Yates
        cut = (shoe.size * rules.penetration).toInt(); dealtFromShoe = 0; reshuffled = true
    }
    private fun draw(): Card { stack?.removeFirstOrNull()?.let { return it }; dealtFromShoe++; return shoe.removeAt(shoe.size - 1) }

    val hand get() = hands[active]
    val upCard get() = dealer.firstOrNull()
    private val peekable get() = rules.dealerPeeks && upCard != null && (upCard!!.isAce || upCard!!.value == 10)

    fun deal(bet: Long) {
        check(phase == Phase.IDLE || phase == Phase.DONE) { "round in progress" }
        require(bet in rules.minBet..rules.maxBet) { "bet must be ${rules.minBet}–${rules.maxBet}" }
        require(bet <= wallet.credits) { "not enough play credits" }
        reshuffled = false
        if (dealtFromShoe >= cut) newShoe()
        wallet.credits -= bet
        dealer.clear(); hands.clear(); active = 0; insuranceBet = 0; result = null
        val p = mutableListOf(draw()); dealer.add(draw()); p.add(draw()); dealer.add(draw())   // player, dealer, player, dealer (hole)
        hands.add(Hand(p, bet))
        phase = if (rules.insurance && dealer[0].isAce) Phase.INSURANCE else Phase.PLAYER
        if (phase == Phase.PLAYER) afterPeek()
    }

    /** Insurance costs half the bet (or "even money" when the player has blackjack — same thing, paid 1:1 for sure). */
    fun insuranceCost() = hands[0].bet / 2
    fun canInsure() = phase == Phase.INSURANCE && wallet.credits >= insuranceCost() && insuranceCost() > 0
    fun insurance(take: Boolean) {
        check(phase == Phase.INSURANCE)
        if (take) { check(canInsure()); insuranceBet = insuranceCost(); wallet.credits -= insuranceBet }
        phase = Phase.PLAYER
        afterPeek()
    }
    private fun afterPeek() {
        val pBJ = isBlackjack(hands[0].cards); val dBJ = isBlackjack(dealer)
        if (pBJ || (dBJ && peekable)) finish()
    }

    fun actions(): List<Action> {
        if (phase != Phase.PLAYER) return emptyList()
        val h = hand; val t = h.total.total; val two = h.cards.size == 2
        if (h.splitAces && !rules.hitSplitAces) return listOf(Action.STAND)
        if (t >= 21) return listOf(Action.STAND)
        val a = mutableListOf(Action.HIT, Action.STAND)
        val afford = wallet.credits >= h.bet
        if (two && afford && (!h.split || rules.doubleAfterSplit)) a.add(Action.DOUBLE)
        if (two && afford && h.cards[0].value == h.cards[1].value && hands.size < rules.maxHands && (!h.splitAces || rules.resplitAces)) a.add(Action.SPLIT)
        if (two && rules.lateSurrender && hands.size == 1 && !h.split) a.add(Action.SURRENDER)
        return a
    }

    fun act(action: Action) {
        require(action in actions()) { "illegal action: $action" }
        val h = hand
        when (action) {
            Action.HIT -> { h.cards.add(draw()); if (h.total.total >= 21) h.done = true }
            Action.STAND -> h.done = true
            Action.DOUBLE -> { wallet.credits -= h.bet; h.bet *= 2; h.doubled = true; h.cards.add(draw()); h.done = true }
            Action.SURRENDER -> { h.surrendered = true; h.done = true }
            Action.SPLIT -> {
                wallet.credits -= h.bet
                val aces = h.cards[0].isAce
                val h2 = Hand(mutableListOf(h.cards.removeAt(1)), h.bet).apply { split = true; splitAces = aces }
                h.split = true; h.splitAces = aces
                h.cards.add(draw()); h2.cards.add(draw())
                hands.add(active + 1, h2)
                for (x in listOf(h, h2)) if ((x.splitAces && !rules.hitSplitAces && !(rules.resplitAces && x.cards[1].isAce)) || x.total.total == 21) x.done = true
            }
        }
        while (active < hands.size && hands[active].done) active++
        if (active >= hands.size) { active = hands.size - 1; finish() }
    }

    private fun finish() {
        val dBJ = isBlackjack(dealer)
        val single = hands.size == 1
        val live = hands.any { !it.surrendered && it.total.total <= 21 } && !(single && isBlackjack(hands[0].cards) && !hands[0].split)
        if (live && !dBJ) {
            while (true) { val t = total(dealer); if (t.total < 17 || (t.total == 17 && t.soft && rules.dealerHitsSoft17)) dealer.add(draw()) else break }
        }
        val dt = total(dealer).total
        var paid = 0L
        val res = hands.map { h ->
            val pt = h.total.total; val natural = isBlackjack(h.cards) && !h.split && single
            val (o, ret) = when {
                h.surrendered -> Outcome.SURRENDER to h.bet / 2
                natural && dBJ -> Outcome.PUSH to h.bet
                natural -> Outcome.BLACKJACK to h.bet + h.bet * rules.bjNum / rules.bjDen
                dBJ -> Outcome.LOSE to 0L
                pt > 21 -> Outcome.BUST to 0L
                dt > 21 || pt > dt -> Outcome.WIN to h.bet * 2
                pt == dt -> Outcome.PUSH to h.bet
                else -> Outcome.LOSE to 0L
            }
            paid += ret; HandResult(o, h.bet, ret, pt)
        }
        val insRet = if (insuranceBet > 0 && dBJ) insuranceBet * 3 else 0L
        wallet.credits += paid + insRet
        phase = Phase.DONE
        result = RoundResult(res, dt, dBJ, insuranceBet, insRet, paid + insRet)
    }

    /** Hole card hidden while the player still acts. */
    val holeHidden get() = phase == Phase.PLAYER || phase == Phase.INSURANCE
}

/** Basic strategy, 4–8 decks, S17, DAS, late surrender (for the Hint button and the house-edge test). */
fun basicStrategy(cards: List<Card>, up: Card, legal: List<Action>, rules: Rules = Rules()): Action {
    val u = if (up.isAce) 11 else up.value; val t = total(cards); fun can(a: Action) = a in legal
    fun dbl(orElse: Action) = if (can(Action.DOUBLE)) Action.DOUBLE else orElse
    if (can(Action.SURRENDER) && !t.soft && cards.size == 2 && !(cards[0].value == 8 && cards[1].value == 8)) {
        if (t.total == 16 && u >= 9) return Action.SURRENDER
        if (t.total == 15 && u == 10) return Action.SURRENDER
    }
    if (can(Action.SPLIT) && cards.size == 2 && cards[0].value == cards[1].value) {
        val v = cards[0].value
        val split = v == 1 || v == 8 || ((v == 2 || v == 3 || v == 7) && u <= 7) || (v == 6 && u <= 6) || (v == 4 && (u == 5 || u == 6)) || (v == 9 && u != 7 && u <= 9)
        if (split) return Action.SPLIT
    }
    if (t.soft) {
        return when {
            t.total <= 12 -> Action.HIT
            t.total >= 20 -> Action.STAND
            t.total == 19 -> if (rules.dealerHitsSoft17 && u == 6) dbl(Action.STAND) else Action.STAND
            t.total == 18 -> if (u in 3..6) dbl(Action.STAND) else if (u <= 8) Action.STAND else Action.HIT
            t.total == 17 -> if (u in 3..6) dbl(Action.HIT) else Action.HIT
            t.total >= 15 -> if (u in 4..6) dbl(Action.HIT) else Action.HIT
            else -> if (u in 5..6) dbl(Action.HIT) else Action.HIT
        }
    }
    return when {
        t.total >= 17 -> Action.STAND
        t.total >= 13 -> if (u <= 6) Action.STAND else Action.HIT
        t.total == 12 -> if (u in 4..6) Action.STAND else Action.HIT
        t.total == 11 -> if (u <= 10 || rules.dealerHitsSoft17) dbl(Action.HIT) else Action.HIT
        t.total == 10 -> if (u <= 9) dbl(Action.HIT) else Action.HIT
        t.total == 9 -> if (u in 3..6) dbl(Action.HIT) else Action.HIT
        else -> Action.HIT
    }
}
