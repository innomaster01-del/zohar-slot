package com.zohar.slotar.scratch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.zohar.slotar.screen.SymbolArt
import com.zohar.slotar.screen.SymbolArt.c
import com.zohar.slotar.screen.SymbolArt.fill
import com.zohar.slotar.screen.SymbolArt.lin
import com.zohar.slotar.screen.SymbolArt.midY
import com.zohar.slotar.screen.SymbolArt.stroke
import com.zohar.slotar.screen.SymbolArt.text
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.min

/** Draws a scratch card: printed art underneath, silver foil on top that the player scratches away. */
class ScratchRenderer {
    val bitmap: Bitmap = Bitmap.createBitmap(ScratchLayout.W, ScratchLayout.H, Bitmap.Config.ARGB_8888)
    private val out = Canvas(bitmap)
    private val art = Bitmap.createBitmap(ScratchLayout.W, ScratchLayout.H, Bitmap.Config.ARGB_8888)
    private val foil = Bitmap.createBitmap(ScratchLayout.W, ScratchLayout.H, Bitmap.Config.ARGB_8888)
    private val foilCanvas = Canvas(foil)
    private val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val nf = NumberFormat.getIntegerInstance(Locale.US)
    var card: ScratchCard? = null; private set
    var state = ScratchState(); private set
    var dirty = true
    private var wipe = -1f                 // reveal-all animation 0..1
    var finished = false; private set      // all revealed → result shown
    val tierColors = mapOf(Tier.SILVER to listOf("#5b6573", "#20262f"), Tier.GOLD to listOf("#8a5a06", "#2a1600"), Tier.PLATINUM to listOf("#3b2a7a", "#0d0626"))

    fun load(c: ScratchCard) {
        card = c; state = ScratchState(); wipe = -1f; finished = false
        drawArt(c); drawFoil(c); dirty = true
    }

    /** Scratch from one card point to another (bitmap px). Returns true if anything new was revealed. */
    fun scratch(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float = 34f) {
        if (card == null || finished) return
        eraser.strokeWidth = radius * 2
        foilCanvas.drawLine(x0, y0, x1, y1, eraser)
        state.stroke(x0, y0, x1, y1, radius); dirty = true
    }
    fun revealAll() { if (card != null && !finished && wipe < 0) wipe = 0f }

    /** Per frame; returns true when the picture changed. Sets [finished] once everything is revealed. */
    fun update(dt: Float): Boolean {
        if (wipe >= 0f && wipe < 1f) {
            val prev = wipe; wipe = min(1f, wipe + dt / 0.8f)
            // diagonal wipe of the foil, top-left → bottom-right
            val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            val y1 = ScratchLayout.H * wipe * 1.05f; foilCanvas.drawRect(0f, ScratchLayout.H * prev * 1.05f - 2f, ScratchLayout.W.toFloat(), y1, p)
            if (wipe >= 1f) state.revealAll(); dirty = true
        }
        if (!finished && state.allRevealed()) { finished = true; dirty = true }
        if (!dirty) return false
        compose(); dirty = false; return true
    }

    private fun compose() {
        out.drawBitmap(art, 0f, 0f, null)
        out.drawBitmap(foil, 0f, 0f, null)
        val cd = card ?: return
        if (finished) {
            // result: winning cells framed, banner across the card
            val fr = stroke(8f, c("#ffd34d")).apply { setShadowLayer(18f, 0f, 0f, c("#ffae00")) }
            for (i in cd.winCells) { val b = ScratchLayout.cell(i); out.drawRoundRect(RectF(b.l + 8, b.t + 8, b.r - 8, b.b - 8), 18f, 18f, fr) }
            if (cd.freeCard) for (i in 0 until 3) { val b = ScratchLayout.bonus(i); out.drawRoundRect(RectF(b.l + 4, b.t + 4, b.r - 4, b.b - 4), 14f, 14f, fr) }
            if (cd.totalWin > 0 && cd.multiplier > 1) { val b = ScratchLayout.MULT; out.drawRoundRect(RectF(b.l + 4, b.t + 4, b.r - 4, b.b - 4), 14f, 14f, fr) }
            val msg = when { cd.totalWin > 0 -> "WIN ${nf.format(cd.totalWin)}!"; cd.freeCard -> "FREE CARD!"; else -> "NO WIN · TRY AGAIN" }
            val y = ScratchLayout.GRID_T + 3 * ScratchLayout.CELL_H / 2
            out.drawRoundRect(RectF(40f, y - 70f, ScratchLayout.W - 40f, y + 70f), 30f, 30f, fill().apply { color = Color.argb(205, 15, 4, 30) })
            val p = text(if (cd.totalWin > 0) 92f else 70f).apply { shader = lin(0f, y - 50f, 0f, y + 50f, SymbolArt.GOLD); setShadowLayer(24f, 0f, 0f, c("#ff8a00")) }
            out.drawText(msg, ScratchLayout.W / 2f, midY(p, y), p)
            if (cd.totalWin > 0 && cd.freeCard) { val p2 = text(40f).apply { color = c("#ffd1f0") }; out.drawText("+ FREE CARD", ScratchLayout.W / 2f, midY(p2, y + 100f), p2) }
        }
    }

