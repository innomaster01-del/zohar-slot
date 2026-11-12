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
    private fun feet(s: Double) = V3(0.0, 0.0, -s)
    private val dt = 1.0 / 30

    @Test fun walksAheadThenWaitsFacingYou() {
        val g = PoppyGuide(); var me = 0.0
        var pose = g.step(dt, me, 0.0, 200.0, false, emptyList(), feet(me), cam(me), ground)
        repeat(30 * 20) { me += 1.4 * dt; pose = g.step(dt, me, 0.0, 200.0, false, emptyList(), feet(me), cam(me), ground) }
        assertEquals(me + PoppyGuide.LEAD, g.along, 0.6)                                // about 4 m in front of you
        assertTrue(g.walking); assertEquals(0, pose.clip)
        assertEquals(PI, abs(GeoAlign.wrap(pose.yaw)), 0.15)                          // facing down the street (−z)
        repeat(30 * 5) { pose = g.step(dt, me, 0.0, 200.0, false, emptyList(), feet(me), cam(me), ground) }    // you stop
        assertFalse(g.walking); assertEquals(1, pose.clip)                             // idle clip, not walking on the spot
        assertEquals(me + PoppyGuide.LEAD, g.along, 0.8)                                // she slows down and stops (a little past 4 m is fine)
        assertEquals(0.0, GeoAlign.wrap(pose.yaw), 0.15)                              // she turned round to you
    }

    @Test fun waitingTurnsIntoSittingYawningSleepingAndWakesUp() {
        val g = PoppyGuide(); val says = HashSet<PoppyGuide.Say>(); var pose = g.step(dt, 0.0, 0.0, 200.0, false, emptyList(), feet(0.0), cam(0.0), ground)
        repeat(30 * 52) { pose = g.step(dt, 0.0, 0.0, 200.0, false, emptyList(), feet(0.0), cam(0.0), ground); g.say?.let { says.add(it) } }
        assertTrue(says.containsAll(listOf(PoppyGuide.Say.COME_ON, PoppyGuide.Say.YAWN, PoppyGuide.Say.ZZZ)))
        assertTrue(pose.sit > 0.9); assertEquals(PoppyGuide.Eyes.CLOSED, pose.eyes)  // asleep, sitting
        var me = 0.0; repeat(30 * 2) { me += 1.4 * dt; g.step(dt, me, 0.0, 200.0, false, emptyList(), feet(me), cam(me), ground); g.say?.let { says.add(it) } }
        assertTrue(says.contains(PoppyGuide.Say.WAKE))                                 // you walk on: she wakes up
    }

    @Test fun pointsBeforeATurnAndLooksBothWaysAtTheCorner() {
        val g = PoppyGuide(); var me = 0.0; val turns = listOf(PoppyGuide.Turn(20.0, -1))
        var pointed = false; var stoppedAtCorner = false; var maxBeforeLook = 0.0; var lookedAt = -1.0; val says = HashSet<PoppyGuide.Say>()
        repeat(30 * 25) {
            me = minOf(me + 1.4 * dt, 40.0)
            val p = g.step(dt, me, 0.0, 200.0, false, turns, feet(me), cam(me), ground)
            g.say?.let { says.add(it) }
            if (p.armLeft > 1.5 && p.faceYaw > 0.5) pointed = true                     // her left arm up, head turned left
            if (lookedAt < 0) maxBeforeLook = maxOf(maxBeforeLook, g.along)
            if (g.say == PoppyGuide.Say.LOOK && lookedAt < 0) lookedAt = g.along
            if (!g.walking && kotlin.math.abs(g.along - 20.0) < 0.4) stoppedAtCorner = true
        }
        assertTrue(pointed); assertTrue(stoppedAtCorner)
        assertTrue(says.containsAll(listOf(PoppyGuide.Say.SOON_LEFT, PoppyGuide.Say.LOOK)))
        assertTrue(maxBeforeLook <= 20.05)                                            // she never crossed before looking
        assertTrue(g.along > 30)                                                       // and then went on
    }

    @Test fun runsToYouWhenYouLeaveTheRouteAndBackWhenYouReturn() {
        val g = PoppyGuide(); var me = 20.0
        repeat(30) { g.step(dt, me, 0.0, 200.0, false, emptyList(), feet(me), cam(me), ground) }
        val away = V3(20.0, 0.0, -20.0); var p = g.step(dt, me, 20.0, 200.0, false, emptyList(), away, V3(20.0, 1.45, -20.0), ground)
        repeat(30 * 6) { p = g.step(dt, me, 20.0, 200.0, false, emptyList(), away, V3(20.0, 1.45, -20.0), ground) }
        assertEquals(PoppyGuide.Mode.TO_YOU, g.mode)
        assertEquals(PoppyGuide.NEAR_YOU, kotlin.math.hypot(p.ground.x - away.x, p.ground.z - away.z), 0.4)   // a few metres from you, not in your face
        assertEquals(20.0 - PoppyGuide.NEAR_YOU, p.ground.x, 0.4)                     // on the side of the path
        repeat(30 * 8) { p = g.step(dt, me, 0.0, 200.0, false, emptyList(), feet(me), cam(me), ground) }
        assertEquals(PoppyGuide.Mode.LEAD, g.mode)
        assertEquals(0.0, p.ground.x, 0.05)                                            // back on the route
    }

    @Test fun staysOnTheRouteAndPartiesAtTheEnd() {
        val g = PoppyGuide(); var maxHop = 0.0; var p = g.step(dt, 48.0, 0.0, 50.0, true, emptyList(), feet(48.0), cam(48.0), ground)
        repeat(30 * 4) { p = g.step(dt, 48.0, 0.0, 50.0, true, emptyList(), feet(48.0), cam(48.0), ground); maxHop = maxOf(maxHop, p.hop) }
        assertEquals(50.0, g.along, 1e-6)                                             // never past the destination
        assertTrue(maxHop > 0.1); assertEquals(PoppyGuide.Eyes.HAPPY, p.eyes); assertEquals(PoppyGuide.Mouth.GRIN, p.mouth)
        assertTrue(p.armLeft > 2 && p.armRight > 2)                                    // both arms up
    }
}

