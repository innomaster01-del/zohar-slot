package com.zohar.slotar.walk

import android.util.Log
import com.google.android.filament.Engine
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Poppy's behaviour (pure logic, unit-tested). Each frame it gets where you are on the route and returns her pose:
 *  - leads the way about 4 m ahead at your pace (her paw prints are drawn behind her)
 *  - before a turn she points with her arm and turns her head that way ("left here!")
 *  - at every manoeuvre (street corner / crossing) she stops and looks left and right before going on
 *  - if you stop: she turns to you and hops ("come on!"), then sits down, yawns, and falls asleep; she wakes when you walk
 *  - if you are away from the route she comes towards you and waits a few metres away, between you and the path,
 *    so you see her and which way to go ("the path is here!"); back to the route when you return
 *  - at the destination: happy eyes, a big smile, hopping with both arms up
 */
class PoppyGuide {
    enum class Eyes { OPEN, HAPPY, CLOSED }
    enum class Mouth { SMILE, GRIN, O }
    enum class Say { LETS_GO, COME_ON, SOON_LEFT, SOON_RIGHT, LEFT_HERE, RIGHT_HERE, LOOK, ALMOST, ARRIVED, WAIT_FOR_ME, WAKE, YAWN, ZZZ }
    enum class Mode { LEAD, TO_YOU, BACK, PARTY }

    /** A manoeuvre on the route: distance along it, and which way (−1 left, +1 right, 0 straight / crossing). */
    data class Turn(val along: Double, val side: Int)

    data class Pose(
        val ground: V3, val yaw: Double, val hop: Double,
        val clip: Int, val clipTime: Double,                   // 0 = walk loop, 1 = idle loop
        val eyes: Eyes, val mouth: Mouth,
        val faceYaw: Double,                                   // head turn (radians, + = to her left)
        val armLeft: Double, val armRight: Double,             // raise (radians) of her own left / right arm
        val sit: Double,                                       // 0 standing … 1 sitting
        val onRoute: Boolean,
    )

    var along = Double.NaN; private set
    var mode = Mode.LEAD; private set
    var walking = false; private set
    /** The current speech bubble, and a counter that changes whenever a new one starts (for her chirp). */
    var say: Say? = null; private set
    var sayCount = 0; private set

    private var pos: V3? = null
    private var speed = 0.0
    private var walkT = 0.0; private var idleClipT = 0.0
    private var yaw = Double.NaN
    private var clock = 0.0
    private var lastUser = Double.NaN
    private var userSpeed = 0.0
    private var idleT = 0.0; private var idleEpisode = 0; private var awayEpisode = 0
    private var sayUntil = 0.0
    private var lookStart = -1.0
    private val looked = HashSet<Int>()
    private val said = HashSet<String>()
    private var sit = 0.0
    private var faceYaw = 0.0
    private var armL = 0.0; private var armR = 0.0
    private var blinkAt = 2.5
    private var wakeHop = 0.0
    private var started = false

    private fun speak(s: Say, key: String? = null, hold: Double = 2.6) {
        if (key != null && !said.add(key)) return
        say = s; sayCount++; sayUntil = clock + hold
    }

