package com.zohar.slotar.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import com.zohar.slotar.screen.SymbolArt.GOLD
import com.zohar.slotar.screen.SymbolArt.c
import com.zohar.slotar.screen.SymbolArt.fill
import com.zohar.slotar.screen.SymbolArt.lin
import com.zohar.slotar.screen.SymbolArt.midY
import com.zohar.slotar.screen.SymbolArt.stroke
import com.zohar.slotar.screen.SymbolArt.text
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class Hud(
    val credits: Long = 0, val bet: Long = 0, val inFree: Boolean = false, val freeLeft: Int = 0, val freeTotal: Long = 0, val message: String = "",
)

/**
 * Draws the machine's video screen (720×1200 portrait) from a [ReelAnimator] into [bitmap].
 * Static parts are cached layers; text layers re-render only when their content changes.
 */
class ReelRenderer(private val a: ReelAnimator) {
    val width = ReelAnimator.SCREEN_W
    val height = ReelAnimator.SCREEN_H
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val cv = Canvas(bitmap)
    var hud = Hud(); set(v) { if (v != field) { field = v; a.dirty = true } }

    private val R = a.cfg.reels.size
    private val ROWS = a.cfg.rows
    private val pad = (width * 0.035f).roundToInt().toFloat()
    private val top = (height * 0.2f).roundToInt().toFloat()
    private val reelsH = (height * 0.6f).roundToInt().toFloat()
    private val gap = (width * 0.008f).roundToInt().toFloat()
    private val reelW = (width - pad * 2 - gap * (R - 1)) / R
    private val cellH = reelsH / ROWS
    private val metersY = top + reelsH + (height * 0.025f).roundToInt()
    private fun reelX(i: Int) = pad + i * (reelW + gap)

    private val L = mapOf(
        "credit" to "CREDIT", "bet" to "BET", "win" to "WIN", "playMoney" to "PLAY MONEY · FOR FUN", "freeSpins" to "FREE SPINS",
        "fsIntro" to "FREE SPINS!", "fsMult" to "ALL WINS ×3", "fsOutro" to "FREE SPINS TOTAL", "collected" to "WON:",
        "big" to "BIG WIN!", "mega" to "MEGA WIN!", "epic" to "EPIC WIN!",
    )
    private val nf = NumberFormat.getIntegerInstance(Locale.US)
    private fun fmt(n: Double) = nf.format(n.roundToInt())

