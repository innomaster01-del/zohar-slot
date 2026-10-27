package com.zohar.slotar.screen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.zohar.slotar.screen.SymbolArt.GOLD
import com.zohar.slotar.screen.SymbolArt.SERIF
import com.zohar.slotar.screen.SymbolArt.c
import com.zohar.slotar.screen.SymbolArt.fill
import com.zohar.slotar.screen.SymbolArt.lin
import com.zohar.slotar.screen.SymbolArt.midY
import com.zohar.slotar.screen.SymbolArt.rad
import com.zohar.slotar.screen.SymbolArt.stroke
import com.zohar.slotar.screen.SymbolArt.text
import kotlin.math.min

/** Pharaoh's Book symbols: original vector art (Egyptian gold, lapis, turquoise). No image files. */
object PharaohArt {
    val LAPIS = listOf(0f to "#7fb2ff", 0.45f to "#1f4fb8", 1f to "#0a1c55")
    val TURQ = listOf(0f to "#b8fff0", 0.45f to "#22c3a4", 1f to "#05463c")

    fun draw(cv: Canvas, id: String, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2; val u = min(w, h)
        cv.save(); cv.translate(cx, cy)
        when (id) {
            "BOOK" -> book(cv, u, open = false)
            "MASK" -> mask(cv, u)
            "SCARAB" -> scarab(cv, u)
            "EYE" -> eye(cv, u)
            "ANKH" -> ankh(cv, u)
            else -> letter(cv, u, id)
        }
        cv.restore()
    }

