package com.zohar.slotar.ar

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.gltfio.FilamentInstance
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Drives the cabinet GLB inside Filament: live reel-screen texture, chasing light pipes (LED_0/1/2),
 * pulsing SPIN cap, the pull lever (LeverPivot), and world positions of the tappable parts.
 */
class MachineController(private val engine: Engine, private val instance: FilamentInstance) {
    private val tm = engine.transformManager
    private val rm = engine.renderableManager
    private val asset = instance.asset
    private val byName = HashMap<String, Int>()
    private val materials = HashMap<String, MutableList<MaterialInstance>>()

    private val screenTex: Texture = Texture.Builder().width(SW).height(SH).levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8).build(engine)
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    // upload buffers in flight: Filament reads them asynchronously, so a buffer is reused only after its callback
    private val freeBuffers = ArrayDeque<ByteBuffer>().apply { repeat(3) { add(ByteBuffer.allocateDirect(SW * SH * 4)) } }
    private val main = Handler(Looper.getMainLooper())

    private var leverRest = FloatArray(16)
    private val buttonRest = HashMap<String, FloatArray>()
    private val pressT = HashMap<String, Float>()
    private var leverEntity = 0
    var ok = false; private set

    init {
        for (e in instance.entities) asset.getName(e)?.let { if (!byName.containsKey(it)) byName[it] = e }
        for (e in instance.entities) {
            if (!rm.hasComponent(e)) continue
            val ri = rm.getInstance(e)
            for (p in 0 until rm.getPrimitiveCount(ri)) {
                val mi = rm.getMaterialInstanceAt(ri, p)
                materials.getOrPut(mi.name ?: "") { ArrayList() }.add(mi)
            }
        }
        // gltfio may not carry material names: fall back to the entity that owns the material
        for ((mat, ent) in listOf("ScreenImage" to "Screen", "LED_0" to "Merged_LED_0", "LED_1" to "Merged_LED_1", "LED_2" to "Merged_LED_2", "Cap_Spin" to "SpinButton", "Candle_A" to "Merged_Candle_A", "Candle_B" to "Merged_Candle_B")) {
            if (materials.containsKey(mat)) continue
            val e = byName[ent] ?: continue
            if (!rm.hasComponent(e)) continue
            val ri = rm.getInstance(e)
            materials[mat] = (0 until rm.getPrimitiveCount(ri)).map { rm.getMaterialInstanceAt(ri, it) }.toMutableList()
        }
        materials["ScreenImage"]?.forEach { it.setParameter("baseColorMap", screenTex, sampler) }
        for (b in BUTTONS) byName[b]?.let { e -> val m = FloatArray(16); tm.getTransform(tm.getInstance(e), m); buttonRest[b] = m }
        leverEntity = byName["LeverPivot"] ?: 0
        if (leverEntity != 0) tm.getTransform(tm.getInstance(leverEntity), leverRest)
        ok = materials.containsKey("ScreenImage") && leverEntity != 0
        Log.i(TAG, "parts=${byName.keys.sorted()} materials=${materials.keys.sorted()} ok=$ok")
    }

    /** Upload the reel screen. Skips the frame (returns false) if all buffers are still in flight. */
    // the GLB's screen UVs follow three.js (v = 0 at the bottom), so rows are uploaded bottom-first
    private val flipped: Bitmap = Bitmap.createBitmap(SW, SH, Bitmap.Config.ARGB_8888)
    private val flipCanvas = android.graphics.Canvas(flipped)
    private val flipMatrix = android.graphics.Matrix().apply { setScale(1f, -1f); postTranslate(0f, SH.toFloat()) }
    fun uploadScreen(bitmap: Bitmap): Boolean {
        val buf = freeBuffers.pollFirst() ?: return false
        flipCanvas.drawBitmap(bitmap, flipMatrix, null)
        buf.clear(); flipped.copyPixelsToBuffer(buf); buf.flip()
        val pbd = Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE, 1, 0, 0, 0, main) { freeBuffers.addLast(buf) }
        screenTex.setImage(engine, 0, pbd)
        return true
    }

    enum class Mood { IDLE, SPIN, WIN, FREE, BIG }
    private var t = 0.0
    private val moodColors = mapOf(
        Mood.IDLE to listOf(floatArrayOf(1f, 0.31f, 0.85f), floatArrayOf(1f, 0.83f, 0.3f), floatArrayOf(0.54f, 0.36f, 1f)),
        Mood.SPIN to listOf(floatArrayOf(0.4f, 0.7f, 1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0.5f, 0.4f, 1f)),
        Mood.WIN to listOf(floatArrayOf(1f, 0.85f, 0.3f), floatArrayOf(1f, 0.6f, 0.1f), floatArrayOf(1f, 0.95f, 0.6f)),
        Mood.FREE to listOf(floatArrayOf(1f, 0.3f, 0.8f), floatArrayOf(0.7f, 0.3f, 1f), floatArrayOf(1f, 0.6f, 0.95f)),
        Mood.BIG to listOf(floatArrayOf(1f, 1f, 0.8f), floatArrayOf(1f, 0.8f, 0.2f), floatArrayOf(1f, 0.4f, 0.1f)),
    )

    /** Light pipes chase in 3 phases; speed/colour follow the game mood. leverDeg = absolute lever angle. */
    fun update(dt: Double, mood: Mood, leverDeg: Float, spinGlow: Float) {
        t += dt
        val speed = when (mood) { Mood.IDLE -> 5.0; Mood.SPIN -> 16.0; Mood.WIN -> 10.0; Mood.FREE -> 12.0; Mood.BIG -> 22.0 }
        val cols = moodColors.getValue(mood)
        val strobe = if (mood == Mood.BIG) (0.55 + 0.45 * sin(t * 30)).toFloat() else 1f
        for (i in 0..2) {
            // phase i is brightest when the chase position passes it
            val ph = ((t * speed / 3.0 + i / 3.0) % 1.0)
            val k = (0.25 + 0.75 * max(0.0, cos(ph * 2 * PI))).toFloat() * strobe * 4f
            val c = cols[i]
            materials["LED_$i"]?.forEach { it.setParameter("emissiveFactor", c[0] * k, c[1] * k, c[2] * k) }
        }
        // candle: steady when idle, flashing on wins / free spins
        val ca = when (mood) { Mood.BIG -> if (sin(t * 18) > 0) 3f else 0.3f; Mood.WIN, Mood.FREE -> if (sin(t * 8) > 0) 2.2f else 0.6f; else -> 1.1f }
        materials["Candle_A"]?.forEach { it.setParameter("emissiveFactor", ca, ca * 0.94f, ca * 0.8f) }
        val cb = when (mood) { Mood.BIG -> if (sin(t * 18) <= 0) 3f else 0.3f; Mood.FREE -> if (sin(t * 8) <= 0) 2.2f else 0.6f; else -> 0.9f }
        materials["Candle_B"]?.forEach { it.setParameter("emissiveFactor", cb, cb * 0.16f, cb * 0.33f) }
        // pressed buttons sink 5 mm and come back
        val done = ArrayList<String>()
        for ((b, k0) in pressT) {
            val k = k0 + dt.toFloat(); pressT[b] = k
            val down = if (k < 0.06f) k / 0.06f else max(0f, 1f - (k - 0.06f) / 0.14f)
            val bn = nodes[b]; val br = nodeRest[b]
            if (bn != null && br != null) {
                bn.position = io.github.sceneview.math.Position(br.x, br.y - 0.006f * down, br.z)
                if (k > 0.2f) { bn.position = br; done.add(b) }
                continue
            }
            val e = byName[b] ?: continue; val rest = buttonRest[b] ?: continue
            val m = rest.copyOf(); m[12] -= rest[4] * 0.005f * down; m[13] -= rest[5] * 0.005f * down; m[14] -= rest[6] * 0.005f * down
            tm.setTransform(tm.getInstance(e), m)
            if (k > 0.2f) { tm.setTransform(tm.getInstance(e), rest); done.add(b) }
        }
        done.forEach { pressT.remove(it) }
        val s = 0.6f + 1.4f * spinGlow
        materials["Cap_Spin"]?.forEach { it.setParameter("emissiveFactor", 0.1f * s, 1f * s, 0.42f * s) }
        // lever: keep the authored translation, replace the rotation about X
        val ln = nodes["LeverPivot"]
        if (ln != null) ln.rotation = io.github.sceneview.math.Rotation(x = leverDeg, y = 0f, z = 0f)
        else if (leverEntity != 0) {
            val m = leverRest.copyOf(); val a = Math.toRadians(leverDeg.toDouble()); val c = cos(a).toFloat(); val sn = sin(a).toFloat()
            m[0] = 1f; m[1] = 0f; m[2] = 0f; m[4] = 0f; m[5] = c; m[6] = sn; m[8] = 0f; m[9] = -sn; m[10] = c
            tm.setTransform(tm.getInstance(leverEntity), m)
        }
    }

    /** World position (x,y,z) of a named part, or null. */
    fun worldPos(name: String): FloatArray? {
        val e = byName[name] ?: return null
        val w = FloatArray(16); tm.getWorldTransform(tm.getInstance(e), w)
        return floatArrayOf(w[12], w[13], w[14])
    }
    /** World position of a point given in a part's local space. */
    fun worldPoint(name: String, lx: Float, ly: Float, lz: Float): FloatArray? {
        val e = byName[name] ?: return null
        val w = FloatArray(16); tm.getWorldTransform(tm.getInstance(e), w)
        return floatArrayOf(w[0] * lx + w[4] * ly + w[8] * lz + w[12], w[1] * lx + w[5] * ly + w[9] * lz + w[13], w[2] * lx + w[6] * ly + w[10] * lz + w[14])
    }

    /** SceneView wraps every glTF node in its own Node and caches transforms: drive the moving parts through those. */
    private val nodes = HashMap<String, io.github.sceneview.node.Node>()
    private val nodeRest = HashMap<String, dev.romainguy.kotlin.math.Float3>()
    fun attach(model: io.github.sceneview.node.ModelNode) {
        nodes.clear(); nodeRest.clear()
        for (n in model.nodes) { val nm = (n as? io.github.sceneview.node.ModelNode.ChildNode)?.name ?: continue; if (!nodes.containsKey(nm)) { nodes[nm] = n; nodeRest[nm] = n.position } }
        Log.i(TAG, "attached nodes: lever=${nodes.containsKey("LeverPivot")} buttons=${BUTTONS.count { nodes.containsKey(it) }}")
    }

    fun press(name: String) { if (buttonRest.containsKey(name)) pressT[name] = 0f }

    /**
     * Which tappable part does a ray (world space) hit? Tests the ray against each part's bounding box in the part's
     * own space, enlarged a little so a fingertip-sized tap still lands. Returns the nearest part or null.
     */
    fun pick(origin: FloatArray, dir: FloatArray, parts: List<String> = TAPPABLE): String? {
        var best: String? = null; var bestT = Float.MAX_VALUE
        val box = com.google.android.filament.Box(); val w = FloatArray(16); val inv = FloatArray(16)
        for (name in parts) {
            val e = byName[name] ?: continue
            if (!rm.hasComponent(e)) continue
            rm.getAxisAlignedBoundingBox(rm.getInstance(e), box)
            tm.getWorldTransform(tm.getInstance(e), w)
            if (!android.opengl.Matrix.invertM(inv, 0, w, 0)) continue
            val o = FloatArray(4); val d = FloatArray(4)
            android.opengl.Matrix.multiplyMV(o, 0, inv, 0, floatArrayOf(origin[0], origin[1], origin[2], 1f), 0)
            android.opengl.Matrix.multiplyMV(d, 0, inv, 0, floatArrayOf(dir[0], dir[1], dir[2], 0f), 0)
            val c = box.center; val h = box.halfExtent
            val pad = if (name == "Screen") 0.0f else 0.025f
            var t0 = 0f; var t1 = Float.MAX_VALUE; var hit = true
            for (k in 0..2) {
                val lo = c[k] - h[k] * 1.15f - pad; val hi = c[k] + h[k] * 1.15f + pad
                if (kotlin.math.abs(d[k]) < 1e-7f) { if (o[k] < lo || o[k] > hi) { hit = false; break } }
                else { var a = (lo - o[k]) / d[k]; var b = (hi - o[k]) / d[k]; if (a > b) { val tt = a; a = b; b = tt }; t0 = max(t0, a); t1 = kotlin.math.min(t1, b); if (t0 > t1) { hit = false; break } }
            }
            // buttons sit in front of the screen's box: prefer them on a near tie
            val score = if (name == "Screen") t0 + 0.05f else t0
            if (hit && score < bestT) { bestT = score; best = name }
        }
        return best
    }

    fun destroy() { runCatching { engine.destroyTexture(screenTex) } }

    companion object {
        const val TAG = "ZoharMachine"
        const val SW = 720
        const val SH = 1200
        const val LEVER_REST = -12f
        const val LEVER_PULLED = 58f
        val BUTTONS = listOf("SpinButton", "BetDownButton", "BetUpButton", "MaxBetButton", "AutoButton")
        val TAPPABLE = BUTTONS + listOf("LeverKnob", "LeverShaft", "Screen")
    }
}
