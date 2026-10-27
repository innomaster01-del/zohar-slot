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
import com.zohar.slotar.screen.SymbolArt.SERIF
import com.zohar.slotar.screen.SymbolArt.c
import com.zohar.slotar.screen.SymbolArt.fill
import com.zohar.slotar.screen.SymbolArt.lin
import com.zohar.slotar.screen.SymbolArt.midY
import com.zohar.slotar.screen.SymbolArt.rad
import com.zohar.slotar.screen.SymbolArt.stroke
import com.zohar.slotar.screen.SymbolArt.text
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Pharaoh's Book video screen (720×1540 portrait, the whole J-curve: art on top, reels, meters, and the lower bend
 * that faces the player like a button deck). Draws only what the [ReelAnimator] holds.
 */
class PharaohRenderer(private val a: ReelAnimator) {
    val width = W; val height = H
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val cv = Canvas(bitmap)
    var hud = Hud(); set(v) { if (v != field) { field = v; a.dirty = true } }

    private val R = 5; private val ROWS = 3
    private val pad = 26f
    private val top = 360f
    private val cellH = 168f
    private val reelsH = cellH * ROWS
    private val gap = 8f
    private val reelW = (width - pad * 2 - gap * (R - 1)) / R
    private val metersY = top + reelsH + 26f
    private val deckY = 1080f
    private fun reelX(i: Int) = pad + i * (reelW + gap)
    private val nf = NumberFormat.getIntegerInstance(Locale.US)
    private fun fmt(n: Double) = nf.format(n.roundToInt())

