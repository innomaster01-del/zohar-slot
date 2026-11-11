package com.zohar.slotar.plinko

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zohar.slotar.Fx
import com.zohar.slotar.audio.SlotSounds
import com.zohar.slotar.blackjack.Rng
import com.zohar.slotar.blackjack.SecureRng
import com.zohar.slotar.blackjack.Wallet
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Play-money wallet for the Plinko app, kept on the phone. Free refill when it runs low. */
class PlinkoWallet(context: Context) : Wallet {
    private val prefs = context.getSharedPreferences("zohar_plinko", Context.MODE_PRIVATE)
    override var credits: Long = prefs.getLong("credits", START)
        set(v) { field = v; prefs.edit().putLong("credits", v).apply() }
    companion object { const val START = 1000L }
}

enum class PUi { BETTING, BUSY }

/**
 * The Plinko board: engine + everything shown on it. The engine draws the ball's whole way down first; the ball on the
 * board then follows that way peg by peg and comes to rest in the engine's pocket.
 */
class PlinkoTable(
    context: Context, private val modelLoader: ModelLoader, private val sounds: SlotSounds, private val scope: CoroutineScope,
    private val onFx: (Fx) -> Unit, rng: Rng = SecureRng, wallet: Wallet? = null,
) {
    private val j = JSONObject(context.assets.open("bj_layout.json").bufferedReader().readText()).getJSONObject("plinko")
    private fun f(n: String) = j.getDouble(n).toFloat()
    val geo = PlinkoGeo(j.getInt("rows"), f("dx"), f("dy"), f("topY"), f("pegR"), f("ballR"), f("dropY"), f("cupY"))
    private val ballZ = f("ballZ"); private val cupFloor = f("cupFloor"); private val labelY = f("labelY")
    val seatEye = j.getJSONArray("seatEye").let { floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
    val wallet: Wallet = wallet ?: PlinkoWallet(context)
    val game = PlinkoEngine(this.wallet, rng)

    // ---------------- UI state ----------------
    var ui by mutableStateOf(PUi.BETTING); private set
    var credits by mutableLongStateOf(this.wallet.credits); private set
    var bet by mutableLongStateOf(25L); private set
    var chipValue by mutableLongStateOf(25L)
    var risk by mutableStateOf(PlinkoRisk.MEDIUM); private set
    var outcome by mutableStateOf(""); private set
    var lastDrop by mutableStateOf<PlinkoDrop?>(null); private set
    val history = androidx.compose.runtime.mutableStateListOf<Int>()        // the last multipliers, in fifths
    var message by mutableStateOf(""); private set
    val drops get() = dropped
    private var dropped = 0
    init { if (bet > this.wallet.credits) bet = 0 }
    fun canDrop() = ui == PUi.BETTING && game.validate(bet) == null

    // ---------------- scene ----------------
    var frame: Node? = null; private set
    private var ball: ModelNode? = null
    private var flash: ModelNode? = null
    private var hit: ModelNode? = null
    private val labels = HashMap<PlinkoRisk, ModelNode>()
    private var now = 0.0
    private var job: Job? = null
    private var legs: List<PlinkoGeo.Leg>? = null
    private var t0 = 0.0
    private var flashUntil = 0.0

    /** The board's frame is in the scene: put the ball, the lights and the multiplier strips on it. */
    fun attach(frame: Node) {
        if (this.frame === frame) return
        this.frame = frame
        fun model(name: String) = ModelNode(modelLoader.createModelInstance("models/$name.glb"), autoAnimate = false).also { frame.addChildNode(it) }
        ball = model("plinko_ball").also { it.position = Position(0f, geo.dropY, ballZ) }
        flash = model("plinko_flash").also { it.isVisible = false }
        hit = model("plinko_hit").also { it.isVisible = false }
        for (r in PlinkoRisk.entries) labels[r] = model("plinko_labels_${r.name.lowercase()}").also { it.position = Position(0f, labelY, 0.064f); it.isVisible = r == risk }
        Log.i("ZoharPL", "attached")
    }

    // ================= per frame =================
    fun update(nowSec: Double) {
        now = nowSec
        legs?.let { l ->
            val p = geo.position(l, now - t0)
            ball?.position = Position(p[0], p[1], ballZ)
        }
        if (flashUntil > 0 && now > flashUntil) { flash?.isVisible = false; flashUntil = 0.0 }
    }

    // ================= betting =================
    fun addChip(v: Long) {
        if (ui != PUi.BETTING) return
        if (bet + v > game.maxBet) { message = "The maximum bet is ${game.maxBet}"; return }
        if (bet + v > wallet.credits) { sounds.play("no_credits"); message = "Not enough play credits"; return }
        bet += v; message = ""; sounds.play("chip")
    }
    fun clearBet() { if (ui != PUi.BETTING) return; bet = 0; message = ""; sounds.play("click") }
    fun chooseRisk(r: PlinkoRisk) {
        if (ui != PUi.BETTING || r == risk) return
        risk = r; for ((k, n) in labels) n.isVisible = k == r
        hit?.isVisible = false; sounds.play("click")
    }
    fun canRefill() = ui == PUi.BETTING && wallet.credits < game.minBet
    fun refill() { if (!canRefill()) return; wallet.credits = PlinkoWallet.START; credits = wallet.credits; bet = 25; message = "A thousand fresh play credits" }

    // ================= the drop =================
    fun drop() {
        if (ui != PUi.BETTING) return
        game.validate(bet)?.let { message = it; return }
        if (job?.isActive == true) return
        ui = PUi.BUSY
        job = scope.launch {
            try {
                val d = game.drop(bet, risk)                               // the whole way down is decided here
                dropped++; outcome = ""; message = ""; lastDrop = null
                credits = wallet.credits - d.returned                      // the prize shows when the ball has landed
                hit?.isVisible = false
                val l = geo.legs(d.path)
                ball?.position = Position(l[0].x0, l[0].y0, ballZ)
                sounds.play("click"); delay(250)
                t0 = now; legs = l
                // one ping per peg, a little higher each row; the peg lights up as the ball touches it
                var at = 0.0; var rights = 0
                for (r in 0 until geo.rows) {
                    val leg = l[r + 1]; val wait = leg.t0 - at; delay((wait * 1000).toLong()); at += wait
                    sounds.play("plinko_peg", 0.75f, 0.85f + 0.035f * r)
                    flash?.let { it.position = Position(geo.pegX(r, geo.pegIndex(rights)), geo.pegY(r), ballZ); it.isVisible = true }; flashUntil = now + 0.11
                    if (d.path[r]) rights++
                }
                delay(((geo.duration(l) - at) * 1000).toLong())
                legs = null; ball?.position = Position(geo.pocketX(d.pocket), geo.cupY, ballZ)
                hit?.let { it.position = Position(geo.pocketX(d.pocket), cupFloor, 0f); it.isVisible = true }
                sounds.play("plinko_land")
                lastDrop = d; history.add(d.fifths); if (history.size > 12) history.removeAt(0)
                credits = wallet.credits
                outcome = when { d.net > 0 -> "YOU WIN ${d.net}"; d.net == 0L -> "MONEY BACK"; else -> "×${Plinko.label(d.fifths)} · BACK ${d.returned}" }
                if (d.fifths >= 10) { sounds.play("coins"); onFx(Fx.COINS) }
                if (d.fifths >= 50) { sounds.play("big_win"); onFx(Fx.BIG_WIN) }
                Log.i("ZoharPL", "drop $dropped: ${d.path.joinToString("") { if (it) "R" else "L" }} pocket ${d.pocket} ×${Plinko.label(d.fifths)} ${d.risk} stake ${d.stake} back ${d.returned} credits=${wallet.credits}")
                delay(600)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("ZoharPL", "drop failed", e)
                // the engine has already settled the ball: show what it decided
                legs = null; game.last?.let { d -> ball?.position = Position(geo.pocketX(d.pocket), geo.cupY, ballZ); lastDrop = d }
            }
            credits = wallet.credits
            if (bet > wallet.credits) bet = wallet.credits / 5 * 5
            ui = PUi.BETTING
        }
    }

    fun close() { job?.cancel() }
}