    // symbol atlas (sharp + motion-blurred), drawn once
    private val sw = reelW.roundToInt(); private val sh = cellH.roundToInt()
    private val atlas = HashMap<String, Bitmap>(); private val blur = HashMap<String, Bitmap>()
    init {
        for (id in a.cfg.symbols) {
            val b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); SymbolArt.draw(Canvas(b), id, sw.toFloat(), sh.toFloat()); atlas[id] = b
            val bb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); val bc = Canvas(bb); val p = Paint().apply { alpha = 72 }
            for (k in -3..3) bc.drawBitmap(b, 0f, k * sh * 0.06f, p); blur[id] = bb
        }
    }
    private fun layer(w: Int, h: Int, f: (Canvas) -> Unit) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { f(Canvas(it)) }
    private val bgLayer = layer(width, height) { g ->
        g.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill().apply { shader = lin(0f, 0f, 0f, height.toFloat(), listOf(0f to "#120424", 0.5f to "#1d0636", 1f to "#07010f")) })
        val fr = RectF(pad - 6, top - 6, width - pad + 6, top + reelsH + 6)
        g.drawRoundRect(fr, 16f, 16f, fill().apply { color = c("#05010a") })
        g.drawRoundRect(fr, 16f, 16f, stroke(6f, 0).apply { shader = lin(0f, top, 0f, top + reelsH, GOLD) })
        for (i in 0 until R) g.drawRect(reelX(i), top, reelX(i) + reelW, top + reelsH, fill().apply { shader = lin(0f, top, 0f, top + reelsH, listOf(0f to "#1b0a33", 0.5f to "#2a1150", 1f to "#14062a")) })
    }
    private val glassLayer = layer(width, reelsH.roundToInt()) { g ->
        val p = fill().apply { shader = lin(0f, 0f, 0f, reelsH, listOf(0f to "#8c000000", 0.18f to "#00000000", 0.82f to "#00000000", 1f to "#99000000")) }
        for (i in 0 until R) g.drawRect(reelX(i), 0f, reelX(i) + reelW, reelsH, p)
        val sep = fill().apply { color = Color.argb(89, 255, 215, 90) }
        for (i in 1 until R) g.drawRect(reelX(i) - gap / 2 - 1, 0f, reelX(i) - gap / 2 + 1, reelsH, sep)
    }
    private class Cached(w: Int, h: Int) { var key: String? = null; val bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp) }
    private val headerL = Cached(width, top.roundToInt())
    private val metersL = Cached(width, (height - metersY).roundToInt())
    private val clipRect = RectF()

    fun draw() {
        cv.drawBitmap(bgLayer, 0f, 0f, null)
        val hk = if (hud.inFree) "F${hud.freeLeft}|${hud.freeTotal}" else "B"
        if (headerL.key != hk) { headerL.key = hk; headerL.cv.drawColor(0, PorterDuff.Mode.CLEAR); drawHeader(headerL.cv) }
        cv.drawBitmap(headerL.bmp, 0f, 0f, null)
        for (i in 0 until R) drawReel(i)
        cv.drawBitmap(glassLayer, 0f, top, null)
        if (a.phase == ReelAnimator.Phase.RESULT || a.phase == ReelAnimator.Phase.SHOW) drawWins()
        val winVal = if (a.phase != ReelAnimator.Phase.SPIN && a.result != null) floor(a.winShown) else 0.0
        val mk = "${hud.credits}|${hud.bet}|$winVal|${hud.message}"
        if (metersL.key != mk) { metersL.key = mk; metersL.cv.drawColor(0, PorterDuff.Mode.CLEAR); drawMeters(metersL.cv, winVal) }
        cv.drawBitmap(metersL.bmp, 0f, metersY, null)
        a.overlay?.let { drawOverlay(it) }
        drawParticles()
        a.dirty = false
    }

    private fun drawHeader(g: Canvas) {
        val h = top - pad * 0.6f
        if (hud.inFree) {
            g.drawRect(0f, 0f, width.toFloat(), top - 4, fill().apply { shader = lin(0f, 0f, width.toFloat(), 0f, listOf(0f to "#ff3d7f", 0.5f to "#ffb400", 1f to "#ff3d7f")); alpha = 46 })
            val p = text(h * 0.34f).apply { color = c("#ffe680"); setShadowLayer(18f, 0f, 0f, c("#ff7a00")) }
            g.drawText("${L["freeSpins"]}: ${hud.freeLeft}", width / 2f, midY(p, h * 0.38f), p)
            val p2 = text(h * 0.2f).apply { color = c("#ffd1f0") }
            g.drawText("${L["fsMult"]} · ${L["collected"]} ${fmt(hud.freeTotal.toDouble())}", width / 2f, midY(p2, h * 0.78f), p2)
        } else {
            val p = text(h * 0.56f); val y = midY(p, h * 0.48f)
            g.drawText("ZOHAR", width / 2f, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = 8f; color = c("#3a0a66"); setShadowLayer(26f, 0f, 0f, c("#c46bff")) })
            p.shader = lin(0f, h * 0.2f, 0f, h * 0.75f, GOLD); g.drawText("ZOHAR", width / 2f, y, p)
            val p2 = text(h * 0.15f).apply { color = c("#e8c7ff") }
            g.drawText(L.getValue("playMoney"), width / 2f, midY(p2, h * 0.9f), p2)
        }
    }

    private val src = Rect(); private val dst = RectF()
    private fun drawReel(i: Int) {
        val r = a.reels[i]; val x = reelX(i); val base = floor(r.pos).toInt(); val frac = (r.pos - base).toFloat()
        cv.save(); clipRect.set(x, top, x + reelW, top + reelsH); cv.clipRect(clipRect)
        src.set(x.toInt(), top.toInt(), (x + reelW).toInt(), (top + reelsH).toInt()); cv.drawBitmap(bgLayer, src, clipRect, null)
        for (row in -1..ROWS) {
            val id = r.strip[(((base + row) % r.n) + r.n) % r.n]
            val y = top + (row - frac) * cellH
            dst.set(x, y, x + reelW, y + cellH)
            cv.drawBitmap(if (r.spinning && r.speed > 12) blur.getValue(id) else atlas.getValue(id), null, dst, null)
        }
        if (r.antic) {
            val p = 0.5f + 0.5f * sin(System.nanoTime() / 9e7).toFloat()
            cv.drawRect(x + 3, top + 3, x + reelW - 3, top + reelsH - 3, stroke(6f, Color.argb((153 + 102 * p).toInt(), 255, (180 + 60 * p).toInt(), 40)).apply { setShadowLayer(25f, 0f, 0f, c("#ffb400")) })
        }
        cv.restore()
    }

    private fun cellCenter(row: Int, col: Int) = floatArrayOf(reelX(col) + reelW / 2, top + row * cellH + cellH / 2)
    private val lineColors = listOf("#ff4d6d", "#ffd166", "#06d6a0", "#4cc9f0", "#c77dff").map { c(it) }
    private fun drawWins() {
        val res = a.result ?: return
        val pulse = 0.5f + 0.5f * sin(a.phaseT * 8).toFloat()
        val wins = res.lineWins; val showAll = a.lineCycle == wins.size || wins.size == 1
        val shown = if (showAll) wins else listOf(wins[a.lineCycle])
        val cells = HashSet<Int>()
        shown.forEach { w -> for (k in 0 until w.count) cells.add(w.rows[k] * 10 + k) }
        if (res.scatter.win > 0 || res.fsStarted) res.scatter.cells.forEach { (r, col) -> cells.add(r * 10 + col) }
        if (cells.isNotEmpty()) {
            val dim = fill().apply { color = Color.argb(140, 5, 0, 15) }
            for (row in 0 until ROWS) for (col in 0 until R) if (row * 10 + col !in cells) cv.drawRect(reelX(col), top + row * cellH, reelX(col) + reelW, top + (row + 1) * cellH, dim)
            val fr = stroke(5f, Color.argb(255, 255, (200 + 55 * pulse).toInt(), 80)).apply { setShadowLayer(12 + 14 * pulse, 0f, 0f, c("#ffcc00")) }
            cells.forEach { k ->
                val row = k / 10; val col = k % 10; val x = reelX(col); val y = top + row * cellH
                cv.drawRoundRect(RectF(x + 5, y + 5, x + reelW - 5, y + cellH - 5), 12f, 12f, fr)
                val s = 1 + 0.06f * pulse
                dst.set(x + reelW * (1 - s) / 2, y + cellH * (1 - s) / 2, x + reelW * (1 - s) / 2 + reelW * s, y + cellH * (1 - s) / 2 + cellH * s)
                cv.drawBitmap(atlas.getValue(res.grid[row][col]), null, dst, null)
            }
        }
        shown.forEach { w ->
            val col = lineColors[w.line % lineColors.size]
            val p = stroke(6f, col).apply { alpha = 230; setShadowLayer(14f, 0f, 0f, col) }
            val path = Path()
            w.rows.forEachIndexed { cc, row -> val (x, y) = cellCenter(row, cc).let { it[0] to it[1] }; if (cc == 0) path.moveTo(x - reelW / 2, y) else path.lineTo(x, y); if (cc == 0) path.lineTo(x, y) }
            val last = cellCenter(w.rows[R - 1], R - 1); path.lineTo(last[0] + reelW / 2, last[1])
            cv.drawPath(path, p)
        }
    }

    private fun meter(g: Canvas, x: Float, w: Float, label: String, value: Double, hot: Boolean) {
        val h = height - metersY - pad * 0.6f
        val r = RectF(x, 0f, x + w, h * 0.62f)
        g.drawRoundRect(r, 12f, 12f, fill().apply { color = Color.argb(140, 0, 0, 0) })
        g.drawRoundRect(r, 12f, 12f, stroke(2f, if (hot) c("#ffd34d") else Color.argb(115, 255, 211, 77)))
        val lp = text(h * 0.14f).apply { color = c("#d8b4ff") }; g.drawText(label, x + w / 2, midY(lp, h * 0.16f), lp)
        val vp = text(h * 0.24f).apply { color = if (hot) c("#fff2a8") else Color.WHITE; if (hot) setShadowLayer(14f, 0f, 0f, c("#ffb400")) }
        g.drawText(fmt(value), x + w / 2, midY(vp, h * 0.42f), vp)
    }
    private fun drawMeters(g: Canvas, winVal: Double) {
        val w3 = (width - pad * 2 - 2 * 12) / 3
        meter(g, pad, w3, L.getValue("credit"), hud.credits.toDouble(), false)
        meter(g, pad + (w3 + 12), w3, L.getValue("bet"), hud.bet.toDouble(), false)
        meter(g, pad + 2 * (w3 + 12), w3, L.getValue("win"), winVal, winVal > 0)
        if (hud.message.isNotEmpty()) { val p = text((height - metersY) * 0.1f).apply { color = c("#ffd6f5") }; g.drawText(hud.message, width / 2f, height - metersY - pad * 1.1f, p) }
    }

    private fun drawOverlay(o: ReelAnimator.Overlay) {
        val k = min(1.0, o.t / 0.35); val s = ReelAnimator.outBack(k, 1.6).toFloat()
        cv.save(); cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill().apply { color = Color.argb((184 * k).toInt(), 10, 0, 25) })
        cv.translate(width / 2f, top + reelsH * 0.45f); cv.scale(s, s)
        val title = when (o.kind) {
            ReelAnimator.Overlay.Kind.FS_INTRO -> L["fsIntro"]; ReelAnimator.Overlay.Kind.FS_OUTRO -> L["fsOutro"]
            ReelAnimator.Overlay.Kind.EPIC -> L["epic"]; ReelAnimator.Overlay.Kind.MEGA -> L["mega"]; else -> L["big"]
        }!!
        val tp = text(width * (if (o.kind == ReelAnimator.Overlay.Kind.FS_OUTRO) 0.075f else 0.11f)); val ty = midY(tp, -width * 0.12f)
        cv.drawText(title, 0f, ty, Paint(tp).apply { style = Paint.Style.STROKE; strokeWidth = 10f; color = c("#4a0a00"); strokeJoin = Paint.Join.ROUND; setShadowLayer(30f, 0f, 0f, c("#ff8a00")) })
        tp.shader = lin(0f, -width * 0.2f, 0f, -width * 0.05f, GOLD); cv.drawText(title, 0f, ty, tp)
        val amount = if (o.kind == ReelAnimator.Overlay.Kind.FS_INTRO) "${o.amount}" else fmt(if (o.kind in ReelAnimator.BIG_KINDS) floor(a.winShown) else o.amount.toDouble())
        val ap = text(width * 0.16f); val ay = midY(ap, width * 0.07f)
        cv.drawText(amount, 0f, ay, Paint(ap).apply { style = Paint.Style.STROKE; strokeWidth = 10f; color = c("#4a0a00"); strokeJoin = Paint.Join.ROUND })
        cv.drawText(amount, 0f, ay, ap.apply { color = c("#fff7cc") })
        val sp = text(width * 0.05f).apply { color = c("#ffd1f0") }
        cv.drawText(if (o.kind == ReelAnimator.Overlay.Kind.FS_INTRO) "${L["freeSpins"]} · ${L["fsMult"]}" else L.getValue("playMoney"), 0f, midY(sp, width * 0.2f), sp)
        cv.restore()
    }
    private val coin = fill()
    private fun drawParticles() {
        a.particles.forEach { p ->
            cv.save(); cv.translate(p.x, p.y); cv.rotate(Math.toDegrees(p.a.toDouble()).toFloat()); cv.scale(kotlin.math.cos(p.a * 2).let { if (abs(it) < 0.05f) 0.05f else it }, 1f)
            coin.shader = lin(-p.r, 0f, p.r, 0f, GOLD); cv.drawCircle(0f, 0f, p.r, coin); cv.restore()
        }
    }
}
