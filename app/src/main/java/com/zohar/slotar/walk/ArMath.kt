package com.zohar.slotar.walk

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A point in the AR session's world (metres, y up). */
data class V3(val x: Double, val y: Double, val z: Double)

/**
 * Ties the AR world (where the camera really moved, from ARCore tracking) to the map (east/north metres around the route
 * origin). Two unknowns, both estimated continuously and smoothed: the yaw between AR −z and north, and the offset.
 * Compass / GPS fixes nudge them gently; an ARCore Geospatial (Street View VPS) fix pulls them hard because it is precise.
 */
class GeoAlign {
    var yaw = 0.0; private set           // radians: AR angle − true heading
    var ox = 0.0; private set
    var oz = 0.0; private set
    var ready = false; private set
    var headingFixes = 0; private set

    /** AR angle of a horizontal direction (0 = −z, clockwise seen from above, like a compass). */
    fun arAngle(fx: Double, fz: Double) = atan2(fx, -fz)

    /** Camera forward (AR) and its true heading in degrees. [gain] 0..1: how much to trust this reading. */
    fun heading(fx: Double, fz: Double, headingDeg: Double, gain: Double) {
        if (hypot(fx, fz) < 0.2) return                                   // camera looking straight down/up: no horizontal heading
        val m = arAngle(fx, fz) - headingDeg * PI / 180
        if (headingFixes == 0) yaw = m else yaw += wrap(m - yaw) * gain
        yaw = wrap(yaw); headingFixes++
    }
    /** Camera AR position and its map position (east/north from the origin). [gain] 0..1. */
    fun position(cam: V3, at: Enu, gain: Double) {
        val c = cos(yaw); val s = sin(yaw)
        val mx = cam.x - (at.e * c + at.n * s); val mz = cam.z - (at.e * s - at.n * c)
        if (!ready) { ox = mx; oz = mz; ready = headingFixes > 0 } else { ox += (mx - ox) * gain; oz += (mz - oz) * gain }
    }
    fun toAr(p: Enu, y: Double): V3 { val c = cos(yaw); val s = sin(yaw); return V3(ox + p.e * c + p.n * s, y, oz + p.e * s - p.n * c) }
    fun toEnu(p: V3): Enu { val c = cos(yaw); val s = sin(yaw); val x = p.x - ox; val z = p.z - oz; return Enu(x * c + z * s, x * s - z * c) }

    companion object { fun wrap(a: Double): Double { var x = a; while (x > PI) x -= 2 * PI; while (x < -PI) x += 2 * PI; return x } }
}

/** Projects AR world points to the screen with the camera's view and projection matrices (column-major, like ARCore). */
class Projector(private val view: FloatArray, private val proj: FloatArray, val w: Float, val h: Float) {
    /** Camera-space point (x right, y up, −z forward). */
    fun cam(p: V3): DoubleArray {
        val v = view
        return doubleArrayOf(v[0] * p.x + v[4] * p.y + v[8] * p.z + v[12], v[1] * p.x + v[5] * p.y + v[9] * p.z + v[13], v[2] * p.x + v[6] * p.y + v[10] * p.z + v[14])
    }
    fun screenOfCam(c: DoubleArray): FloatArray? {
        val q = proj
        val x = q[0] * c[0] + q[4] * c[1] + q[8] * c[2] + q[12]
        val y = q[1] * c[0] + q[5] * c[1] + q[9] * c[2] + q[13]
        val wq = q[3] * c[0] + q[7] * c[1] + q[11] * c[2] + q[15]
        if (wq <= 1e-6) return null
        return floatArrayOf(((x / wq + 1) / 2 * w).toFloat(), ((1 - y / wq) / 2 * h).toFloat())
    }
    fun screen(p: V3): FloatArray? { val c = cam(p); return if (c[2] > -NEAR) null else screenOfCam(c) }

    /** A ground polygon clipped against the near plane, then projected (null when fully behind the camera). */
    fun polygon(pts: List<V3>): List<FloatArray>? {
        val cs = pts.map { cam(it) }
        val out = ArrayList<DoubleArray>()
        for (i in cs.indices) {
            val a = cs[i]; val b = cs[(i + 1) % cs.size]
            val ina = a[2] <= -NEAR; val inb = b[2] <= -NEAR
            if (ina) out.add(a)
            if (ina != inb) { val t = (-NEAR - a[2]) / (b[2] - a[2]); out.add(doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, -NEAR)) }
        }
        if (out.size < 3) return null
        return out.mapNotNull { screenOfCam(it) }.takeIf { it.size >= 3 }
    }
    companion object { const val NEAR = 0.25 }
}

/** The floor ribbon of the route ahead: quads (left/right edges) and chevrons, in AR world coordinates. */
object Ribbon {
    data class Quad(val pts: List<V3>, val s: Double)
    data class Chevron(val pts: List<V3>, val s: Double)

    /** [path] = (distance along, AR point on the ground) pairs. */
    fun build(path: List<Pair<Double, V3>>, width: Double, chevronEvery: Double = 3.0): Pair<List<Quad>, List<Chevron>> {
        val quads = ArrayList<Quad>(); val chev = ArrayList<Chevron>()
        if (path.size < 2) return quads to chev
        fun side(i: Int): Pair<Double, Double> {                          // unit left-hand normal at point i (XZ)
            val a = path[maxOf(0, i - 1)].second; val b = path[minOf(path.size - 1, i + 1)].second
            val dx = b.x - a.x; val dz = b.z - a.z; val l = hypot(dx, dz).coerceAtLeast(1e-6)
            return (dz / l) to (-dx / l)
        }
        for (i in 0 until path.size - 1) {
            val (s0, a) = path[i]; val (_, b) = path[i + 1]
            val (na, ma) = side(i); val (nb, mb) = side(i + 1); val hw = width / 2
            quads.add(Quad(listOf(V3(a.x + na * hw, a.y, a.z + ma * hw), V3(b.x + nb * hw, b.y, b.z + mb * hw), V3(b.x - nb * hw, b.y, b.z - mb * hw), V3(a.x - na * hw, a.y, a.z - ma * hw)), s0))
        }
        // chevrons pointing along the route
        val s0 = path.first().first; val s1 = path.last().first
        var s = (Math.ceil(s0 / chevronEvery) * chevronEvery)
        while (s < s1 - 0.5) {
            var i = 0; while (i < path.size - 2 && path[i + 1].first < s) i++
            val (sa, a) = path[i]; val (sb, b) = path[i + 1]; val t = if (sb - sa < 1e-6) 0.0 else (s - sa) / (sb - sa)
            val p = V3(a.x + (b.x - a.x) * t, a.y + 0.01, a.z + (b.z - a.z) * t)
            val dx = b.x - a.x; val dz = b.z - a.z; val l = hypot(dx, dz).coerceAtLeast(1e-6); val fx = dx / l; val fz = dz / l; val lx = fz; val lz = -fx
            val hw = width * 0.32; val len = width * 0.32; val th = width * 0.13
            fun q(f: Double, side: Double) = V3(p.x + fx * f + lx * side, p.y, p.z + fz * f + lz * side)
            chev.add(Chevron(listOf(q(len / 2, 0.0), q(-len / 2, hw), q(-len / 2 - th, hw), q(len / 2 - th, 0.0), q(-len / 2 - th, -hw), q(-len / 2, -hw)), s))
            s += chevronEvery
        }
        return quads to chev
    }
}
