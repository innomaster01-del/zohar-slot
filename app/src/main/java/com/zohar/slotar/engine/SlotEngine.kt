package com.zohar.slotar.engine

import java.security.SecureRandom

/**
 * ZOHAR 5×3 video slot rules (port of video-slot-engine.js, same maths: RTP 95.9 %). PLAY MONEY ONLY.
 * Left-to-right line pays with WILD substitution, SCATTER anywhere (× total bet, triggers free spins ×3).
 * The engine decides every result; the screen and the 3D machine only show it.
 */
data class SlotConfig(
    val rows: Int,
    val wild: String,
    val scatter: String,
    val symbols: List<String>,
    val pays: Map<String, IntArray>,           // × line bet for 3 / 4 / 5 of a kind
    val scatterPays: Map<Int, Int>,             // × total bet
    val fsTrigger: Int, val fsSpins: Int, val fsRetrigger: Int, val fsMultiplier: Int,
    val lines: List<IntArray>,
    val lineBets: IntArray,
    val reels: List<List<String>>,
)

val ZOHAR = SlotConfig(
    rows = 3, wild = "W", scatter = "S",
    symbols = listOf("W", "S", "CROWN", "DIAMOND", "SEVEN", "BELL", "A", "K", "Q", "J", "TEN"),
    pays = mapOf(
        "W" to intArrayOf(100, 500, 2000), "CROWN" to intArrayOf(50, 200, 800), "DIAMOND" to intArrayOf(40, 150, 600),
        "SEVEN" to intArrayOf(30, 100, 400), "BELL" to intArrayOf(20, 60, 250), "A" to intArrayOf(10, 30, 125),
        "K" to intArrayOf(10, 30, 100), "Q" to intArrayOf(9, 20, 80), "J" to intArrayOf(7, 16, 60), "TEN" to intArrayOf(7, 16, 50),
    ),
    scatterPays = mapOf(3 to 5, 4 to 20, 5 to 100),
    fsTrigger = 3, fsSpins = 10, fsRetrigger = 10, fsMultiplier = 3,
    lines = listOf(
        intArrayOf(1, 1, 1, 1, 1), intArrayOf(0, 0, 0, 0, 0), intArrayOf(2, 2, 2, 2, 2), intArrayOf(0, 1, 2, 1, 0), intArrayOf(2, 1, 0, 1, 2),
        intArrayOf(0, 0, 1, 2, 2), intArrayOf(2, 2, 1, 0, 0), intArrayOf(1, 0, 0, 0, 1), intArrayOf(1, 2, 2, 2, 1), intArrayOf(0, 1, 1, 1, 0),
        intArrayOf(2, 1, 1, 1, 2), intArrayOf(1, 0, 1, 2, 1), intArrayOf(1, 2, 1, 0, 1), intArrayOf(0, 1, 0, 1, 0), intArrayOf(2, 1, 2, 1, 2),
        intArrayOf(1, 1, 0, 1, 1), intArrayOf(1, 1, 2, 1, 1), intArrayOf(0, 2, 0, 2, 0), intArrayOf(2, 0, 2, 0, 2), intArrayOf(0, 2, 2, 2, 0),
    ),
    lineBets = intArrayOf(1, 2, 5, 10),
    reels = listOf(
        "Q A TEN SEVEN K J W S A Q TEN BELL K J DIAMOND Q A TEN SEVEN K J BELL CROWN A Q TEN DIAMOND K J BELL",
        "W K J BELL DIAMOND Q A TEN SEVEN K J S W A Q TEN BELL K J DIAMOND TEN A Q SEVEN J K BELL CROWN Q A TEN",
        "BELL CROWN Q A TEN W K J BELL DIAMOND Q A TEN SEVEN K J S W A Q TEN BELL K J DIAMOND TEN A Q SEVEN J K",
        "A DIAMOND BELL K J SEVEN TEN Q A W CROWN K J BELL TEN Q A DIAMOND SEVEN K J S TEN Q A BELL W K J CROWN TEN Q",
        "J BELL TEN Q A CROWN SEVEN J K DIAMOND BELL Q A TEN W J K SEVEN CROWN TEN Q A BELL J K DIAMOND S TEN Q A SEVEN W K",
    ).map { it.split(" ") },
)

