package com.zohar.slotar

import android.graphics.RectF
import android.view.MotionEvent

/**
 * Every touch of the window, seen BEFORE Compose and the 3D view get it (Activity.dispatchTouchEvent).
 * The scene's own touch callback turned out not to receive taps on some setups, so machine taps / lever drags
 * are handled from here. Touches that start on the on-screen controls (blocked rects) are left to those controls.
 */
object TouchBus {
    @Volatile var handler: ((MotionEvent) -> Unit)? = null
    val blocked = HashMap<String, RectF>()
    private var gestureBlocked = false
    fun dispatch(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) gestureBlocked = blocked.values.any { it.contains(e.x, e.y) }
        if (!gestureBlocked) handler?.invoke(e)
    }
}
