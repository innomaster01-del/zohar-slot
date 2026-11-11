package com.zohar.slotar.plinko

/**
 * Where the pegs and pockets are, and the ball's way over them. Plain numbers, no 3D library, so the unit test can walk
 * every one of the 4096 ways down and check the ball meets a real peg at each row and ends over the engine's pocket.
 *
 * Row r (0 = top) has r + 3 pegs, dx apart, centred; rows are dy apart. The ball falls onto the middle peg of the top
 * row, and at each row goes half a step left or right, which puts it right above a peg of the next row.
 */
class PlinkoGeo(val rows: Int, val dx: Float, val dy: Float, val topY: Float, val pegR: Float, val ballR: Float, val dropY: Float, val cupY: Float) {
    fun pegX(row: Int, i: Int) = (i - (row + 2) / 2f) * dx
    fun pegY(row: Int) = topY - row * dy
    fun pocketX(k: Int) = (k - rows / 2f) * dx
    /** Which peg of row r the ball meets, after `rights` turns to the right so far. */
    fun pegIndex(rights: Int) = 1 + rights

    /** One leg of the way: from (x0,y0) to (x1,y1) in `dur` s. kind 0 = a fall (speeding up), 1 = a hop off a peg (up a little, then down). */
    class Leg(val t0: Double, val dur: Double, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val kind: Int)

    /** The legs of a ball that goes `path`; leg r + 1 starts on the peg of row r (that is when the peg sounds). */
    fun legs(path: List<Boolean>, hop: Double = 0.19): List<Leg> {
        val out = ArrayList<Leg>(); var t = 0.0
        val touch = pegR + ballR
        var x = pegX(0, 1); var y = pegY(0) + touch
        out.add(Leg(t, 0.22, x, dropY, x, y, 0)); t += 0.22
        var rights = 0
        for (r in 0 until rows) {
            val nx = x + (if (path[r]) dx / 2 else -dx / 2); if (path[r]) rights++
            val ny = if (r < rows - 1) pegY(r + 1) + touch else cupY
            val d = if (r < rows - 1) hop else hop * 1.25
            out.add(Leg(t, d, x, y, nx, ny, 1)); t += d
            x = nx; y = ny
        }
        return out
    }
    fun duration(legs: List<Leg>) = legs.last().let { it.t0 + it.dur }
    /** Ball centre (x, y) at time t. */
    fun position(legs: List<Leg>, t: Double): FloatArray {
        val l = legs.lastOrNull { t >= it.t0 } ?: legs.first()
        val u = ((t - l.t0) / l.dur).toFloat().coerceIn(0f, 1f)
        return if (l.kind == 0) floatArrayOf(l.x0, l.y0 + (l.y1 - l.y0) * u * u)
        else {
            // off the peg: sideways at an even pace, up by a fifth of a row and down again
            val up = dy * 0.22f
            floatArrayOf(l.x0 + (l.x1 - l.x0) * u, l.y0 + (l.y1 - l.y0) * u * u + up * 4f * u * (1f - u) * (1f - u))
        }
    }
}
