package com.zohar.slotar.blackjack

import android.util.Log
import com.google.android.filament.Engine
import io.github.sceneview.node.ModelNode
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Drives the dealer avatar (dealer.glb: Rocketbox Business_Male_04, MIT): clips with cross-fades, head that follows the
 * player, blinking, smiling and lip movement while he talks. Everything goes through Filament directly, every frame:
 * applyAnimation → (applyCrossFade) → head look-at → updateBoneMatrices → morph weights.
 */
class DealerRig(private val engine: Engine, val node: ModelNode) {
    private val inst = node.modelInstance
    private val animator = node.animator
    private val asset = inst.asset
    private val tm = engine.transformManager
    private val rm = engine.renderableManager
    private val clipIndex = HashMap<String, Int>()
    private var cur = -1; private var curStart = 0.0; private var curLoop = true
    private var prev = -1; private var prevTime = 0.0; private var fadeStart = 0.0; private val fadeDur = 0.28
    private var face = 0; private val morphNames = ArrayList<String>(); private var weights = FloatArray(0)
    private fun joint(n: String) = asset.getFirstEntityByName(n)
    private val jNeck = joint("Bip01_Neck"); private val jHead = joint("Bip01_Head")
    private val jHandL = joint("Bip01_L_Hand"); private val jHandR = joint("Bip01_R_Hand")
    private val jFingerL = joint("Bip01_L_Finger2"); private val jFingerR = joint("Bip01_R_Finger2")
    val ok: Boolean

    // face state
    var smile = 0f; private var smileNow = 0f
    var talking = false; private var mouth = 0f; private var mouthTarget = 0f; private var mouthShape = 0; private var mouthT = 0.0
    private var blinkAt = 1.5; private var blink = 0f
    /** World-space point the head turns to (the phone camera = the player's eyes). */
    var lookAt: FloatArray? = null
    private var lookW = 0f

    init {
        for (i in 0 until animator.animationCount) clipIndex[animator.getAnimationName(i)] = i
        for (e in inst.entities) {
            if (!rm.hasComponent(e)) continue
            val ri = rm.getInstance(e)
            runCatching { rm.setCulling(ri, false) }               // skinned + leaning: the bind-pose box would cull him
            val names = runCatching { asset.getMorphTargetNames(e) }.getOrNull()
            if (names != null && names.isNotEmpty() && face == 0) { face = e; morphNames.addAll(names); weights = FloatArray(names.size) }
        }
        ok = clipIndex.containsKey("Idle") && jHead != 0
        Log.i("ZoharBJ", "dealer clips=${clipIndex.keys} morphs=${morphNames.size} head=${jHead != 0}")
        play("Idle", 0.0, loop = true)
    }

    fun has(clip: String) = clipIndex.containsKey(clip)
    fun duration(clip: String) = clipIndex[clip]?.let { animator.getAnimationDuration(it).toDouble() } ?: 0.0
    /** Start a clip now (cross-fading from the current one). One-shot clips fall back to Idle when they end. */
    fun play(clip: String, now: Double, loop: Boolean = false) {
        val i = clipIndex[clip] ?: return
        if (cur >= 0) { prev = cur; prevTime = curTime(now); fadeStart = now }
        cur = i; curStart = now; curLoop = loop
    }
    private fun curTime(now: Double): Double {
        val d = animator.getAnimationDuration(cur).toDouble(); val t = now - curStart
        return if (curLoop) t % d else min(t, d)
    }
    val busyUntil get() = if (curLoop) 0.0 else curStart + animator.getAnimationDuration(cur)

    fun update(now: Double, dt: Float) {
        if (cur < 0) return
        // back to idle after a one-shot clip
        if (!curLoop && now - curStart >= animator.getAnimationDuration(cur) - fadeDur * 0.5) play(if ((now / 9).toInt() % 3 == 2 && has("Idle2")) "Idle2" else "Idle", now, loop = true)
        animator.applyAnimation(cur, curTime(now).toFloat())
        if (prev >= 0 && now - fadeStart < fadeDur) {
            val a = ((now - fadeStart) / fadeDur).toFloat()
            runCatching { animator.applyCrossFade(prev, prevTime.toFloat(), 1f - smoothstep(a)) }
        } else prev = -1
        lookAtPlayer(dt)
        animator.updateBoneMatrices()
        node.onWorldTransformChanged()
        faceUpdate(now, dt)
    }

