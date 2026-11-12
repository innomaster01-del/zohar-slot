package com.zohar.slotar.walk

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The street's height in AR from many hit tests (floor planes and ARCore depth) across the lower part of the camera view.
 * Bushes, benches and kerbs also answer, so the floor is taken low in the spread of recent answers (20th percentile)
 * rather than their average. Null when nothing answered recently.
 */
class GroundFinder(private val windowMs: Long = 2500) {
    private val ys = ArrayDeque<Pair<Long, Double>>()
    var lastHitAt = 0L; private set
    fun add(t: Long, y: Double) { ys.addLast(t to y); lastHitAt = t; while (ys.size > 80) ys.removeFirst() }
    /** How many answers came in the last [windowMs] (drives the scan progress). */
    fun count(now: Long): Int = ys.count { now - it.first < windowMs }
    /** A strong answer: a tap on the floor counts as several. */
    fun addStrong(t: Long, y: Double) { repeat(6) { add(t, y) } }
    fun estimate(now: Long): Double? {
        val recent = ys.filter { now - it.first < windowMs }.map { it.second }.sorted()
        return if (recent.size < 3) null else recent[(recent.size * 0.2).toInt()]
    }
}

/**
 * Keeps the drawn route on the sidewalk you actually walk on. The map's walking line and your GPS position disagree by
 * a few metres sideways (and the map line often runs down the middle of the street); while you are near the route, the
 * whole drawing is shifted by your sideways distance from the line, so the trail starts at your feet.
 */
class SidewalkSnap {
    var e = 0.0; private set
    var n = 0.0; private set
    fun update(user: Enu, line: Polyline, along: Double, offset: Double, gain: Double = 0.15) {
        if (offset > MAX) { e *= 0.97; n *= 0.97; return }
        val p = line.at(along); val a = line.at((along - 3).coerceAtLeast(0.0)); val b = line.at((along + 3).coerceAtMost(line.length))
        val te = b.e - a.e; val tn = b.n - a.n; val tl = hypot(te, tn); if (tl < 1e-6) return
        val ue = te / tl; val un = tn / tl
        val de = user.e - p.e; val dn = user.n - p.n
        val side = de * (-un) + dn * ue                                         // signed sideways distance (left of the line +)
        val we = -un * side; val wn = ue * side
        e += (we - e) * gain; n += (wn - n) * gain
    }
    fun apply(p: Enu) = Enu(p.e + e, p.n + n)
    companion object { const val MAX = 18.0 }
}

/**
 * Corrects the compass with your own walking: when you have walked a straight stretch along a straight part of the route,
 * the direction you moved in AR is the route's direction on the map. Returns (AR dx, AR dz, map bearing in degrees)
 * for GeoAlign.heading, or null.
 */
class MotionHeading {
    private var start: V3? = null; private var startAlong = 0.0
    fun update(cam: V3, along: Double, offset: Double, line: Polyline): DoubleArray? {
        val s0 = start
        if (s0 == null || offset > 10 || along < startAlong - 1) { start = cam; startAlong = along; return null }
        val walked = along - startAlong
        val dx = cam.x - s0.x; val dz = cam.z - s0.z; val moved = hypot(dx, dz)
        if (walked < 7 || moved < 6) return null
        start = cam; startAlong = along
        if (moved / walked !in 0.75..1.3) return null                           // you did not walk straight along the route
        val b0 = bearing(line, startAlong - walked, startAlong - walked / 2); val b1 = bearing(line, startAlong - walked / 2, startAlong)
        if (abs(GeoAlign.wrap(Math.toRadians(b1 - b0))) > Math.toRadians(12.0)) return null   // the route bends here
        return doubleArrayOf(dx, dz, bearing(line, startAlong - walked, startAlong))
    }
    private fun bearing(line: Polyline, s0: Double, s1: Double): Double {
        val a = line.at(s0); val b = line.at(s1)
        return (Math.toDegrees(atan2(b.e - a.e, b.n - a.n)) + 360) % 360
    }
}
