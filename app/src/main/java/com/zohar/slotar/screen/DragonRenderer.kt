package com.zohar.slotar.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import com.zohar.slotar.DragonGame
import com.zohar.slotar.HoldWin
import com.zohar.slotar.engine.Dragon
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

private val NF = NumberFormat.getIntegerInstance(Locale.US)
private fun fmt(n: Double) = NF.format(n.roundToInt())
private fun fmt(n: Long) = NF.format(n)

/** Gold cloud scroll (xiangyun) used as an ornament on the screen and the topper. */
internal fun cloud(g: Canvas, x: Float, y: Float, s: Float, p: Paint) {
    val path = Path()
    path.addCircle(x, y, s * 0.5f, Path.Direction.CW); path.addCircle(x + s * 0.55f, y - s * 0.18f, s * 0.42f, Path.Direction.CW)
    path.addCircle(x + s * 1.05f, y, s * 0.36f, Path.Direction.CW); path.addCircle(x - s * 0.5f, y + s * 0.08f, s * 0.3f, Path.Direction.CW)
    g.drawPath(path, p)
}

/** The dragon character 龍 in embossed gold with a soft red glow (moon, topper, Hold & Win intro). */
internal fun seal(g: Canvas, cx: Float, cy: Float, size: Float, alpha: Int) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD); textAlign = Paint.Align.CENTER }
    val y = midY(p, cy)
    g.drawText("龍", cx, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = size * 0.05f; color = c("#5a0004"); strokeJoin = Paint.Join.ROUND; this.alpha = alpha; setShadowLayer(size * 0.15f, 0f, 0f, c("#ff3a1a")) })
    p.shader = lin(0f, cy - size / 2, 0f, cy + size / 2, listOf(0f to "#fff6c8", 0.4f to "#ffd34d", 0.75f to "#d99a14", 1f to "#8a5a06")); p.alpha = alpha
    g.drawText("龍", cx, y, p)
}

/** A stylised dragon body: a long S curve of scales with a head. [k] (0..1) animates the curl. */
internal fun dragon(g: Canvas, x0: Float, y0: Float, len: Float, k: Float, alpha: Int = 255) {
    val n = 46
    val body = fill().apply { shader = lin(x0, 0f, x0 + len, 0f, listOf(0f to "#ffe08a", 0.5f to "#f2a516", 1f to "#b8640a")); this.alpha = alpha }
    val edge = stroke(2f, c("#6a2a00")).apply { this.alpha = alpha }
    for (i in n downTo 0) {
        val u = i / n.toFloat()
        val x = x0 + u * len
        val y = y0 + sin(u * 2.6f * PI.toFloat() + k * 2 * PI.toFloat()) * len * 0.09f
        val r = len * (0.012f + 0.03f * sin(u * PI.toFloat()).coerceAtLeast(0.25f))
        g.drawCircle(x, y, r, body); g.drawCircle(x, y, r, edge)
    }
    val hx = x0 + len; val hy = y0 + sin(2.6f * PI.toFloat() + k * 2 * PI.toFloat()) * len * 0.09f
    val head = Path().apply { moveTo(hx - len * 0.02f, hy - len * 0.04f); lineTo(hx + len * 0.09f, hy - len * 0.015f); lineTo(hx + len * 0.1f, hy + len * 0.02f); lineTo(hx - len * 0.02f, hy + len * 0.045f); close() }
    g.drawPath(head, fill().apply { shader = lin(hx, hy - 30, hx, hy + 30, listOf(0f to "#ffe9a8", 1f to "#c76a0a")); this.alpha = alpha })
    g.drawPath(head, edge)
    g.drawCircle(hx + len * 0.035f, hy - len * 0.012f, len * 0.008f, fill().apply { color = c("#ff2a10"); this.alpha = alpha })
    val wh = stroke(len * 0.004f, c("#fff2c0")).apply { this.alpha = alpha }
    g.drawLine(hx + len * 0.09f, hy, hx + len * 0.16f, hy - len * 0.05f, wh); g.drawLine(hx + len * 0.09f, hy + len * 0.01f, hx + len * 0.17f, hy + len * 0.04f, wh)
}

