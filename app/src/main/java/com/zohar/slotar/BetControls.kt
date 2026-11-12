package com.zohar.slotar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------- the buttons every game uses ----------------
// A pill with a thin ring round it and a round icon on the left, in the same colours as before: green for the main
// action, dark glass for the rest.
private val GREEN = Color(0xFF18C95A); private val GREEN_RING = Color(0xFF7DF0A8); private val INK = Color(0xFF031A0B)
private val GLASS = Color(0xCC12141C); private val GLASS_RING = Color(0x66FFFFFF)

enum class CtlIcon { NONE, CHIP, REMOVE, REPEAT, ADD }

/** Which icon a button gets from what it says. */
private fun iconFor(label: String, primary: Boolean, small: Boolean): CtlIcon {
    val l = label.lowercase(Locale.US)
    return when {
        "clear" in l || "remove" in l -> CtlIcon.REMOVE
        "rebet" in l || "repeat" in l || "same bet" in l -> CtlIcon.REPEAT
        "refill" in l -> CtlIcon.ADD
        "place bet" in l -> CtlIcon.CHIP
        primary && !small && label.length > 1 -> CtlIcon.CHIP
        else -> CtlIcon.NONE
    }
}

@Composable
internal fun Ctl(label: String, enabled: Boolean = true, primary: Boolean = false, wide: Boolean = false, small: Boolean = false, icon: CtlIcon? = null, onClick: () -> Unit) {
    val h = if (small) 42.dp else 54.dp
    val pill = RoundedCornerShape(50)
    val ic = icon ?: iconFor(label, primary, small)
    val fg = if (primary) INK else Color.White
    Box(Modifier.height(h).then(if (wide) Modifier.widthIn(min = 120.dp) else Modifier).alpha(if (enabled) 1f else 0.42f)
        .border(1.5.dp, if (primary) GREEN_RING else GLASS_RING, pill).padding(3.dp)
        .clip(pill).background(if (primary) Brush.verticalGradient(listOf(Color(0xFF3BE07A), GREEN)) else Brush.verticalGradient(listOf(Color(0xD9262A36), GLASS)))
        .clickable(enabled = enabled) { onClick() }
        .padding(start = if (ic != CtlIcon.NONE) 6.dp else if (wide) 18.dp else 12.dp, end = if (wide) 18.dp else 12.dp),
        contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ic != CtlIcon.NONE) { CtlGlyph(ic, fg, if (small) 24.dp else 30.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (label.any { it.isLetter() }) label.uppercase(Locale.US) else label, color = fg, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp,
                fontSize = if (wide) 18.sp else if (small) 12.sp else 17.sp, maxLines = 1)
        }
    }
}

/** The round icon: a ring with a mark in it (a chip, an ✕, two turning arrows, a +). */
@Composable
private fun CtlGlyph(icon: CtlIcon, color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2; val c = center; val w = r * 0.13f
        drawCircle(color, r - w / 2, c, style = Stroke(w))
        when (icon) {
            CtlIcon.CHIP -> {
                // a chip: notches round the edge and a small ring inside
                for (i in 0 until 8) { val a = i * PI / 4; drawLine(color, Offset(c.x + cos(a).toFloat() * r * 0.55f, c.y + sin(a).toFloat() * r * 0.55f), Offset(c.x + cos(a).toFloat() * r * 0.78f, c.y + sin(a).toFloat() * r * 0.78f), w, StrokeCap.Round) }
                drawCircle(color, r * 0.36f, c, style = Stroke(w))
            }
            CtlIcon.REMOVE -> { val k = r * 0.36f; drawLine(color, Offset(c.x - k, c.y - k), Offset(c.x + k, c.y + k), w * 1.2f, StrokeCap.Round); drawLine(color, Offset(c.x + k, c.y - k), Offset(c.x - k, c.y + k), w * 1.2f, StrokeCap.Round) }
            CtlIcon.ADD -> { val k = r * 0.42f; drawLine(color, Offset(c.x - k, c.y), Offset(c.x + k, c.y), w * 1.2f, StrokeCap.Round); drawLine(color, Offset(c.x, c.y - k), Offset(c.x, c.y + k), w * 1.2f, StrokeCap.Round) }
            CtlIcon.REPEAT -> {
                val k = r * 0.48f
                for (s in listOf(0f, 180f)) {
                    drawArc(color, s + 20f, 130f, false, Offset(c.x - k, c.y - k), Size(2 * k, 2 * k), style = Stroke(w, cap = StrokeCap.Round))
                    val a = Math.toRadians((s + 150f).toDouble()); val tip = Offset(c.x + cos(a).toFloat() * k, c.y + sin(a).toFloat() * k)
                    val t = Math.toRadians((s + 150f + 90f).toDouble()); val d = Offset(cos(t).toFloat(), sin(t).toFloat())
                    val p = Path().apply { moveTo(tip.x + d.x * r * 0.22f, tip.y + d.y * r * 0.22f); lineTo(tip.x - d.y * r * 0.2f, tip.y + d.x * r * 0.2f); lineTo(tip.x + d.y * r * 0.2f, tip.y - d.x * r * 0.2f); close() }
                    drawPath(p, color)
                }
            }
            CtlIcon.NONE -> {}
        }
    }
}

