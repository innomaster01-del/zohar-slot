package com.zohar.slotar.roulette

import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Wallet

/**
 * European roulette (single zero), play money. THE ENGINE DECIDES, THE WHEEL ONLY SHOWS: the number is drawn here,
 * uniformly from 0–36 with the unbiased RNG, before the ball is launched; the animation then lands the ball on it.
 * Every bet covers n numbers and returns 36 / n × the stake when it wins (pays 36/n − 1 to 1), so every bet returns
 * exactly 36/37 = 97.30 % — house edge 2.70 %, the same for all bets.
 */
object Wheel {
    /** Pocket order on a European wheel, clockwise seen from above, starting at zero. */
    val ORDER = intArrayOf(0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26)
    private val RED = setOf(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36)
    fun isRed(n: Int) = n in RED
    fun color(n: Int) = when { n == 0 -> "green"; isRed(n) -> "red"; else -> "black" }
    fun pocketIndex(n: Int) = ORDER.indexOf(n)
}

/** A spot on the layout: the numbers it covers, its name, and where its chips sit (table frame, metres). */
data class RSpot(val numbers: List<Int>, val name: String, val x: Float, val z: Float) {
    val key get() = name
    val pays get() = 36 / numbers.size - 1
    val inside get() = numbers.size <= 6
}

/**
 * The betting layout seen from the player: zero on the left, numbers in 12 columns of three (top row 3, 6 … 36,
 * bottom row 1, 4 … 34, nearest the player), "2 to 1" column boxes on the right, then the dozens row and the
 * even-money row toward the player. A tap resolves to the bet a dealer would read from a chip placed there:
 * centre of a number = straight, on a line = split, on a crossing = corner, on the bottom edge = street / six line.
 */
class RouletteGrid(val gx0: Float, val gz0: Float, val cw: Float, val ch: Float, val dh: Float, val eh: Float) {
    private val e = 0.24f                      // how near a line (fraction of a cell) counts as "on the line"
    fun num(col: Int, row: Int) = 3 * col + (3 - row)                     // row 0 = top (3, 6 …)
    fun cellX(col: Int) = gx0 + (col + 0.5f) * cw
    fun rowZ(row: Int) = gz0 + (row + 0.5f) * ch

    fun straight(n: Int): RSpot = if (n == 0) RSpot(listOf(0), "0", gx0 - cw / 2, gz0 + 1.5f * ch)
        else ((n - 1) / 3).let { c -> RSpot(listOf(n), "$n", cellX(c), rowZ(3 - ((n - 1) % 3 + 1))) }