/**
 * Dragon Fortune's main video screen (720×1140, the C-curve: title, reels, meters and the lower bend with the rules).
 * Base game reels come from the [ReelAnimator]; the Hold & Win board comes from [game].hold. Shows only engine results.
 */
class DragonRenderer(private val a: ReelAnimator, private val game: DragonGame) {
    val width = W; val height = H
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val cv = Canvas(bitmap)
    var dirty = true
    var credits = 0L; set(v) { if (v != field) { field = v; dirty = true } }
    var message = ""; set(v) { if (v != field) { field = v; dirty = true } }

    private val R = 5; private val ROWS = 3
    private val pad = 22f
    private val top = 316f
    private val cellH = 168f
    private val reelsH = cellH * ROWS
    private val gap = 8f
    private val reelW = (width - pad * 2 - gap * (R - 1)) / R
    private val metersY = top + reelsH + 26f
    private val deckY = 978f
    private fun reelX(i: Int) = pad + i * (reelW + gap)

    private val sw = reelW.roundToInt(); private val sh = cellH.roundToInt()
    private val atlas = HashMap<String, Bitmap>(); private val blur = HashMap<String, Bitmap>()
    private val coins = HashMap<String, Bitmap>()
    init {
        for (id in a.cfg.symbols) {
            val b = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); DragonArt.draw(Canvas(b), id, sw.toFloat(), sh.toFloat()); atlas[id] = b
            val bb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); val bc = Canvas(bb); val p = Paint().apply { alpha = 72 }
            for (k in -3..3) bc.drawBitmap(b, 0f, k * sh * 0.06f, p); blur[id] = bb
        }
    }
    private fun coinLabel(v: Int) = Dragon.label(v) ?: fmt(v.toLong() * (game.last?.bet ?: game.engine.bet))
    private fun coinBmp(label: String) = coins.getOrPut(label) {
        if (coins.size > 60) coins.clear()
        Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also { b -> DragonArt.draw(Canvas(b), "COIN", sw.toFloat(), sh.toFloat(), label) }
    }

    private fun layer(w: Int, h: Int, f: (Canvas) -> Unit) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { f(Canvas(it)) }
    private val bgLayer = layer(width, height) { g ->
        g.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill().apply { shader = lin(0f, 0f, 0f, height.toFloat(), listOf(0f to "#3a0004", 0.35f to "#7a0a0e", 0.7f to "#3a0004", 1f to "#120001")) })
        // lattice pattern
        val lat = stroke(2f, Color.argb(30, 255, 200, 120))
        var y = top - 10; while (y < height) { var x = 0f; while (x < width) { g.drawRect(x + 6, y + 6, x + 40, y + 40, lat); g.drawLine(x + 6, y + 23, x + 40, y + 23, lat); x += 46 }; y += 46 }
        // header sky: deep red with a golden moon, clouds and a dragon
        g.drawRect(0f, 0f, width.toFloat(), top - 20, fill().apply { shader = lin(0f, 0f, 0f, top - 20, listOf(0f to "#120004", 0.6f to "#5a0610", 1f to "#a8141a")) })
        g.drawCircle(width * 0.875f, 104f, 100f, fill().apply { shader = rad(width * 0.875f, 104f, 140f, listOf(0f to "#fff2c0", 0.4f to "#ffcf4a", 0.75f to "#40ff7a1a", 1f to "#00ff7a1a")) })
        val cl = fill().apply { shader = lin(0f, 150f, 0f, 300f, listOf(0f to "#ffe08a", 1f to "#b8640a")); alpha = 200 }
        for ((x, yy, s) in listOf(Triple(60f, 250f, 70f), Triple(250f, 275f, 56f), Triple(470f, 262f, 64f), Triple(660f, 280f, 50f))) cloud(g, x, yy, s, cl)
        g.drawRect(0f, top - 26, width.toFloat(), top - 16, fill().apply { shader = lin(0f, 0f, width.toFloat(), 0f, GOLD) })
        // reel window
        val fr = RectF(pad - 9, top - 9, width - pad + 9, top + reelsH + 9)
        g.drawRoundRect(fr, 20f, 20f, fill().apply { color = c("#0a0001") })
        g.drawRoundRect(fr, 20f, 20f, stroke(9f, 0).apply { shader = lin(0f, top, 0f, top + reelsH, GOLD) })
        for (i in 0 until R) g.drawRect(reelX(i), top, reelX(i) + reelW, top + reelsH, fill().apply { shader = lin(0f, top, 0f, top + reelsH, listOf(0f to "#1a0204", 0.5f to "#3a060a", 1f to "#140102")) })
        // lower bend: rules + spin art
        g.drawRect(0f, deckY, width.toFloat(), height.toFloat(), fill().apply { shader = lin(0f, deckY, 0f, height.toFloat(), listOf(0f to "#1a0103", 1f to "#050000")) })
        g.drawRect(0f, deckY, width.toFloat(), deckY + 6, fill().apply { shader = lin(0f, 0f, width.toFloat(), 0f, GOLD) })
        val rp = text(26f).apply { color = c("#ffd89a") }
        g.drawText("243 WAYS · 龍 WILD ON REELS 2–4", width / 2f, deckY + 46, rp)
        g.drawText("6+ COINS = HOLD & WIN · 3 RESPINS", width / 2f, deckY + 84, rp)
        val rp2 = text(22f).apply { color = c("#d8a878") }
        g.drawText("FILL ALL 15 CELLS = GRAND 1000× BET · TAP TO SPIN", width / 2f, deckY + 120, rp2)
    }
    private val glassLayer = layer(width, reelsH.roundToInt()) { g ->
        val p = fill().apply { shader = lin(0f, 0f, 0f, reelsH, listOf(0f to "#b0000000", 0.18f to "#00000000", 0.82f to "#00000000", 1f to "#b0000000")) }
        for (i in 0 until R) g.drawRect(reelX(i), 0f, reelX(i) + reelW, reelsH, p)
        val sep = fill().apply { shader = lin(0f, 0f, 0f, reelsH, GOLD); alpha = 150 }
        for (i in 1 until R) g.drawRect(reelX(i) - gap / 2 - 1.5f, 0f, reelX(i) - gap / 2 + 1.5f, reelsH, sep)
    }
    private class Cached(w: Int, h: Int) { var key: String? = null; val bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp) }
    private val headerL = Cached(width, (top - 26).roundToInt())
    private val metersL = Cached(width, (deckY - metersY).roundToInt())

    fun burst(n: Int) = a.burst(n)
    /** True while something on screen moves on its own clock (win pulses, Hold & Win). */
    fun animating() = game.hold != null || (a.phase == ReelAnimator.Phase.RESULT && game.last?.wayWins?.isNotEmpty() == true)

    private val now get() = System.nanoTime() / 1e9

    fun draw() {
        cv.drawBitmap(bgLayer, 0f, 0f, null)
        val h = game.hold
        val hk = if (h != null) "H${h.left}|${h.locked.size}" else "B"
        if (headerL.key != hk) { headerL.key = hk; headerL.cv.drawColor(0, PorterDuff.Mode.CLEAR); drawHeader(headerL.cv, h) }
        cv.drawBitmap(headerL.bmp, 0f, 0f, null)
        if (h != null) drawHold(h) else {
            for (i in 0 until R) drawReel(i)
            cv.drawBitmap(glassLayer, 0f, top, null)
            if (a.phase == ReelAnimator.Phase.RESULT || a.phase == ReelAnimator.Phase.SHOW) drawWays()
        }
        val last = game.last
        val winVal = when {
            h != null -> (last?.waysWin ?: 0L).toDouble() + h.collected
            a.phase != ReelAnimator.Phase.SPIN && last != null -> floor(a.winShown) + (if (last.holdWin) last.hwWin else 0L)
            else -> 0.0
        }
        val mk = "$credits|${game.engine.bet}|$winVal|$message"
        if (metersL.key != mk) { metersL.key = mk; metersL.cv.drawColor(0, PorterDuff.Mode.CLEAR); drawMeters(metersL.cv, winVal) }
        cv.drawBitmap(metersL.bmp, 0f, metersY, null)
        a.overlay?.let { drawOverlay(it.kind.name, it.t, floor(a.winShown), it.dur) }
        if (h != null) when (h.stage) {
            HoldWin.Stage.INTRO -> drawOverlay("HOLD", h.t, h.locked.size.toDouble(), 2.2)
            HoldWin.Stage.GRAND -> drawOverlay("GRAND", h.t, Dragon.GRAND.toDouble() * h.res.bet, 3.5)
            HoldWin.Stage.TOTAL -> drawOverlay("TOTAL", h.t, h.collected.toDouble(), 3.0)
            else -> {}
        }
        drawParticles()
        dirty = false; a.dirty = false
    }

    private fun drawHeader(g: Canvas, h: HoldWin?) {
        if (h != null) {
            g.drawRect(0f, 0f, width.toFloat(), top - 26, fill().apply { color = Color.argb(160, 30, 0, 2) })
            val p = text(70f, SERIF).apply { shader = lin(0f, 50f, 0f, 130f, GOLD); setShadowLayer(20f, 0f, 0f, c("#ff3a1a")) }
            g.drawText("HOLD & WIN", width / 2f, midY(p, 92f), p)
            val p2 = text(28f).apply { color = c("#ffe2a0") }
            g.drawText("RESPINS LEFT", width / 2f, midY(p2, 168f), p2)
            for (i in 0 until 3) {
                val x = width / 2f + (i - 1) * 86f; val on = i < h.left
                g.drawCircle(x, 236f, 32f, fill().apply { shader = if (on) rad(x, 226f, 40f, listOf(0f to "#fff6c8", 0.5f to "#ffcf4a", 1f to "#a86a00")) else null; if (!on) color = c("#3a0a0a"); if (on) setShadowLayer(16f, 0f, 0f, c("#ffb400")) })
                g.drawCircle(x, 236f, 32f, stroke(3f, c("#ffd34d")))
                val np = text(32f).apply { color = if (on) c("#5a1a00") else c("#7a4a3a") }; g.drawText("${i + 1}", x, midY(np, 236f), np)
            }
        } else {
            seal(g, width * 0.875f, 104f, 112f, 170)
            val t1 = text(96f, SERIF); val t2 = text(80f, SERIF)
            for ((s, y, p) in listOf(Triple("DRAGON", 108f, t1), Triple("FORTUNE", 200f, t2))) {
                val yy = midY(p, y)
                g.drawText(s, width / 2f, yy, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = 12f; color = c("#2a0000"); strokeJoin = Paint.Join.ROUND; setShadowLayer(26f, 0f, 0f, c("#ff2a1a")) })
                p.shader = lin(0f, y - 48, 0f, y + 48, GOLD); g.drawText(s, width / 2f, yy, p)
            }
            val p3 = text(22f).apply { color = c("#ffe9c0") }
            g.drawText("243 WAYS · HOLD & WIN · PLAY MONEY", width / 2f, midY(p3, 266f), p3)
        }
    }

    private val src = Rect(); private val dst = RectF(); private val clip = RectF()
    private fun drawReel(i: Int) {
        val r = a.reels[i]; val x = reelX(i); val base = floor(r.pos).toInt(); val frac = (r.pos - base).toFloat()
        cv.save(); clip.set(x, top, x + reelW, top + reelsH); cv.clipRect(clip)
        src.set(x.toInt(), top.toInt(), (x + reelW).toInt(), (top + reelsH).toInt()); cv.drawBitmap(bgLayer, src, clip, null)
        val res = game.last
        for (row in -1..ROWS) {
            val id = r.strip[(((base + row) % r.n) + r.n) % r.n]
            val y = top + (row - frac) * cellH
            dst.set(x, y, x + reelW, y + cellH)
            val bmp = when {
                r.spinning && r.speed > 12 -> blur.getValue(id)
                !r.spinning && id == "COIN" && row in 0 until ROWS && res != null -> res.coins[row * 5 + i]?.let { coinBmp(coinLabel(it)) } ?: atlas.getValue(id)
                else -> atlas.getValue(id)
            }
            cv.drawBitmap(bmp, null, dst, null)
        }
        if (r.antic) {
            val p = 0.5f + 0.5f * sin(System.nanoTime() / 9e7).toFloat()
            cv.drawRect(x + 3, top + 3, x + reelW - 3, top + reelsH - 3, stroke(6f, Color.argb((153 + 102 * p).toInt(), 255, (90 + 80 * p).toInt(), 30)).apply { setShadowLayer(25f, 0f, 0f, c("#ff4a1a")) })
        }
        cv.restore()
    }

    private val wayColors = listOf("#ffcf4a", "#ff6b4a", "#7dffb0", "#5ad0ff", "#e2a0ff").map { c(it) }
    private fun drawWays() {
        val res = game.last ?: return
        val wins = res.wayWins
        val pulse = 0.5f + 0.5f * sin(a.phaseT * 8).toFloat()
        val coinsLit = res.holdWin
        if (wins.isEmpty() && !coinsLit) return
        val cyc = ((a.phaseT / 1.1).toInt()) % (wins.size + 1)
        val shown = if (wins.size <= 1 || cyc == wins.size) wins else listOf(wins[cyc])
        val cells = HashSet<Int>()
        shown.forEach { w -> w.cells.forEach { (r, col) -> cells.add(r * 5 + col) } }
        if (coinsLit) cells.addAll(res.coins.keys)
        val dim = fill().apply { color = Color.argb(140, 12, 0, 0) }
        for (row in 0 until ROWS) for (col in 0 until R) if (row * 5 + col !in cells) cv.drawRect(reelX(col), top + row * cellH, reelX(col) + reelW, top + (row + 1) * cellH, dim)
        val col0 = if (shown.size == 1) wayColors[wins.indexOf(shown[0]) % wayColors.size] else c("#ffcf4a")
        val fr = stroke(5f, col0).apply { setShadowLayer(12 + 14 * pulse, 0f, 0f, col0) }
        cells.forEach { k ->
            val row = k / 5; val col = k % 5; val x = reelX(col); val y = top + row * cellH
            cv.drawRoundRect(RectF(x + 5, y + 5, x + reelW - 5, y + cellH - 5), 12f, 12f, fr)
            val s = 1 + 0.07f * pulse
            dst.set(x + reelW * (1 - s) / 2, y + cellH * (1 - s) / 2, x + reelW * (1 - s) / 2 + reelW * s, y + cellH * (1 - s) / 2 + cellH * s)
            val id = res.grid[row][col]
            cv.drawBitmap(if (id == "COIN") res.coins[k]?.let { coinBmp(coinLabel(it)) } ?: atlas.getValue(id) else atlas.getValue(id), null, dst, null)
        }
        if (shown.size == 1) {                                // banner: what this way paid
            val w = shown[0]
            val s = "${w.symbol} ×${w.reels} · ${w.ways} WAY${if (w.ways > 1) "S" else ""} · ${fmt(w.win)}"
            val p = text(30f).apply { color = Color.WHITE }
            val bw = p.measureText(s) + 50; val by = top + reelsH - 34
            val r = RectF(width / 2f - bw / 2, by - 26, width / 2f + bw / 2, by + 26)
            cv.drawRoundRect(r, 26f, 26f, fill().apply { color = Color.argb(220, 40, 0, 4) }); cv.drawRoundRect(r, 26f, 26f, stroke(3f, col0))
            cv.drawText(s, width / 2f, midY(p, by), p)
        }
    }

    /** The Hold & Win board: locked coins stay, empty cells spin during a respin and the engine's coins land in them. */
    private fun drawHold(h: HoldWin) {
        val t = h.t.toFloat()
        for (row in 0 until ROWS) for (col in 0 until R) {
            val k = row * 5 + col; val x = reelX(col); val y = top + row * cellH
            val cell = RectF(x + 3, y + 3, x + reelW - 3, y + cellH - 3)
            cv.drawRoundRect(cell, 14f, 14f, fill().apply { shader = lin(0f, y, 0f, y + cellH, listOf(0f to "#2a0408", 0.5f to "#4a0a10", 1f to "#1a0204")) })
            val v = h.locked[k]
            if (v == null) {
                if (h.landing) {
                    // a blurred coin rushing through the empty cell
                    cv.save(); cv.clipRect(cell)
                    val off = ((t * 9 + col * 0.37f + row * 0.19f) % 1f) * cellH
                    for (j in -1..0) { dst.set(x, y + off + j * cellH, x + reelW, y + off + (j + 1) * cellH); cv.drawBitmap(blur.getValue("COIN"), null, dst, Paint().apply { alpha = 120 }) }
                    cv.restore()
                }
                cv.drawRoundRect(cell, 14f, 14f, stroke(3f, Color.argb(160, 255, 80, 60)))
            } else {
                val fresh = k in h.lastLanded
                val s = if (fresh) ReelAnimator.outBack(min(1.0, (h.t - HoldWin.SPIN_T) / 0.35), 2.0).toFloat().coerceAtLeast(0.05f) else 1f
                val collected = h.collectedCell(k)
                dst.set(x + reelW * (1 - s) / 2, y + cellH * (1 - s) / 2, x + reelW * (1 + s) / 2, y + cellH * (1 + s) / 2)
                cv.drawBitmap(coinBmp(coinLabel(v)), null, dst, if (collected) Paint().apply { alpha = 150 } else null)
                val glow = 0.5f + 0.5f * sin(t * 6 + k).toFloat()
                cv.drawRoundRect(cell, 14f, 14f, stroke(4f, if (collected) c("#7dffb0") else Color.argb(255, 255, (190 + 60 * glow).toInt(), 60)).apply { setShadowLayer(10 + 12 * glow, 0f, 0f, c("#ffb400")) })
            }
        }
        cv.drawBitmap(glassLayer, 0f, top, null)
        val p = text(28f).apply { color = c("#ffe2a0") }
        val info = when (h.stage) { HoldWin.Stage.COLLECT, HoldWin.Stage.TOTAL -> "COLLECTING ${fmt(h.collected)}"; else -> "${h.locked.size} / 15 COINS" }
        val r = RectF(width / 2f - 170, top + reelsH - 50, width / 2f + 170, top + reelsH - 8)
        cv.drawRoundRect(r, 21f, 21f, fill().apply { color = Color.argb(200, 30, 0, 2) })
        cv.drawText(info, width / 2f, midY(p, r.centerY()), p)
    }

    private fun meter(g: Canvas, x: Float, w: Float, label: String, value: Double, hot: Boolean) {
        val h = 104f
        val r = RectF(x, 0f, x + w, h)
        g.drawRoundRect(r, 14f, 14f, fill().apply { shader = lin(0f, 0f, 0f, h, listOf(0f to "#3a0408", 1f to "#140002")) })
        g.drawRoundRect(r, 14f, 14f, stroke(3f, 0).apply { shader = lin(0f, 0f, 0f, h, GOLD); if (!hot) alpha = 150 })
        val lp = text(22f).apply { color = c("#f0c890") }; g.drawText(label, x + w / 2, midY(lp, 26f), lp)
        val vp = text(40f).apply { color = if (hot) c("#fff2a8") else Color.WHITE; if (hot) setShadowLayer(14f, 0f, 0f, c("#ff6a00")) }
        g.drawText(fmt(value), x + w / 2, midY(vp, 68f), vp)
    }
    private fun drawMeters(g: Canvas, winVal: Double) {
        val w3 = (width - pad * 2 - 2 * 12) / 3
        meter(g, pad, w3, "CREDIT", credits.toDouble(), false)
        meter(g, pad + (w3 + 12), w3, "BET", game.engine.bet.toDouble(), false)
        meter(g, pad + 2 * (w3 + 12), w3, "WIN", winVal, winVal > 0)
        if (message.isNotEmpty()) { val p = text(24f).apply { color = c("#ffe2a0") }; g.drawText(message, width / 2f, 136f, p) }
    }

    private fun drawOverlay(kind: String, tD: Double, amount: Double, dur: Double) {
        val k = min(1.0, tD / 0.35); val s = ReelAnimator.outBack(k, 1.6).toFloat()
        val fade = if (tD > dur - 0.3) ((dur - tD) / 0.3).coerceIn(0.0, 1.0) else 1.0
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill().apply { color = Color.argb((180 * k * fade).toInt(), 20, 0, 2) })
        val cy = top + reelsH * 0.45f
        cv.save(); cv.translate(width / 2f, cy); cv.rotate((tD * 22).toFloat())
        val ray = fill().apply { color = Color.argb((60 * k * fade).toInt(), 255, 120, 60) }
        for (i in 0 until 16) { val p = Path(); val an = i / 16.0 * 2 * PI; p.moveTo(0f, 0f); p.lineTo((cos(an) * 800).toFloat(), (sin(an) * 800).toFloat()); p.lineTo((cos(an + 0.14) * 800).toFloat(), (sin(an + 0.14) * 800).toFloat()); p.close(); cv.drawPath(p, ray) }
        cv.restore()
        if (kind == "HOLD") seal(cv, width / 2f, cy + 250, 210f, (200 * fade).toInt())
        cv.save(); cv.translate(width / 2f, cy); cv.scale(s, s)
        val (title, sub) = when (kind) {
            "HOLD" -> "HOLD & WIN" to "${amount.toInt()} COINS LOCKED · 3 RESPINS"
            "GRAND" -> "GRAND JACKPOT" to "ALL 15 CELLS!"
            "TOTAL" -> "TOTAL WIN" to ""
            "EPIC" -> "EPIC WIN!" to ""
            "MEGA" -> "MEGA WIN!" to ""
            else -> "BIG WIN!" to ""
        }
        val tp = text(width * (if (title.length > 10) 0.09f else 0.11f), SERIF); val ty = midY(tp, -width * 0.13f)
        cv.drawText(title, 0f, ty, Paint(tp).apply { style = Paint.Style.STROKE; strokeWidth = 12f; color = c("#3a0000"); strokeJoin = Paint.Join.ROUND; setShadowLayer(30f, 0f, 0f, c("#ff2a1a")) })
        tp.shader = lin(0f, -width * 0.2f, 0f, -width * 0.06f, GOLD); cv.drawText(title, 0f, ty, tp)
        if (kind == "HOLD") { val sp = text(32f).apply { color = c("#ffe9c0") }; cv.drawText(sub, 0f, midY(sp, width * 0.04f), sp) }
        else {
            if (sub.isNotEmpty()) { val sp = text(30f).apply { color = c("#ffe9c0") }; cv.drawText(sub, 0f, midY(sp, -width * 0.03f), sp) }
            val am = fmt(amount); val ap = text(width * 0.15f); val ay = midY(ap, width * 0.09f)
            cv.drawText(am, 0f, ay, Paint(ap).apply { style = Paint.Style.STROKE; strokeWidth = 12f; color = c("#3a0000"); strokeJoin = Paint.Join.ROUND })
            cv.drawText(am, 0f, ay, ap.apply { color = c("#fff7cc") })
        }
        cv.restore()
    }

    private val coinP = fill()
    private fun drawParticles() {
        a.particles.forEach { p ->
            cv.save(); cv.translate(p.x, p.y); cv.rotate(Math.toDegrees(p.a.toDouble()).toFloat()); cv.scale(cos(p.a * 2).let { if (abs(it) < 0.05f) 0.05f else it }, 1f)
            coinP.shader = lin(-p.r, 0f, p.r, 0f, GOLD); cv.drawCircle(0f, 0f, p.r, coinP); cv.restore()
        }
    }

    companion object { const val W = 720; const val H = 1140 }
}

