package com.zohar.slotar.walk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where a map point lands on a Mapbox static image: Web Mercator (512 px tiles) around [center] at [zoom], the map turned
 * so that [bearing] (degrees, clockwise from north) points up. Returns logical pixels from the image's top-left.
 */
internal object StaticMapProjection {
    private fun world(p: LatLng, zoom: Double): DoubleArray {
        val size = 512.0 * Math.pow(2.0, zoom); val lat = Math.toRadians(p.lat.coerceIn(-85.0, 85.0))
        return doubleArrayOf((p.lng + 180) / 360 * size, (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * size)
    }
    fun project(p: LatLng, center: LatLng, zoom: Double, bearing: Double, w: Double, h: Double): DoubleArray {
        val a = world(p, zoom); val c = world(center, zoom)
        val dx = a[0] - c[0]; val dy = a[1] - c[1]; val b = Math.toRadians(bearing)
        return doubleArrayOf(w / 2 + dx * cos(b) + dy * sin(b), h / 2 - dx * sin(b) + dy * cos(b))
    }
}

/**
 * The map that slides up from the bottom with a tap: a frosted-glass card, the street map turned the way you face,
 * the route drawn as Poppy's paw prints (the part you already walked faded), Poppy a few metres ahead of you, your
 * arrow, and the destination flag. Drag it down (or tap the handle) to close.
 */
@Composable
internal fun MapSheet(w: Words, api: MapboxApi, route: Route?, along: Double, poppyAlong: Double, at: () -> LatLng?, heading: () -> Double,
                      gender: String, remaining: String, onClose: () -> Unit) {
    val dens = LocalDensity.current.density
    var box by remember { mutableStateOf(IntSize.Zero) }
    var map by remember { mutableStateOf<Bitmap?>(null) }
    var mapAt by remember { mutableStateOf<LatLng?>(null) }
    var mapBearing by remember { mutableStateOf(0.0) }
    var drag by remember { mutableFloatStateOf(0f) }
    val zoom = 16.4
    val sheetShape = DomeShape(0.26f, 30.dp)                                   // a dome rising from the bottom, like a window onto the map
    val tint = if (gender == "boy") Color(0xFF5FB3FF) else Color(0xFFFF7FBF)
    // the map image follows you: a new one after 12 m, a turn of 25°, or 8 s
    LaunchedEffect(box) {
        if (box.width == 0) return@LaunchedEffect
        val wl = (box.width / dens).roundToInt().coerceIn(120, 640); val hl = (box.height / dens).roundToInt().coerceIn(120, 640)
        var lastT = 0L
        while (true) {
            val p = at(); val hd = heading().let { if (it.isNaN()) 0.0 else it }
            val moved = mapAt?.let { a -> p != null && Geo.distance(a, p) > 12 } ?: true
            val turned = kotlin.math.abs(GeoAlign.wrap(Math.toRadians(hd - mapBearing))) > Math.toRadians(25.0)
            if (p != null && (moved || turned || SystemClock.uptimeMillis() - lastT > 8000)) {
                lastT = SystemClock.uptimeMillis()
                runCatching { withContext(Dispatchers.IO) { URL(api.staticMapUrl(null, p, zoom, hd, wl, hl)).openStream().use { BitmapFactory.decodeStream(it) } } }
                    .onSuccess { map = it; mapAt = p; mapBearing = hd }.onFailure { Log.w("ZoharWalk", "sheet map: ${it.message}") }
            }
            delay(700)
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).graphicsLayer { translationY = drag }
            .shadow(24.dp, sheetShape)
            .clip(sheetShape)
            .background(Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0xCC111A2C), Color(0xE60B1220))))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x99FFFFFF), Color(0x1AFFFFFF))), sheetShape)
            .pointerInput(Unit) { detectVerticalDragGestures(onDragEnd = { if (drag > 140f) onClose(); drag = 0f }, onDragCancel = { drag = 0f }) { _, dy -> drag = (drag + dy).coerceAtLeast(0f) } }
            .padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 2.dp).width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x99FFFFFF)).clickable { onClose() })
        Box(Modifier.fillMaxWidth().height(350.dp).clip(DomeShape(0.24f, 22.dp)).background(Color(0xFF0B1220)).onSizeChanged { box = it }) {
            Canvas(Modifier.fillMaxWidth().height(350.dp)) {
                val bm = map; val center = mapAt
                if (bm != null && center != null) {
                    drawImage(bm.asImageBitmap(), dstSize = androidx.compose.ui.unit.IntSize(size.width.roundToInt(), size.height.roundToInt()))
                    val wl = size.width / dens; val hl = size.height / dens
                    fun px(p: LatLng): Offset { val q = StaticMapProjection.project(p, center, zoom, mapBearing, wl.toDouble(), hl.toDouble()); return Offset((q[0] * dens).toFloat(), (q[1] * dens).toFloat()) }
                    val r = route
                    if (r != null) {
                        // the route as paw prints, every few metres, left-right; the walked part faded
                        val step = 7.0; var k = 0; var s = 0.0
                        while (s <= r.length) {
                            val a = Geo.fromEnu(r.line.origin, r.line.at(s)); val b = Geo.fromEnu(r.line.origin, r.line.at(s + 1.0))
                            val pa = px(a); val pb = px(b)
                            if (pa.x > -20 && pa.y > -20 && pa.x < size.width + 20 && pa.y < size.height + 20) {
                                val ang = atan2((pb.y - pa.y).toDouble(), (pb.x - pa.x).toDouble()).toFloat()
                                val side = if (k % 2 == 0) 1f else -1f
                                val off = Offset(-sin(ang) * side * 7f, cos(ang) * side * 7f)
                                paw(pa + off, ang, 6.5f * dens / 2.6f, tint.copy(alpha = if (s < along) 0.35f else 0.95f))
                            }
                            s += step; k++
                        }
                        // destination flag
                        val d = px(Geo.fromEnu(r.line.origin, r.line.at(r.length)))
                        drawLine(Color.White, d, d + Offset(0f, -34f), 4f)
                        drawPath(Path().apply { moveTo(d.x, d.y - 34f); lineTo(d.x + 26f, d.y - 27f); lineTo(d.x, d.y - 19f); close() }, Color(0xFFF5B841))
                        drawCircle(Color(0xFFF5B841), 6f, d)
                        // Poppy a few metres ahead of you
                        if (!poppyAlong.isNaN()) miniPoppy(px(Geo.fromEnu(r.line.origin, r.line.at(poppyAlong))), tint, 1f)
                    }
                    // you: an arrow pointing the way you face (always up, the map turns with you)
                    val me = at()?.let { px(it) } ?: Offset(size.width / 2, size.height / 2)
                    drawCircle(Color(0x5522D3EE), 28f, me)
                    drawPath(Path().apply { moveTo(me.x, me.y - 18f); lineTo(me.x + 13f, me.y + 13f); lineTo(me.x, me.y + 6f); lineTo(me.x - 13f, me.y + 13f); close() }, Color.White)
                    drawPath(Path().apply { moveTo(me.x, me.y - 13f); lineTo(me.x + 9f, me.y + 9f); lineTo(me.x, me.y + 4f); lineTo(me.x - 9f, me.y + 9f); close() }, Color(0xFF22D3EE))
                }
            }
            if (map == null) Text(w.t("טוען מפה…", "Loading the map…"), color = Color(0xFF9FB0C8), fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(remaining, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(w.t("סגירה", "Close"), color = Color(0xFF0B1220), fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).clickable { onClose() }.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/** One paw print: a pad and three toes, pointing along [angle] (radians, screen). */
private fun DrawScope.paw(c: Offset, angle: Float, s: Float, color: Color) {
    rotate(degrees = Math.toDegrees(angle.toDouble()).toFloat() + 90f, pivot = c) {
        drawOval(color, Offset(c.x - s * 1.15f, c.y - s * 0.5f), Size(s * 2.3f, s * 1.9f))
        for ((dx, dy) in listOf(-1.25f to -1.35f, 0f to -1.85f, 1.25f to -1.35f)) drawCircle(color, s * 0.55f, Offset(c.x + dx * s, c.y + dy * s))
    }
}

/** A tiny Poppy for the map: a fluffy ball with two long ears and two eyes, outlined in white. */
private fun DrawScope.miniPoppy(c: Offset, tint: Color, k: Float) {
    val r = 15f * k
    for (sx in listOf(-1f, 1f)) {
        drawOval(Color.White, Offset(c.x + sx * r * 0.42f - r * 0.32f, c.y - r * 2.05f), Size(r * 0.64f, r * 1.5f))
        drawOval(tint, Offset(c.x + sx * r * 0.42f - r * 0.24f, c.y - r * 1.97f), Size(r * 0.48f, r * 1.34f))
    }
    drawCircle(Color.White, r + 3f, c); drawCircle(tint, r, c)
    for (sx in listOf(-1f, 1f)) { drawCircle(Color(0xFF1C1622), r * 0.2f, Offset(c.x + sx * r * 0.36f, c.y - r * 0.05f)); drawCircle(Color.White, r * 0.07f, Offset(c.x + sx * r * 0.36f + 1.5f, c.y - r * 0.12f)) }
    drawCircle(Color(0x66FF7BA8), r * 0.14f, Offset(c.x - r * 0.62f, c.y + r * 0.25f)); drawCircle(Color(0x66FF7BA8), r * 0.14f, Offset(c.x + r * 0.62f, c.y + r * 0.25f))
    drawArc(Color(0xFF2A1A24), 20f, 140f, false, Offset(c.x - r * 0.22f, c.y + r * 0.1f), Size(r * 0.44f, r * 0.3f), style = Stroke(width = 2.5f))
}