    /**
     * [userAlong] where you are along the route, [userOffset] how far you are from it (m), [userGround] the ground under you
     * and [camera] the phone (AR world). [ground] maps a distance along the route to the AR ground point there.
     */
    fun step(dt: Double, userAlong: Double, userOffset: Double, routeLength: Double, arrived: Boolean, turns: List<Turn>,
             userGround: V3, camera: V3, ground: (Double) -> V3, leadCap: Double = LEAD): Pose {
        clock += dt
        if (say != null && clock > sayUntil) say = null
        if (!started) { started = true; speak(Say.LETS_GO); wakeHop = 0.5 }      // she pops in with a hop

        // your walking speed (positions come a few times a second, so it is smoothed)
        val raw = if (lastUser.isNaN() || dt <= 0) 0.0 else ((userAlong - lastUser) / dt).coerceIn(0.0, 3.0)
        if (lastUser.isNaN() || abs(userAlong - lastUser) > 20) userSpeed = 0.0 else userSpeed += (raw - userSpeed) * (dt * 1.5).coerceAtMost(1.0)
        lastUser = userAlong
        val youMove = userSpeed > 0.35

        // mode
        val before = mode
        mode = when {
            arrived -> Mode.PARTY
            userOffset > OFF_ROUTE -> Mode.TO_YOU
            before == Mode.TO_YOU || before == Mode.BACK -> Mode.BACK
            else -> Mode.LEAD
        }
        if (mode == Mode.TO_YOU && before != Mode.TO_YOU) { awayEpisode++; speak(Say.WAIT_FOR_ME, "away$awayEpisode") }

        // a wall or a door close in front of you: she waits in front of it instead of standing inside it
        var target = (userAlong + minOf(LEAD, leadCap.coerceAtLeast(1.0))).coerceAtMost(routeLength)
        if (mode == Mode.PARTY) target = routeLength

        // street corners: stop and look both ways, once per manoeuvre
        val from = if (along.isNaN()) userAlong else along
        val nextTurnIdx = turns.indexOfFirst { it.along > from - 0.3 && it.along < routeLength - 5 }
        val nextTurn = turns.getOrNull(nextTurnIdx)
        var looking = false
        if (mode == Mode.LEAD && nextTurn != null && nextTurnIdx !in looked) {
            target = minOf(target, nextTurn.along)
            if (!along.isNaN() && along >= nextTurn.along - 0.35) {
                if (lookStart < 0) { lookStart = clock; speak(Say.LOOK, "look$nextTurnIdx", 2.0) }
                looking = clock - lookStart < LOOK_TIME
                if (!looking) { looked.add(nextTurnIdx); lookStart = -1.0 }
            }
        }

        // move
        val p0 = pos
        val p: V3
        var running = false
        if (mode == Mode.LEAD || mode == Mode.PARTY) {
            if (along.isNaN() || along - target > JUMP_BACK || target - along > JUMP_AHEAD) along = target
            // your pace plus a pull towards her spot, never faster than lets her stop exactly there (corner, destination)
            val gap = target - along
            val want = if (looking) 0.0 else minOf(userSpeed + gap * 1.3, gap * 4.0).coerceIn(0.0, MAX_SPEED)
            speed += (want - speed) * (dt * 6).coerceAtMost(1.0)
            along = (along + speed * dt).coerceAtMost(maxOf(target, along))
            p = ground(along)
        } else {
            val start = p0 ?: ground(if (along.isNaN()) target else along)
            val goal = if (mode == Mode.TO_YOU) {
                // on the way from you to the nearest part of the path, a few metres from you (never in your face)
                val rp = ground(userAlong.coerceIn(0.0, routeLength))
                val dx = rp.x - userGround.x; val dz = rp.z - userGround.z; val d = hypot(dx, dz).coerceAtLeast(1e-6)
                val k = minOf(NEAR_YOU, d * 0.6)
                V3(userGround.x + dx / d * k, rp.y, userGround.z + dz / d * k)
            } else ground(target)
            val dx = goal.x - start.x; val dz = goal.z - start.z; val d = hypot(dx, dz)
            val want = if (d < 0.25) 0.0 else RUN_SPEED.coerceAtMost(d * 2.5)
            speed += (want - speed) * (dt * 6).coerceAtMost(1.0)
            val stepLen = (speed * dt).coerceAtMost(d)
            p = if (d < 1e-6) goal else V3(start.x + dx / d * stepLen, goal.y, start.z + dz / d * stepLen)
            running = speed > 1.6
            if (mode == Mode.BACK && d < 0.4) { mode = Mode.LEAD; along = target }
        }
        pos = p
        walking = speed > 0.15 && !(mode == Mode.PARTY && along >= routeLength - 0.05)

        // where she faces: along the way while walking, at you while waiting
        val face = when {
            walking && (mode == Mode.LEAD || mode == Mode.PARTY) -> { val a = ground((along - 0.8).coerceAtLeast(0.0)); val b = ground((along + 0.8).coerceAtMost(routeLength)); atan2(b.x - a.x, b.z - a.z) }
            walking && p0 != null && hypot(p.x - p0.x, p.z - p0.z) > 0.004 -> atan2(p.x - p0.x, p.z - p0.z)
            else -> atan2(camera.x - p.x, camera.z - p.z)
        }
        yaw = if (yaw.isNaN()) face else yaw + GeoAlign.wrap(face - yaw) * (dt * (if (walking) 6.0 else 3.0)).coerceAtMost(1.0)

        // waiting for you: come on → sit → yawn → sleep; she wakes up with a hop when you walk on
        if (mode == Mode.LEAD && !walking && !looking && !youMove) idleT += dt
        else if (idleT > 0) { if (idleT > SLEEP_AT) { wakeHop = 0.5; speak(Say.WAKE) }; idleT = 0.0; idleEpisode++ }
        if (idleT > 4 && idleT < SIT_AT) speak(Say.COME_ON, "comeon$idleEpisode")
        if (idleT > YAWN_AT && idleT < SLEEP_AT) speak(Say.YAWN, "yawn$idleEpisode")
        if (idleT > SLEEP_AT && say == null && ((idleT - SLEEP_AT) % 6.0) < dt) speak(Say.ZZZ, null, 2.5)
        sit += ((if (idleT > SIT_AT) 1.0 else 0.0) - sit) * (dt * 3).coerceAtMost(1.0)

        // a turn ahead: point with the arm and look that way
        var wantFace = 0.0; var wantL = 0.0; var wantR = 0.0
        val me = if (along.isNaN()) userAlong else along
        val upcoming = turns.firstOrNull { it.side != 0 && it.along > me - 1.0 }
        if (mode == Mode.LEAD && upcoming != null && upcoming.along - me < POINT_FROM) {
            val toTurn = upcoming.along - me
            if (upcoming.side < 0) { wantFace = HEAD_TURN; wantL = ARM_RAISE } else { wantFace = -HEAD_TURN; wantR = ARM_RAISE }
            if (toTurn > 2.5) speak(if (upcoming.side < 0) Say.SOON_LEFT else Say.SOON_RIGHT, "soon${upcoming.along.toInt()}")
            else speak(if (upcoming.side < 0) Say.LEFT_HERE else Say.RIGHT_HERE, "here${upcoming.along.toInt()}")
        }
        if (looking) wantFace = HEAD_TURN * 1.2 * sin((clock - lookStart) / LOOK_TIME * 2 * PI)
        if (mode == Mode.PARTY && !walking) { wantL = 2.6 + 0.3 * sin(clock * 9); wantR = 2.6 + 0.3 * sin(clock * 9 + 1.5) }
        if (routeLength - userAlong < 60 && mode == Mode.LEAD) speak(Say.ALMOST, "almost")
        if (mode == Mode.PARTY) speak(Say.ARRIVED, "arrived", 4.0)
        val k = (dt * 5).coerceAtMost(1.0)
        faceYaw += (wantFace - faceYaw) * k; armL += (wantL - armL) * k; armR += (wantR - armR) * k

        // face
        if (clock > blinkAt + 0.13) blinkAt = clock + 2.6 + (clock * 7.31 % 1.0) * 2.4
        val blinking = clock > blinkAt && clock < blinkAt + 0.13
        val asleep = idleT > SLEEP_AT
        val yawning = idleT > YAWN_AT && idleT < YAWN_AT + 2.2
        val eyes = when {
            mode == Mode.PARTY -> Eyes.HAPPY
            asleep || yawning || blinking -> Eyes.CLOSED
            say == Say.LEFT_HERE || say == Say.RIGHT_HERE || say == Say.WAKE -> Eyes.HAPPY
            else -> Eyes.OPEN
        }
        val mouth = when {
            yawning -> Mouth.O
            mode == Mode.PARTY || say == Say.LETS_GO || say == Say.WAKE -> Mouth.GRIN
            mode == Mode.TO_YOU && running -> Mouth.O
            else -> Mouth.SMILE
        }

        // the body: walk clip while moving (faster when running), idle clip (breathing) otherwise
        if (walking) walkT += dt * (speed / 1.2).coerceIn(0.7, 2.4) else walkT = 0.0
        idleClipT += dt * (if (asleep) 0.6 else 1.0)
        wakeHop = (wakeHop - dt).coerceAtLeast(0.0)
        val hop = when {
            mode == Mode.PARTY && !walking -> 0.18 * abs(sin(clock * 5.0))
            wakeHop > 0 -> 0.2 * sin(wakeHop / 0.5 * PI)
            running -> 0.05 * abs(sin(walkT * PI / CLIP))
            !walking && !looking && mode == Mode.LEAD && idleT in 1.0..SIT_AT -> { val t = clock % 2.4; if (t < 0.45) 0.14 * sin(t / 0.45 * PI) else 0.0 }
            else -> 0.0
        }
        return Pose(p, yaw, hop, if (walking) 0 else 1, if (walking) walkT else idleClipT, eyes, mouth, faceYaw, armL, armR, sit, mode == Mode.LEAD || mode == Mode.PARTY)
    }