/** The 27" topper over Dragon Fortune: four jackpot plaques (credits at the current bet) on red lacquer with the dragon. */
class DragonTopper {
    val bitmap: Bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
    private val cv = Canvas(bitmap)
    var bet = -1L; private set
    private val bg = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888).also { b ->
        val g = Canvas(b)
        g.drawRect(0f, 0f, 1280f, 720f, fill().apply { shader = rad(640f, 300f, 900f, listOf(0f to "#c0141e", 0.55f to "#5a0208", 1f to "#140001")) })
        val lat = stroke(2f, Color.argb(36, 255, 210, 120)); var y = 0f
        while (y < 720) { var x = 0f; while (x < 1280) { g.drawRect(x + 6, y + 6, x + 52, y + 52, lat); x += 58 }; y += 58 }
        val cl = fill().apply { shader = lin(0f, 560f, 0f, 720f, listOf(0f to "#ffe08a", 1f to "#a8540a")); alpha = 210 }
        for (i in 0 until 9) cloud(g, 40f + i * 150f, 690f - (i % 2) * 24f, 70f, cl)
        g.drawRect(0f, 0f, 1280f, 12f, fill().apply { shader = lin(0f, 0f, 1280f, 0f, GOLD) }); g.drawRect(0f, 708f, 1280f, 720f, fill().apply { shader = lin(0f, 0f, 1280f, 0f, GOLD) })
    }

    private fun plaque(r: RectF, name: String, value: Long, col: String, lit: Boolean, shine: Float) {
        cv.drawRoundRect(r, 30f, 30f, fill().apply { shader = lin(0f, r.top, 0f, r.bottom, listOf(0f to col, 1f to "#1a0004")); setShadowLayer(if (lit) 50f else 22f, 0f, 0f, if (lit) Color.WHITE else c(col)) })
        cv.drawRoundRect(r, 30f, 30f, stroke(if (lit) 10f else 6f, 0).apply { shader = lin(0f, r.top, 0f, r.bottom, GOLD) })
        // moving shine
        cv.save(); cv.clipRect(r)
        val sx = r.left + (r.width() + 300) * shine - 150
        cv.drawRect(sx - 60, r.top, sx + 60, r.bottom, fill().apply { shader = lin(sx - 60, 0f, sx + 60, 0f, listOf(0f to "#00ffffff", 0.5f to "#55ffffff", 1f to "#00ffffff")) })
        cv.restore()
        val np = text(r.height() * 0.3f, SERIF).apply { color = c("#ffe9a8") }; cv.drawText(name, r.centerX(), midY(np, r.top + r.height() * 0.3f), np)
        val vp = text(r.height() * 0.38f).apply { color = if (lit) c("#ffffff") else c("#fff2c0"); setShadowLayer(14f, 0f, 0f, c("#ffb400")) }
        cv.drawText(NF.format(value), r.centerX(), midY(vp, r.top + r.height() * 0.7f), vp)
    }

    /** Redraw for [bet]; [time] drives the shine; [lit] flashes one jackpot while it is on the board. */
    fun draw(bet: Long, time: Double, lit: String?) {
        this.bet = bet
        cv.drawBitmap(bg, 0f, 0f, null)
        seal(cv, 150f, 270f, 230f, 90); seal(cv, 1130f, 270f, 230f, 90)
        val tp = text(66f, SERIF); val ty = midY(tp, 92f)
        cv.drawText("DRAGON FORTUNE", 640f, ty, Paint(tp).apply { style = Paint.Style.STROKE; strokeWidth = 10f; color = c("#2a0000"); strokeJoin = Paint.Join.ROUND; setShadowLayer(22f, 0f, 0f, c("#ff3a1a")) })
        tp.shader = lin(0f, 50f, 0f, 130f, GOLD); cv.drawText("DRAGON FORTUNE", 640f, ty, tp)
        val flash = lit != null && (time * 4).toInt() % 2 == 0
        val sh = ((time * 0.35) % 1.0).toFloat()
        plaque(RectF(300f, 160f, 980f, 380f), "GRAND", Dragon.GRAND * bet, "#c8961a", lit == "GRAND" && flash, sh)
        val names = listOf(Triple("MAJOR", Dragon.MAJOR, "#7a1aa8"), Triple("MINOR", Dragon.MINOR, "#1a5ab0"), Triple("MINI", Dragon.MINI, "#1a8a3a"))
        names.forEachIndexed { i, (n, m, col) -> plaque(RectF(60f + i * 400f, 420f, 420f + i * 400f, 600f), n, m.toLong() * bet, col, lit == n && flash, (sh + 0.3f * (i + 1)) % 1f) }
        val fp = text(24f).apply { color = c("#ffe2a0") }; cv.drawText("PLAY MONEY · JACKPOTS × TOTAL BET · FOR FUN ONLY", 640f, 648f, fp)
    }
}
