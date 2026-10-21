package com.zohar.slotar.screen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Original vector symbol art (port of reels-screen.js drawSymbol). No image files. */
object SymbolArt {
    val GOLD = listOf(0f to "#fff6c2", 0.35f to "#ffd34d", 0.6f to "#d99a14", 1f to "#8a5a06")
    val SANS: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val SERIF: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    fun c(hex: String) = Color.parseColor(hex)
    fun lin(x0: Float, y0: Float, x1: Float, y1: Float, stops: List<Pair<Float, String>>) =
        LinearGradient(x0, y0, x1, y1, stops.map { c(it.second) }.toIntArray(), stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
    fun rad(x: Float, y: Float, r: Float, stops: List<Pair<Float, String>>) =
        RadialGradient(x, y, r, stops.map { c(it.second) }.toIntArray(), stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
    fun fill() = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    fun stroke(w: Float, col: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w; color = col; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    fun text(size: Float, tf: Typeface = SANS) = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = tf; textAlign = Paint.Align.CENTER }
    /** y for vertically centred text */
    fun midY(p: Paint, y: Float) = y - (p.descent() + p.ascent()) / 2

    fun star(cv: Canvas, x: Float, y: Float, r: Float, r2: Float, paint: Paint, n: Int = 5) {
        val path = Path()
        for (i in 0 until n * 2) {
            val a = -Math.PI / 2 + i * Math.PI / n; val rr = if (i % 2 == 1) r2 else r
            val px = (x + cos(a) * rr).toFloat(); val py = (y + sin(a) * rr).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close(); cv.drawPath(path, paint)
    }

    fun draw(cv: Canvas, id: String, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2; val u = min(w, h)
        cv.save()
        when (id) {
            "A", "K", "Q", "J", "TEN" -> {
                val col = mapOf("A" to ("#ff6b6b" to "#b3001b"), "K" to ("#d58cff" to "#6a1b9a"), "Q" to ("#7cc6ff" to "#0b5394"),
                    "J" to ("#7dffa8" to "#0b7a3b"), "TEN" to ("#ffc46b" to "#b35900")).getValue(id)
                val txt = if (id == "TEN") "10" else id
                val p = text(u * (if (id == "TEN") 0.56f else 0.66f)); val y = midY(p, cy + u * 0.03f)
                val s = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = u * 0.07f; color = c("#1a0d00"); strokeJoin = Paint.Join.ROUND; setShadowLayer(u * 0.12f, 0f, 0f, c(col.first)) }
                cv.drawText(txt, cx, y, s)
                p.shader = lin(0f, cy - u * 0.3f, 0f, cy + u * 0.3f, listOf(0f to "#ffffff", 0.25f to col.first, 0.75f to col.second, 1f to "#2a0010"))
                cv.drawText(txt, cx, y, p)
                val hl = Paint(p).apply { shader = null; style = Paint.Style.STROKE; strokeWidth = u * 0.018f; color = Color.argb(166, 255, 255, 255) }
                cv.drawText(txt, cx, y, hl)
            }
            "SEVEN" -> {
                val p = text(u * 0.86f, SERIF); val y = midY(p, cy + u * 0.05f)
                cv.drawText("7", cx, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = u * 0.1f; color = c("#ffe9a8"); strokeJoin = Paint.Join.ROUND; setShadowLayer(u * 0.22f, 0f, 0f, c("#ff2a3a")) })
                p.shader = lin(0f, cy - u * 0.4f, 0f, cy + u * 0.4f, listOf(0f to "#ff9a9a", 0.45f to "#ff1a2c", 1f to "#7a0010")); cv.drawText("7", cx, y, p)
                cv.drawText("7", cx, y, Paint(p).apply { shader = null; style = Paint.Style.STROKE; strokeWidth = u * 0.02f; color = c("#8a0010") })
            }
            "BELL" -> {
                cv.translate(cx, cy)
                val path = Path().apply {
                    moveTo(-u * 0.34f, u * 0.22f); cubicTo(-u * 0.32f, -u * 0.05f, -u * 0.26f, -u * 0.36f, 0f, -u * 0.36f)
                    cubicTo(u * 0.26f, -u * 0.36f, u * 0.32f, -u * 0.05f, u * 0.34f, u * 0.22f); quadTo(0f, u * 0.3f, -u * 0.34f, u * 0.22f); close()
                }
                cv.drawPath(path, fill().apply { shader = lin(-u * 0.3f, 0f, u * 0.3f, 0f, listOf(0f to "#8a5a06", 0.3f to "#ffd34d", 0.5f to "#fff3b0", 0.75f to "#e0a21a", 1f to "#7a4c04")); setShadowLayer(u * 0.15f, 0f, 0f, c("#ffcf33")) })
                cv.drawPath(path, stroke(u * 0.02f, c("#5c3a00")))
                cv.drawCircle(0f, u * 0.29f, u * 0.07f, fill().apply { shader = lin(0f, u * 0.2f, 0f, u * 0.36f, GOLD) })
                cv.save(); cv.rotate(17f, -u * 0.12f, -u * 0.12f)
                cv.drawOval(RectF(-u * 0.16f, -u * 0.26f, -u * 0.08f, u * 0.02f), fill().apply { color = Color.argb(140, 255, 255, 255) }); cv.restore()
            }
            "DIAMOND" -> {
                cv.translate(cx, cy + u * 0.02f)
                val top = -u * 0.24f; val mid = -u * 0.06f; val bot = u * 0.36f; val hw = u * 0.38f; val tw = u * 0.2f
                val glow = fill().apply { color = c("#0a6fb8"); setShadowLayer(u * 0.2f, 0f, 0f, c("#4fd8ff")) }
                val outline = Path().apply { moveTo(-hw, mid); lineTo(-tw, top); lineTo(tw, top); lineTo(hw, mid); lineTo(0f, bot); close() }
                cv.drawPath(outline, glow)
                listOf(Triple(floatArrayOf(-hw, mid, -tw, top, 0f, mid), "#bff4ff", 0), Triple(floatArrayOf(0f, mid, -tw, top, tw, top), "#e9fbff", 0),
                    Triple(floatArrayOf(0f, mid, tw, top, hw, mid), "#8fe6ff", 0), Triple(floatArrayOf(-hw, mid, 0f, mid, 0f, bot), "#1aa7e0", 0),
                    Triple(floatArrayOf(0f, mid, hw, mid, 0f, bot), "#0a6fb8", 0)).forEach { (pt, col, _) ->
                    cv.drawPath(Path().apply { moveTo(pt[0], pt[1]); lineTo(pt[2], pt[3]); lineTo(pt[4], pt[5]); close() }, fill().apply { color = c(col) })
                }
                val s = stroke(u * 0.012f, Color.argb(204, 255, 255, 255)); cv.drawPath(outline, s); cv.drawLine(-hw, mid, hw, mid, s)
                cv.drawCircle(-u * 0.1f, top + u * 0.06f, u * 0.025f, fill().apply { color = Color.WHITE })
                cv.drawCircle(u * 0.2f, mid + u * 0.08f, u * 0.018f, fill().apply { color = Color.WHITE })
            }
            "CROWN" -> {
                cv.translate(cx, cy + u * 0.04f)
                val path = Path().apply { moveTo(-u * 0.38f, u * 0.2f); lineTo(-u * 0.42f, -u * 0.18f); lineTo(-u * 0.2f, 0f); lineTo(0f, -u * 0.3f); lineTo(u * 0.2f, 0f); lineTo(u * 0.42f, -u * 0.18f); lineTo(u * 0.38f, u * 0.2f); close() }
                cv.drawPath(path, fill().apply { shader = lin(0f, -u * 0.3f, 0f, u * 0.22f, GOLD); setShadowLayer(u * 0.18f, 0f, 0f, c("#ffd34d")) })
                val st = stroke(u * 0.02f, c("#6b4300")); cv.drawPath(path, st)
                val band = RectF(-u * 0.4f, u * 0.18f, u * 0.4f, u * 0.28f)
                cv.drawRoundRect(band, u * 0.03f, u * 0.03f, fill().apply { shader = lin(0f, u * 0.18f, 0f, u * 0.3f, GOLD) }); cv.drawRoundRect(band, u * 0.03f, u * 0.03f, st)
                listOf(Triple(-u * 0.42f, -u * 0.2f, "#ff3b5c"), Triple(0f, -u * 0.32f, "#3bc7ff"), Triple(u * 0.42f, -u * 0.2f, "#ff3b5c")).forEach { (x, y, col) ->
                    cv.drawCircle(x, y, u * 0.05f, fill().apply { shader = rad(x - u * 0.015f, y - u * 0.015f, u * 0.055f, listOf(0f to "#ffffff", 0.4f to col, 1f to "#330000")) })
                }
                cv.drawCircle(0f, u * 0.08f, u * 0.06f, fill().apply { shader = rad(0f, u * 0.08f, u * 0.07f, listOf(0f to "#ffffff", 0.4f to "#2dff9a", 1f to "#003a1c")) })
            }
            "W" -> {
                cv.translate(cx, cy)
                val r = RectF(-u * 0.44f, -u * 0.32f, u * 0.44f, u * 0.32f)
                cv.drawRoundRect(r, u * 0.12f, u * 0.12f, fill().apply { shader = lin(0f, -u * 0.4f, 0f, u * 0.4f, listOf(0f to "#e3b5ff", 0.5f to "#7a1fd1", 1f to "#2a0057")); setShadowLayer(u * 0.25f, 0f, 0f, c("#c46bff")) })
                cv.drawRoundRect(r, u * 0.12f, u * 0.12f, stroke(u * 0.035f, 0).apply { shader = lin(0f, -u * 0.32f, 0f, u * 0.32f, GOLD) })
                val p = text(u * 0.3f); val y = midY(p, u * 0.02f)
                cv.drawText("WILD", 0f, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = u * 0.05f; color = c("#3a1500"); strokeJoin = Paint.Join.ROUND })
                p.shader = lin(0f, -u * 0.12f, 0f, u * 0.14f, GOLD); cv.drawText("WILD", 0f, y, p)
                val sp = fill().apply { color = Color.argb(230, 255, 240, 180) }
                for (i in 0 until 6) { val a = i / 6.0 * Math.PI * 2; star(cv, (cos(a) * u * 0.36).toFloat(), (sin(a) * u * 0.25).toFloat(), u * 0.035f, u * 0.012f, sp) }
            }
            "S" -> {
                cv.translate(cx, cy)
                cv.drawCircle(0f, 0f, u * 0.4f, fill().apply { shader = lin(-u * 0.3f, -u * 0.3f, u * 0.3f, u * 0.3f, GOLD); setShadowLayer(u * 0.28f, 0f, 0f, c("#ffe066")) })
                cv.drawCircle(0f, 0f, u * 0.4f, stroke(u * 0.03f, c("#7a4c04")))
                cv.drawCircle(0f, 0f, u * 0.32f, stroke(u * 0.015f, Color.argb(178, 122, 76, 4)))
                star(cv, 0f, -u * 0.06f, u * 0.2f, u * 0.085f, fill().apply { shader = lin(0f, -u * 0.25f, 0f, u * 0.25f, listOf(0f to "#fff8d6", 1f to "#ffb300")) })
                val p = text(u * 0.13f); val y = midY(p, u * 0.2f)
                cv.drawText("BONUS", 0f, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = u * 0.03f; color = c("#5c3a00") })
                cv.drawText("BONUS", 0f, y, p.apply { color = c("#fff2b3") })
            }
        }
        cv.restore()
    }
}
