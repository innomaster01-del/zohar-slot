package com.zohar.slotar

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import java.io.ByteArrayOutputStream

/** CI only: grabs what the 3D view really rendered (PixelCopy of the SurfaceView) and prints it to logcat as base64 JPEG. */
object DebugCapture {
    private fun find(v: View): View? {
        if (v is SurfaceView || v is TextureView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }
    fun shoot(activity: Activity, name: String, width: Int = 360) {
        val v = find(activity.window.decorView) ?: run { Log.w("ZoharImg", "$name no 3D view"); return }
        if (v.width <= 0 || v.height <= 0) return
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val done: (Bitmap) -> Unit = { b ->
            val h = (b.height * width / b.width.toFloat()).toInt()
            val small = Bitmap.createScaledBitmap(b, width, h, true)
            val bos = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 75, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP).chunked(3000).forEachIndexed { k, c -> Log.i("ZoharImg", "$name $k $c") }
            Log.i("ZoharImg", "$name end")
        }
        when (v) {
            is TextureView -> v.bitmap?.let(done)
            is SurfaceView -> PixelCopy.request(v, bmp, { r -> if (r == PixelCopy.SUCCESS) done(bmp) else Log.w("ZoharImg", "$name pixelcopy=$r") }, Handler(Looper.getMainLooper()))
        }
    }
}
