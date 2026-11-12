package com.zohar.slotar.crash

import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Wallet
import kotlin.math.exp
import kotlin.math.ln

/**
 * SKY RUSH, a crash game, play money. THE ENGINE DECIDES, THE SKY ONLY SHOWS: the multiplier the jet will crash at is
 * drawn when it takes off, before anything moves. The multiplier then climbs with time, m(t) = e^(K·t), from 1.00×; the
 * player may cash out each bet at the multiplier shown, or set it to cash out by itself at a target.
 *
 * The draw: one whole number r in [0, 10⁹), crash = ⌊97·10⁹ / (10⁹ − r)⌋ hundredths, never below 1.00× and never above
 * the top of 1,000×. So the chance the jet reaches m (in hundredths) is exactly 0.97·100 / m: it reaches 2.00× in 48.5 %
 * of rounds, 10× in 9.7 %, and in about 4 % of rounds (1 − 0.97/1.01) it crashes at once, at 1.00×. A bet cashed at m (when the jet reaches m) pays stake × m, so
 * every target returns 97 % over time, however the player plays; the only loss beyond that is rounding a prize down to a
 * whole credit (well under one credit).
 */
object Crash {
    /** How fast the multiplier climbs: e^(K·t). 2× after 6.3 s, 10× after 21 s, 100× after 42 s. */
    const val K = 0.11
    const val MAX = 100_000L                  // 1,000.00×, in hundredths
    const val SCALE = 1_000_000_000L
    /** The crash point (hundredths) for a draw r in [0, 10⁹). */
    fun crashOf(r: Long): Long { require(r in 0 until SCALE); return (97L * SCALE / (SCALE - r)).coerceIn(100L, MAX) }
    /** The multiplier, in hundredths (rounded down), t seconds after take-off. */
    fun at(t: Double): Long = if (t <= 0) 100L else minOf(MAX, kotlin.math.floor(100.0 * exp(K * t) + 1e-9).toLong())
    /** How long after take-off the multiplier reaches m hundredths. */
    fun timeOf(m: Long): Double = ln(m / 100.0) / K
    /** The chance a round reaches m hundredths (m ≥ 100). */
    fun chance(m: Long): Double = if (m <= 100) 1.0 else minOf(1.0, 97.0 / m)
    fun label(m: Long) = "${m / 100}.${(m % 100).toString().padStart(2, '0')}×"
}

enum class CrashPhase { BETTING, FLYING, CRASHED }

/** One of the (up to) two bets in a round. `auto`: cash out by itself at this multiplier (hundredths), or null. */
class CrashBet(val stake: Long, val auto: Long?) {
    /** The multiplier (hundredths) it was cashed out at, or null while it rides (or if it was lost). */
    var cashedAt: Long? = null; internal set
    var returned = 0L; internal set
}

data class CrashRound(val crash: Long, val bets: List<CrashBet?>) {
    val staked get() = bets.sumOf { it?.stake ?: 0L }
    val returned get() = bets.sumOf { it?.returned ?: 0L }
}

class CrashEngine(private val wallet: Wallet, private val rng: Rng = SecureRng, private val clock: () -> Double = { System.nanoTime() / 1e9 }) {
    val minBet = 5L; val maxBet = 500L
    var phase = CrashPhase.BETTING; private set
    /** This round's crash point (hundredths). Known to the engine from take-off; the screen shows it only when it happens. */
    var crash = 0L; private set
    var bets: List<CrashBet?> = listOf(null, null); private set
    var last: CrashRound? = null; private set
    private var start = 0.0
    /** For tests: the next draws r. */
    var stack: ArrayDeque<Long>? = null

    fun validate(stake: Long): String? = when {
        stake < minBet -> "The minimum bet is $minBet."
        stake > maxBet -> "The maximum bet is $maxBet."
        stake % 5 != 0L -> "Bets go in steps of 5."
        else -> null
    }

    /**
     * Take the stakes (null = that bet is not played) and take off. The crash point is drawn here. `roll`: seconds the jet
     * rolls down the runway first; the multiplier starts climbing from 1.00× only when it leaves the ground.
     */
    fun takeOff(b1: CrashBet?, b2: CrashBet?, roll: Double = 0.0) {
        check(phase != CrashPhase.FLYING) { "already flying" }
        require(b1 != null || b2 != null) { "Place a bet." }
        for (b in listOfNotNull(b1, b2)) { validate(b.stake)?.let { throw IllegalArgumentException(it) }; require(b.auto == null || b.auto in 101..Crash.MAX) { "auto cash-out from 1.01×" } }
        val total = (b1?.stake ?: 0L) + (b2?.stake ?: 0L)
        require(total <= wallet.credits) { "Not enough play credits." }
        wallet.credits -= total
        val r = stack?.removeFirstOrNull() ?: rng.next(Crash.SCALE.toInt()).toLong()        // uniform, no bias (the generators reject the overflow)
        crash = Crash.crashOf(r)
        bets = listOf(b1, b2); start = clock() + roll; phase = CrashPhase.FLYING
    }

    val elapsed get() = if (phase == CrashPhase.FLYING) clock() - start else 0.0
    /** The multiplier now (hundredths), never past the crash point. */
    fun current(): Long = minOf(Crash.at(elapsed), crash)

    /**
     * Move the round on to now: pays the bets whose own target has been reached, and ends the round once the multiplier
     * has gone past the crash point. Call it every frame; returns true when the round has just crashed.
     */
    fun tick(): Boolean {
        if (phase != CrashPhase.FLYING || elapsed < 0) return false        // still on the runway
        val m = Crash.at(elapsed)
        for (b in bets) if (b != null && b.cashedAt == null && b.auto != null && b.auto <= minOf(m, crash)) pay(b, b.auto)
        if (m > crash || crash == 100L || m >= Crash.MAX) {
            // the top prize: whatever still rides is cashed at 1,000×
            if (crash >= Crash.MAX) for (b in bets) if (b != null && b.cashedAt == null) pay(b, Crash.MAX)
            phase = CrashPhase.CRASHED; last = CrashRound(crash, bets); return true
        }
        return false
    }

    /** Cash bet i (0 or 1) at the multiplier now. False if it is too late (the jet has crashed) or there is nothing to cash. */
    fun cashOut(i: Int): Boolean {
        if (tick() || phase != CrashPhase.FLYING || elapsed < 0) return false
        val b = bets.getOrNull(i) ?: return false
        if (b.cashedAt != null) return false
        pay(b, current()); return true
    }
    private fun pay(b: CrashBet, m: Long) { b.cashedAt = m; b.returned = b.stake * m / 100; wallet.credits += b.returned }

    /** Back to betting (after the crash has been shown). */
    fun next() { if (phase == CrashPhase.CRASHED) phase = CrashPhase.BETTING }

    /**
     * The table could not go on showing the round (a fault, not a choice of the player): every bet still riding is
     * cashed at the multiplier reached so far, so nothing is taken from him by the fault.
     */
    fun abandon() {
        if (phase != CrashPhase.FLYING) return
        val m = current(); for (b in bets) if (b != null && b.cashedAt == null) pay(b, m)
        phase = CrashPhase.BETTING; last = CrashRound(crash, bets)
    }
}
