package com.zohar.slotar.walk

/**
 * Turn-by-turn progress on a walking route (pure logic, unit-tested): snaps each position fix onto the route
 * (never backwards onto an earlier leg), tracks the next manoeuvre, decides when to speak / buzz, and when the walker
 * has left the route long enough to fetch a new one.
 */
class Navigator(val route: Route) {
    enum class Cue { PREPARE, NOW, ARRIVED, OFF_ROUTE }
    data class State(val along: Double, val offset: Double, val stepIndex: Int, val toNext: Double, val remaining: Double, val etaSec: Double, val arrived: Boolean, val offRoute: Boolean)

    var along = 0.0; private set
    var state = State(0.0, 0.0, nextStepAfter(0.0), route.steps.getOrNull(1)?.along ?: route.length, route.length, route.duration, false, false); private set
    private val spoken = HashSet<String>()
    private var offSince = -1.0
    private val speed = (route.length / route.duration.coerceAtLeast(1.0)).coerceIn(0.8, 2.0)

    private fun nextStepAfter(s: Double): Int { val i = route.steps.indexOfFirst { it.along > s + 3 }; return if (i < 0) route.steps.size - 1 else i }

    /** A new position fix at time [t] seconds with horizontal accuracy [acc] metres. Returns the cues to announce now. */
    fun update(p: LatLng, acc: Double, t: Double): List<Pair<Cue, Step?>> {
        val cues = ArrayList<Pair<Cue, Step?>>()
        val snap = route.line.snap(Geo.toEnu(route.line.origin, p), from = along)
        val tolerance = 18.0 + acc.coerceIn(0.0, 30.0)
        if (snap.offset <= tolerance) { along = maxOf(along, snap.along); offSince = -1.0 }
        else if (offSince < 0) offSince = t
        val off = offSince >= 0 && t - offSince > 8.0
        val idx = nextStepAfter(along)
        val step = route.steps[idx]
        val toNext = (step.along - along).coerceAtLeast(0.0)
        val remaining = (route.length - along).coerceAtLeast(0.0)
        val arrived = remaining < 12.0 && snap.offset < tolerance
        state = State(along, snap.offset, idx, toNext, remaining, remaining / speed, arrived, off)
        if (arrived) { if (spoken.add("arrived")) cues.add(Cue.ARRIVED to route.steps.last()) ; return cues }
        if (off && spoken.add("off@" + (offSince * 10).toInt())) cues.add(Cue.OFF_ROUTE to null)
        if (!off && step.type != "arrive") {
            if (toNext in 15.0..45.0 && spoken.add("prep$idx")) cues.add(Cue.PREPARE to step)
            if (toNext < 12.0 && spoken.add("now$idx")) cues.add(Cue.NOW to step)
        }
        return cues
    }
}
