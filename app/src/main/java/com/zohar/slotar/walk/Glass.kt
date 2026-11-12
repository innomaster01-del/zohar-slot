package com.zohar.slotar.walk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Frosted glass: a translucent fill that is a little brighter at the top, and a hairline rim that catches light at the
 * top edge and fades out below. [level] is how heavy the material is (structure heavier, controls lighter).
 */
internal fun Modifier.glass(shape: Shape, level: Float = 1f, tint: Color = Color.White): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.13f * level), tint.copy(alpha = 0.06f * level))))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f))), shape)

/** Dark glass for panels over the camera: the camera shows through, text on it stays readable. */
internal fun Modifier.darkGlass(shape: Shape): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color(0xB3161E2E), Color(0xD90A0F1A))))
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.05f))), shape)

/** A dome: the top is half an ellipse across the full width (rising [rise] × width), the bottom has rounded corners. */
internal class DomeShape(private val rise: Float = 0.3f, private val corner: Dp = 28.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width; val h = size.height
        val a = (w * rise).coerceAtMost(h * 0.6f); val r = with(density) { corner.toPx() }.coerceAtMost(h / 4)
        val p = Path().apply {
            moveTo(0f, a)
            arcTo(Rect(0f, 0f, w, 2 * a), 180f, 180f, false)
            lineTo(w, h - r)
            arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            lineTo(r, h)
            arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
            close()
        }
        return Outline.Generic(p)
    }
}

/** Line icons drawn by hand (no emoji): coffee, food, pharmacy, supermarket, pin, search, arrow. */
@Composable
internal fun LineIcon(kind: String, color: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { lineIcon(kind, color) }
}

internal fun DrawScope.lineIcon(kind: String, color: Color) {
    val w = size.width; val h = size.height; val st = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
        "coffee" -> {
            drawPath(Path().apply { moveTo(w * 0.16f, h * 0.42f); lineTo(w * 0.70f, h * 0.42f); lineTo(w * 0.64f, h * 0.84f); lineTo(w * 0.22f, h * 0.84f); close() }, color, style = st)
            drawArc(color, -90f, 180f, false, Offset(w * 0.58f, h * 0.48f), Size(w * 0.26f, h * 0.22f), style = st)
            for (x in listOf(0.32f, 0.48f)) drawPath(Path().apply { moveTo(w * x, h * 0.30f); cubicTo(w * (x - 0.07f), h * 0.22f, w * (x + 0.07f), h * 0.16f, w * x, h * 0.08f) }, color, style = st)
        }
        "restaurant" -> {
            drawLine(color, Offset(w * 0.32f, h * 0.10f), Offset(w * 0.32f, h * 0.90f), st.width, StrokeCap.Round)
            for (x in listOf(0.20f, 0.44f)) drawLine(color, Offset(w * x, h * 0.10f), Offset(w * x, h * 0.32f), st.width, StrokeCap.Round)
            drawArc(color, 0f, 180f, false, Offset(w * 0.20f, h * 0.20f), Size(w * 0.24f, h * 0.24f), style = st)
            drawPath(Path().apply { moveTo(w * 0.70f, h * 0.90f); lineTo(w * 0.70f, h * 0.10f); cubicTo(w * 0.86f, h * 0.18f, w * 0.88f, h * 0.44f, w * 0.70f, h * 0.54f) }, color, style = st)
        }
        "pharmacy" -> {
            drawRoundRect(color, Offset(w * 0.14f, h * 0.14f), Size(w * 0.72f, h * 0.72f), CornerRadius(w * 0.2f), style = st)
            drawLine(color, Offset(w * 0.5f, h * 0.32f), Offset(w * 0.5f, h * 0.68f), st.width, StrokeCap.Round)
            drawLine(color, Offset(w * 0.32f, h * 0.5f), Offset(w * 0.68f, h * 0.5f), st.width, StrokeCap.Round)
        }
        "supermarket" -> {
            drawPath(Path().apply { moveTo(w * 0.08f, h * 0.16f); lineTo(w * 0.22f, h * 0.16f); lineTo(w * 0.32f, h * 0.64f); lineTo(w * 0.80f, h * 0.64f); lineTo(w * 0.88f, h * 0.30f); lineTo(w * 0.26f, h * 0.30f) }, color, style = st)
            drawCircle(color, w * 0.065f, Offset(w * 0.38f, h * 0.82f)); drawCircle(color, w * 0.065f, Offset(w * 0.74f, h * 0.82f))
        }
        "search" -> {
            drawCircle(color, w * 0.28f, Offset(w * 0.44f, h * 0.44f), style = st)
            drawLine(color, Offset(w * 0.65f, h * 0.65f), Offset(w * 0.86f, h * 0.86f), st.width, StrokeCap.Round)
        }
        "arrow" -> {                                                                   // points along the reading direction's end
            drawLine(color, Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f), st.width, StrokeCap.Round)
            drawPath(Path().apply { moveTo(w * 0.56f, h * 0.26f); lineTo(w * 0.8f, h * 0.5f); lineTo(w * 0.56f, h * 0.74f) }, color, style = st)
        }
        else -> {                                                                      // pin
            drawPath(Path().apply { moveTo(w * 0.5f, h * 0.92f); cubicTo(w * 0.18f, h * 0.58f, w * 0.18f, h * 0.38f, w * 0.18f, h * 0.38f); cubicTo(w * 0.18f, h * 0.10f, w * 0.82f, h * 0.10f, w * 0.82f, h * 0.38f); cubicTo(w * 0.82f, h * 0.38f, w * 0.82f, h * 0.58f, w * 0.5f, h * 0.92f); close() }, color, style = st)
            drawCircle(color, w * 0.09f, Offset(w * 0.5f, h * 0.38f), style = st)
        }
    }
}

/** Poppy as a small icon (a fluffy ball with two long ears and eyes), fitted to the canvas; [alpha] dims her. */
internal fun DrawScope.miniPoppyIcon(tint: Color, alpha: Float = 1f) {
    val w = size.width; val h = size.height; val r = w * 0.3f; val c = Offset(w / 2, h * 0.64f)
    val col = tint.copy(alpha = alpha)
    for (sx in listOf(-1f, 1f)) drawOval(col, Offset(c.x + sx * r * 0.45f - r * 0.24f, c.y - r * 2.0f), Size(r * 0.48f, r * 1.35f))
    drawCircle(col, r, c)
    for (sx in listOf(-1f, 1f)) drawCircle(Color(0xFF1C1622).copy(alpha = alpha), r * 0.18f, Offset(c.x + sx * r * 0.36f, c.y - r * 0.05f))
}
