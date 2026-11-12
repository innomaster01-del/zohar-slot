package com.zohar.slotar

import com.zohar.slotar.walk.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class WalkGeoTest {
    @Test fun polylineMatchesGoogleExample() {
        val pts = listOf(LatLng(38.5, -120.2), LatLng(40.7, -120.95), LatLng(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Geo.encodePolyline(pts))
        val back = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        back.zip(pts).forEach { (a, b) -> assertEquals(b.lat, a.lat, 1e-5); assertEquals(b.lng, a.lng, 1e-5) }
    }
    @Test fun distancesAndBearings() {
        val a = LatLng(32.0753, 34.7746); val b = LatLng(32.0808, 34.7813)
        assertEquals(878.0, Geo.distance(a, b), 15.0)
        assertEquals(45.0, Geo.bearing(LatLng(0.0, 0.0), LatLng(0.001, 0.001)), 0.5)
        val e = Geo.toEnu(a, b); val c = Geo.fromEnu(a, e)
        assertEquals(b.lat, c.lat, 1e-9); assertEquals(b.lng, c.lng, 1e-9)
        assertEquals(Geo.distance(a, b), Math.hypot(e.e, e.n), 2.0)
    }
    /** An L-shaped street: 100 m north, then 100 m east, coming back 2 m beside the first leg. */
    private fun lRoute(): Route {
        val o = LatLng(32.0, 34.8)
        val geo = listOf(Enu(0.0, 0.0), Enu(0.0, 100.0), Enu(100.0, 100.0), Enu(100.0, 2.0), Enu(4.0, 2.0)).map { Geo.fromEnu(o, it) }
        val line = Polyline(geo.first(), geo)
        val steps = listOf(
            Step("Head north", "depart", "straight", 0.0, 100.0, "A", geo[0]), Step("Turn right", "turn", "right", 100.0, 100.0, "B", geo[1]),
            Step("Turn right", "turn", "right", 200.0, 98.0, "C", geo[2]), Step("Turn right", "turn", "right", 298.0, 96.0, "D", geo[3]),
            Step("You have arrived", "arrive", "straight", 394.0, 0.0, "", geo[4]))
        return Route(line, steps, 394 / 1.4)
    }
    @Test fun snapNeverJumpsBackToAnEarlierLeg() {
        val r = lRoute(); val o = r.line.origin
        val late = r.line.snap(Enu(50.0, 2.5), from = 330.0)        // walking the last leg, 2 m beside the first one
        assertTrue("snapped to ${late.along}", late.along > 300)
        val early = r.line.snap(Enu(1.0, 50.0), from = 0.0)
        assertEquals(50.0, early.along, 0.5)
        assertEquals(394.0, r.length, 0.01)
        assertEquals(0.0, Geo.distance(o, Geo.fromEnu(o, r.line.at(0.0))), 0.01)
        assertEquals(90.0, r.line.headingAt(150.0), 1.0)
    }
    @Test fun navigatorCuesInOrderAndArrives() {
        val r = lRoute(); val n = Navigator(r); val cues = ArrayList<String>()
        var s = 0.0; var t = 0.0
        while (s <= r.length) { val p = Geo.fromEnu(r.line.origin, r.line.at(s)); n.update(p, 5.0, t).forEach { (c, st) -> cues.add("$c ${st?.along?.toInt()}") }; s += 1.4; t += 1 }
        assertEquals(listOf("PREPARE 100", "NOW 100", "PREPARE 200", "NOW 200", "PREPARE 298", "NOW 298", "ARRIVED 394"), cues)
        assertTrue(n.state.arrived)
    }
    @Test fun navigatorDetectsLeavingTheRoute() {
        val r = lRoute(); val n = Navigator(r)
        val off = Geo.fromEnu(r.line.origin, Enu(-60.0, 40.0))
        val got = (0..12).flatMap { n.update(off, 5.0, it.toDouble()) }.map { it.first }
        assertTrue(Navigator.Cue.OFF_ROUTE in got)
    }
    @Test fun parsesMapboxDirections() {
        val geo = Geo.encodePolyline(listOf(LatLng(32.0, 34.8), LatLng(32.0009, 34.8), LatLng(32.0009, 34.801)))
        val j = JSONObject("""{"code":"Ok","routes":[{"geometry":"$geo","duration":160,"legs":[{"steps":[
            {"distance":100,"name":"A","maneuver":{"type":"depart","instruction":"Walk north","location":[34.8,32.0]}},
            {"distance":94,"name":"B","maneuver":{"type":"turn","modifier":"right","instruction":"פנה ימינה","location":[34.8,32.0009]}},
            {"distance":0,"name":"","maneuver":{"type":"arrive","instruction":"Arrived","location":[34.801,32.0009]}}]}]}]}""")
        val r = MapboxApi.parseDirections(j)
        assertEquals(3, r.steps.size); assertEquals("פנה ימינה", r.steps[1].instruction); assertEquals("right", r.steps[1].modifier)
        assertEquals(100.0, r.steps[1].along, 1.0); assertEquals(r.length, r.steps[2].along, 1.0)
    }
}

class WalkArTest {
    @Test fun alignmentMapsNorthToWhereTheCompassSays() {
        val g = GeoAlign()
        // the camera looks along AR +x, and the compass says that is east (90°)
        g.heading(1.0, 0.0, 90.0, 1.0); g.position(V3(5.0, 1.5, -3.0), Enu(10.0, 20.0), 1.0)
        val me = g.toAr(Enu(10.0, 20.0), 0.0); assertEquals(5.0, me.x, 1e-9); assertEquals(-3.0, me.z, 1e-9)
        val east = g.toAr(Enu(11.0, 20.0), 0.0); assertEquals(6.0, east.x, 1e-9); assertEquals(-3.0, east.z, 1e-9)   // 1 m east = 1 m along +x
        val north = g.toAr(Enu(10.0, 21.0), 0.0); assertEquals(5.0, north.x, 1e-9); assertEquals(-4.0, north.z, 1e-9) // north is to the left of east: −z
        val back = g.toEnu(V3(6.0, 0.0, -4.0)); assertEquals(11.0, back.e, 1e-9); assertEquals(21.0, back.n, 1e-9)
    }
    @Test fun headingSmoothingWrapsAround() {
        val g = GeoAlign(); g.heading(0.0, -1.0, 359.0, 1.0); g.heading(0.0, -1.0, 1.0, 0.5)
        assertEquals(0.0, Math.toDegrees(-g.yaw), 0.6)
    }
    private fun lookAt(eye: V3, yawDeg: Double): FloatArray {     // camera at eye looking at horizontal angle yaw (0 = −z)
        val a = yawDeg * PI / 180; val fx = sin(a); val fz = -cos(a)
        val r = doubleArrayOf(-fz, 0.0, fx)                       // right = forward × up
        val v = FloatArray(16)
        v[0] = r[0].toFloat(); v[4] = r[1].toFloat(); v[8] = r[2].toFloat(); v[12] = (-(r[0] * eye.x + r[2] * eye.z)).toFloat()
        v[1] = 0f; v[5] = 1f; v[9] = 0f; v[13] = (-eye.y).toFloat()
        v[2] = (-fx).toFloat(); v[6] = 0f; v[10] = (-fz).toFloat(); v[14] = (fx * eye.x + fz * eye.z).toFloat()
        v[15] = 1f; return v
    }
    private fun persp(): FloatArray { val f = 1 / Math.tan(Math.toRadians(30.0)); val n = 0.1; val fa = 200.0
        return FloatArray(16).also { p -> p[0] = (f / 0.5).toFloat(); p[5] = f.toFloat(); p[10] = ((fa + n) / (n - fa)).toFloat(); p[11] = -1f; p[14] = (2 * fa * n / (n - fa)).toFloat() } }
    @Test fun projectsAheadAndClipsBehind() {
        val pj = Projector(lookAt(V3(0.0, 1.5, 0.0), 0.0), persp(), 1000f, 2000f)
        val c = pj.screen(V3(0.0, 1.5, -10.0))!!; assertEquals(500f, c[0], 1f); assertEquals(1000f, c[1], 1f)
        assertNull(pj.screen(V3(0.0, 0.0, 5.0)))                                                 // behind
        val below = pj.screen(V3(0.0, 0.0, -5.0))!!; assertTrue(below[1] > 1000f)               // the ground ahead is below the centre
        val quad = pj.polygon(listOf(V3(-0.5, 0.0, 3.0), V3(-0.5, 0.0, -6.0), V3(0.5, 0.0, -6.0), V3(0.5, 0.0, 3.0)))!!
        assertTrue(quad.size >= 4); quad.forEach { assertTrue(it[1] >= 1000f) }
    }
    @Test fun ribbonFollowsTheRoute() {
        val path = (0..20).map { (it * 2.0) to V3(0.0, 0.0, -it * 2.0) }
        val (q, ch) = Ribbon.build(path, 1.0, 3.0)
        assertEquals(20, q.size); assertEquals(14, ch.size)
        q.forEach { quad -> assertEquals(1.0, abs(quad.pts[0].x - quad.pts[3].x), 1e-9) }
        ch.forEach { c -> assertTrue(c.pts[0].z < c.pts[1].z) }                          // the tip points along the route (−z)
    }
}

class PoppyGuideTest {
    private val ground = { s: Double -> V3(0.0, 0.0, -s) }                                // a straight street along −z
    private fun cam(s: Double) = V3(0.0, 1.45, -s)

    @Test fun walksAheadThenWaitsFacingYou() {
        val g = PoppyGuide(); var me = 0.0; val dt = 1.0 / 30
        var pose = g.step(dt, me, 200.0, false, ground, cam(me))
        repeat(30 * 20) { me += 1.4 * dt; pose = g.step(dt, me, 200.0, false, ground, cam(me)) }
        assertEquals(me + PoppyGuide.LEAD, g.along, 0.6)                                // about 4 m in front of you
        assertTrue(g.walking)
        assertEquals(PI, abs(GeoAlign.wrap(pose.yaw)), 0.15)                          // facing down the street (−z)
        repeat(30 * 5) { pose = g.step(dt, me, 200.0, false, ground, cam(me)) }        // you stop
        assertFalse(g.walking)
        assertEquals(me + PoppyGuide.LEAD, g.along, 0.8)                                // she slows down and stops (a little past 4 m is fine)
        assertEquals(0.0, GeoAlign.wrap(pose.yaw), 0.15)                              // she turned round to you
        assertEquals(0.0, pose.clipTime % PoppyGuide.CLIP, 1e-9)                      // standing, not walking on the spot
    }

    @Test fun staysOnTheRouteAndJumpsAtTheEnd() {
        val g = PoppyGuide(); val dt = 1.0 / 30; var maxHop = 0.0
        repeat(30 * 4) { val p = g.step(dt, 48.0, 50.0, true, ground, cam(48.0)); maxHop = maxOf(maxHop, p.hop) }
        assertEquals(50.0, g.along, 1e-6)                                             // never past the destination
        assertTrue(maxHop > 0.1)
        g.step(dt, 300.0, 400.0, false, ground, cam(300.0))                           // a new route far ahead: she reappears ahead of you
        assertEquals(304.0, g.along, 0.2)
    }
}