    companion object {
        const val LEAD = 3.0           // metres ahead of you (close enough to sit low in the view)
        const val MAX_SPEED = 2.4      // m/s on the route: she catches up quickly but never runs off
        const val RUN_SPEED = 3.2      // m/s when she runs to you
        const val OFF_ROUTE = 15.0     // you are this far from the route: she comes towards you
        const val NEAR_YOU = 3.5       // she waits this far from you, towards the path
        const val JUMP_BACK = 12.0     // a reroute behind you: she reappears ahead
        const val JUMP_AHEAD = 30.0
        const val LOOK_TIME = 1.8      // seconds of looking left and right at a corner
        const val POINT_FROM = 8.0     // starts pointing this far before a turn
        const val HEAD_TURN = 0.75
        const val ARM_RAISE = 2.0
        const val SIT_AT = 18.0; const val YAWN_AT = 40.0; const val SLEEP_AT = 46.0
        const val CLIP = 0.56          // the walk clip in poppy_*.glb
        const val SCALE = 0.6f         // about 80 cm tall with the ears
        const val HEIGHT = 1.35 * SCALE

        /** Manoeuvres of a route as turns for Poppy (start and arrival left out). */
        fun turnsOf(route: Route): List<Turn> = route.steps.filter { it.type != "depart" && it.type != "arrive" }.map {
            Turn(it.along, when { it.modifier.contains("left") -> -1; it.modifier.contains("right") -> 1; else -> 0 })
        }
    }
}

