package com.zohar.slotar.craps

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * How the die model is built and how to lay it down (tools/3d/blackjack/craps.js → die): in the model +Y shows 1,
 * −Y 6, +Z 2, −Z 5, +X 3, −X 4. To show a value, the die is turned about one axis so that face looks up.
 * Plain numbers, no 3D library: the unit test turns every face's normal with these and checks it ends up pointing up.
 */
object DiceMath {
    /** The outward normal of the face that carries `value`, in the model. */
    fun normal(value: Int): FloatArray = when (value) {
        1 -> floatArrayOf(0f, 1f, 0f); 6 -> floatArrayOf(0f, -1f, 0f)
        2 -> floatArrayOf(0f, 0f, 1f); 5 -> floatArrayOf(0f, 0f, -1f)
        3 -> floatArrayOf(1f, 0f, 0f); 4 -> floatArrayOf(-1f, 0f, 0f)
        else -> throw IllegalArgumentException("die value $value")
    }
    /** The turn that brings `value` to the top: axis ('x' or 'z') and degrees (right-handed). */
    fun faceUp(value: Int): Pair<Char, Float> = when (value) {
        1 -> 'x' to 0f; 6 -> 'x' to 180f
        2 -> 'x' to -90f; 5 -> 'x' to 90f
        3 -> 'z' to 90f; 4 -> 'z' to -90f
        else -> throw IllegalArgumentException("die value $value")
    }
    /** v turned by `deg` about the x or z axis. */
    fun turn(v: FloatArray, axis: Char, deg: Float): FloatArray {
        val a = deg * PI / 180; val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return if (axis == 'x') floatArrayOf(v[0], v[1] * c - v[2] * s, v[1] * s + v[2] * c) else floatArrayOf(v[0] * c - v[1] * s, v[0] * s + v[1] * c, v[2])
    }

    /** One leg of a die's flight: from a to b in `dur` seconds, rising `apex` above the straight line (0 = a slide). */
    class Leg(val t0: Double, val dur: Double, val a: FloatArray, val b: FloatArray, val apex: Float)
    /**
     * The path of one thrown die, decided before it moves: out of the hand, onto the far wall, back off it and three
     * shorter and shorter hops to where it will lie, then a short slide. `rest` is where it stops (its centre).
     * r() gives numbers in 0..1 (only the look of the throw comes from it, never the faces).
     */
    fun flight(from: FloatArray, wall: FloatArray, rest: FloatArray, r: () -> Float): List<Leg> {
        val y = rest[1]
        fun mix(p: FloatArray, q: FloatArray, u: Float) = floatArrayOf(p[0] + (q[0] - p[0]) * u, y, p[2] + (q[2] - p[2]) * u)
        val l1 = floatArrayOf(wall[0] + (rest[0] - wall[0]) * 0.30f, y, wall[2] + 0.13f + 0.06f * r())
        val l2 = mix(l1, rest, 0.55f); val l3 = mix(l1, rest, 0.86f)
        val legs = ArrayList<Leg>(); var t = 0.0
        fun leg(d: Double, a: FloatArray, b: FloatArray, apex: Float) { legs.add(Leg(t, d, a, b, apex)); t += d }
        leg(0.50 + 0.06 * r(), from, wall, 0.09f + 0.04f * r())
        leg(0.26, wall, l1, 0.02f)
        leg(0.24, l1, l2, 0.035f + 0.01f * r())
        leg(0.17, l2, l3, 0.013f)
        leg(0.24, l3, rest, 0f)
        return legs
    }
    /** Where the die is `t` seconds into its flight (after the last leg: at rest). */
    fun position(legs: List<Leg>, t: Double): FloatArray {
        val l = legs.lastOrNull { t >= it.t0 } ?: legs.first()
        var u = ((t - l.t0) / l.dur).toFloat().coerceIn(0f, 1f)
        if (l.apex == 0f) u = 1f - (1f - u) * (1f - u)                  // the slide slows down
        return floatArrayOf(l.a[0] + (l.b[0] - l.a[0]) * u, l.a[1] + (l.b[1] - l.a[1]) * u + l.apex * 4f * u * (1f - u), l.a[2] + (l.b[2] - l.a[2]) * u)
    }
    /** How much of the tumbling is left at time t: 1 at the start, 0 from the last landing on (the die then lies flat). */
    fun tumble(legs: List<Leg>, t: Double): Float { val end = legs.last().t0; val u = (t / end).toFloat().coerceIn(0f, 1f); return (1f - u) * (1f - u) }
    fun duration(legs: List<Leg>) = legs.last().let { it.t0 + it.dur }
}