/** Uniform integer in [0, n). */
fun interface Rng { fun next(n: Int): Int }

/** Unbiased, cryptographically strong (rejection sampling inside SecureRandom.nextInt). */
class CryptoRng : Rng { private val r = SecureRandom(); override fun next(n: Int) = r.nextInt(n) }

/** Deterministic RNG for tests and replays only. */
class SeededRng(seed: Long) : Rng { private val r = java.util.Random(seed); override fun next(n: Int) = r.nextInt(n) }

data class LineWin(val line: Int, val rows: IntArray, val symbol: String, val count: Int, val win: Long)
data class ScatterResult(val count: Int, val cells: List<Pair<Int, Int>>, val win: Long)
data class Evaluation(val lineWins: List<LineWin>, val scatter: ScatterResult, val baseWin: Long)

data class SpinResult(
    val ok: Boolean,
    val mode: String = "base",           // "base" | "free"
    val multiplier: Int = 1,
    val cost: Long = 0,
    val stops: IntArray = IntArray(0),
    val grid: List<List<String>> = emptyList(),
    val lineWins: List<LineWin> = emptyList(),
    val scatter: ScatterResult = ScatterResult(0, emptyList(), 0),
    val totalWin: Long = 0,
    val triggered: Int = 0,
    val fsStarted: Boolean = false,
    val fsEnded: Boolean = false,
    val fsTotal: Long = 0,
    val freeLeft: Int = 0,
    val totalBet: Long = 0,
    val credits: Long = 0,
)

fun gridFromStops(cfg: SlotConfig, stops: IntArray): List<List<String>> =
    (0 until cfg.rows).map { r -> cfg.reels.mapIndexed { i, strip -> strip[(stops[i] + r) % strip.size] } }

/** Best pay for one line (left to right). Returns (symbol, count, pay) or null. */
fun evalLine(cfg: SlotConfig, syms: List<String>): Triple<String, Int, Int>? {
    var w = 0; while (w < syms.size && syms[w] == cfg.wild) w++
    val wildPay = if (w >= 3) cfg.pays.getValue(cfg.wild)[w - 3] else 0
    var best: Triple<String, Int, Int>? = if (wildPay > 0) Triple(cfg.wild, w, wildPay) else null
    val target = syms.firstOrNull { it != cfg.wild }
    if (target != null && target != cfg.scatter && cfg.pays.containsKey(target)) {
        var n = 0; while (n < syms.size && (syms[n] == target || syms[n] == cfg.wild)) n++
        if (n >= 3) { val p = cfg.pays.getValue(target)[n - 3]; if (best == null || p > best.third) best = Triple(target, n, p) }
    }
    return best
}

fun evaluate(cfg: SlotConfig, grid: List<List<String>>, lineBet: Int): Evaluation {
    val wins = ArrayList<LineWin>()
    cfg.lines.forEachIndexed { li, rows ->
        val b = evalLine(cfg, rows.mapIndexed { c, r -> grid[r][c] })
        if (b != null) wins.add(LineWin(li, rows, b.first, b.second, b.third.toLong() * lineBet))
    }
    val cells = ArrayList<Pair<Int, Int>>()
    grid.forEachIndexed { r, row -> row.forEachIndexed { c, s -> if (s == cfg.scatter) cells.add(r to c) } }
    val totalBet = lineBet.toLong() * cfg.lines.size
    val sc = ScatterResult(cells.size, cells, (cfg.scatterPays[cells.size] ?: 0) * totalBet)
    return Evaluation(wins, sc, wins.sumOf { it.win } + sc.win)
}

