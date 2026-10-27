package com.zohar.slotar.screen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.zohar.slotar.screen.SymbolArt.GOLD
import com.zohar.slotar.screen.SymbolArt.SERIF
import com.zohar.slotar.screen.SymbolArt.c
import com.zohar.slotar.screen.SymbolArt.fill
import com.zohar.slotar.screen.SymbolArt.lin
import com.zohar.slotar.screen.SymbolArt.midY
import com.zohar.slotar.screen.SymbolArt.rad
import com.zohar.slotar.screen.SymbolArt.stroke
import com.zohar.slotar.screen.SymbolArt.text
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Dragon Fortune symbols: red lacquer, gold and jade medallions with Chinese characters; gold coins with values. */
object DragonArt {
    private val CJK: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)        // system CJK fallback draws the characters
    val RED = listOf(0f to "#ff6a5a", 0.5f to "#c0101a", 1f to "#4a0004")
    val JADE = listOf(0f to "#c8ffe0", 0.5f to "#2fb56e", 1f to "#063a1e")

    fun draw(cv: Canvas, id: String, w: Float, h: Float, coinLabel: String? = null) {
        val u = min(w, h)
        cv.save(); cv.translate(w / 2, h / 2)
        when (id) {
            "WILD" -> medallion(cv, u, "龍", RED, "WILD", big = true)
            "PHOENIX" -> medallion(cv, u, "鳳", listOf(0f to "#ffd27a", 0.5f to "#ff7a1a", 1f to "#7a2a00"), "PHOENIX")
            "TIGER" -> medallion(cv, u, "虎", listOf(0f to "#fff2d0", 0.5f to "#e08a2a", 1f to "#5a2a00"), "TIGER")
            "KOI" -> koi(cv, u)
            "TURTLE" -> medallion(cv, u, "龜", JADE, "TURTLE")
            "COIN" -> coin(cv, u, coinLabel ?: "")
            else -> letter(cv, u, id)
        }
        cv.restore()
    }

    private fun medallion(cv: Canvas, u: Float, ch: String, cols: List<Pair<Float, String>>, label: String, big: Boolean = false) {
        val r = u * 0.42f
        cv.drawCircle(0f, 0f, r, fill().apply { shader = lin(0f, -r, 0f, r, GOLD); setShadowLayer(u * 0.16f, 0f, 0f, c(if (big) "#ff3a2a" else "#ffcf4a")) })
        cv.drawCircle(0f, 0f, r * 0.86f, fill().apply { shader = rad(-r * 0.2f, -r * 0.3f, r, cols) })
        // cloud pattern ring
        val ring = stroke(u * 0.012f, Color.argb(120, 255, 230, 160))
        for (i in 0 until 12) { val a = i / 12.0 * Math.PI * 2; cv.drawCircle((cos(a) * r * 0.78f).toFloat(), (sin(a) * r * 0.78f).toFloat(), u * 0.035f, ring) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = u * 0.46f; typeface = CJK; textAlign = Paint.Align.CENTER }
        val y = midY(p, -u * 0.02f)
        cv.drawText(ch, 0f, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = u * 0.04f; color = c("#3a0a00"); strokeJoin = Paint.Join.ROUND })
        p.shader = lin(0f, -u * 0.2f, 0f, u * 0.2f, GOLD); cv.drawText(ch, 0f, y, p)
        val lp = text(u * (if (big) 0.16f else 0.11f)); val ly = midY(lp, r * 0.98f)
        val band = RectF(-r * 0.8f, ly - u * 0.09f, r * 0.8f, ly + u * 0.07f)
        cv.drawRoundRect(band, u * 0.05f, u * 0.05f, fill().apply { shader = lin(0f, band.top, 0f, band.bottom, if (big) listOf(0f to "#ffe08a", 1f to "#c78a1a") else listOf(0f to "#5a0a0a", 1f to "#2a0004")) })
        cv.drawRoundRect(band, u * 0.05f, u * 0.05f, stroke(u * 0.012f, c("#ffd34d")))
        cv.drawText(label, 0f, midY(lp, (band.top + band.bottom) / 2), lp.apply { color = if (big) c("#5a0a00") else c("#ffe9a8") })
    }

    private fun koi(cv: Canvas, u: Float) {
        val r = u * 0.42f
        cv.drawCircle(0f, 0f, r, fill().apply { shader = rad(0f, 0f, r, listOf(0f to "#2a6aa8", 0.8f to "#0a2a5a", 1f to "#061630")) })
        cv.drawCircle(0f, 0f, r, stroke(u * 0.03f, 0).apply { shader = lin(0f, -r, 0f, r, GOLD) })
        val body = Path().apply { moveTo(-u * 0.26f, 0f); cubicTo(-u * 0.12f, -u * 0.2f, u * 0.14f, -u * 0.16f, u * 0.2f, 0f); cubicTo(u * 0.14f, u * 0.16f, -u * 0.12f, u * 0.2f, -u * 0.26f, 0f) }
        cv.drawPath(body, fill().apply { shader = lin(-u * 0.26f, 0f, u * 0.2f, 0f, listOf(0f to "#ffffff", 0.4f to "#ff6a2a", 1f to "#c0101a")); setShadowLayer(u * 0.08f, 0f, 0f, c("#ffb07a")) })
        val tail = Path().apply { moveTo(u * 0.18f, 0f); lineTo(u * 0.34f, -u * 0.13f); quadTo(u * 0.28f, 0f, u * 0.34f, u * 0.13f); close() }
        cv.drawPath(tail, fill().apply { color = c("#ff7a3a") })
        cv.drawCircle(-u * 0.17f, -u * 0.03f, u * 0.025f, fill().apply { color = c("#1a0a00") })
        for (k in 0 until 3) cv.drawCircle(-u * 0.02f + k * u * 0.07f, -u * 0.02f, u * 0.03f, fill().apply { color = Color.argb(160, 255, 255, 255) })
        val lp = text(u * 0.1f).apply { color = c("#ffe9a8") }; cv.drawText("KOI", 0f, midY(lp, u * 0.3f), lp)
    }

    /** Gold coin with a square hole; [label] is its value (credits) or MINI / MINOR / MAJOR. */
    fun coin(cv: Canvas, u: Float, label: String) {
        val r = u * 0.42f
        cv.drawCircle(0f, 0f, r * 1.12f, fill().apply { shader = rad(0f, 0f, r * 1.12f, listOf(0f to "#ffcf4a", 0.6f to "#80ff7a1a", 1f to "#00ff3a00")) })
        cv.drawCircle(0f, 0f, r, fill().apply { shader = lin(-r, -r, r, r, listOf(0f to "#fff6c8", 0.35f to "#ffd34d", 0.7f to "#d99a14", 1f to "#7a4c04")) })
        cv.drawCircle(0f, 0f, r * 0.84f, stroke(u * 0.02f, c("#8a5a06")))
        cv.drawRect(-r * 0.16f, -r * 0.62f, r * 0.16f, -r * 0.3f, fill().apply { color = c("#7a3a00") })
        val jackpot = label == "MINI" || label == "MINOR" || label == "MAJOR"
        val p = text(u * (if (jackpot) 0.17f else if (label.length > 4) 0.16f else 0.22f)).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val y = midY(p, r * 0.18f)
        if (jackpot) {
            val band = RectF(-r * 0.92f, y - u * 0.13f, r * 0.92f, y + u * 0.05f)
            val col = when (label) { "MAJOR" -> "#a43ad6"; "MINOR" -> "#2a7ad6"; else -> "#2ab05a" }
            cv.drawRoundRect(band, u * 0.06f, u * 0.06f, fill().apply { color = c(col) }); cv.drawRoundRect(band, u * 0.06f, u * 0.06f, stroke(u * 0.012f, Color.WHITE))
        }
        cv.drawText(label, 0f, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = u * 0.03f; color = if (jackpot) c("#1a0a2a") else c("#fff2c0") })
        cv.drawText(label, 0f, y, p.apply { color = if (jackpot) Color.WHITE else c("#6a1a00") })
    }

    private fun letter(cv: Canvas, u: Float, id: String) {
        val col = mapOf("A" to ("#ff7a6a" to "#a8100a"), "K" to ("#7dffb0" to "#0b7a3b"), "Q" to ("#7cc6ff" to "#0b4f8a"),
            "J" to ("#ffd36b" to "#a86a00"), "TEN" to ("#e2a0ff" to "#5a1a8a")).getValue(id)
        val txt = if (id == "TEN") "10" else id
        val p = text(u * (if (id == "TEN") 0.56f else 0.68f), SERIF); val y = midY(p, u * 0.03f)
        cv.drawText(txt, 0f, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = u * 0.1f; color = c("#2a0004"); strokeJoin = Paint.Join.ROUND; setShadowLayer(u * 0.1f, 0f, 0f, c(col.first)) })
        cv.drawText(txt, 0f, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = u * 0.04f; shader = lin(0f, -u * 0.3f, 0f, u * 0.3f, GOLD) })
        p.shader = lin(0f, -u * 0.3f, 0f, u * 0.3f, listOf(0f to "#ffffff", 0.3f to col.first, 0.8f to col.second, 1f to "#100000")); cv.drawText(txt, 0f, y, p)
    }
}