class WalkSearchTest {
    @Test fun parsesSearchBoxAndSortsNearestFirst() {
        val j = JSONObject("""{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[34.80,32.10]},"properties":{"name":"Far Cafe","mapbox_id":"a","full_address":"Far St 1","coordinates":{"latitude":32.10,"longitude":34.80}}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[34.8245,32.0822]},"properties":{"name":"קפה קרוב","mapbox_id":"b","place_formatted":"רמת גן","coordinates":{"latitude":32.0822,"longitude":34.8245}}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[34.0,32.0]},"properties":{"name":""}}]}""")
        val ps = MapboxApi.parseSearchBox(j)
        assertEquals(2, ps.size)                                                       // nameless results are dropped
        assertEquals("רמת גן", ps[1].address)
        val sorted = MapboxApi.sortByDistance(ps, LatLng(32.0820, 34.8240))
        assertEquals("קפה קרוב", sorted[0].name)
    }

    @Test fun staticMapProjectionTurnsWithYou() {
        val c = LatLng(32.0820, 34.8240); val north = LatLng(32.0830, 34.8240)
        val p0 = StaticMapProjection.project(c, c, 16.4, 0.0, 300.0, 400.0)
        assertEquals(150.0, p0[0], 1e-6); assertEquals(200.0, p0[1], 1e-6)            // the centre is the middle of the image
        val n0 = StaticMapProjection.project(north, c, 16.4, 0.0, 300.0, 400.0)
        assertEquals(150.0, n0[0], 0.5); assertTrue(n0[1] < 200 - 50)                  // north is up when you face north
        val n90 = StaticMapProjection.project(north, c, 16.4, 90.0, 300.0, 400.0)
        assertTrue(n90[0] < 150 - 50); assertEquals(200.0, n90[1], 0.5)               // facing east, north is on your left
    }

    @Test fun hebrewWords() {
        val he = Words(true); val en = Words(false)
        assertEquals("385 מ׳", he.dist(386.0)); assertEquals("1.2 ק״מ", he.dist(1200.0)); assertEquals("385 m", en.dist(386.0))
        assertEquals("4 ש׳ 12 דק׳", he.mins(252 * 60.0)); assertEquals("12 min", en.mins(720.0))
    }
}

class GroundAndSnapTest {
    @Test fun floorIsLowInTheSpreadNotTheAverage() {
        val g = GroundFinder(); val t = 1000L
        assertNull(g.estimate(t))
        listOf(-1.40, -1.41, -1.39, -1.42, -0.95, -0.90, -1.40, -1.38, -1.41, -0.85).forEach { g.add(t, it) }   // floor + a bench / bush
        assertEquals(-1.40, g.estimate(t + 100)!!, 0.03)
        assertNull(g.estimate(t + 5000))                                                // old answers expire
    }

    @Test fun snapPutsTheTrailUnderYourFeet() {
        val line = Polyline(LatLng(32.0, 34.8), listOf(LatLng(32.0, 34.8), LatLng(32.001, 34.8)))   // a line going north
        val s = SidewalkSnap(); val you = Enu(4.0, 50.0)                               // you walk 4 m east of it
        repeat(60) { s.update(you, line, 50.0, 4.0) }
        val drawn = s.apply(line.at(50.0))
        assertEquals(4.0, drawn.e, 0.05); assertEquals(line.at(50.0).n, drawn.n, 0.05) // shifted sideways only
        repeat(200) { s.update(you, line, 50.0, 40.0) }                                // far from the route: the shift fades
        assertEquals(0.0, s.e, 0.1)
    }

    @Test fun walkingStraightCorrectsTheCompass() {
        val line = Polyline(LatLng(32.0, 34.8), listOf(LatLng(32.0, 34.8), LatLng(32.001, 34.8)))   // north
        val m = MotionHeading()
        assertNull(m.update(V3(0.0, 0.0, 0.0), 10.0, 2.0, line))
        assertNull(m.update(V3(3.0, 0.0, 0.0), 13.0, 2.0, line))
        val r = m.update(V3(8.0, 0.0, 0.0), 18.0, 2.0, line)!!                        // walked 8 m along +x in AR
        assertEquals(8.0, r[0], 1e-9); assertEquals(0.0, r[1], 1e-9); assertEquals(0.0, r[2], 0.5)
        val a = GeoAlign(); a.heading(r[0], r[1], r[2], 1.0)
        assertEquals(PI / 2, a.yaw, 1e-6)                                              // AR +x is north
    }
}
