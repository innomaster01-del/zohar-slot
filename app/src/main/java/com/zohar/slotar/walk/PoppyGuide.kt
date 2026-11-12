package com.zohar.slotar.walk

import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Poppy walks the route a few metres ahead of you and leads the way. She walks at your pace, stops and turns to you
 * when you stop or fall behind (with a little hop now and then: "come on!"), and jumps for joy at the destination.
 * The walk itself is the model's own "walk" clip (feet, arms, ears), played at a speed that matches hers.
 */
class PoppyGuide {
    /** Distance along the route (metres) she is standing at; NaN until the first update. */
    var along = Double.NaN; private set
    var walking = false; private set
    private var speed = 0.0
    private var phase = 0.0
    private var yaw = Double.NaN
    private var clock = 0.0
    private var lastUser = Double.NaN
    private var userSpeed = 0.0          // how fast you walk (smoothed): she matches it, so the gap stays LEAD

    /** The math, without the 3D node (unit-tested). Returns her ground point, facing (radians, 0 = AR +z) and hop height. */
    fun step(dt: Double, userAlong: Double, routeLength: Double, arrived: Boolean, ground: (Double) -> V3, camera: V3): Pose {
        clock += dt
        val target = (userAlong + LEAD).coerceAtMost(routeLength)
        if (along.isNaN() || along - target > JUMP_BACK || target - along > JUMP_AHEAD) along = target   // first frame, or a new route
        // your position comes in steps (the navigator updates a few times a second), so your speed is smoothed
        val raw = if (lastUser.isNaN() || dt <= 0) 0.0 else ((userAlong - lastUser) / dt).coerceIn(0.0, 3.0)
        if (lastUser.isNaN() || kotlin.math.abs(userAlong - lastUser) > 20) userSpeed = 0.0 else userSpeed += (raw - userSpeed) * (dt * 1.5).coerceAtMost(1.0)
        lastUser = userAlong
        val want = (userSpeed + (target - along) * 1.3).coerceIn(0.0, MAX_SPEED)
        speed += (want - speed) * (dt * 6).coerceAtMost(1.0)
        along = (along + speed * dt).coerceAtMost(routeLength)
        walking = speed > 0.15 && !arrived
        val p = ground(along)
        // facing: along the route while walking, towards you while waiting
        val face = if (walking) {
            val a = ground((along - 0.6).coerceAtLeast(0.0)); val b = ground((along + 0.6).coerceAtMost(routeLength))
            atan2(b.x - a.x, b.z - a.z)
        } else atan2(camera.x - p.x, camera.z - p.z)
        yaw = if (yaw.isNaN()) face else yaw + GeoAlign.wrap(face - yaw) * (dt * (if (walking) 5.0 else 3.0)).coerceAtMost(1.0)
        // walk clip time: the cycle runs faster when she walks faster; standing still eases back to the rest pose
        if (walking) phase += dt * (speed / 1.2).coerceIn(0.7, 1.8)
        else { val r = phase % CLIP; if (r > 0.0) { val n = phase + dt; phase = if (n % CLIP < r) 0.0 else n } }   // finish the step, then stand
        val hop = when {
            arrived -> 0.18 * kotlin.math.abs(sin(clock * 5.0))                                     // jumping for joy
            !walking -> { val t = clock % 2.4; if (t < 0.45) 0.14 * sin(t / 0.45 * PI) else 0.0 }   // "come on!"
            else -> 0.0
        }
        return Pose(p, yaw, hop, phase)
    }

    data class Pose(val ground: V3, val yaw: Double, val hop: Double, val clipTime: Double)

    /** Puts the 3D model where [step] says, plays its walk clip. */
    fun apply(node: ModelNode, pose: Pose) {
        node.worldPosition = Position(pose.ground.x.toFloat(), (pose.ground.y + pose.hop).toFloat(), pose.ground.z.toFloat())
        val h = pose.yaw / 2
        node.worldQuaternion = dev.romainguy.kotlin.math.Quaternion(0f, sin(h).toFloat(), 0f, cos(h).toFloat())
        runCatching {
            val a = node.animator
            if (a.animationCount > 0) { a.applyAnimation(0, (pose.clipTime % CLIP).toFloat()); a.updateBoneMatrices() }
        }
    }

    companion object {
        const val LEAD = 4.0          // metres ahead of you
        const val MAX_SPEED = 2.4     // m/s: she catches up quickly but never runs off
        const val JUMP_BACK = 12.0    // you went back a long way (or a reroute): she reappears ahead of you
        const val JUMP_AHEAD = 30.0
        const val CLIP = 0.56         // length of the walk clip in poppy_*.glb
        const val SCALE = 0.6f        // about 80 cm tall with her ears
        const val HEIGHT = 1.35 * SCALE
    }
}

/** Screen box around Poppy (left, top, right, bottom), so the floor arrows behind her are not painted over her. */
internal fun poppyBox(pj: Projector, pose: PoppyGuide.Pose): FloatArray? {
    val g = pose.ground
    val foot = pj.screen(V3(g.x, g.y + pose.hop, g.z)) ?: return null
    val head = pj.screen(V3(g.x, g.y + pose.hop + PoppyGuide.HEIGHT, g.z)) ?: return null
    val h = hypot((foot[0] - head[0]).toDouble(), (foot[1] - head[1]).toDouble()).toFloat()
    val cx = (foot[0] + head[0]) / 2; val w = h * 0.5f
    return floatArrayOf(cx - w, minOf(head[1], foot[1]) - h * 0.05f, cx + w, maxOf(head[1], foot[1]) + h * 0.08f)
}
