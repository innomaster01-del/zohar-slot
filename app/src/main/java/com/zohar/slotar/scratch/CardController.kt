package com.zohar.slotar.scratch

import android.graphics.Bitmap
import android.graphics.Canvas
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

/** Drives the scratch-card GLB: uploads the live card face and maps world points / rays onto card pixels. */
class CardController(private val engine: Engine, instance: FilamentInstance) {
    private val tm = engine.transformManager
    private val rm = engine.renderableManager
    private var faceEntity = 0
    private var rimMaterials = ArrayList<MaterialInstance>()
    private val tex: Texture = Texture.Builder().width(ScratchLayout.W).height(ScratchLayout.H).levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8).build(engine)
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private val free = ArrayDeque<ByteBuffer>().apply { repeat(3) { add(ByteBuffer.allocateDirect(ScratchLayout.W * ScratchLayout.H * 4)) } }
    private val main = Handler(Looper.getMainLooper())
    private val flipped = Bitmap.createBitmap(ScratchLayout.W, ScratchLayout.H, Bitmap.Config.ARGB_8888)
    private val flipCanvas = Canvas(flipped)
    private val flipMatrix = android.graphics.Matrix().apply { setScale(1f, -1f); postTranslate(0f, ScratchLayout.H.toFloat()) }
    var ok = false; private set

    init {
        val asset = instance.asset
        for (e in instance.entities) {
            val name = asset.getName(e) ?: continue
            if (name == "CardFace" && rm.hasComponent(e)) {
                faceEntity = e
                val ri = rm.getInstance(e)
                for (p in 0 until rm.getPrimitiveCount(ri)) rm.getMaterialInstanceAt(ri, p).setParameter("baseColorMap", tex, sampler)
            }
            if (name == "CardRim" && rm.hasComponent(e)) { val ri = rm.getInstance(e); for (p in 0 until rm.getPrimitiveCount(ri)) rimMaterials.add(rm.getMaterialInstanceAt(ri, p)) }
        }
        ok = faceEntity != 0
        Log.i("ZoharCard", "card face=${faceEntity != 0} rim=${rimMaterials.size}")
    }

    fun upload(bitmap: Bitmap): Boolean {
        val buf = free.pollFirst() ?: return false
        flipCanvas.drawBitmap(bitmap, flipMatrix, null)       // GLB UVs are three.js style (v up)
        buf.clear(); flipped.copyPixelsToBuffer(buf); buf.flip()
        tex.setImage(engine, 0, Texture.PixelBufferDescriptor(buf, Texture.Format.RGBA, Texture.Type.UBYTE, 1, 0, 0, 0, main) { free.addLast(buf) })
        return true
    }

    /** Glow the rim on wins (k = 0 normal … 3 bright). */
    fun rimGlow(k: Float) { for (m in rimMaterials) runCatching { m.setParameter("emissiveFactor", 0.9f * k, 0.7f * k, 0.2f * k) } }

    private fun inverseWorld(): FloatArray? {
        if (faceEntity == 0) return null
        val w = FloatArray(16); tm.getWorldTransform(tm.getInstance(faceEntity), w)
        val inv = FloatArray(16); return if (android.opengl.Matrix.invertM(inv, 0, w, 0)) inv else null
    }
    private fun toPixel(lx: Float, ly: Float): FloatArray? {
        val u = lx / FACE_W + 0.5f; val v = ly / FACE_H + 0.5f
        if (u < -0.05f || u > 1.05f || v < -0.05f || v > 1.05f) return null
        return floatArrayOf(u * ScratchLayout.W, (1f - v) * ScratchLayout.H)
    }
    /** Where a world-space ray meets the card, in card pixels (null if it misses). */
    fun rayToPixel(origin: FloatArray, dir: FloatArray): FloatArray? {
        val inv = inverseWorld() ?: return null
        val o = FloatArray(4); val d = FloatArray(4)
        android.opengl.Matrix.multiplyMV(o, 0, inv, 0, floatArrayOf(origin[0], origin[1], origin[2], 1f), 0)
        android.opengl.Matrix.multiplyMV(d, 0, inv, 0, floatArrayOf(dir[0], dir[1], dir[2], 0f), 0)
        if (kotlin.math.abs(d[2]) < 1e-6f) return null
        val t = -o[2] / d[2]; if (t < 0) return null
        return toPixel(o[0] + d[0] * t, o[1] + d[1] * t)
    }
    /** A world point (fingertip) close to the card surface → card pixel, else null. */
    fun pointToPixel(p: FloatArray, maxDist: Float = 0.03f): FloatArray? {
        val inv = inverseWorld() ?: return null
        val l = FloatArray(4); android.opengl.Matrix.multiplyMV(l, 0, inv, 0, floatArrayOf(p[0], p[1], p[2], 1f), 0)
        if (kotlin.math.abs(l[2]) > maxDist) return null
        return toPixel(l[0], l[1])
    }
    /** Where a world ray meets the (unbounded) card plane, in world space. */
    fun rayToWorld(origin: FloatArray, dir: FloatArray): FloatArray? {
        val inv = inverseWorld() ?: return null
        val o = FloatArray(4); val d = FloatArray(4)
        android.opengl.Matrix.multiplyMV(o, 0, inv, 0, floatArrayOf(origin[0], origin[1], origin[2], 1f), 0)
        android.opengl.Matrix.multiplyMV(d, 0, inv, 0, floatArrayOf(dir[0], dir[1], dir[2], 0f), 0)
        if (kotlin.math.abs(d[2]) < 1e-6f) return null
        val t = -o[2] / d[2]; if (t < 0) return null
        val w = FloatArray(16); tm.getWorldTransform(tm.getInstance(faceEntity), w)
        val out = FloatArray(4); android.opengl.Matrix.multiplyMV(out, 0, w, 0, floatArrayOf(o[0] + d[0] * t, o[1] + d[1] * t, 0f, 1f), 0)
        return floatArrayOf(out[0], out[1], out[2])
    }
    /** Card pixel → world point on the face (self-tests). */
    fun pixelToWorld(px: Float, py: Float): FloatArray? {
        if (faceEntity == 0) return null
        val lx = (px / ScratchLayout.W - 0.5f) * FACE_W; val ly = (0.5f - py / ScratchLayout.H) * FACE_H
        val w = FloatArray(16); tm.getWorldTransform(tm.getInstance(faceEntity), w)
        val out = FloatArray(4); android.opengl.Matrix.multiplyMV(out, 0, w, 0, floatArrayOf(lx, ly, 0f, 1f), 0)
        return floatArrayOf(out[0], out[1], out[2])
    }
    /** World position of the card centre (for effects and the virtual hand depth). */
    fun center(): FloatArray? { if (faceEntity == 0) return null; val w = FloatArray(16); tm.getWorldTransform(tm.getInstance(faceEntity), w); return floatArrayOf(w[12], w[13], w[14]) }

    fun destroy() { runCatching { engine.destroyTexture(tex) } }

    companion object { const val FACE_W = 0.206f; const val FACE_H = 0.296f }
}
