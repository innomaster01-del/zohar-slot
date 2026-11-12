package com.zohar.slotar.walk

import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The way ahead drawn in 3D on the street (so Poppy stands in front of it and nothing is cut out):
 * a see-through ribbon with white edges, raised white chevrons that glide forward, Poppy's paw prints from you to her,
 * and her soft shadow. Pieces are pooled model nodes under [parent]; unused ones are hidden.
 */
class Trail3D(private val modelLoader: ModelLoader, private val parent: Node, gender: String) {
    private val pawFile = "models/poppy_paw_$gender.glb"
    private val ribbons = ArrayList<ModelNode>(); private val chevrons = ArrayList<ModelNode>(); private val paws = ArrayList<ModelNode>()
    private var shadow: ModelNode? = null
    private val fades = ArrayList<ModelNode>()

    private fun take(pool: ArrayList<ModelNode>, i: Int, file: String): ModelNode? {
        while (pool.size <= i) {
            val n = runCatching { ModelNode(modelLoader.createModelInstance(file), autoAnimate = false) }.getOrNull() ?: return null
            n.isVisible = false; parent.addChildNode(n); pool.add(n)
        }
        return pool[i]
    }
    private fun place(n: ModelNode, p: V3, yaw: Double, sx: Double = 1.0, sy: Double = 1.0, sz: Double = 1.0) {
        n.worldPosition = Position(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
        n.worldQuaternion = dev.romainguy.kotlin.math.Quaternion(0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat())
        n.scale = Scale(sx.toFloat(), sy.toFloat(), sz.toFloat())
        n.isVisible = true
    }
    private fun hideFrom(pool: ArrayList<ModelNode>, i: Int) { for (k in i until pool.size) pool[k].isVisible = false }

    fun hideAll() { fades.forEach { it.isVisible = false }; hideFrom(ribbons, 0); hideFrom(chevrons, 0); hideFrom(paws, 0); shadow?.isVisible = false }

    /**
     * [route] in map metres, [toAr] maps a map point to the AR ground at height y, [you] your distance along the route,
     * [poppy] her pose (her distance along it is [poppyAlong], NaN when she is off the route), [time] seconds.
     */
    fun update(route: Route, toAr: (Enu, Double) -> V3, groundY: Double, you: Double, poppyAlong: Double, poppy: PoppyGuide.Pose, time: Double) {
        val line = route.line
        val y0 = groundY + LIFT
        // how far to draw: up to FAR_TO ahead, but only a few metres past the next turn (so the way round the corner is
        // shown without a long bar through the houses beyond it)
        val nextTurn = route.steps.firstOrNull { it.along > you + FADE_TO && (it.modifier.contains("left") || it.modifier.contains("right")) && it.type != "arrive" }
        val end = minOf(you + FAR_TO, route.length, nextTurn?.let { it.along + AFTER_TURN } ?: Double.MAX_VALUE)
        val start = you + FADE_FROM
        if (end - start < 1.0) { fades.forEach { it.isVisible = false }; hideFrom(ribbons, 0) }
        else {
            // one continuous strip: the route as AR points every 2 m (and at its corners), corners rounded, then cut into
            // 1 m pieces that meet edge to edge (no overlaps, so no darker stripes where see-through pieces would double up)
            val raw = line.slice(start, end, 2.0).map { (_, e) -> toAr(e, y0) }
            val path = resample(rounded(raw), STEP)
            val total = (path.size - 1) * STEP
            val nearLen = minOf(FADE_TO - FADE_FROM, total * 0.4)
            val farLen = minOf(FAR_TO - FAR_FROM, maxOf(1.5, total * 0.3)).coerceAtMost(total - nearLen)
            val iNear = (nearLen / STEP).toInt().coerceIn(1, path.size - 1)
            val iFar = (path.size - 1 - (farLen / STEP).toInt()).coerceIn(iNear, path.size - 1)
            // both ends melt away (smooth fade + fine sparkles): at your feet, and at the far end
            fun fadePiece(i: Int, a: V3, b: V3, flip: Boolean) {
                val n = if (fades.size > i) fades[i] else runCatching { ModelNode(modelLoader.createModelInstance("models/trail_ribbon_fade.glb"), autoAnimate = false) }.getOrNull()?.also { it.isVisible = false; parent.addChildNode(it); fades.add(it) } ?: return
                val len = hypot(b.x - a.x, b.z - a.z)
                if (len > 0.2) place(n, V3((a.x + b.x) / 2, y0, (a.z + b.z) / 2), atan2(b.x - a.x, b.z - a.z) + (if (flip) kotlin.math.PI else 0.0), RIBBON_WIDTH, 1.0, len) else n.isVisible = false
            }
            fadePiece(0, path[0], path[iNear], false)
            if (iFar < path.size - 1) fadePiece(1, path[iFar], path.last(), true) else fades.getOrNull(1)?.isVisible = false
            var r = 0
            for (i in iNear until iFar) {
                val a = path[i]; val b = path[i + 1]; val len = hypot(b.x - a.x, b.z - a.z); if (len < 0.02) continue
                val n = take(ribbons, r, "models/trail_ribbon.glb") ?: break
                place(n, V3((a.x + b.x) / 2, y0, (a.z + b.z) / 2), atan2(b.x - a.x, b.z - a.z), RIBBON_WIDTH, 1.0, len); r++
            }
            hideFrom(ribbons, r)
        }
        // chevrons every 3 m, gliding forward
        val flow = (time * 1.1) % CHEVRON_EVERY
        var c = 0
        var s = floor((you + FADE_TO - 0.4 - flow) / CHEVRON_EVERY) * CHEVRON_EVERY + flow
        if (s < you + FADE_TO - 0.4) s += CHEVRON_EVERY
        while (s < minOf(end - 2.0, route.length - 1.0)) {
            if (poppyAlong.isNaN() || kotlin.math.abs(s - poppyAlong) > 0.7) {
                val n = take(chevrons, c, "models/trail_chevron.glb") ?: break
                val p = toAr(line.at(s), y0 + 0.01); val q = toAr(line.at(s + 0.5), y0 + 0.01)
                place(n, p, atan2(q.x - p.x, q.z - p.z)); c++
            }
            s += CHEVRON_EVERY
        }
        hideFrom(chevrons, c)
        // Poppy's paw prints: on a fixed grid along the route (so they stay put on the street), from you to just behind her
        var k = 0
        if (!poppyAlong.isNaN()) {
            var i = kotlin.math.ceil((you + 0.9) / PAW_EVERY).toInt()
            while (i * PAW_EVERY < poppyAlong - 0.45 && k < 40) {
                val d = i * PAW_EVERY; val side = if (i % 2 == 0) 1.0 else -1.0
                val e = line.at(d); val f = line.at(d + 0.3); val dx = f.e - e.e; val dn = f.n - e.n; val l = hypot(dx, dn).coerceAtLeast(1e-6)
                val off = Enu(e.e + (-dn / l) * PAW_SIDE * side, e.n + (dx / l) * PAW_SIDE * side)    // left / right of the line
                val p = toAr(off, y0 + 0.006); val q = toAr(Enu(off.e + dx, off.n + dn), y0 + 0.006)
                val n = take(paws, k, pawFile) ?: break
                place(n, p, atan2(q.x - p.x, q.z - p.z) + side * 0.12, 1.25, 1.0, 1.25); k++; i++
            }
        }
        hideFrom(paws, k)
        // her shadow: on the ground under her, smaller while she is in the air
        val sh = shadow ?: runCatching { ModelNode(modelLoader.createModelInstance("models/poppy_shadow.glb"), autoAnimate = false) }.getOrNull()?.also { parent.addChildNode(it); shadow = it }
        sh?.let { val k2 = (1.0 - poppy.hop * 2.0).coerceIn(0.5, 1.0) * SHADOW_SIZE; place(it, V3(poppy.ground.x, poppy.ground.y + 0.005, poppy.ground.z), poppy.yaw, k2, 1.0, k2 * 0.9) }
    }

    /** Rounds the corners of a polyline (Chaikin, three passes; the ends stay where they are). */
    private fun rounded(p: List<V3>): List<V3> {
        var pts = p
        repeat(3) {
            if (pts.size < 3) return pts
            val out = ArrayList<V3>(pts.size * 2); out.add(pts.first())
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                if (i > 0) out.add(V3(a.x + (b.x - a.x) * 0.25, a.y, a.z + (b.z - a.z) * 0.25))
                if (i < pts.size - 2) out.add(V3(a.x + (b.x - a.x) * 0.75, a.y, a.z + (b.z - a.z) * 0.75))
            }
            out.add(pts.last()); pts = out
        }
        return pts
    }
    /** Points every [step] metres along a polyline. */
    private fun resample(p: List<V3>, step: Double): List<V3> {
        if (p.size < 2) return p
        val out = arrayListOf(p.first()); var carry = 0.0
        for (i in 0 until p.size - 1) {
            val a = p[i]; val b = p[i + 1]; val l = hypot(b.x - a.x, b.z - a.z); if (l < 1e-6) continue
            var t = step - carry
            while (t <= l) { out.add(V3(a.x + (b.x - a.x) * t / l, a.y, a.z + (b.z - a.z) * t / l)); t += step }
            carry = (carry + l) % step
        }
        return out
    }

    companion object {
        const val STEP = 1.0           // metres per ribbon piece
        const val AFTER_TURN = 5.0     // the trail goes this far past the next turn
        const val RIBBON_WIDTH = 0.85
        const val LIFT = 0.03         // a few cm above the street, so depth occlusion does not nibble it
        const val FAR_FROM = 40.0     // the far end starts fading here (m ahead of you) …
        const val FAR_TO = 48.0       // … and is gone here
        const val FADE_FROM = 0.8     // the ribbon starts fading in here (m ahead of you) …
        const val FADE_TO = 3.2       // … and is full from here
        const val CHEVRON_EVERY = 3.0
        const val PAW_EVERY = 0.34
        const val PAW_SIDE = 0.09
        const val SHADOW_SIZE = 0.62
    }
}