    /** The book: red leather, gold corners and clasp, a gold scarab on the cover. */
    fun book(cv: Canvas, u: Float, open: Boolean) {
        val r = RectF(-u * 0.34f, -u * 0.4f, u * 0.34f, u * 0.4f)
        cv.drawRoundRect(RectF(r.left + u * 0.05f, r.top + u * 0.03f, r.right + u * 0.04f, r.bottom + u * 0.03f), u * 0.04f, u * 0.04f, fill().apply { color = c("#efe3c2") })   // pages
        cv.drawRoundRect(r, u * 0.05f, u * 0.05f, fill().apply { shader = lin(-u * 0.3f, -u * 0.4f, u * 0.3f, u * 0.4f, listOf(0f to "#d63a2a", 0.5f to "#8e1610", 1f to "#4a0806")); setShadowLayer(u * 0.2f, 0f, 0f, c("#ffcf4a")) })
        val gold = fill().apply { shader = lin(0f, -u * 0.4f, 0f, u * 0.4f, GOLD) }
        cv.drawRoundRect(RectF(r.left, r.top, r.left + u * 0.06f, r.bottom), u * 0.03f, u * 0.03f, gold)      // spine
        for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val p = Path().apply { val x = if (sx < 0) r.left else r.right; val y = if (sy < 0) r.top else r.bottom; moveTo(x, y); lineTo(x - sx * u * 0.13f, y); lineTo(x, y - sy * u * 0.13f); close() }
            cv.drawPath(p, gold)
        }
        cv.drawRoundRect(RectF(-u * 0.24f, -u * 0.3f, u * 0.27f, u * 0.3f), u * 0.03f, u * 0.03f, stroke(u * 0.015f, c("#ffd34d")))
        cv.save(); cv.scale(0.55f, 0.55f); cv.translate(u * 0.03f, 0f); scarabShape(cv, u, gold = true); cv.restore()
        cv.drawRect(r.right - u * 0.02f, -u * 0.06f, r.right + u * 0.05f, u * 0.06f, gold)                    // clasp
    }

    private fun scarabShape(cv: Canvas, u: Float, gold: Boolean) {
        val body = if (gold) fill().apply { shader = lin(0f, -u * 0.3f, 0f, u * 0.3f, GOLD) } else fill().apply { shader = rad(-u * 0.05f, -u * 0.05f, u * 0.32f, TURQ) }
        val wing = fill().apply { shader = lin(-u * 0.4f, 0f, u * 0.4f, 0f, if (gold) GOLD else listOf(0f to "#ffe9a0", 0.5f to "#e0a21a", 1f to "#7a4c04")) }
        for (sx in listOf(-1f, 1f)) {
            val p = Path().apply { moveTo(sx * u * 0.08f, -u * 0.08f); cubicTo(sx * u * 0.5f, -u * 0.32f, sx * u * 0.48f, u * 0.05f, sx * u * 0.12f, u * 0.12f); close() }
            cv.drawPath(p, wing); cv.drawPath(p, stroke(u * 0.012f, c("#5c3a00")))
        }
        cv.drawOval(RectF(-u * 0.13f, -u * 0.12f, u * 0.13f, u * 0.28f), body)
        cv.drawLine(0f, -u * 0.1f, 0f, u * 0.27f, stroke(u * 0.012f, c("#043028")))
        cv.drawCircle(0f, -u * 0.18f, u * 0.08f, body)
        cv.drawCircle(0f, -u * 0.33f, u * 0.07f, fill().apply { shader = rad(0f, -u * 0.33f, u * 0.08f, listOf(0f to "#fff2b0", 0.5f to "#ff8a1a", 1f to "#a83a00")) })   // the sun disc
        val legs = stroke(u * 0.018f, if (gold) c("#8a5a06") else c("#0c3f37"))
        for (sx in listOf(-1f, 1f)) for (k in 0 until 3) cv.drawLine(sx * u * 0.11f, u * (0.0f + k * 0.08f), sx * u * 0.22f, u * (0.02f + k * 0.11f), legs)
    }
    fun scarab(cv: Canvas, u: Float) {
        cv.drawCircle(0f, 0f, u * 0.42f, fill().apply { shader = rad(0f, 0f, u * 0.44f, listOf(0f to "#1e6e60", 0.7f to "#0b2f29", 1f to "#00000000")) })
        scarabShape(cv, u, gold = false)
    }

    fun mask(cv: Canvas, u: Float) {
        // nemes headdress: blue/gold stripes framing the golden face
        val head = Path().apply { moveTo(-u * 0.2f, -u * 0.36f); quadTo(0f, -u * 0.5f, u * 0.2f, -u * 0.36f); lineTo(u * 0.42f, u * 0.2f); lineTo(u * 0.3f, u * 0.42f); lineTo(-u * 0.3f, u * 0.42f); lineTo(-u * 0.42f, u * 0.2f); close() }
        cv.drawPath(head, fill().apply { shader = lin(0f, -u * 0.5f, 0f, u * 0.4f, GOLD); setShadowLayer(u * 0.16f, 0f, 0f, c("#ffcf4a")) })
        cv.save(); cv.clipPath(head)
        val blue = fill().apply { shader = lin(0f, -u * 0.5f, 0f, u * 0.4f, LAPIS) }
        var x = -u * 0.46f; while (x < u * 0.46f) { cv.drawRect(x, -u * 0.5f, x + u * 0.045f, u * 0.45f, blue); x += u * 0.09f }
        cv.restore()
        cv.drawPath(head, stroke(u * 0.015f, c("#5c3a00")))
        // face
        val face = RectF(-u * 0.17f, -u * 0.3f, u * 0.17f, u * 0.2f)
        cv.drawOval(face, fill().apply { shader = lin(-u * 0.17f, 0f, u * 0.17f, 0f, listOf(0f to "#a8761c", 0.4f to "#ffe08a", 0.6f to "#ffd34d", 1f to "#8a5a06")) })
        val kohl = fill().apply { color = c("#0b1d4a") }
        for (sx in listOf(-1f, 1f)) {
            cv.drawOval(RectF(sx * u * 0.1f - u * 0.05f, -u * 0.13f, sx * u * 0.1f + u * 0.05f, -u * 0.09f), fill().apply { color = Color.WHITE })
            cv.drawCircle(sx * u * 0.1f, -u * 0.11f, u * 0.018f, kohl)
            cv.drawLine(sx * u * 0.05f, -u * 0.11f, sx * u * 0.19f, -u * 0.13f, stroke(u * 0.012f, c("#0b1d4a")))
        }
        cv.drawRoundRect(RectF(-u * 0.06f, u * 0.06f, u * 0.06f, u * 0.09f), u * 0.02f, u * 0.02f, fill().apply { color = c("#7a3a10") })
        // beard and the uraeus (cobra) on the brow
        val beard = Path().apply { moveTo(-u * 0.05f, u * 0.2f); lineTo(u * 0.05f, u * 0.2f); lineTo(u * 0.04f, u * 0.4f); lineTo(-u * 0.04f, u * 0.4f); close() }
        cv.drawPath(beard, blue); cv.drawPath(beard, stroke(u * 0.01f, c("#ffd34d")))
        cv.drawCircle(0f, -u * 0.33f, u * 0.04f, fill().apply { shader = rad(0f, -u * 0.34f, u * 0.05f, listOf(0f to "#ffffff", 0.4f to "#ff3b3b", 1f to "#5a0000")) })
    }

    fun eye(cv: Canvas, u: Float) {
        cv.drawCircle(0f, 0f, u * 0.42f, fill().apply { shader = rad(0f, -u * 0.1f, u * 0.46f, listOf(0f to "#ffe9a0", 0.6f to "#d99a14", 1f to "#6b4300")); setShadowLayer(u * 0.14f, 0f, 0f, c("#ffcf4a")) })
        cv.drawCircle(0f, 0f, u * 0.36f, fill().apply { shader = rad(0f, 0f, u * 0.38f, LAPIS) })
        val gold = stroke(u * 0.045f, c("#ffd34d"))
        val lid = Path().apply { moveTo(-u * 0.27f, 0f); quadTo(0f, -u * 0.24f, u * 0.27f, -u * 0.02f); quadTo(0f, u * 0.15f, -u * 0.27f, 0f) }
        cv.drawPath(lid, fill().apply { color = Color.WHITE }); cv.drawPath(lid, gold)
        cv.drawCircle(0f, -u * 0.035f, u * 0.075f, fill().apply { color = c("#0b1d4a") }); cv.drawCircle(-u * 0.02f, -u * 0.055f, u * 0.02f, fill().apply { color = Color.WHITE })
        cv.drawLine(-u * 0.27f, -u * 0.17f, u * 0.25f, -u * 0.2f, gold)                 // brow
        cv.drawLine(-u * 0.04f, u * 0.08f, -u * 0.08f, u * 0.3f, gold)                 // tear line
        val tail = Path().apply { moveTo(u * 0.04f, u * 0.08f); quadTo(u * 0.22f, u * 0.32f, u * 0.08f, u * 0.3f) }
        cv.drawPath(tail, gold)
    }

    fun ankh(cv: Canvas, u: Float) {
        cv.drawRoundRect(RectF(-u * 0.38f, -u * 0.42f, u * 0.38f, u * 0.42f), u * 0.1f, u * 0.1f, fill().apply { shader = lin(0f, -u * 0.42f, 0f, u * 0.42f, listOf(0f to "#8a4bd6", 0.6f to "#3b1470", 1f to "#1a0636")) })
        cv.drawRoundRect(RectF(-u * 0.38f, -u * 0.42f, u * 0.38f, u * 0.42f), u * 0.1f, u * 0.1f, stroke(u * 0.02f, c("#e9c46a")))
        val g = stroke(u * 0.1f, 0).apply { shader = lin(0f, -u * 0.36f, 0f, u * 0.36f, GOLD); setShadowLayer(u * 0.1f, 0f, 0f, c("#ffcf4a")) }
        cv.drawOval(RectF(-u * 0.11f, -u * 0.36f, u * 0.11f, -u * 0.06f), g)
        cv.drawLine(-u * 0.26f, -u * 0.01f, u * 0.26f, -u * 0.01f, g)
        cv.drawLine(0f, -u * 0.04f, 0f, u * 0.36f, g)
    }

    /** A K Q J 10 carved in sandstone with coloured inlay. */
    fun letter(cv: Canvas, u: Float, id: String) {
        val col = mapOf("A" to ("#ff7a5c" to "#a8240a"), "K" to ("#6fd3ff" to "#0b4f8a"), "Q" to ("#c58cff" to "#53158f"),
            "J" to ("#7dffb0" to "#0b7a3b"), "TEN" to ("#ffd36b" to "#a86a00")).getValue(id)
        val txt = if (id == "TEN") "10" else id
        val p = text(u * (if (id == "TEN") 0.56f else 0.68f), SERIF); val y = midY(p, u * 0.03f)
        cv.drawText(txt, 0f, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = u * 0.09f; color = c("#3a2408"); strokeJoin = Paint.Join.ROUND; setShadowLayer(u * 0.1f, 0f, 0f, c(col.first)) })
        cv.drawText(txt, 0f, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = u * 0.045f; shader = lin(0f, -u * 0.3f, 0f, u * 0.3f, GOLD) })
        p.shader = lin(0f, -u * 0.3f, 0f, u * 0.3f, listOf(0f to "#ffffff", 0.3f to col.first, 0.8f to col.second, 1f to "#1a0800")); cv.drawText(txt, 0f, y, p)
    }
}
