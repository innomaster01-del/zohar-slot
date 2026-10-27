package com.zohar.slotar.engine

/**
 * DRAGON FORTUNE: 5×3, 243 ways + Hold & Win with four fixed jackpots. PLAY MONEY ONLY. The engine decides everything
 * (stops, coin values, every respin) before anything is shown; the screen only replays it.
 *  - Ways: a symbol (or the DRAGON wild, reels 2–4) on reels 1..n in a row pays pay[n] × (matches per reel multiplied) × bet / 25.
 *  - Hold & Win: 6+ coins → they lock, 3 respins; each empty cell lands a coin with probability [P_RESPIN]; a new coin resets
 *    the respins to 3. Coins pay their value × bet (MINI 20×, MINOR 50×, MAJOR 250× are coin values); all 15 cells = GRAND 1000×.
 * Exact RTP 95.33 % (tools/math/dragon_exact.py; DragonTest checks the same formula and a long simulation).
 */
object Dragon {
    val SYMBOLS = listOf("WILD", "PHOENIX", "TIGER", "KOI", "TURTLE", "A", "K", "Q", "J", "TEN", "COIN")
    val PAYS = mapOf(
        "PHOENIX" to intArrayOf(50, 125, 400), "TIGER" to intArrayOf(40, 100, 250), "KOI" to intArrayOf(25, 75, 200), "TURTLE" to intArrayOf(25, 60, 150),
        "A" to intArrayOf(12, 25, 100), "K" to intArrayOf(12, 25, 80), "Q" to intArrayOf(12, 20, 60), "J" to intArrayOf(8, 20, 50), "TEN" to intArrayOf(8, 15, 50),
    )
    /** Coin values × total bet, with weights. 20 = MINI, 50 = MINOR, 250 = MAJOR. */
    val VALUES = listOf(1 to 300, 2 to 220, 3 to 150, 5 to 120, 8 to 80, 10 to 60, 15 to 40, 20 to 20, 50 to 8, 250 to 2)
    const val MINI = 20; const val MINOR = 50; const val MAJOR = 250; const val GRAND = 1000
    const val P_RESPIN = 0.055
    const val TRIGGER = 6
    val BETS = longArrayOf(25, 50, 125, 250)
    val REELS = listOf(
        "TURTLE TEN A TURTLE Q COIN TEN K KOI A TURTLE Q TIGER K TEN COIN A PHOENIX KOI Q J A TEN J PHOENIX K Q Q K TIGER TIGER K COIN KOI COIN J COIN TURTLE A TEN J COIN J TEN J",
        "TURTLE TURTLE TEN TEN KOI Q TEN TEN TIGER Q J COIN A COIN TURTLE Q A WILD K KOI K WILD J COIN TURTLE J A TIGER Q TEN TIGER PHOENIX K J A K A COIN TEN Q K COIN J KOI COIN PHOENIX J",
        "A Q TIGER A TURTLE TEN COIN K K K Q Q WILD KOI K TIGER PHOENIX J COIN COIN KOI J TEN TURTLE K TEN COIN COIN A TEN COIN Q Q WILD TEN J J A TEN TURTLE J J TIGER PHOENIX KOI A TURTLE",
        "J Q Q K TURTLE TEN J TEN TEN K COIN TIGER COIN WILD A TIGER PHOENIX A TIGER A A WILD K K J KOI KOI COIN KOI J Q A K TURTLE TURTLE Q COIN TEN J TEN TURTLE J COIN COIN Q TEN PHOENIX",
        "K J KOI TIGER K Q J COIN A A J J COIN PHOENIX Q Q TURTLE COIN K Q TIGER TEN K A TURTLE TURTLE KOI J TEN KOI COIN Q TEN TEN PHOENIX TEN A K COIN COIN TEN J A TURTLE TIGER",
    ).map { it.split(" ") }
    fun label(v: Int) = when (v) { MINI -> "MINI"; MINOR -> "MINOR"; MAJOR -> "MAJOR"; else -> null }

    /** Exact return to player (same derivation as tools/math/dragon_exact.py). */
    fun exactRtp(): Map<String, Double> {
        fun window(s: List<String>, i: Int) = (0 until 3).map { s[(i + it) % s.size] }
        var ways = 0.0
        for ((t, p) in PAYS) {
            val em = REELS.map { s -> s.indices.sumOf { i -> window(s, i).count { it == t || it == "WILD" } }.toDouble() / s.size }
            val p0 = REELS.map { s -> s.indices.count { i -> window(s, i).none { it == t || it == "WILD" } }.toDouble() / s.size }
            for (n in 3..5) { var prod = 1.0; for (c in 0 until n) prod *= em[c]; if (n < 5) prod *= p0[n]; ways += p[n - 3] * prod }
        }
        ways /= 25
        var tot = doubleArrayOf(1.0)
        for (s in REELS) {
            val d = DoubleArray(4); for (i in s.indices) d[window(s, i).count { it == "COIN" }] += 1.0 / s.size
            val n = DoubleArray(tot.size + 3); for (a in tot.indices) for (b in 0..3) n[a + b] += tot[a] * d[b]; tot = n
        }
        val memo = HashMap<Int, DoubleArray>()
        fun choose(n: Int, k: Int): Double { var r = 1.0; for (i in 0 until k) r = r * (n - i) / (i + 1); return r }
        fun final(nc: Int, r: Int): DoubleArray {                       // distribution of the final coin count
            val out = DoubleArray(16)
            if (nc == 15 || r == 0) { out[nc] = 1.0; return out }
            memo[nc * 4 + r]?.let { return it }
            val free = 15 - nc
            for (k in 0..free) {
                val pk = choose(free, k) * Math.pow(P_RESPIN, k.toDouble()) * Math.pow(1 - P_RESPIN, (free - k).toDouble())
                val sub = if (k > 0) final(nc + k, 3) else final(nc, r - 1)
                for (f in 0..15) out[f] += pk * sub[f]
            }
            memo[nc * 4 + r] = out; return out
        }
        val w = VALUES.sumOf { it.second }.toDouble(); val ev = VALUES.sumOf { it.first * it.second } / w
        var hw = 0.0; var trig = 0.0
        for (nc in TRIGGER until tot.size) { if (tot[nc] == 0.0) continue; trig += tot[nc]; val f = final(nc, 3); for (k in 0..15) hw += tot[nc] * f[k] * (k * ev + if (k == 15) GRAND else 0) }
        return mapOf("rtp" to ways + hw, "ways" to ways, "holdwin" to hw, "trigger" to trig)
    }
}

