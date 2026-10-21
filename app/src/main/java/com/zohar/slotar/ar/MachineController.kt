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
        for ((mat, ent) in listOf("ScreenImage" to "Screen", "LED_0" to "Merged_LED_0", "LED_1" to "Merged_LED_1", "LED_2" to "Merged_LED_2", "Cap_Spin" to "SpinButton")) {
            if (materials.containsKey(mat)) continue
            val e = byName[ent] ?: continue
            if (!rm.hasComponent(e)) continue
            val ri = rm.getInstance(e)
            materials[mat] = (0 until rm.getPrimitiveCount(ri)).map { rm.getMaterialInstanceAt(ri, it) }.toMutableList()
        }
        materials["ScreenImage"]?.forEach { it.setParameter("baseColorMap", screenTex, sampler) }
        leverEntity = byName["LeverPivot"] ?: 0
        if (leverEntity != 0) tm.getTransform(tm.getInstance(leverEntity), leverRest)
        ok = materials.containsKey("ScreenImage") && leverEntity != 0
        Log.i(TAG, "parts=${byName.keys.sorted()} materials=${materials.keys.sorted()} ok=$ok")
    }

    /** Upload the reel screen. Skips the frame (returns false) if all buffers are still in flight. */
    fun uploadScreen(bitmap: Bitmap): Boolean {
        val buf = freeBuffers.pollFirst() ?: return false
        buf.clear(); bitmap.copyPixelsToBuffer(buf); buf.flip()
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
        val s = 0.6f + 1.4f * spinGlow
        materials["Cap_Spin"]?.forEach { it.setParameter("emissiveFactor", 0.1f * s, 1f * s, 0.42f * s) }
        // lever: keep the authored translation, replace the rotation about X
        if (leverEntity != 0) {
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

    fun destroy() { runCatching { engine.destroyTexture(screenTex) } }

    companion object {
        const val TAG = "ZoharMachine"
        const val SW = 720
        const val SH = 1200
        const val LEVER_REST = -12f
        const val LEVER_PULLED = 58f
    }
}