/**
 * Puts Poppy's model where the guide says, every frame, straight through Filament: the clip (walk or idle), then her
 * head turn, raised arms, sitting, and which eyes / mouth show (the GLB has each face in its own node; hidden = tiny scale).
 * In the model she faces +z and her own left is +x: node "ArmR" is at +x (her left arm), "ArmL" at −x.
 */
class PoppyRig(engine: Engine, private val node: ModelNode) {
    private val tm = engine.transformManager
    private val asset = node.modelInstance.asset
    private val animator = node.animator
    private fun inst(name: String): Int { val e = asset.getFirstEntityByName(name); return if (e == 0) 0 else tm.getInstance(e) }
    private val face = inst("Face"); private val body = inst("Body")
    private val armPlusX = inst("ArmR"); private val armMinusX = inst("ArmL")
    private val looks = listOf("EyeOpenL", "EyeOpenR", "EyeHappyL", "EyeHappyR", "EyeClosedL", "EyeClosedR", "MouthSmile", "MouthGrin", "MouthO").associateWith { inst(it) }
    private fun rest(i: Int) = FloatArray(16).also { if (i != 0) tm.getTransform(i, it) }
    private val faceRest = rest(face); private val armPlusRest = rest(armPlusX); private val armMinusRest = rest(armMinusX)
    val ok = face != 0 && body != 0

    init { Log.i("ZoharWalk", "poppy rig ok=$ok clips=${animator.animationCount} looks=${looks.count { it.value != 0 }}") }

