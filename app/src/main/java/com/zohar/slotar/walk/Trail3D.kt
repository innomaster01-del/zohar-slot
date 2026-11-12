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

    fun hideAll() { hideFrom(ribbons, 0); hideFrom(chevrons, 0); hideFrom(paws, 0); shadow?.isVisible = false }

    /**
     * [route] in map metres, [toAr] maps a map point to the AR ground at height y, [you] your distance along the route,
     * [poppy] her pose (her distance along it is [poppyAlong], NaN when she is off the route), [time] seconds.
     */
    fun update(route: Route, toAr: (Enu, Double) -> V3, groundY: Double, you: Double, poppyAlong: Double, poppy: PoppyGuide.Pose, time: Double) {
        val line = route.line
        // ribbon: from 1.5 m ahead of you to 60 m (resampled every 2 m and at every corner)
        val pts = line.slice(you + 1.5, you + 60.0, 2.0).map { (s, e) -> s to toAr(e, groundY + 0.01) }
        var r = 0
        for (i in 0 until pts.size - 1) {
            val a = pts[i].second; val b = pts[i + 1].second; val len = hypot(b.x - a.x, b.z - a.z); if (len < 0.05) continue
            val n = take(ribbons, r, "models/trail_ribbon.glb") ?: break
            place(n, V3((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2), atan2(b.x - a.x, b.z - a.z), RIBBON_WIDTH, 1.0, len + 0.03); r++
        }
        hideFrom(ribbons, r)
        // chevrons every 3 m, gliding forward
        val flow = (time * 1.1) % CHEVRON_EVERY
        var c = 0
        var s = floor((you + 2.5 - flow) / CHEVRON_EVERY) * CHEVRON_EVERY + flow
        if (s < you + 2.5) s += CHEVRON_EVERY
        while (s < minOf(you + 45.0, route.length - 1.0)) {
            if (poppyAlong.isNaN() || kotlin.math.abs(s - poppyAlong) > 0.7) {
                val n = take(chevrons, c, "models/trail_chevron.glb") ?: break
                val p = toAr(line.at(s), groundY + 0.02); val q = toAr(line.at(s + 0.5), groundY + 0.02)
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
                val p = toAr(off, groundY + 0.015); val q = toAr(Enu(off.e + dx, off.n + dn), groundY + 0.015)
                val n = take(paws, k, pawFile) ?: break
                place(n, p, atan2(q.x - p.x, q.z - p.z) + side * 0.12, 1.25, 1.0, 1.25); k++; i++
            }
        }
        hideFrom(paws, k)
        // her shadow: on the ground under her, smaller while she is in the air
        val sh = shadow ?: runCatching { ModelNode(modelLoader.createModelInstance("models/poppy_shadow.glb"), autoAnimate = false) }.getOrNull()?.also { parent.addChildNode(it); shadow = it }
        sh?.let { val k2 = (1.0 - poppy.hop * 2.0).coerceIn(0.5, 1.0) * SHADOW_SIZE; place(it, V3(poppy.ground.x, poppy.ground.y + 0.005, poppy.ground.z), poppy.yaw, k2, 1.0, k2 * 0.9) }
    }

    companion object {
        const val RIBBON_WIDTH = 0.9
        const val CHEVRON_EVERY = 3.0
        const val PAW_EVERY = 0.34
        const val PAW_SIDE = 0.09
        const val SHADOW_SIZE = 0.62
    }
}
