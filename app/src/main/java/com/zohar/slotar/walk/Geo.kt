package com.zohar.slotar.walk

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on Earth (degrees). */
data class LatLng(val lat: Double, val lng: Double)

/** Local flat east/north metres around an origin (good to a few cm over the few km of a walk). */
data class Enu(val e: Double, val n: Double)

object Geo {
    const val R = 6_371_008.8
    private fun rad(d: Double) = d * PI / 180
    fun deg(r: Double) = r * 180 / PI

    fun distance(a: LatLng, b: LatLng): Double {
        val dLat = rad(b.lat - a.lat); val dLng = rad(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLng / 2).let { it * it }
        return 2 * R * atan2(sqrt(h), sqrt(1 - h))
    }
    /** Initial bearing a → b, degrees clockwise from north, 0..360. */
    fun bearing(a: LatLng, b: LatLng): Double {
        val y = sin(rad(b.lng - a.lng)) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(rad(b.lng - a.lng))
        return (deg(atan2(y, x)) + 360) % 360
    }
    fun toEnu(origin: LatLng, p: LatLng) = Enu(rad(p.lng - origin.lng) * R * cos(rad(origin.lat)), rad(p.lat - origin.lat) * R)
    fun fromEnu(origin: LatLng, v: Enu) = LatLng(origin.lat + deg(v.n / R), origin.lng + deg(v.e / (R * cos(rad(origin.lat)))))
    /** Smallest signed difference a − b in degrees, −180..180. */
    fun angleDiff(a: Double, b: Double): Double { var d = (a - b) % 360; if (d > 180) d -= 360; if (d < -180) d += 360; return d }

    /** Google encoded polyline (precision 5), used by the static map overlay. */
    fun encodePolyline(pts: List<LatLng>): String {
        val sb = StringBuilder(); var pLat = 0L; var pLng = 0L
        fun enc(v: Long) { var x = if (v < 0) (v shl 1).inv() else v shl 1; while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }; sb.append((x + 63).toInt().toChar()) }
        for (p in pts) { val la = (p.lat * 1e5).roundToLong(); val ln = (p.lng * 1e5).roundToLong(); enc(la - pLat); enc(ln - pLng); pLat = la; pLng = ln }
        return sb.toString()
    }
    fun decodePolyline(s: String): List<LatLng> {
        val out = ArrayList<LatLng>(); var i = 0; var lat = 0L; var lng = 0L
        fun dec(): Long { var shift = 0; var res = 0L; while (true) { val b = s[i++].code - 63; res = res or ((b and 0x1f).toLong() shl shift); shift += 5; if (b < 0x20) break }; return if (res and 1L != 0L) (res shr 1).inv() else res shr 1 }
        while (i < s.length) { lat += dec(); lng += dec(); out.add(LatLng(lat / 1e5, lng / 1e5)) }
        return out
    }
}

/** Where a point falls on a polyline: segment index, fraction, distance along the line, and its offset from the line. */
data class Snap(val segment: Int, val t: Double, val along: Double, val offset: Double, val point: Enu)

/** A route polyline in local metres (origin = its first point) with cumulative distances. */
class Polyline(val origin: LatLng, val geo: List<LatLng>) {
    val pts: List<Enu> = geo.map { Geo.toEnu(origin, it) }
    val cum: DoubleArray = DoubleArray(pts.size).also { c -> for (i in 1 until pts.size) c[i] = c[i - 1] + hypot(pts[i].e - pts[i - 1].e, pts[i].n - pts[i - 1].n) }
    val length get() = cum.last()

    /** Nearest point on the line to [p] (metres). With [from], only segments at or after that distance (minus a margin) count, so a walk never snaps backwards onto an earlier leg that passes nearby. */
    fun snap(p: Enu, from: Double = 0.0, back: Double = 30.0): Snap {
        var best = Snap(0, 0.0, 0.0, Double.MAX_VALUE, pts.first())
        for (i in 0 until pts.size - 1) {
            if (cum[i + 1] < from - back) continue
            val a = pts[i]; val b = pts[i + 1]; val dx = b.e - a.e; val dy = b.n - a.n; val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-9) 0.0 else (((p.e - a.e) * dx + (p.n - a.n) * dy) / l2).coerceIn(0.0, 1.0)
            val q = Enu(a.e + dx * t, a.n + dy * t); val d = hypot(p.e - q.e, p.n - q.n)
            if (d < best.offset - 1e-9) best = Snap(i, t, cum[i] + t * (cum[i + 1] - cum[i]), d, q)
        }
        return best
    }
    /** The point at distance [s] along the line. */
    fun at(s: Double): Enu {
        val x = s.coerceIn(0.0, length)
        var i = 0; while (i < pts.size - 2 && cum[i + 1] < x) i++
        val seg = cum[i + 1] - cum[i]; val t = if (seg < 1e-9) 0.0 else (x - cum[i]) / seg
        return Enu(pts[i].e + (pts[i + 1].e - pts[i].e) * t, pts[i].n + (pts[i + 1].n - pts[i].n) * t)
    }
    /** Points of the line between distances [s0] and [s1], resampled every [step] metres and at every corner. */
    fun slice(s0: Double, s1: Double, step: Double = 2.0): List<Pair<Double, Enu>> {
        val out = ArrayList<Pair<Double, Enu>>(); val a = s0.coerceIn(0.0, length); val b = s1.coerceIn(0.0, length)
        var s = a
        while (s < b) { out.add(s to at(s)); var next = s + step; for (c in cum) if (c > s + 1e-6 && c < next) { next = c; break }; s = next }
        out.add(b to at(b)); return out
    }
    fun headingAt(s: Double): Double { val p = at(s); val q = at(s + 1.5); return (Geo.deg(atan2(q.e - p.e, q.n - p.n)) + 360) % 360 }
}

/** Is a value meaningfully different from zero (for tests and guards). */
internal fun Double.near(o: Double, eps: Double) = abs(this - o) <= eps