    fun apply(p: PoppyGuide.Pose) {
        node.worldPosition = Position(p.ground.x.toFloat(), (p.ground.y + p.hop).toFloat(), p.ground.z.toFloat())
        val h = p.yaw / 2
        node.worldQuaternion = dev.romainguy.kotlin.math.Quaternion(0f, sin(h).toFloat(), 0f, cos(h).toFloat())
        if (animator.animationCount > p.clip) {
            val len = animator.getAnimationDuration(p.clip).toDouble().coerceAtLeast(0.01)
            animator.applyAnimation(p.clip, (p.clipTime % len).toFloat()); animator.updateBoneMatrices()
        }
        if (!ok) return
        if (p.sit > 0.01) tm.setTransform(body, M4.trs(0.0, -0.035 * p.sit, -0.015 * p.sit, 1 + 0.03 * p.sit, 1 - 0.06 * p.sit, 1 + 0.03 * p.sit))
        tm.setTransform(face, M4.mul(faceRest, M4.rotY(p.faceYaw)))
        if (p.armLeft > 0.02 && armPlusX != 0) tm.setTransform(armPlusX, M4.withRotation(armPlusRest, M4.mul(M4.rotZ(p.armLeft), M4.rotX(-0.35))))
        if (p.armRight > 0.02 && armMinusX != 0) tm.setTransform(armMinusX, M4.withRotation(armMinusRest, M4.mul(M4.rotZ(-p.armRight), M4.rotX(-0.35))))
        fun show(name: String, on: Boolean) { val i = looks[name] ?: 0; if (i != 0) tm.setTransform(i, M4.scale(if (on) 1.0 else 1e-4)) }
        for (s in listOf("L", "R")) {
            show("EyeOpen$s", p.eyes == PoppyGuide.Eyes.OPEN); show("EyeHappy$s", p.eyes == PoppyGuide.Eyes.HAPPY); show("EyeClosed$s", p.eyes == PoppyGuide.Eyes.CLOSED)
        }
        show("MouthSmile", p.mouth == PoppyGuide.Mouth.SMILE); show("MouthGrin", p.mouth == PoppyGuide.Mouth.GRIN); show("MouthO", p.mouth == PoppyGuide.Mouth.O)
    }
}

/** Small column-major 4x4 helpers for Filament transforms. */
internal object M4 {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
    fun scale(s: Double) = identity().also { it[0] = s.toFloat(); it[5] = s.toFloat(); it[10] = s.toFloat() }
    fun rotX(a: Double) = identity().also { val c = cos(a).toFloat(); val s = sin(a).toFloat(); it[5] = c; it[6] = s; it[9] = -s; it[10] = c }
    fun rotY(a: Double) = identity().also { val c = cos(a).toFloat(); val s = sin(a).toFloat(); it[0] = c; it[2] = -s; it[8] = s; it[10] = c }
    fun rotZ(a: Double) = identity().also { val c = cos(a).toFloat(); val s = sin(a).toFloat(); it[0] = c; it[1] = s; it[4] = -s; it[5] = c }
    fun trs(tx: Double, ty: Double, tz: Double, sx: Double, sy: Double, sz: Double) =
        identity().also { it[0] = sx.toFloat(); it[5] = sy.toFloat(); it[10] = sz.toFloat(); it[12] = tx.toFloat(); it[13] = ty.toFloat(); it[14] = tz.toFloat() }
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0 until 4) for (row in 0 until 4) { var s = 0f; for (k in 0 until 4) s += a[k * 4 + row] * b[c * 4 + k]; r[c * 4 + row] = s }
        return r
    }
    /** [rest]'s translation with the rotation [r] (the arms have no rest rotation of their own). */
    fun withRotation(rest: FloatArray, r: FloatArray): FloatArray = r.copyOf().also { it[12] = rest[12]; it[13] = rest[13]; it[14] = rest[14] }
}

/** Screen point just above Poppy's head (for her speech bubble and the confetti), or null when she is off screen. */
internal fun poppyHead(pj: Projector, pose: PoppyGuide.Pose): FloatArray? =
    pj.screen(V3(pose.ground.x, pose.ground.y + pose.hop + PoppyGuide.HEIGHT + 0.08, pose.ground.z))