// ---------------- the bet slider ----------------
private val nf: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
/** The amounts the slider stops at for a chip of 5 … 500. */
val CHIP_STEPS: List<Long> = listOf(5L, 10, 15, 20, 25, 30, 40, 50, 60, 75, 100, 125, 150, 200, 250, 300, 400, 500)

/** A slider of amounts: drag the chip (or tap the track) to set it. The amount floats over the chip. */
@Composable
internal fun BetSlider(value: Long, steps: List<Long> = CHIP_STEPS, enabled: Boolean = true, modifier: Modifier = Modifier, onChange: (Long) -> Unit) {
    val i = steps.indexOfFirst { it >= value }.let { if (it < 0) steps.size - 1 else it }
    LevelSlider(steps.size, i, { nf.format(steps[it]) }, enabled, modifier) { onChange(steps[it]) }
}

/** The same slider over levels 0 … count−1 (a slot machine's bet levels); `label` says what each level is. */
@Composable
internal fun LevelSlider(count: Int, index: Int, label: (Int) -> String, enabled: Boolean = true, modifier: Modifier = Modifier, onIndex: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val cur by rememberUpdatedState(index); val set by rememberUpdatedState(onIndex)
    val density = LocalDensity.current
    val knob = 44.dp; val trackH = 10.dp
    BoxWithConstraints(modifier.fillMaxWidth().widthIn(max = 460.dp).height(88.dp).alpha(if (enabled) 1f else 0.45f)) {
        val wPx = constraints.maxWidth.toFloat(); val knobPx = with(density) { knob.toPx() }
        val usable = (wPx - knobPx).coerceAtLeast(1f)
        val frac = if (count <= 1) 0f else index.toFloat() / (count - 1)
        val kx = knobPx / 2 + usable * frac
        fun pick(x: Float) {
            if (!enabled || count <= 1) return
            val k = (((x - knobPx / 2) / usable).coerceIn(0f, 1f) * (count - 1)).roundToInt()
            if (k != cur) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); set(k) }
        }
        // the track: faint, filled up to the chip
        Canvas(Modifier.fillMaxWidth().height(knob).offset(y = 40.dp).pointerInput(enabled, count) { detectTapGestures { pick(it.x) } }.pointerInput(enabled, count) {
            detectHorizontalDragGestures(onDragStart = { pick(it.x) }) { ch, _ -> pick(ch.position.x) } }) {
            val th = trackH.toPx(); val y = size.height / 2 - th / 2
            drawRoundRect(Color(0x40FFFFFF), Offset(knobPx / 4, y), Size(size.width - knobPx / 2, th), CornerRadius(th / 2))
            drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF0E8A3E), GREEN, Color(0xFF7DF0A8)), startX = knobPx / 4, endX = kx), Offset(knobPx / 4, y), Size((kx - knobPx / 4).coerceAtLeast(0f), th), CornerRadius(th / 2))
            drawRoundRect(Color(0x66FFFFFF), Offset(knobPx / 4, y), Size(size.width - knobPx / 2, th), CornerRadius(th / 2), style = Stroke(1.dp.toPx()))
            drawChip(Offset(kx, size.height / 2), knobPx / 2)
        }
        // the amount, in a bubble over the chip
        val bubbleW = 104.dp; val bwPx = with(density) { bubbleW.toPx() }
        Box(Modifier.offset { IntOffset((kx - bwPx / 2).coerceIn(0f, wPx - bwPx).roundToInt(), 0) }.width(bubbleW), contentAlignment = Alignment.TopCenter) {
            Text(label(index), Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xD9161A24)).border(1.5.dp, Color(0x80FFFFFF), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 4.dp),
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
        // the bubble's tail, pointing at the chip
        Canvas(Modifier.offset { IntOffset((kx - with(density) { 7.dp.toPx() }).roundToInt(), with(density) { 33.dp.toPx() }.roundToInt()) }.size(14.dp, 8.dp)) {
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, Color(0xD9161A24))
        }
    }
}

/** Our chip, drawn: red and white edge spots, a gold ring, a dark middle with a gold Z. */
private fun DrawScope.drawChip(c: Offset, r: Float) {
    drawCircle(Color(0x55000000), r * 1.02f, c + Offset(0f, r * 0.08f))
    drawCircle(Color(0xFFE8323C), r, c)
    for (i in 0 until 8) { val a0 = i * 45f + 8f
        drawArc(Color.White, a0, 26f, true, Offset(c.x - r * 0.98f, c.y - r * 0.98f), Size(r * 1.96f, r * 1.96f)) }
    drawCircle(Color(0xFFE8323C), r * 0.74f, c)
    drawCircle(Color(0xFFFFCF5A), r * 0.66f, c, style = Stroke(r * 0.07f))
    drawCircle(Color(0xFF2A070C), r * 0.58f, c)
    drawIntoCanvas { cv ->
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color(0xFFFFCF5A).toArgb(); textSize = r * 0.82f; textAlign = android.graphics.Paint.Align.CENTER; typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD) }
        cv.nativeCanvas.drawText("Z", c.x, c.y + r * 0.29f, p)
    }
}