data class WayWin(val symbol: String, val reels: Int, val ways: Int, val win: Long, val cells: List<Pair<Int, Int>>)
/** One respin: the cells (row, col) that landed a coin and their values; empty when nothing landed. */
data class Respin(val landed: List<Triple<Int, Int, Int>>, val left: Int)
data class DragonResult(
    val ok: Boolean,
    val bet: Long = 0,
    val stops: IntArray = IntArray(0),
    val grid: List<List<String>> = emptyList(),
    val coins: Map<Int, Int> = emptyMap(),                  // row*5+col → value × bet (base screen)
    val wayWins: List<WayWin> = emptyList(),
    val waysWin: Long = 0,
    val holdWin: Boolean = false,
    val respins: List<Respin> = emptyList(),
    val finalCoins: Map<Int, Int> = emptyMap(),             // after the respins
    val grand: Boolean = false,
    val hwWin: Long = 0,
    val totalWin: Long = 0,
    val credits: Long = 0,
)

class DragonEngine(var credits: Long = 2000, private val rng: Rng = CryptoRng()) {
    var betIndex = 0; private set
    val bet get() = Dragon.BETS[betIndex]
    var lastResult: DragonResult? = null; private set
    fun setBetIndex(i: Int) { betIndex = i.coerceIn(0, Dragon.BETS.size - 1) }
    fun canSpin() = credits >= bet
    fun refill(amount: Long = 2000): Long { if (credits < bet) credits += amount; return credits }

    private fun coinValue(): Int {
        val tot = Dragon.VALUES.sumOf { it.second }; var r = rng.next(tot)
        for ((v, w) in Dragon.VALUES) { if (r < w) return v; r -= w }
        return Dragon.VALUES.last().first
    }

    fun spin(): DragonResult {
        val b = bet
        if (credits < b) return DragonResult(false, credits = credits)
        credits -= b
        val stops = IntArray(5) { rng.next(Dragon.REELS[it].size) }
        val grid = (0 until 3).map { r -> (0 until 5).map { c -> Dragon.REELS[c][(stops[c] + r) % Dragon.REELS[c].size] } }
        val coins = HashMap<Int, Int>()
        for (r in 0 until 3) for (c in 0 until 5) if (grid[r][c] == "COIN") coins[r * 5 + c] = coinValue()
        // ways
        val wins = ArrayList<WayWin>()
        for ((t, p) in Dragon.PAYS) {
            var n = 0; var ways = 1; val cells = ArrayList<Pair<Int, Int>>()
            while (n < 5) {
                val m = (0 until 3).filter { r -> grid[r][n] == t || grid[r][n] == "WILD" }
                if (m.isEmpty()) break
                ways *= m.size; m.forEach { cells.add(it to n) }; n++
            }
            if (n >= 3) wins.add(WayWin(t, n, ways, p[n - 3].toLong() * ways * b / 25, cells))
        }
        val waysWin = wins.sumOf { it.win }
        // hold & win
        var respins = emptyList<Respin>(); val fin = HashMap(coins); var grand = false; var hwWin = 0L
        val hw = coins.size >= Dragon.TRIGGER
        if (hw) {
            val list = ArrayList<Respin>(); var left = 3
            while (left > 0 && fin.size < 15) {
                val landed = ArrayList<Triple<Int, Int, Int>>()
                for (k in 0 until 15) if (k !in fin && rng.next(1_000_000) < (Dragon.P_RESPIN * 1_000_000).toInt()) { val v = coinValue(); fin[k] = v; landed.add(Triple(k / 5, k % 5, v)) }
                left = if (landed.isNotEmpty()) 3 else left - 1
                list.add(Respin(landed, left))
            }
            respins = list; grand = fin.size == 15
            hwWin = fin.values.sumOf { it.toLong() } * b + if (grand) Dragon.GRAND.toLong() * b else 0L
        }
        val total = waysWin + hwWin
        credits += total
        return DragonResult(true, b, stops, grid, coins, wins, waysWin, hw, respins, if (hw) fin else emptyMap(), grand, hwWin, total, credits).also { lastResult = it }
    }
}
