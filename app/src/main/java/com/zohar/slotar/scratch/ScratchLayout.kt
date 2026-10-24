package com.zohar.slotar.scratch

import kotlin.math.max
import kotlin.math.min

/** Card face layout (bitmap pixels, 720 × 1028 = the 21 × 30 cm card) and the scratched-area bookkeeping (pure, unit-tested). */
object ScratchLayout {
    const val W = 720
    const val H = 1028
    data class Box(val l: Float, val t: Float, val r: Float, val b: Float) { val cx get() = (l + r) / 2; val cy get() = (t + b) / 2 }
    val GRID_L = 60f; val GRID_T = 262f; val CELL_W = 200f; val CELL_H = 172f; val GAP = 0f
    fun cell(i: Int) = Box(GRID_L + (i % 3) * CELL_W, GRID_T + (i / 3) * CELL_H, GRID_L + (i % 3 + 1) * CELL_W, GRID_T + (i / 3 + 1) * CELL_H)
    fun bonus(i: Int) = Box(56f + i * 132f, 812f, 56f + i * 132f + 120f, 932f)
    val MULT = Box(470f, 812f, 664f, 932f)
    /** 13 scratch regions: 9 cells, 3 bonus spots, 1 multiplier box. */
    val regions: List<Box> = (0 until 9).map { cell(it) } + (0 until 3).map { bonus(it) } + listOf(MULT)
}

class ScratchState(private val step: Int = 8) {
    private val gw = ScratchLayout.W / step; private val gh = ScratchLayout.H / step
    private val done = BooleanArray(gw * gh)
    private val regionCells = ScratchLayout.regions.map { r ->
        val list = ArrayList<Int>()
        for (y in (r.t / step).toInt() until (r.b / step).toInt()) for (x in (r.l / step).toInt() until (r.r / step).toInt()) list.add(y * gw + x)
        list.toIntArray()
    }
    var revealedAll = false; private set

    /** Scratch a stroke from (x0,y0) to (x1,y1) with radius r (bitmap px). */
    fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, r: Float) {
        val len = max(1f, kotlin.math.hypot(x1 - x0, y1 - y0)); val n = (len / (r * 0.5f)).toInt() + 1
        for (k in 0..n) { val t = k / n.toFloat(); dot(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, r) }
    }
    private fun dot(cx: Float, cy: Float, r: Float) {
        val x0 = max(0, ((cx - r) / step).toInt()); val x1 = min(gw - 1, ((cx + r) / step).toInt())
        val y0 = max(0, ((cy - r) / step).toInt()); val y1 = min(gh - 1, ((cy + r) / step).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val dx = (x + 0.5f) * step - cx; val dy = (y + 0.5f) * step - cy
            if (dx * dx + dy * dy <= r * r) done[y * gw + x] = true
        }
    }
    fun coverage(region: Int): Float { val c = regionCells[region]; if (c.isEmpty()) return 1f; return c.count { done[it] } / c.size.toFloat() }
    fun revealed(region: Int) = revealedAll || coverage(region) >= 0.55f
    fun allRevealed() = revealedAll || ScratchLayout.regions.indices.all { revealed(it) }
    fun revealAll() { revealedAll = true }
}