    fun resolve(x: Float, z: Float): RSpot? {
        val u = (x - gx0) / cw; val v = (z - gz0) / ch
        // zero box (left of the grid, the full height of the three rows)
        if (u < 0f && u >= -1f && v >= 0f && v < 3f) {
            // a chip on zero's right edge, between zero and a number: split / trio / first four
            return straight(0)
        }
        if (v >= 0f && v < 3f && u >= 0f && u < 12f) {
            val c = u.toInt(); val r = v.toInt(); val fu = u - c; val fv = v - r
            val left = fu < e; val right = fu > 1 - e; val top = fv < e; val bottom = fv > 1 - e
            // bottom edge of the grid: street (3 numbers) or six line (two streets)
            if (r == 2 && bottom) {
                if (right && c < 11) return line(c)
                if (left && c > 0) return line(c - 1)
                if (left && c == 0) return RSpot(listOf(0, 1, 2, 3), "First four 0-1-2-3", gx0, gz0 + 3 * ch)
                return street(c)
            }
            val vNb = when { top && r > 0 -> r - 1; bottom && r < 2 -> r + 1; else -> -1 }          // row across the horizontal line
            val hNb = when { left -> c - 1; right && c < 11 -> c + 1; else -> -2 }                 // column across the vertical line (-1 = zero)
            if (hNb == -1) {
                // the line between zero and the first column
                if (vNb >= 0) { val ns = listOf(0, num(0, r), num(0, vNb)).sorted(); return RSpot(ns, "Trio ${ns.joinToString("-")}", gx0, gz0 + (maxOf(r, vNb)) * ch) }
                return RSpot(listOf(0, num(0, r)), "Split 0-${num(0, r)}", gx0, rowZ(r))
            }
            if (hNb >= 0 && vNb >= 0) {
                val ns = listOf(num(c, r), num(c, vNb), num(hNb, r), num(hNb, vNb)).sorted()
                return RSpot(ns, "Corner ${ns.joinToString("-")}", gx0 + maxOf(c, hNb) * cw, gz0 + maxOf(r, vNb) * ch)
            }
            if (hNb >= 0) { val ns = listOf(num(c, r), num(hNb, r)).sorted(); return RSpot(ns, "Split ${ns.joinToString("-")}", gx0 + maxOf(c, hNb) * cw, rowZ(r)) }
            if (vNb >= 0) { val ns = listOf(num(c, r), num(c, vNb)).sorted(); return RSpot(ns, "Split ${ns.joinToString("-")}", cellX(c), gz0 + maxOf(r, vNb) * ch) }
            return straight(num(c, r))
        }
        // 2 to 1 column boxes
        if (u >= 12f && u < 13f && v >= 0f && v < 3f) return column(v.toInt())
        // dozens
        if (u >= 0f && u < 12f && z >= gz0 + 3 * ch && z < gz0 + 3 * ch + dh) return dozen((u / 4).toInt())
        // even-money row
        if (u >= 0f && u < 12f && z >= gz0 + 3 * ch + dh && z < gz0 + 3 * ch + dh + eh) return even((u / 2).toInt())
        return null
    }
    fun street(c: Int) = RSpot(listOf(3 * c + 1, 3 * c + 2, 3 * c + 3), "Street ${3 * c + 1}-${3 * c + 3}", cellX(c), gz0 + 3 * ch)
    fun line(c: Int) = RSpot((3 * c + 1..3 * c + 6).toList(), "Six line ${3 * c + 1}-${3 * c + 6}", gx0 + (c + 1) * cw, gz0 + 3 * ch)
    fun column(r: Int) = RSpot((1..36).filter { it % 3 == (3 - r) % 3 }, "Column ${3 - r}", gx0 + 12.5f * cw, rowZ(r))
    fun dozen(k: Int) = RSpot((12 * k + 1..12 * k + 12).toList(), listOf("1st 12", "2nd 12", "3rd 12")[k], gx0 + (4 * k + 2) * cw, gz0 + 3 * ch + dh / 2)
    fun even(k: Int): RSpot {
        val z = gz0 + 3 * ch + dh + eh / 2; val x = gx0 + (2 * k + 1) * cw
        return when (k) {
            0 -> RSpot((1..18).toList(), "1-18", x, z); 1 -> RSpot((1..36).filter { it % 2 == 0 }, "Even", x, z)
            2 -> RSpot((1..36).filter { Wheel.isRed(it) }, "Red", x, z); 3 -> RSpot((1..36).filter { !Wheel.isRed(it) }, "Black", x, z)
            4 -> RSpot((1..36).filter { it % 2 == 1 }, "Odd", x, z); else -> RSpot((19..36).toList(), "19-36", x, z)
        }
    }
}

data class RResult(val number: Int, val stakes: Map<RSpot, Long>, val returns: Map<RSpot, Long>) {
    val staked get() = stakes.values.sum(); val returned get() = returns.values.sum()
    val color get() = Wheel.color(number)
}

class RouletteEngine(private val wallet: Wallet, private val rng: Rng = SecureRng) {
    val minBet = 5L; val maxInside = 100L; val maxOutside = 500L; val maxTotal = 2000L
    /** For tests: the next numbers to come out. */
    var stack: ArrayDeque<Int>? = null

    fun validate(stakes: Map<RSpot, Long>): String? {
        if (stakes.values.any { it < 0 }) return "negative bet"
        val total = stakes.values.sum()
        if (total < minBet) return "Place at least $minBet."
        for ((s, a) in stakes) {
            if (s.inside && a > maxInside) return "${s.name}: up to $maxInside."
            if (!s.inside && a > maxOutside) return "${s.name}: up to $maxOutside."
        }
        if (total > maxTotal) return "Table maximum is $maxTotal a spin."
        if (total > wallet.credits) return "Not enough play credits."
        return null
    }

    fun spin(stakes: Map<RSpot, Long>): RResult {
        validate(stakes)?.let { throw IllegalArgumentException(it) }
        val bets = stakes.filterValues { it > 0 }
        wallet.credits -= bets.values.sum()
        val n = stack?.removeFirstOrNull() ?: rng.next(37)
        val ret = bets.mapValues { (s, a) -> if (n in s.numbers) a * 36 / s.numbers.size else 0L }
        wallet.credits += ret.values.sum()
        return RResult(n, bets, ret)
    }
}
