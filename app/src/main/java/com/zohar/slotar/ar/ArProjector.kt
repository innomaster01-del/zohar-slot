package com.zohar.slotar.ar

import android.opengl.Matrix
import com.google.ar.core.Frame

/** World ↔ screen-pixel mapping straight from ARCore's camera matrices (the same ones the AR view renders with). */
class ArProjector {
    private val view = FloatArray(16); private val proj = FloatArray(16); private val vp = FloatArray(16); private val inv = FloatArray(16)
    var w = 0; private set
    var h = 0; private set
    var valid = false; private set

    fun update(frame: Frame, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        frame.camera.getViewMatrix(view, 0); frame.camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        valid = Matrix.invertM(inv, 0, vp, 0); w = widthPx; h = heightPx
    }

    /** Screen pixels (x right, y down) of a world point, or null when behind the camera. */
    fun toScreen(p: FloatArray?): FloatArray? {
        if (p == null || !valid) return null
        val c = FloatArray(4); Matrix.multiplyMV(c, 0, vp, 0, floatArrayOf(p[0], p[1], p[2], 1f), 0)
        if (c[3] <= 1e-4f) return null
        return floatArrayOf((c[0] / c[3] + 1f) / 2f * w, (1f - c[1] / c[3]) / 2f * h)
    }

    /** World-space ray (origin, unit direction) through a screen pixel. */
    fun ray(x: Float, y: Float): Pair<FloatArray, FloatArray>? {
        if (!valid) return null
        val nx = x / w * 2f - 1f; val ny = 1f - y / h * 2f
        val a = FloatArray(4); val b = FloatArray(4)
        Matrix.multiplyMV(a, 0, inv, 0, floatArrayOf(nx, ny, -1f, 1f), 0)
        Matrix.multiplyMV(b, 0, inv, 0, floatArrayOf(nx, ny, 1f, 1f), 0)
        val o = floatArrayOf(a[0] / a[3], a[1] / a[3], a[2] / a[3]); val f = floatArrayOf(b[0] / b[3], b[1] / b[3], b[2] / b[3])
        val d = floatArrayOf(f[0] - o[0], f[1] - o[1], f[2] - o[2]); val l = kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        return o to floatArrayOf(d[0] / l, d[1] / l, d[2] / l)
    }
}