    private val sw = reelW.roundToInt(); private val sh = cellH.roundToInt()
    private val atlas = HashMap<String, Bitmap>(); private val blur = HashMap<String, Bitmap>()
    private val tall = HashMap<String, Bitmap>()                // a whole reel tall, for the expanding symbol
    init {
        for (id in a.cfg.symbols) {
            val b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); PharaohArt.draw(Canvas(b), id, sw.toFloat(), sh.toFloat()); atlas[id] = b
            val bb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); val bc = Canvas(bb); val p = Paint().apply { alpha = 72 }
            for (k in -3..3) bc.drawBitmap(b, 0f, k * sh * 0.06f, p); blur[id] = bb
        }
    }
    private fun tallOf(id: String) = tall.getOrPut(id) {
        Bitmap.createBitmap(sw, reelsH.roundToInt(), Bitmap.Config.ARGB_8888).also { b ->
            val g = Canvas(b)
            g.drawRoundRect(RectF(2f, 2f, sw - 2f, reelsH - 2), 14f, 14f, fill().apply { shader = lin(0f, 0f, 0f, reelsH, listOf(0f to "#6b3a08", 0.5f to "#d99a14", 1f to "#6b3a08")) })
            g.drawRoundRect(RectF(6f, 6f, sw - 6f, reelsH - 6), 12f, 12f, fill().apply { shader = lin(0f, 0f, 0f, reelsH, listOf(0f to "#2a1606", 0.5f to "#4a2a0a", 1f to "#2a1606")) })
            g.save(); g.translate(0f, (reelsH - sw * 1.6f) / 2); PharaohArt.draw(g, id, sw.toFloat(), sw * 1.6f); g.restore()
            // light rays behind
        }
    }

    private fun layer(w: Int, h: Int, f: (Canvas) -> Unit) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { f(Canvas(it)) }
    private val bgLayer = layer(width, height) { g ->
        // temple wall
        g.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill().apply { shader = lin(0f, 0f, 0f, height.toFloat(), listOf(0f to "#2a1606", 0.4f to "#4a2a0c", 1f to "#160a02")) })
        val glyph = fill().apply { color = Color.argb(26, 255, 220, 150) }
        var y = top - 20; while (y < height) { var x = 12f; while (x < width) { g.drawRect(x, y, x + 18, y + 4, glyph); g.drawCircle(x + 30, y + 20, 7f, glyph); g.drawRect(x + 6, y + 34, x + 10, y + 52, glyph); x += 46 }; y += 62 }
        // sky + sun + pyramids in the header
        g.drawRect(0f, 0f, width.toFloat(), top - 24, fill().apply { shader = lin(0f, 0f, 0f, top - 24, listOf(0f to "#140a2e", 0.45f to "#8a2a3a", 0.8f to "#e0742a", 1f to "#f7b84a")) })
        val sun = fill().apply { shader = rad(width / 2f, top - 70, 190f, listOf(0f to "#fffbe0", 0.35f to "#ffd27a", 1f to "#00ffb040")) }
        g.drawCircle(width / 2f, top - 70, 190f, sun)
        val ray = fill().apply { color = Color.argb(34, 255, 220, 140) }
        for (i in 0 until 26) { val an = PI + i / 25.0 * PI; val p = Path(); p.moveTo(width / 2f, top - 70); p.lineTo((width / 2 + cos(an) * 900).toFloat(), (top - 70 + sin(an) * 900).toFloat()); p.lineTo((width / 2 + cos(an + 0.05) * 900).toFloat(), (top - 70 + sin(an + 0.05) * 900).toFloat()); p.close(); g.drawPath(p, ray) }
        g.drawCircle(width / 2f, top - 70, 70f, fill().apply { shader = rad(width / 2f, top - 80, 80f, listOf(0f to "#ffffff", 0.5f to "#fff0a0", 1f to "#ffb43a")) })
        for ((x, s, col) in listOf(Triple(130f, 170f, "#3a1e08"), Triple(width - 120f, 150f, "#3a1e08"), Triple(width / 2f + 40, 120f, "#4a2a0c"))) {
            val p = Path(); p.moveTo(x - s, top - 24); p.lineTo(x, top - 24 - s * 0.9f); p.lineTo(x + s, top - 24); p.close()
            g.drawPath(p, fill().apply { shader = lin(x - s, 0f, x + s, 0f, listOf(0f to "#c7863a", 0.5f to col, 1f to "#1a0c02")) })
        }
        g.drawRect(0f, top - 30, width.toFloat(), top - 20, fill().apply { shader = lin(0f, 0f, width.toFloat(), 0f, GOLD) })
        // reel window: gold frame, dark stone reels
        val fr = RectF(pad - 8, top - 8, width - pad + 8, top + reelsH + 8)
        g.drawRoundRect(fr, 18f, 18f, fill().apply { color = c("#0d0602") })
        g.drawRoundRect(fr, 18f, 18f, stroke(8f, 0).apply { shader = lin(0f, top, 0f, top + reelsH, GOLD) })
        for (i in 0 until R) g.drawRect(reelX(i), top, reelX(i) + reelW, top + reelsH, fill().apply { shader = lin(0f, top, 0f, top + reelsH, listOf(0f to "#2a1708", 0.5f to "#3d230c", 1f to "#1e0f04")) })
        // the lower bend: a deck in the glass (spin button art + rules)
        g.drawRect(0f, deckY, width.toFloat(), height.toFloat(), fill().apply { shader = lin(0f, deckY, 0f, height.toFloat(), listOf(0f to "#120802", 1f to "#050201")) })
        g.drawRect(0f, deckY, width.toFloat(), deckY + 6, fill().apply { shader = lin(0f, 0f, width.toFloat(), 0f, GOLD) })
        val rp = text(26f).apply { color = c("#ffd89a") }
        g.drawText("BOOK = WILD + SCATTER", width / 2f, deckY + 60, rp)
        g.drawText("3 BOOKS ANYWHERE = 10 FREE SPINS", width / 2f, deckY + 98, rp)
        val rp2 = text(22f).apply { color = c("#c8a878") }
        g.drawText("ONE SYMBOL EXPANDS IN FREE SPINS · 10 LINES", width / 2f, deckY + 132, rp2)
        val bx = width / 2f; val by = deckY + 240
        g.drawCircle(bx, by, 92f, fill().apply { shader = rad(bx, by - 20, 110f, listOf(0f to "#fff6c8", 0.5f to "#f2b630", 1f to "#7a4c04")); setShadowLayer(30f, 0f, 0f, c("#ffb43a")) })
        g.drawCircle(bx, by, 92f, stroke(5f, c("#5c3a00")))
        val sp = text(46f).apply { color = c("#3a1a00") }; g.drawText("SPIN", bx, midY(sp, by), sp)
        val lp = text(22f).apply { color = c("#ffd89a") }
        g.drawText("TAP THE SCREEN OR THE SPIN BUTTON", width / 2f, height - 46f, lp)
    }
    private val glassLayer = layer(width, reelsH.roundToInt()) { g ->
        val p = fill().apply { shader = lin(0f, 0f, 0f, reelsH, listOf(0f to "#a0000000", 0.18f to "#00000000", 0.82f to "#00000000", 1f to "#a0000000")) }
        for (i in 0 until R) g.drawRect(reelX(i), 0f, reelX(i) + reelW, reelsH, p)
        val sep = fill().apply { shader = lin(0f, 0f, 0f, reelsH, GOLD); alpha = 150 }
        for (i in 1 until R) g.drawRect(reelX(i) - gap / 2 - 1.5f, 0f, reelX(i) - gap / 2 + 1.5f, reelsH, sep)
    }
    private class Cached(w: Int, h: Int) { var key: String? = null; val bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp) }
    private val headerL = Cached(width, (top - 30).roundToInt())
    private val metersL = Cached(width, (deckY - metersY).roundToInt())

    fun draw() {
        cv.drawBitmap(bgLayer, 0f, 0f, null)
        val hk = if (hud.inFree) "F${hud.freeLeft}|${hud.freeTotal}|${a.result?.special}" else "B"
        if (headerL.key != hk) { headerL.key = hk; headerL.cv.drawColor(0, PorterDuff.Mode.CLEAR); drawHeader(headerL.cv) }
        cv.drawBitmap(headerL.bmp, 0f, 0f, null)
        for (i in 0 until R) drawReel(i)
        cv.drawBitmap(glassLayer, 0f, top, null)
        if (a.phase == ReelAnimator.Phase.RESULT || a.phase == ReelAnimator.Phase.SHOW) { drawExpansion(); drawWins() }
        val winVal = if (a.phase != ReelAnimator.Phase.SPIN && a.result != null) floor(a.winShown) else 0.0
        val mk = "${hud.credits}|${hud.bet}|$winVal|${hud.message}"
        if (metersL.key != mk) { metersL.key = mk; metersL.cv.drawColor(0, PorterDuff.Mode.CLEAR); drawMeters(metersL.cv, winVal) }
        cv.drawBitmap(metersL.bmp, 0f, metersY, null)
        a.overlay?.let { drawOverlay(it) }
        drawParticles()
        a.dirty = false
    }

    private fun drawHeader(g: Canvas) {
        if (hud.inFree) {
            g.drawRect(0f, 0f, width.toFloat(), top - 30, fill().apply { color = Color.argb(150, 20, 8, 2) })
            val p = text(64f, SERIF).apply { shader = lin(0f, 40f, 0f, 120f, GOLD); setShadowLayer(18f, 0f, 0f, c("#ff8a00")) }
            g.drawText("FREE SPINS ${hud.freeLeft}", width / 2f, midY(p, 95f), p)
            val p2 = text(26f).apply { color = c("#ffe2a0") }
            g.drawText("WON ${fmt(hud.freeTotal.toDouble())} · EXPANDING SYMBOL", width / 2f, midY(p2, 160f), p2)
            a.result?.special?.let { s -> val r = RectF(width / 2f - 70, 190f, width / 2f + 70, 320f); g.drawRoundRect(r, 16f, 16f, fill().apply { color = c("#1a0c02") }); g.drawRoundRect(r, 16f, 16f, stroke(5f, 0).apply { shader = lin(0f, 190f, 0f, 320f, GOLD) }); g.save(); g.translate(r.left, r.top); PharaohArt.draw(g, s, r.width(), r.height()); g.restore() }
        } else {
            val t1 = text(84f, SERIF); val t2 = text(110f, SERIF)
            for ((s, y, p) in listOf(Triple("PHARAOH'S", 100f, t1), Triple("BOOK", 210f, t2))) {
                val yy = midY(p, y)
                g.drawText(s, width / 2f, yy, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = 12f; color = c("#2a1000"); strokeJoin = Paint.Join.ROUND; setShadowLayer(26f, 0f, 0f, c("#ff9a1a")) })
                p.shader = lin(0f, y - 50, 0f, y + 50, GOLD); g.drawText(s, width / 2f, yy, p)
            }
            val p3 = text(22f).apply { color = c("#ffe9c0") }
            g.drawText("PLAY MONEY · FOR FUN", width / 2f, midY(p3, 300f), p3)
        }
    }

    private val src = Rect(); private val dst = RectF(); private val clip = RectF()
    private fun drawReel(i: Int) {
        val r = a.reels[i]; val x = reelX(i); val base = floor(r.pos).toInt(); val frac = (r.pos - base).toFloat()
        cv.save(); clip.set(x, top, x + reelW, top + reelsH); cv.clipRect(clip)
        src.set(x.toInt(), top.toInt(), (x + reelW).toInt(), (top + reelsH).toInt()); cv.drawBitmap(bgLayer, src, clip, null)
        for (row in -1..ROWS) {
            val id = r.strip[(((base + row) % r.n) + r.n) % r.n]
            val y = top + (row - frac) * cellH
            dst.set(x, y, x + reelW, y + cellH)
            cv.drawBitmap(if (r.spinning && r.speed > 12) blur.getValue(id) else atlas.getValue(id), null, dst, null)
        }
        if (r.antic) {
            val p = 0.5f + 0.5f * sin(System.nanoTime() / 9e7).toFloat()
            cv.drawRect(x + 3, top + 3, x + reelW - 3, top + reelsH - 3, stroke(6f, Color.argb((153 + 102 * p).toInt(), 255, (170 + 60 * p).toInt(), 40)).apply { setShadowLayer(25f, 0f, 0f, c("#ffb400")) })
        }
        cv.restore()
    }

    /** Free spins: the drawn symbol grows over every reel it shows on (first ~0.8 s of the result), then stays. */
    private fun drawExpansion() {
        val res = a.result ?: return
        if (res.expand.isEmpty() || res.special == null) return
        val k = if (a.phase == ReelAnimator.Phase.RESULT) min(1.0, a.phaseT / 0.8).toFloat() else 1f
        val img = tallOf(res.special)
        for (col in res.expand) {
            val x = reelX(col); val hh = reelsH * (0.2f + 0.8f * k)
            dst.set(x, top + (reelsH - hh) / 2, x + reelW, top + (reelsH + hh) / 2)
            cv.drawBitmap(img, null, dst, Paint().apply { alpha = (255 * min(1f, k * 1.6f)).toInt() })
            val glow = 0.5f + 0.5f * sin(a.phaseT * 7).toFloat()
            cv.drawRoundRect(dst, 14f, 14f, stroke(6f, Color.argb(255, 255, (190 + 60 * glow).toInt(), 60)).apply { setShadowLayer(18 + 16 * glow, 0f, 0f, c("#ffb43a")) })
        }
    }

    private fun cellCenter(row: Int, col: Int) = floatArrayOf(reelX(col) + reelW / 2, top + row * cellH + cellH / 2)
    private val lineColors = listOf("#ffcf4a", "#5ad0ff", "#ff6b4a", "#7dffb0", "#c58cff").map { c(it) }
    private fun drawWins() {
        val res = a.result ?: return
        val pulse = 0.5f + 0.5f * sin(a.phaseT * 8).toFloat()
        val wins = res.lineWins; val showAll = a.lineCycle == wins.size || wins.size == 1
        val shown = if (wins.isEmpty()) emptyList() else if (showAll) wins else listOf(wins[a.lineCycle])
        val cells = HashSet<Int>()
        shown.forEach { w -> for (k in 0 until w.count) cells.add(w.rows[k] * 10 + k) }
        if (res.scatter.win > 0 || res.fsStarted) res.scatter.cells.forEach { (r, col) -> cells.add(r * 10 + col) }
        if (cells.isNotEmpty()) {
            val dim = fill().apply { color = Color.argb(130, 10, 4, 0) }
            for (row in 0 until ROWS) for (col in 0 until R) if (row * 10 + col !in cells && col !in res.expand) cv.drawRect(reelX(col), top + row * cellH, reelX(col) + reelW, top + (row + 1) * cellH, dim)
            val fr = stroke(5f, Color.argb(255, 255, (200 + 55 * pulse).toInt(), 80)).apply { setShadowLayer(12 + 14 * pulse, 0f, 0f, c("#ffcc00")) }
            cells.forEach { k ->
                val row = k / 10; val col = k % 10; if (col in res.expand) return@forEach
                val x = reelX(col); val y = top + row * cellH
                cv.drawRoundRect(RectF(x + 5, y + 5, x + reelW - 5, y + cellH - 5), 12f, 12f, fr)
                val s = 1 + 0.07f * pulse
                dst.set(x + reelW * (1 - s) / 2, y + cellH * (1 - s) / 2, x + reelW * (1 - s) / 2 + reelW * s, y + cellH * (1 - s) / 2 + cellH * s)
                cv.drawBitmap(atlas.getValue(res.grid[row][col]), null, dst, null)
            }
        }
        shown.forEach { w ->
            val col = lineColors[w.line % lineColors.size]
            val p = stroke(6f, col).apply { alpha = 230; setShadowLayer(14f, 0f, 0f, col) }
            val path = Path()
            w.rows.forEachIndexed { cc, row -> val ce = cellCenter(row, cc); if (cc == 0) { path.moveTo(ce[0] - reelW / 2, ce[1]); path.lineTo(ce[0], ce[1]) } else path.lineTo(ce[0], ce[1]) }
            val last = cellCenter(w.rows[R - 1], R - 1); path.lineTo(last[0] + reelW / 2, last[1])
            cv.drawPath(path, p)
        }
    }

    private fun meter(g: Canvas, x: Float, w: Float, label: String, value: Double, hot: Boolean) {
        val h = 104f
        val r = RectF(x, 0f, x + w, h)
        g.drawRoundRect(r, 14f, 14f, fill().apply { shader = lin(0f, 0f, 0f, h, listOf(0f to "#2a1606", 1f to "#120802")) })
        g.drawRoundRect(r, 14f, 14f, stroke(3f, 0).apply { shader = lin(0f, 0f, 0f, h, GOLD); if (!hot) alpha = 150 })
        val lp = text(22f).apply { color = c("#e8c890") }; g.drawText(label, x + w / 2, midY(lp, 26f), lp)
        val vp = text(40f).apply { color = if (hot) c("#fff2a8") else Color.WHITE; if (hot) setShadowLayer(14f, 0f, 0f, c("#ffb400")) }
        g.drawText(fmt(value), x + w / 2, midY(vp, 68f), vp)
    }
    private fun drawMeters(g: Canvas, winVal: Double) {
        val w3 = (width - pad * 2 - 2 * 12) / 3
        meter(g, pad, w3, "CREDIT", hud.credits.toDouble(), false)
        meter(g, pad + (w3 + 12), w3, "BET", hud.bet.toDouble(), false)
        meter(g, pad + 2 * (w3 + 12), w3, "WIN", winVal, winVal > 0)
        if (hud.message.isNotEmpty()) { val p = text(24f).apply { color = c("#ffe2a0") }; g.drawText(hud.message, width / 2f, 140f, p) }
    }

    private fun drawOverlay(o: ReelAnimator.Overlay) {
        val k = min(1.0, o.t / 0.35); val s = ReelAnimator.outBack(k, 1.6).toFloat()
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill().apply { color = Color.argb((190 * k).toInt(), 15, 6, 0) })
        val cy = top + reelsH * 0.45f
        // rotating sun rays behind every overlay
        cv.save(); cv.translate(width / 2f, cy); cv.rotate((o.t * 25).toFloat())
        val ray = fill().apply { color = Color.argb((60 * k).toInt(), 255, 210, 120) }
        for (i in 0 until 18) { val p = Path(); val an = i / 18.0 * 2 * PI; p.moveTo(0f, 0f); p.lineTo((cos(an) * 800).toFloat(), (sin(an) * 800).toFloat()); p.lineTo((cos(an + 0.12) * 800).toFloat(), (sin(an + 0.12) * 800).toFloat()); p.close(); cv.drawPath(p, ray) }
        cv.restore()
        if (o.kind == ReelAnimator.Overlay.Kind.FS_INTRO) { drawBookIntro(o, cy); return }
        cv.save(); cv.translate(width / 2f, cy); cv.scale(s, s)
        val title = when (o.kind) { ReelAnimator.Overlay.Kind.FS_OUTRO -> "FREE SPINS WON"; ReelAnimator.Overlay.Kind.EPIC -> "EPIC WIN!"; ReelAnimator.Overlay.Kind.MEGA -> "MEGA WIN!"; else -> "BIG WIN!" }
        val tp = text(width * 0.11f, SERIF); val ty = midY(tp, -width * 0.13f)
        cv.drawText(title, 0f, ty, Paint(tp).apply { style = Paint.Style.STROKE; strokeWidth = 12f; color = c("#3a1200"); strokeJoin = Paint.Join.ROUND; setShadowLayer(30f, 0f, 0f, c("#ff8a00")) })
        tp.shader = lin(0f, -width * 0.2f, 0f, -width * 0.06f, GOLD); cv.drawText(title, 0f, ty, tp)
        val amount = fmt(if (o.kind in ReelAnimator.BIG_KINDS) floor(a.winShown) else o.amount.toDouble())
        val ap = text(width * 0.16f); val ay = midY(ap, width * 0.07f)
        cv.drawText(amount, 0f, ay, Paint(ap).apply { style = Paint.Style.STROKE; strokeWidth = 12f; color = c("#3a1200"); strokeJoin = Paint.Join.ROUND })
        cv.drawText(amount, 0f, ay, ap.apply { color = c("#fff7cc") })
        cv.restore()
    }

    /** Free spins intro: the book opens, its pages flicker through the symbols and stop on the expanding one. */
    private fun drawBookIntro(o: ReelAnimator.Overlay, cy: Float) {
        val res = a.result ?: return
        val t = o.t.toFloat()
        val open = min(1f, t / 0.6f)
        val bw = 300f; val bh = 380f
        cv.save(); cv.translate(width / 2f, cy)
        // pages
        cv.drawRoundRect(RectF(-bw, -bh / 2, bw, bh / 2), 18f, 18f, fill().apply { shader = lin(0f, -bh / 2, 0f, bh / 2, listOf(0f to "#f7ecd0", 1f to "#d8c49a")); setShadowLayer(40f, 0f, 0f, c("#ffcf4a")) })
        cv.drawLine(0f, -bh / 2, 0f, bh / 2, stroke(4f, c("#8a6a3a")))
        // covers swinging open
        for (sx in listOf(-1f, 1f)) {
            val wv = bw * (1 - open) + 2f
            val r = if (sx < 0) RectF(-wv, -bh / 2 - 6, 0f, bh / 2 + 6) else RectF(0f, -bh / 2 - 6, wv, bh / 2 + 6)
            if (open < 1f) cv.drawRoundRect(r, 12f, 12f, fill().apply { shader = lin(r.left, 0f, r.right, 0f, listOf(0f to "#c8321e", 1f to "#5a0a06")) })
        }
        // symbol flicker on the right page, then the chosen one
        val syms = a.cfg.expandable
        val id = if (t < 1.7f) syms[((t * 14).toInt()) % syms.size] else (res.special ?: syms[0])
        val sz = if (t < 1.7f) 220f else 220f * (1f + 0.1f * sin((t - 1.7f) * 10).let { abs(it) })
        if (open > 0.5f) { cv.save(); cv.translate(bw / 2 - sz / 2, -sz / 2); PharaohArt.draw(cv, id, sz, sz); cv.restore()
            val p = text(30f, SERIF).apply { color = c("#4a2a0a") }
            cv.drawText("10 FREE", -bw / 2, -40f, p); cv.drawText("SPINS", -bw / 2, 0f, p)
            if (t >= 1.7f) { val q = text(22f).apply { color = c("#7a1a0a") }; cv.drawText("EXPANDS!", -bw / 2, 60f, q) } }
        cv.restore()
        val tp = text(64f, SERIF).apply { shader = lin(0f, cy - bh / 2 - 90, 0f, cy - bh / 2 - 30, GOLD); setShadowLayer(24f, 0f, 0f, c("#ff8a00")) }
        cv.drawText("FREE SPINS!", width / 2f, midY(tp, cy - bh / 2 - 60), tp)
    }

    private val coin = fill()
    private fun drawParticles() {
        a.particles.forEach { p ->
            cv.save(); cv.translate(p.x, p.y); cv.rotate(Math.toDegrees(p.a.toDouble()).toFloat()); cv.scale(cos(p.a * 2).let { if (abs(it) < 0.05f) 0.05f else it }, 1f)
            coin.shader = lin(-p.r, 0f, p.r, 0f, GOLD); cv.drawCircle(0f, 0f, p.r, coin); cv.restore()
        }
    }

    companion object { const val W = 720; const val H = 1540 }
}