fun validateConfig(cfg: SlotConfig): List<String> {
    val errs = ArrayList<String>(); val ids = cfg.symbols.toSet()
    cfg.reels.forEachIndexed { i, strip ->
        strip.forEachIndexed { j, s ->
            if (s !in ids) errs.add("reel $i stop $j: unknown symbol $s")
            if (s == cfg.scatter) for (k in 1 until cfg.rows) if (strip[(j + k) % strip.size] == cfg.scatter) errs.add("reel $i: scatters at $j and ${(j + k) % strip.size} can show together")
        }
    }
    if (cfg.reels.size != 5) errs.add("this engine expects 5 reels")
    cfg.lines.forEachIndexed { i, l -> if (l.size != cfg.reels.size || l.any { it < 0 || it >= cfg.rows }) errs.add("line $i invalid") }
    cfg.pays.forEach { (k, v) -> if (k !in ids || v.size != 3) errs.add("pays.$k invalid") }
    return errs
}

class SlotEngine(val cfg: SlotConfig = ZOHAR, var credits: Long = 2000, private val rng: Rng = CryptoRng(), betIndex: Int = 0) {
    init { val e = validateConfig(cfg); require(e.isEmpty()) { "Invalid slot config:\n" + e.joinToString("\n") } }
    var betIndex = betIndex; private set
    var mode = "base"; internal set
    var freeLeft = 0; internal set
    var freeWon = 0L; internal set
    var spins = 0L; private set
    var totalBetPaid = 0L; private set
    var totalWon = 0L; private set
    var lastResult: SpinResult? = null; private set

    val lineBet get() = cfg.lineBets[betIndex]
    val totalBetAmount: Long get() = lineBet.toLong() * cfg.lines.size
    val inFreeSpins get() = mode == "free"

    fun setBetIndex(i: Int): Int { if (mode == "base") betIndex = i.coerceIn(0, cfg.lineBets.size - 1); return betIndex }
    fun canSpin() = mode == "free" || credits >= totalBetAmount

    fun spin(): SpinResult {
        val free = mode == "free"
        val cost = if (free) 0L else totalBetAmount
        if (!free && credits < cost) return SpinResult(ok = false, credits = credits)
        credits -= cost
        val stops = IntArray(cfg.reels.size) { rng.next(cfg.reels[it].size) }
        val grid = gridFromStops(cfg, stops)
        val ev = evaluate(cfg, grid, lineBet)
        val mult = if (free) cfg.fsMultiplier else 1
        val totalWin = ev.baseWin * mult
        credits += totalWin
        var triggered = 0; var fsStarted = false; var fsEnded = false
        if (free) { freeLeft--; freeWon += totalWin }
        if (ev.scatter.count >= cfg.fsTrigger) {
            triggered = if (free) cfg.fsRetrigger else cfg.fsSpins
            if (!free) { mode = "free"; freeWon = 0; fsStarted = true }
            freeLeft += triggered
        }
        val fsTotal = freeWon
        if (free && freeLeft <= 0) { mode = "base"; freeLeft = 0; fsEnded = true }
        spins++; totalBetPaid += cost; totalWon += totalWin
        val r = SpinResult(true, if (free) "free" else "base", mult, cost, stops, grid,
            ev.lineWins.map { it.copy(win = it.win * mult) }, ev.scatter.copy(win = ev.scatter.win * mult),
            totalWin, triggered, fsStarted, fsEnded, fsTotal, freeLeft, totalBetAmount, credits)
        lastResult = r
        return r
    }

    /** Free play money when broke in the base game. */
    fun refill(amount: Long = 2000): Long { if (mode == "base" && credits < totalBetAmount) credits += amount; return credits }
    fun observedRtp() = if (totalBetPaid > 0) totalWon.toDouble() / totalBetPaid else 0.0
}
