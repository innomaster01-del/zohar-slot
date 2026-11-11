package com.zohar.slotar.bonus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import com.zohar.slotar.audio.SlotSounds
import com.zohar.slotar.blackjack.Card
import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.craps.DiceMath
import com.zohar.slotar.hilo.HiLoCall
import com.zohar.slotar.plinko.PlinkoGeo
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

enum class BPhase { INTRO, READY, BUSY, DONE }

/**
 * One bonus mini-game on its glowing pieces: no table, no frame. It plays one round and ends with a BonusResult.
 * The engine draws the outcome first; what moves here only shows it.
 */
class BonusStage(
    val kind: BonusKind, val base: Long, private val context: Context, private val engine: Engine, private val modelLoader: ModelLoader,
    private val sounds: SlotSounds, private val scope: CoroutineScope, private val rng: Rng = SecureRng,
) {
    private val j = JSONObject(context.assets.open("bj_layout.json").bufferedReader().readText()).getJSONObject("bonus")
    private fun v(o: JSONObject, n: String) = o.getJSONArray(n).let { Float3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
    private val dieSize = j.getDouble("die").toFloat()
    private val cardSpots = j.getJSONArray("cards").let { a -> List(a.length()) { i -> a.getJSONArray(i).let { Float3(it.getDouble(0).toFloat(), 0.004f, it.getDouble(2).toFloat()) } } }
    private val lowerAt = v(j, "lower"); private val higherAt = v(j, "higher")
    private val tileX = j.getJSONArray("tile").getDouble(0).toFloat(); private val tileZ = j.getJSONArray("tile").getDouble(1).toFloat()
    private val pj = j.getJSONObject("plinko"); private fun pf(n: String) = pj.getDouble(n).toFloat()
    val geo = PlinkoGeo(pj.getInt("rows"), pf("dx"), pf("dy"), pf("topY"), pf("pegR"), pf("ballR"), pf("dropY"), pf("cupY"))
    private val cupFloor = pf("cupFloor")
    // only the look of a throw comes from here (where the dice come to rest, how they tumble), never an outcome
    private val look = java.util.Random()
    private fun rnd() = look.nextFloat()

    var phase by mutableStateOf(BPhase.INTRO); private set
    var result by mutableStateOf<BonusResult?>(null); private set
    /** The glowing words that hang over the pieces, and where (in the stage's own space). */
    var caption by mutableStateOf(""); private set
    var captionAt: Float3 = Float3(0f, 0.16f, 0f); private set
    val hilo: BonusHiLo? = if (kind == BonusKind.HILO) BonusHiLo(rng) else null
    fun offer(c: HiLoCall) = hilo?.offer(c)

    var frame: Node? = null; private set
    private var now = 0.0
    private var job: Job? = null
    private fun model(name: String): ModelNode = ModelNode(modelLoader.createModelInstance("models/$name.glb"), autoAnimate = false).also { frame!!.addChildNode(it) }

    /** The stage's frame is in the scene: put the pieces on it and open the round. */
    fun attach(frame: Node) {
        if (this.frame === frame) return
        this.frame = frame
        when (kind) {
            BonusKind.HILO -> {
                model("bonus_ring")
                model("bonus_frame_cyan").position = Position(cardSpots[0].x, 0f, cardSpots[0].z)
                model("bonus_frame_pink").position = Position(cardSpots[1].x, 0f, cardSpots[1].z)
                lowerTile = model("bonus_lower").also { it.position = Position(lowerAt.x, 0f, lowerAt.z); it.isVisible = offer(HiLoCall.LOWER) != null }
                higherTile = model("bonus_higher").also { it.position = Position(higherAt.x, 0f, higherAt.z); it.isVisible = offer(HiLoCall.HIGHER) != null }
                val g = hilo!!
                cardA = CardPiece(model("bonus_card")).also { it.face(g.first); it.node.isVisible = false }
                cardB = CardPiece(model("bonus_card")).also { it.node.isVisible = false }
                captionAt = Float3(0f, 0.17f, cardSpots[0].z)
                job = scope.launch {
                    delay(350)
                    cardA?.dropIn(now, cardSpots[0], faceUp = true); sounds.play("card_slide"); delay(380)
                    cardB?.dropIn(now, cardSpots[1], faceUp = false); sounds.play("card_slide"); delay(480)
                    caption = "Higher or lower?"; phase = BPhase.READY
                }
            }
            BonusKind.DICE -> {
                model("bonus_ring")
                repeat(2) { i -> dice.add(DiePiece(model("bonus_die"), if (i == 0) -0.04f else 0.04f)) }
                captionAt = Float3(0f, 0.21f, 0f); caption = "Tap to throw"; phase = BPhase.READY
            }
            BonusKind.PLINKO -> {
                model("bonus_plinko")
                ball = model("plinko_ball").also { it.position = Position(0f, geo.dropY, 0f) }
                flash = model("plinko_flash").also { it.isVisible = false }
                hit = model("plinko_hit").also { it.isVisible = false }
                captionAt = Float3(0f, geo.dropY + 0.09f, 0f); caption = "Tap to drop"; phase = BPhase.READY
            }
        }
        Log.i("ZoharBonus", "attached $kind")
    }

    fun update(nowSec: Double) {
        now = nowSec
        cardA?.update(now); cardB?.update(now)
        for (d in dice) d.update(now)
        legs?.let { l -> val p = geo.position(l, now - t0); ball?.position = Position(p[0], p[1], 0f) }
        if (flashUntil > 0 && now > flashUntil) { flash?.isVisible = false; flashUntil = 0.0 }
    }

    /** A tap on the ring (Higher or Lower only): which call tile is under the finger. */
    fun tapGround(x: Float, z: Float) {
        if (kind != BonusKind.HILO) return
        fun on(p: Float3) = abs(x - p.x) <= tileX + 0.012f && abs(z - p.z) <= tileZ + 0.012f
        if (on(lowerAt)) call(HiLoCall.LOWER) else if (on(higherAt)) call(HiLoCall.HIGHER)
    }
    /** The one move of the dice and Plinko rounds: throw / drop. */
    fun act() { when (kind) { BonusKind.DICE -> throwDice(); BonusKind.PLINKO -> dropBall(); BonusKind.HILO -> {} } }

    private fun finish(pct: Int, words: String) {
        result = BonusResult(kind, pct, base); caption = words
        if (pct >= 200) { sounds.play("big_win"); sounds.play("coins") } else if (pct > 0) sounds.play("win_small") else sounds.play("click")
        Log.i("ZoharBonus", "$kind bonus $pct% of $base = ${base * pct / 100}")
        phase = BPhase.DONE
    }

    // ================= Higher or Lower =================
    private var cardA: CardPiece? = null; private var cardB: CardPiece? = null
    private var lowerTile: ModelNode? = null; private var higherTile: ModelNode? = null
    fun call(c: HiLoCall) {
        val g = hilo ?: return
        if (phase != BPhase.READY || g.offer(c) == null) return
        phase = BPhase.BUSY
        job = scope.launch {
            val (card, pct) = g.guess(c)                                     // the card was fixed when the round opened
            (if (c == HiLoCall.HIGHER) lowerTile else higherTile)?.isVisible = false
            caption = c.label + "…"; sounds.play("click"); delay(350)
            cardB?.let { it.face(card); it.flip(now) }; sounds.play("card_flip"); delay(650)
            finish(pct, if (pct > 0) "+$pct%" else if (card.rank == g.first.rank) "Same rank · +0%" else "+0%")
        }
    }
    private val tex = HashMap<String, Texture>()
    private val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)
    private fun texture(id: String): Texture = tex.getOrPut(id) {
        val src = context.assets.open("cards/$id.jpg").use { BitmapFactory.decodeStream(it) }
        // the card mesh comes from three.js (UV v-up): upload the rows flipped
        val m = android.graphics.Matrix().apply { setScale(1f, -1f); postTranslate(0f, src.height.toFloat()) }
        val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).copy(Bitmap.Config.ARGB_8888, false)
        val levels = 1 + (Math.log(max(bmp.width, bmp.height).toDouble()) / Math.log(2.0)).toInt()
        val t = Texture.Builder().width(bmp.width).height(bmp.height).levels(levels).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8).build(engine)
        var lv = bmp
        for (i in 0 until levels) {
            if (i > 0) lv = Bitmap.createScaledBitmap(lv, max(1, lv.width / 2), max(1, lv.height / 2), true)
            TextureHelper.setBitmap(engine, t, i, lv)
        }
        t
    }
    /** A card lying on the ring. roll 180° = face down. */
    inner class CardPiece(val node: ModelNode) {
        private val asset = node.modelInstance.asset
        private val rm = engine.renderableManager
        private fun mat(name: String) = asset.getFirstEntityByName(name).takeIf { it != 0 && rm.hasComponent(it) }?.let { rm.getMaterialInstanceAt(rm.getInstance(it), 0) }
        init { runCatching { mat("CardBack")?.setParameter("baseColorMap", texture("back"), sampler) } }
        fun face(c: Card) { runCatching { mat("CardFace")?.setParameter("baseColorMap", texture(c.id), sampler) } }
        private var mode = 0                        // 0 still, 1 dropping in, 2 turning over
        private var t0 = 0.0; private var spot = Float3(); private var roll = 0f
        private fun put(y: Float, rollDeg: Float) {
            node.transform = Transform(position = Position(spot.x, spot.y + y, spot.z), quaternion = Quaternion.fromAxisAngle(Float3(0f, 0f, 1f), rollDeg))
        }
        fun dropIn(now: Double, at: Float3, faceUp: Boolean) { spot = at; roll = if (faceUp) 0f else 180f; mode = 1; t0 = now; put(0.22f, roll); node.isVisible = true }
        fun flip(now: Double) { mode = 2; t0 = now }
        fun update(now: Double) {
            when (mode) {
                1 -> { val u = ((now - t0) / 0.32).toFloat().coerceIn(0f, 1f); put(0.22f * (1f - u) * (1f - u), roll); if (u >= 1f) mode = 0 }
                2 -> { val u = smooth(((now - t0) / 0.5).toFloat()); roll = 180f * (1f - u); put(0.07f * sin(u * PI.toFloat()), roll); if (now - t0 >= 0.5) { roll = 0f; put(0f, 0f); mode = 0 } }
            }
        }
    }

    // ================= dice =================
    private val dice = ArrayList<DiePiece>()
    private fun throwDice() {
        if (phase != BPhase.READY) return
        phase = BPhase.BUSY
        job = scope.launch {
            val (a, b) = BonusDice.roll(rng)                                 // both dice are decided before they fall
            caption = ""; sounds.play("dice_shake"); delay(380)
            val y = dieSize / 2; var longest = 0.0
            dice.forEachIndexed { i, d ->
                val sgn = if (i == 0) -1f else 1f
                val rest = floatArrayOf(sgn * (0.035f + 0.05f * rnd()), y, -0.05f + 0.09f * rnd())
                val from = floatArrayOf(sgn * 0.03f, 0.36f, 0.30f)
                fun at(k: Float) = floatArrayOf(rest[0] + sgn * 0.03f * k, y, rest[2] + 0.11f * k)
                val l1 = at(1f); val l2 = at(0.4f); val l3 = at(0.12f)
                val legs = listOf(DiceMath.Leg(0.0, 0.34, from, l1, 0.05f), DiceMath.Leg(0.34, 0.22, l1, l2, 0.045f), DiceMath.Leg(0.56, 0.15, l2, l3, 0.018f), DiceMath.Leg(0.71, 0.2, l3, rest, 0f))
                d.fly(now + i * 0.04, legs, if (i == 0) a else b, 360f * rnd(), Float3(rnd() - 0.5f, rnd() - 0.5f, rnd() - 0.5f), 800f + 600f * rnd())
                longest = maxOf(longest, DiceMath.duration(legs) + i * 0.04)
            }
            for ((wait, vol) in listOf(340L to 1f, 220L to 0.75f, 150L to 0.5f)) { delay(wait); sounds.play("dice_hit", vol) }
            delay(((longest - 0.71) * 1000).toLong().coerceAtLeast(0L) + 250L)
            finish(BonusDice.pct(a + b), "${a + b}  ·  +${BonusDice.pct(a + b)}%")
        }
    }
    /** One die: it hovers and turns until it is thrown, then follows its legs and lies showing `value`. */
    inner class DiePiece(val node: ModelNode, private val homeX: Float) {
        private var value = 0
        private var legs: List<DiceMath.Leg>? = null
        private var t0 = 0.0; private var yaw = 0f; private var axis = Float3(1f, 0f, 0f); private var spin = 0f
        private var pos = Float3(homeX, 0.11f, 0f)
        private fun rest(): Quaternion { val (a, deg) = DiceMath.faceUp(value); return Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), yaw) * Quaternion.fromAxisAngle(if (a == 'x') Float3(1f, 0f, 0f) else Float3(0f, 0f, 1f), deg) }
        fun fly(start: Double, path: List<DiceMath.Leg>, v: Int, yawDeg: Float, spinAxis: Float3, spinDeg: Float) {
            legs = path; t0 = start; value = v; yaw = yawDeg; spin = spinDeg
            val l = kotlin.math.sqrt(spinAxis.x * spinAxis.x + spinAxis.y * spinAxis.y + spinAxis.z * spinAxis.z).coerceAtLeast(1e-3f)
            axis = Float3(spinAxis.x / l, spinAxis.y / l, spinAxis.z / l)
        }
        fun update(now: Double) {
            val path = legs
            if (value == 0) {
                // waiting to be thrown: hover over the ring, turning
                val bob = 0.012f * sin((now * 2.2 + homeX * 30).toFloat())
                node.transform = Transform(position = Position(homeX, 0.11f + bob, 0f), quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), (now * 70).toFloat() % 360f) * Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), 30f))
                return
            }
            if (path == null) return
            val t = (now - t0).coerceAtLeast(0.0)
            val p = DiceMath.position(path, t); pos = Float3(p[0], p[1], p[2])
            val left = DiceMath.tumble(path, t)
            val q = if (left > 0f) rest() * Quaternion.fromAxisAngle(axis, spin * left) else rest()
            node.transform = Transform(position = Position(pos.x, pos.y, pos.z), quaternion = q)
            if (t >= DiceMath.duration(path)) legs = null
        }
    }

    // ================= Plinko =================
    private var ball: ModelNode? = null; private var flash: ModelNode? = null; private var hit: ModelNode? = null
    private var legs: List<PlinkoGeo.Leg>? = null
    private var t0 = 0.0; private var flashUntil = 0.0
    private fun dropBall() {
        if (phase != BPhase.READY) return
        phase = BPhase.BUSY
        job = scope.launch {
            val path = BonusPlinko.path(rng)                                 // the whole way down is decided here
            val pocket = path.count { it }; val pct = BonusPlinko.pct(pocket)
            caption = ""; sounds.play("click"); delay(200)
            val l = geo.legs(path); t0 = now; legs = l
            var at = 0.0; var rights = 0
            for (r in 0 until geo.rows) {
                val wait = l[r + 1].t0 - at; delay((wait * 1000).toLong()); at += wait
                sounds.play("plinko_peg", 0.75f, 0.85f + 0.05f * r)
                flash?.let { it.position = Position(geo.pegX(r, geo.pegIndex(rights)), geo.pegY(r), 0f); it.isVisible = true }; flashUntil = now + 0.11
                if (path[r]) rights++
            }
            delay(((geo.duration(l) - at) * 1000).toLong())
            legs = null; ball?.position = Position(geo.pocketX(pocket), geo.cupY, 0f)
            // the lit-pocket model is built 3 cm forward (for the big board): pull it back onto the triangle's plane
            hit?.let { it.position = Position(geo.pocketX(pocket), cupFloor, -0.03f); it.isVisible = true }
            sounds.play("plinko_land"); delay(250)
            captionAt = Float3(geo.pocketX(pocket), geo.pegY(geo.rows - 1) + 0.06f, 0f)
            finish(pct, "+$pct%")
        }
    }

    fun close() { job?.cancel(); for (t in tex.values) runCatching { engine.destroyTexture(t) } }

    companion object { fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) } }
}
