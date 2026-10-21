package com.zohar.slotar.ar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import io.github.sceneview.ar.arcore.cameraImage
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds the user's hand in the AR camera image (MediaPipe Hand Landmarker, on-device) and reports fingertips
 * in VIEW-normalized coordinates (0..1, same space as the screen), so they can be compared with where the
 * lever knob is drawn. Runs on a worker thread at ~15 fps; never blocks the render loop.
 */
class HandTracker(context: Context, private val onHand: (Hand?) -> Unit) : AutoCloseable {
    /** tips: index, middle, thumb (x,y view-normalized). */
    data class Hand(val index: FloatArray, val middle: FloatArray, val thumb: FloatArray, val timeMs: Long)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "zohar-hands") }
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var lastSubmit = 0L
    private var lastTs = 0L

    private val landmarker: HandLandmarker? = runCatching {
        HandLandmarker.createFromOptions(context, HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("mediapipe/hand_landmarker.task").setDelegate(Delegate.GPU).build())
            .setRunningMode(RunningMode.VIDEO).setNumHands(1)
            .setMinHandDetectionConfidence(0.45f).setMinHandPresenceConfidence(0.45f).setMinTrackingConfidence(0.45f).build())
    }.recoverCatching {
        Log.w(TAG, "GPU delegate failed, using CPU", it)
        HandLandmarker.createFromOptions(context, HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("mediapipe/hand_landmarker.task").build())
            .setRunningMode(RunningMode.VIDEO).setNumHands(1).build())
    }.onFailure { Log.e(TAG, "HandLandmarker unavailable", it) }.getOrNull()

    val available get() = landmarker != null

    // camera planes copied on the GL thread, converted on the worker
    private var y = ByteArray(0); private var u = ByteArray(0); private var v = ByteArray(0)
    private var yStride = 0; private var uvStride = 0; private var uvPix = 0; private var iw = 0; private var ih = 0
    private val corners = FloatArray(6)                 // image-normalized coords of view (0,0), (1,0), (0,1)
    private var outW = 192; private var outH = 384
    private var rgb = ByteArray(0); private var rgbDirect: ByteBuffer = ByteBuffer.allocateDirect(0)

    fun onFrame(frame: Frame, viewW: Int, viewH: Int) {
        val lm = landmarker ?: return
        if (closed.get() || viewW <= 0 || viewH <= 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSubmit < 60 || !busy.compareAndSet(false, true)) return
        val ok = runCatching {
            frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), Coordinates2d.IMAGE_NORMALIZED, corners)
            val img = frame.cameraImage() ?: return@runCatching false
            img.use {
                val p = it.planes
                y = copy(p[0].buffer, y); u = copy(p[1].buffer, u); v = copy(p[2].buffer, v)
                yStride = p[0].rowStride; uvStride = p[1].rowStride; uvPix = p[1].pixelStride; iw = it.width; ih = it.height
            }
            outW = 192; outH = (192f * viewH / viewW).toInt().coerceIn(192, 480)
            true
        }.getOrElse { Log.w(TAG, "frame copy failed", it); false }
        if (!ok) { busy.set(false); return }
        lastSubmit = now
        val ts = maxOf(now, lastTs + 1); lastTs = ts
        worker.execute { detect(lm, ts) }
    }

    private fun copy(b: ByteBuffer, dst: ByteArray): ByteArray {
        val out = if (dst.size == b.remaining()) dst else ByteArray(b.remaining()); b.get(out); b.rewind(); return out
    }

    private fun detect(lm: HandLandmarker, ts: Long) {
        try {
            if (closed.get()) return
            val need = outW * outH * 3
            if (rgb.size != need) { rgb = ByteArray(need); rgbDirect = ByteBuffer.allocateDirect(need) }
            // upright view-aligned RGB: each output pixel = view point (x,y) → camera image via the affine corner map
            val ax = corners[0]; val ay = corners[1]; val bx = corners[2] - ax; val by = corners[3] - ay; val cx = corners[4] - ax; val cy = corners[5] - ay
            var o = 0
            for (j in 0 until outH) {
                val vy = (j + 0.5f) / outH
                for (i in 0 until outW) {
                    val vx = (i + 0.5f) / outW
                    val px = ((ax + vx * bx + vy * cx) * iw).toInt().coerceIn(0, iw - 1)
                    val py = ((ay + vx * by + vy * cy) * ih).toInt().coerceIn(0, ih - 1)
                    val yy = (y[py * yStride + px].toInt() and 0xff) - 16
                    val ci = (py / 2) * uvStride + (px / 2) * uvPix
                    val uu = (u[ci].toInt() and 0xff) - 128; val vv = (v[ci].toInt() and 0xff) - 128
                    val c = 1.164f * yy
                    rgb[o++] = (c + 1.596f * vv).toInt().coerceIn(0, 255).toByte()
                    rgb[o++] = (c - 0.392f * uu - 0.813f * vv).toInt().coerceIn(0, 255).toByte()
                    rgb[o++] = (c + 2.017f * uu).toInt().coerceIn(0, 255).toByte()
                }
            }
            rgbDirect.clear(); rgbDirect.put(rgb); rgbDirect.flip()
            val mp = ByteBufferImageBuilder(rgbDirect, outW, outH, MPImage.IMAGE_FORMAT_RGB).build()
            val res = lm.detectForVideo(mp, ts)
            val hand = res.landmarks().firstOrNull()?.let { l ->
                fun p(k: Int) = floatArrayOf(l[k].x(), l[k].y())
                Hand(p(8), p(12), p(4), ts)
            }
            main.post { if (!closed.get()) onHand(hand) }
        } catch (t: Throwable) {
            Log.w(TAG, "hand detection failed", t)
        } finally { busy.set(false) }
    }

    override fun close() { closed.set(true); worker.execute { runCatching { landmarker?.close() } }; worker.shutdown() }

    companion object { const val TAG = "ZoharHands" }
}
