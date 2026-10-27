package com.zohar.slotar

import com.zohar.slotar.engine.*
import org.junit.Assert.*
import org.junit.Test

class PharaohTest {
    private val cfg = PHARAOH

    @Test fun configValid() = assertEquals(emptyList<String>(), validateConfig(cfg))

    /** Exact RTP from the strips (reels independent, so each line sees one symbol per reel with the strip frequencies). */
    @Test fun exactRtp() {
        val syms = cfg.symbols
        val f = cfg.reels.map { s -> syms.associateWith { k -> s.count { it == k }.toDouble() / s.size } }
        var eline = 0.0
        val combo = Array(5) { "" }
        fun rec(c: Int, pr: Double) {
            if (pr == 0.0) return
            if (c == 5) { evalLine(cfg, combo.toList())?.let { eline += pr * it.third }; return }
            for (s in syms) { combo[c] = s; rec(c + 1, pr * f[c].getValue(s)) }
        }
        rec(0, 1.0)
        fun dist(ps: List<Double>): DoubleArray { var d = doubleArrayOf(1.0); for (p in ps) { val n = DoubleArray(d.size + 1); for (k in d.indices) { n[k] += d[k] * (1 - p); n[k + 1] += d[k] * p }; d = n }; return d }
        fun visible(sym: String) = cfg.reels.map { s -> s.indices.count { i -> (0 until 3).any { r -> s[(i + r) % s.size] == sym } }.toDouble() / s.size }
        val nb = dist(visible("BOOK"))
        val escat = (0..5).sumOf { nb[it] * (cfg.scatterPays[it] ?: 0) * 10.0 }
        val q = (3..5).sumOf { nb[it] }
        val eexp = cfg.expandable.sumOf { s -> val d = dist(visible(s)); (3..5).sumOf { k -> d[k] * cfg.pays.getValue(s)[k - 3] * 10.0 } } / cfg.expandable.size
        val perSpin = eline * 10 + escat
        val rtp = (perSpin + q * (10 / (1 - 10 * q)) * (perSpin + eexp)) / 10
        println("Pharaoh exact RTP ${"%.4f".format(rtp * 100)} %, free spins 1 in ${"%.0f".format(1 / q)}")
        assertEquals(0.95214, rtp, 1e-5)
    }

    @Test fun simulationAgrees() {
        val e = SlotEngine(cfg, 1_000_000_000_000L, SeededRng(21)); var paid = 0L; var won = 0L; var exp = 0
        repeat(1_500_000) { val r = e.spin(); paid += r.cost; won += r.totalWin; if (r.expandWin > 0) exp++ }
        val rtp = won.toDouble() / paid
        println("Pharaoh simulated RTP ${"%.3f".format(rtp * 100)} % (expanding wins: $exp)")
        assertTrue("RTP $rtp", rtp in 0.92..0.985); assertTrue(exp > 0)
    }

    @Test fun bookStartsFreeSpinsAndPicksAnExpandingSymbol() {
        val trig = cfg.reels.mapIndexed { i, s -> if (i < 3) s.indexOf("BOOK") else 0 }
        var n = 0
        val e = SlotEngine(cfg, 1000, Rng { m -> if (n < 5) { val v = trig[n]; n++; v } else { n++; (n * 7) % m } })
        val r = e.spin(); assertTrue(r.fsStarted); assertNotNull(r.special); assertTrue(r.special in cfg.expandable); assertEquals(10, e.freeLeft)
    }
}

class DragonTest {
    @Test fun exactRtp() {
        val r = Dragon.exactRtp()
        println("Dragon exact: $r")
        assertEquals(0.95328, r.getValue("rtp"), 1e-4)
        assertEquals(0.00799, r.getValue("trigger"), 1e-4)
    }
    @Test fun simulationAgrees() {
        val e = DragonEngine(1_000_000_000_000L, SeededRng(8)); var paid = 0L; var won = 0L; var hw = 0
        repeat(2_000_000) { val r = e.spin(); paid += r.bet; won += r.totalWin; if (r.holdWin) hw++ }
        val rtp = won.toDouble() / paid
        println("Dragon simulated RTP ${"%.3f".format(rtp * 100)} %, hold & win 1 in ${2_000_000 / hw.coerceAtLeast(1)}")
        assertTrue("RTP $rtp", rtp in 0.91..1.0)
    }
    @Test fun holdAndWinRules() {
        val e = DragonEngine(1_000_000_000L, SeededRng(3))
        var seen = 0
        repeat(200_000) {
            val r = e.spin()
            if (r.holdWin) {
                seen++
                assertTrue(r.coins.size >= 6)
                var left = 3; val have = HashSet(r.coins.keys)
                for (rs in r.respins) {
                    rs.landed.forEach { (row, col, _) -> assertTrue(have.add(row * 5 + col)) }
                    left = if (rs.landed.isNotEmpty()) 3 else left - 1; assertEquals(left, rs.left)
                }
                assertTrue(left == 0 || have.size == 15); assertEquals(have, r.finalCoins.keys)
                assertEquals(r.finalCoins.values.sumOf { it.toLong() } * r.bet + if (r.grand) 1000 * r.bet else 0, r.hwWin)
            }
        }
        assertTrue(seen > 500)
    }
    @Test fun waysPay() {
        val e = DragonEngine(1_000_000L, SeededRng(1)); var n = 0
        repeat(20_000) { val r = e.spin(); for (w in r.wayWins) { n++; assertEquals(Dragon.PAYS.getValue(w.symbol)[w.reels - 3].toLong() * w.ways * r.bet / 25, w.win) } }
        assertTrue(n > 1000)
    }
}