    private fun drawArt(cd: ScratchCard) {
        val g = Canvas(art); val W = ScratchLayout.W.toFloat(); val H = ScratchLayout.H.toFloat()
        val tc = tierColors.getValue(cd.tier)
        g.drawRect(0f, 0f, W, H, fill().apply { shader = lin(0f, 0f, 0f, H, listOf(0f to tc[0], 0.35f to tc[1], 1f to "#07020f")) })
        // sunburst + sparkles
        val ray = stroke(4f, Color.argb(40, 255, 214, 90)); for (i in 0..24) g.drawLine(W / 2, 240f, i * W / 24, -20f, ray)
        for (i in 0 until 60) g.drawCircle((i * 137 % 720).toFloat(), (i * 71 % 1028).toFloat(), 1.5f + i % 3, fill().apply { color = Color.argb(60 + i % 4 * 30, 255, 230, 160) })
        // header
        val t1 = text(108f, SymbolArt.SERIF).apply { shader = lin(0f, 40f, 0f, 150f, SymbolArt.GOLD); setShadowLayer(22f, 0f, 0f, c("#c46bff")) }
        g.drawText("ZOHAR", W / 2, midY(t1, 96f), t1)
        val t2 = text(38f).apply { color = Color.WHITE; letterSpacing = 0.12f }
        g.drawText("${cd.tier.label} SCRATCH", W / 2, midY(t2, 168f), t2)
        val t3 = text(26f).apply { color = c("#ffe7a8") }
        g.drawText("MATCH 3 AMOUNTS · WIN THAT AMOUNT", W / 2, midY(t3, 222f), t3)
        // printed play area under the foil
        for (i in 0 until 9) {
            val b = ScratchLayout.cell(i)
            g.drawRoundRect(RectF(b.l + 6, b.t + 6, b.r - 6, b.b - 6), 16f, 16f, fill().apply { color = c("#fff8e6") })
            val v = cd.cells[i]
            val p = text(if (v >= 1000) 50f else 62f).apply { shader = lin(0f, b.t, 0f, b.b, listOf(0f to "#7a2bd9", 1f to "#2a0057")) }
            g.drawText(nf.format(v), b.cx, midY(p, b.cy - 6f), p)
            val s = text(20f).apply { color = c("#6b5a7a") }; g.drawText("CREDITS", b.cx, midY(s, b.b - 30f), s)
        }
        for (i in 0 until 3) {
            val b = ScratchLayout.bonus(i)
            g.drawRoundRect(RectF(b.l, b.t, b.r, b.b), 16f, 16f, fill().apply { color = c("#1b0b33") })
            if (cd.stars[i]) SymbolArt.star(g, b.cx, b.cy, 46f, 19f, fill().apply { shader = lin(0f, b.t, 0f, b.b, SymbolArt.GOLD) })
            else { val p = text(54f).apply { color = c("#7fd4ff") }; g.drawText("♦", b.cx, midY(p, b.cy), p) }
        }
        val m = ScratchLayout.MULT
        g.drawRoundRect(RectF(m.l, m.t, m.r, m.b), 16f, 16f, fill().apply { shader = lin(0f, m.t, 0f, m.b, listOf(0f to "#ff3d7f", 1f to "#6b0a3a")) })
        val mp = text(70f).apply { color = Color.WHITE; setShadowLayer(12f, 0f, 0f, c("#ffd34d")) }; g.drawText("×${cd.multiplier}", m.cx, midY(mp, m.cy), mp)
        // labels + footer
        val lb = text(22f).apply { color = c("#ffe7a8") }
        g.drawText("3 ★ = FREE CARD", 56f + 186f, midY(lb, 954f), lb); g.drawText("SUPER SCRATCH", m.cx, midY(lb, 954f), lb)
        val ft = text(22f).apply { color = c("#d8c8f0") }
        g.drawText("PLAY MONEY · PRICE ${cd.tier.price} · RTP ${"%.0f".format(cd.tier.rtpTarget * 100)}% · TOP WIN ${nf.format(ScratchMath.topWin(cd.tier))}", W / 2, midY(ft, 1000f), ft)
    }

    private fun drawFoil(cd: ScratchCard) {
        foil.eraseColor(Color.TRANSPARENT)
        val silver = listOf(0f to "#f4f6f8", 0.45f to "#aeb4bd", 0.55f to "#d6dae0", 1f to "#8d939c")
        val p = fill().apply { shader = lin(0f, 0f, ScratchLayout.W.toFloat(), ScratchLayout.H.toFloat(), silver) }
        val grain = fill().apply { color = Color.argb(28, 0, 0, 0) }
        val label = text(30f).apply { color = c("#6c727c") }
        for (r in ScratchLayout.regions) {
            val rect = RectF(r.l + 2, r.t + 2, r.r - 2, r.b - 2)
            foilCanvas.drawRoundRect(rect, 16f, 16f, p)
            var y = r.t + 6; while (y < r.b) { foilCanvas.drawRect(r.l + 4, y, r.r - 4, y + 2, grain); y += 9 }
        }
        val c0 = ScratchLayout.cell(4); foilCanvas.drawText("SCRATCH", c0.cx, midY(label, c0.cy), label)
        val big = text(26f).apply { color = c("#6c727c") }
        foilCanvas.drawText("SCRATCH", ScratchLayout.MULT.cx, midY(big, ScratchLayout.MULT.cy), big)
        @Suppress("UNUSED_VARIABLE") val unused = cd
    }
}