    // ---- head follows the player: rotate neck (60 %) + head (40 %) so the face (+Y of the Biped head) points at the camera
    private fun lookAtPlayer(dt: Float) {
        val target = lookAt
        lookW += ((if (target != null) 1f else 0f) - lookW) * min(1f, dt * 3f)
        if (target == null && lookW < 0.01f || jHead == 0 || jNeck == 0) return
        val t = target ?: return
        val hw = world(jHead); val hp = floatArrayOf(hw[12], hw[13], hw[14])
        val fwd = norm(floatArrayOf(hw[4], hw[5], hw[6]))                    // head local +Y in world = where the face points
        val want = norm(floatArrayOf(t[0] - hp[0], t[1] - hp[1], t[2] - hp[2]))
        val axis = cross(fwd, want); val s = len(axis); if (s < 1e-5f) return
        var ang = acos(dot(fwd, want).coerceIn(-1f, 1f))
        ang = min(ang, 0.85f) * lookW                                         // ~49° max, eased in/out
        val ax = floatArrayOf(axis[0] / s, axis[1] / s, axis[2] / s)
        rotateJointWorld(jNeck, ax, ang * 0.6f)
        rotateJointWorld(jHead, ax, ang * 0.4f)
    }
    private fun rotateJointWorld(e: Int, axis: FloatArray, ang: Float) {
        val ti = tm.getInstance(e); if (ti == 0) return
        val parentEntity = tm.getParent(ti)
        val pw = if (parentEntity != 0) FloatArray(16).also { tm.getWorldTransform(tm.getInstance(parentEntity), it) } else ident()
        val local = FloatArray(16).also { tm.getTransform(ti, it) }
        val wOld = mul(pw, local)
        // rotate the joint's world orientation about its own position
        val r = rotMat(axis, ang)
        val rot3 = mul(r, wOld); rot3[12] = wOld[12]; rot3[13] = wOld[13]; rot3[14] = wOld[14]
        val inv = FloatArray(16); android.opengl.Matrix.invertM(inv, 0, pw, 0)
        tm.setTransform(ti, mul(inv, rot3))
    }
    /** World matrix of a joint; resolve the instance fresh (the animator can reindex the TransformManager). */
    private fun world(e: Int): FloatArray { val m = FloatArray(16); tm.getWorldTransform(tm.getInstance(e), m); return m }
    /** A point just above the dealer's head (for the speech bubble). */
    fun headWorld(): FloatArray? { if (jHead == 0) return null; val m = world(jHead); return floatArrayOf(m[12], m[13] + 0.24f, m[14]) }
    fun handWorld(left: Boolean): FloatArray? {
        val e = if (left) (if (jFingerL != 0) jFingerL else jHandL) else (if (jFingerR != 0) jFingerR else jHandR)
        if (e == 0) return null; val m = world(e); return floatArrayOf(m[12], m[13], m[14])
    }

    // ---- face: blink, smile, talk
    private fun faceUpdate(now: Double, dt: Float) {
        if (face == 0) return
        if (now > blinkAt) { blink = 1f; blinkAt = now + 2.2 + (now * 7919 % 1.0) * 3.0 }
        blink = max(0f, blink - dt * 7f)
        val b = if (blink > 0.5f) (1f - blink) * 2f else blink * 2f            // close then open
        smileNow += (smile - smileNow) * min(1f, dt * 4f)
        if (talking) {
            mouthT -= dt; if (mouthT <= 0) { mouthT = 0.07 + (now * 31 % 1.0) * 0.08; mouthTarget = if (mouthTarget > 0.2f) 0.05f else 0.35f + (now * 17 % 1.0).toFloat() * 0.45f; mouthShape = (mouthShape + 1 + (now * 13).toInt() % 3) % 4 }
        } else mouthTarget = 0f
        mouth += (mouthTarget - mouth) * min(1f, dt * 18f)
        java.util.Arrays.fill(weights, 0f)
        set("AK_09_EyeBlinkLeft", b); set("AK_10_EyeBlinkRight", b)
        set("AK_44_MouthSmileLeft", smileNow * 0.7f); set("AK_45_MouthSmileRight", smileNow * 0.7f)
        set("AK_07_CheekSquintLeft", smileNow * 0.35f); set("AK_08_CheekSquintRight", smileNow * 0.35f)
        val vis = listOf("AA_VI_10_aa", "AA_VI_11_E", "AA_VI_13_O", "AA_VI_08_nn")[mouthShape]
        set(vis, mouth); set("AK_25_JawOpen", mouth * 0.25f)
        runCatching { rm.setMorphWeights(rm.getInstance(face), weights, 0) }
    }
    private fun set(n: String, v: Float) { val i = morphNames.indexOf(n); if (i >= 0) weights[i] = v.coerceIn(0f, 1f) }

    companion object {
        fun smoothstep(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        fun ident() = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        fun mul(a: FloatArray, b: FloatArray) = FloatArray(16).also { android.opengl.Matrix.multiplyMM(it, 0, a, 0, b, 0) }
        fun norm(v: FloatArray): FloatArray { val l = len(v).coerceAtLeast(1e-6f); return floatArrayOf(v[0] / l, v[1] / l, v[2] / l) }
        fun len(v: FloatArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun rotMat(axis: FloatArray, ang: Float) = FloatArray(16).also { android.opengl.Matrix.setRotateM(it, 0, Math.toDegrees(ang.toDouble()).toFloat(), axis[0], axis[1], axis[2]) }
        @Suppress("unused") private fun s(x: Float) = sin(x)
    }
}
