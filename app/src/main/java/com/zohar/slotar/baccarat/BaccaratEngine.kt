package com.zohar.slotar.baccarat

import com.zohar.slotar.blackjack.Card
import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Wallet

/**
 * Baccarat (Punto Banco), play money. THE ENGINE DECIDES, THE TABLE ONLY SHOWS: the whole coup is dealt here first.
 * 8 decks, cut card at ~ 14 cards from the end (reshuffle between coups). Card values: A = 1, 2–9 face value, 10/J/Q/K = 0;
 * a hand is worth its total mod 10. Naturals (8 or 9 on two cards) stop the coup. Player draws on 0–5, stands on 6–7.
 * Banker: stands on 7; if the player stood, draws on 0–5; otherwise by the standard table on the player's third card.
 * Pays: Player 1:1 · Banker 1:1 less 5 % commission · Tie 8:1 (Player/Banker bets push on a tie) · Player/Banker Pair 11:1.
 */
fun bacValue(c: Card) = if (c.rank >= 10) 0 else c.rank
fun bacTotal(cs: List<Card>) = cs.sumOf { bacValue(it) } % 10

/** Does the banker draw? b = banker's two-card total, p3 = value of the player's third card (null if the player stood). */
fun bankerDraws(b: Int, p3: Int?): Boolean = when {
    p3 == null -> b <= 5
    b <= 2 -> true
    b == 3 -> p3 != 8
    b == 4 -> p3 in 2..7
    b == 5 -> p3 in 4..7
    b == 6 -> p3 == 6 || p3 == 7
    else -> false
}

enum class BacBet(val label: String) { PLAYER("Player"), BANKER("Banker"), TIE("Tie"), PPAIR("Player Pair"), BPAIR("Banker Pair") }
enum class BacWinner { PLAYER, BANKER, TIE }
data class BacResult(val winner: BacWinner, val player: List<Card>, val banker: List<Card>, val pTotal: Int, val bTotal: Int,
                     val playerPair: Boolean, val bankerPair: Boolean, val stakes: Map<BacBet, Long>, val returns: Map<BacBet, Long>) {
    val staked get() = stakes.values.sum(); val returned get() = returns.values.sum()
    val natural get() = player.size == 2 && banker.size == 2 && (pTotal >= 8 || bTotal >= 8)
}

class BaccaratEngine(private val wallet: Wallet, private val rng: Rng = SecureRng, val decks: Int = 8) {
    val maxMain = 500L; val maxSide = 100L; val minBet = 5L
    private var shoe = ArrayList<Card>(); private var cut = 0
    var reshuffled = false; private set
    var result: BacResult? = null; private set
    /** For tests: the next cards to come out (top first). */
    var stack: ArrayDeque<Card>? = null
    init { newShoe() }
    private fun newShoe() {
        shoe = ArrayList(); repeat(decks) { for (s in 0..3) for (r in 1..13) shoe.add(Card(r, s)) }
        for (i in shoe.size - 1 downTo 1) { val j = rng.next(i + 1); val t = shoe[i]; shoe[i] = shoe[j]; shoe[j] = t }
        cut = 14; reshuffled = true
    }
    private fun draw(): Card { stack?.removeFirstOrNull()?.let { return it }; return shoe.removeAt(shoe.size - 1) }

    fun validate(stakes: Map<BacBet, Long>): String? {
        val main = (stakes[BacBet.PLAYER] ?: 0) + (stakes[BacBet.BANKER] ?: 0) + (stakes[BacBet.TIE] ?: 0)
        if (stakes.values.any { it < 0 }) return "negative bet"
        if (main < minBet) return "Place at least $minBet on Player, Banker or Tie."
        if ((stakes[BacBet.PLAYER] ?: 0) > maxMain || (stakes[BacBet.BANKER] ?: 0) > maxMain) return "Table maximum is $maxMain."
        if ((stakes[BacBet.TIE] ?: 0) > maxSide || (stakes[BacBet.PPAIR] ?: 0) > maxSide || (stakes[BacBet.BPAIR] ?: 0) > maxSide) return "Tie and pairs go up to $maxSide."
        if (stakes.values.sum() > wallet.credits) return "Not enough play credits."
        return null
    }

    fun deal(stakes: Map<BacBet, Long>): BacResult {
        validate(stakes)?.let { throw IllegalArgumentException(it) }
        reshuffled = false
        if (stack == null && shoe.size <= cut) newShoe()
        wallet.credits -= stakes.values.sum()
        val p = mutableListOf(draw()); val b = mutableListOf(draw()); p.add(draw()); b.add(draw())
        val p2 = bacTotal(p); val b2 = bacTotal(b)
        if (p2 < 8 && b2 < 8) {
            var p3: Int? = null
            if (p2 <= 5) { val c = draw(); p.add(c); p3 = bacValue(c) }
            if (bankerDraws(b2, p3)) b.add(draw())
        }
        val pt = bacTotal(p); val bt = bacTotal(b)
        val winner = when { pt > bt -> BacWinner.PLAYER; bt > pt -> BacWinner.BANKER; else -> BacWinner.TIE }
        val pPair = p[0].rank == p[1].rank; val bPair = b[0].rank == b[1].rank
        val ret = HashMap<BacBet, Long>()
        for ((bet, s) in stakes) if (s > 0) ret[bet] = when (bet) {
            BacBet.PLAYER -> when (winner) { BacWinner.PLAYER -> s * 2; BacWinner.TIE -> s; else -> 0 }
            BacBet.BANKER -> when (winner) { BacWinner.BANKER -> s + (s * 95 + 50) / 100; BacWinner.TIE -> s; else -> 0 }   // 5 % commission, rounded to the nearest credit
            BacBet.TIE -> if (winner == BacWinner.TIE) s * 9 else 0
            BacBet.PPAIR -> if (pPair) s * 12 else 0
            BacBet.BPAIR -> if (bPair) s * 12 else 0
        }
        wallet.credits += ret.values.sum()
        return BacResult(winner, p, b, pt, bt, pPair, bPair, stakes.filterValues { it > 0 }, ret).also { result = it }
    }
}
