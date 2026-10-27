package com.zohar.slotar

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zohar.slotar.ar.MachineController
import com.zohar.slotar.audio.SlotSounds
import com.zohar.slotar.engine.Dragon
import com.zohar.slotar.engine.DragonEngine
import com.zohar.slotar.engine.DragonResult
import com.zohar.slotar.engine.ScatterResult
import com.zohar.slotar.engine.SlotConfig
import com.zohar.slotar.engine.SpinResult
import com.zohar.slotar.screen.DragonRenderer
import com.zohar.slotar.screen.DragonTopper
import com.zohar.slotar.screen.ReelAnimator

/** The reel strips of Dragon Fortune in the shape the reel animator understands (COIN plays the scatter's role for anticipation). */
val DRAGON_REELS = SlotConfig(
    rows = 3, wild = "WILD", scatter = "COIN", symbols = Dragon.SYMBOLS, pays = Dragon.PAYS, scatterPays = emptyMap(),
    fsTrigger = 4, fsSpins = 0, fsRetrigger = 0, fsMultiplier = 1, lines = emptyList(), lineBets = intArrayOf(1), reels = Dragon.REELS,
)

/**
 * Hold & Win replay. Everything was decided by the engine in [res]; this only paces it for the screen:
 * intro → respins (each: empty cells spin, then the engine's coins land) → (GRAND) → collect coin by coin → total.
 */
class HoldWin(val res: DragonResult) {
    enum class Stage { INTRO, RESPIN, GRAND, COLLECT, TOTAL, DONE }
    var stage = Stage.INTRO; private set
    var t = 0.0; private set
    var step = 0; private set                         // respin index
    var left = 3; private set
    val locked = LinkedHashMap(res.coins)             // cell → value × bet
    var lastLanded: Set<Int> = emptySet(); private set
    private var order: List<Int> = emptyList()
    var collected = 0L; private set
    var collectIdx = -1; private set
    val landing get() = stage == Stage.RESPIN && t < SPIN_T
    fun update(dt: Double, ev: (String) -> Unit) {
        t += dt
        when (stage) {
            Stage.INTRO -> if (t >= 2.2) { stage = if (res.respins.isEmpty()) Stage.COLLECT else Stage.RESPIN; t = 0.0; if (stage == Stage.RESPIN) ev("respin") }
            Stage.RESPIN -> if (t >= SPIN_T && step < res.respins.size && lastStepLanded != step) {
                val rs = res.respins[step]; lastStepLanded = step
                rs.landed.forEach { (r, c, v) -> locked[r * 5 + c] = v }
                lastLanded = rs.landed.map { (r, c, _) -> r * 5 + c }.toSet(); left = rs.left
                ev(if (rs.landed.isNotEmpty()) "land" else "miss")
            } else if (t >= SPIN_T + (if (lastLanded.isNotEmpty()) 0.9 else 0.45)) {
                step++; t = 0.0; lastLanded = emptySet()
                if (step >= res.respins.size) { stage = if (res.grand) Stage.GRAND else Stage.COLLECT; ev(if (res.grand) "grand" else "collect") } else ev("respin")
            }
            Stage.GRAND -> if (t >= 3.5) { stage = Stage.COLLECT; t = 0.0; ev("collect") }
            Stage.COLLECT -> {
                if (order.isEmpty()) order = locked.keys.sortedBy { (it % 5) * 3 + it / 5 }
                val k = (t / 0.24).toInt()
                while (collectIdx < k && collectIdx < order.size - 1) { collectIdx++; collected += locked.getValue(order[collectIdx]).toLong() * res.bet; ev("tick") }
                if (collectIdx >= order.size - 1 && t > order.size * 0.24 + 0.5) { if (res.grand) collected += Dragon.GRAND.toLong() * res.bet; stage = Stage.TOTAL; t = 0.0; ev("total") }
            }
            Stage.TOTAL -> if (t >= 3.0) { stage = Stage.DONE }
            Stage.DONE -> {}
        }
    }
    private var lastStepLanded = -1
    fun collectedCell(k: Int) = stage == Stage.COLLECT && order.indexOf(k) in 0..collectIdx || stage == Stage.TOTAL
    companion object { const val SPIN_T = 0.85 }
}

/** Dragon Fortune: 243 ways + Hold & Win, C-curve screen and a jackpot topper. PLAY MONEY. */
class DragonGame(private val sounds: SlotSounds, private val onFx: (Fx) -> Unit) : SlotMachine {
    override val id = "dragon"; override val title = "DRAGON FORTUNE"; override val cabinetFile = "models/dragon_cabinet.glb"; override val hasLever = false
    val engine = DragonEngine(2000)
    private var auto = 0
    private var pending = -1.0
    private var moodT = 0.0
    private var topperT = 0.0
    private var topperDirty = true
    var hold: HoldWin? = null; private set
    var last: DragonResult? = null; private set
    override var mood = MachineController.Mood.IDLE; private set
    override var credits by mutableLongStateOf(engine.credits); private set
    override var bet by mutableLongStateOf(engine.bet); private set
    override var busy by mutableStateOf(false); private set
    override var inFree by mutableStateOf(false); private set
    override var freeLeft by mutableIntStateOf(0); private set
    override var autoLeft by mutableIntStateOf(0); private set

    private val listener = object : ReelAnimator.Listener {
        override fun onSpinStart(r: SpinResult) { sounds.spinStart() }
        override fun onReelStop(index: Int, hasScatter: Boolean) {
            sounds.play(if (hasScatter) "scatter_stop" else "reel_stop")
            if (index == 4) sounds.spinEnd()
            if (index < 4 && animator.reels[index + 1].antic) sounds.play("anticipation", 0.7f)
        }
        override fun onResult(r: SpinResult, overlay: ReelAnimator.Overlay.Kind?) {
            val x = r.totalWin.toDouble() / r.totalBet
            if (last?.holdWin == true) { sounds.play("thunder", 0.9f); sounds.play("free_spins"); onFx(Fx.LIGHTNING); onFx(Fx.FREE_SPINS); setMood(MachineController.Mood.FREE, 3.0) }
            when {
                overlay != null && overlay in ReelAnimator.BIG_KINDS -> { sounds.play("big_win"); sounds.play("coins"); onFx(Fx.BIG_WIN); onFx(Fx.COINS); setMood(MachineController.Mood.BIG, 4.0) }
                x >= 5 -> { sounds.play("win_small"); sounds.play("coins", 0.7f); onFx(Fx.COINS); setMood(MachineController.Mood.WIN, 2.0) }
                r.totalWin > 0 -> { sounds.play("win_small"); setMood(MachineController.Mood.WIN, 1.5) }
            }
        }
        override fun onDone(r: SpinResult) {
            val res = last
            if (res != null && res.holdWin && hold == null) { hold = HoldWin(res); renderer.dirty = true; syncHud(shown = res.credits - res.totalWin); return }
            finish()
        }
    }
    val animator = ReelAnimator(DRAGON_REELS, listener, DragonRenderer.W, DragonRenderer.H)
    val renderer = DragonRenderer(animator, this)
    val topperArt = DragonTopper()
    override val screen get() = renderer.bitmap
    override val topper: Bitmap get() = topperArt.bitmap
    init { syncHud(); topperArt.draw(engine.bet, 0.0, null) }

    private fun finish() { syncHud(); if (auto > 0) pending = 0.5 }
    private fun setMood(m: MachineController.Mood, secs: Double) { mood = m; moodT = secs }
    private fun syncHud(shown: Long = engine.credits, message: String = "") {
        credits = engine.credits; bet = engine.bet; busy = animator.busy || hold != null; autoLeft = auto
        inFree = hold != null; freeLeft = hold?.left ?: 0
        renderer.credits = shown; renderer.message = message; renderer.dirty = true
        if (topperArt.bet != engine.bet) topperDirty = true
    }

    private fun toSpin(d: DragonResult) = SpinResult(
        ok = true, cost = d.bet, stops = d.stops, grid = d.grid, scatter = ScatterResult(d.coins.size, d.coins.keys.map { it / 5 to it % 5 }, 0),
        totalWin = d.waysWin, totalBet = d.bet, credits = d.credits,
    )
    override fun spin(): String {
        if (hold != null) return "busy"
        if (animator.busy) { animator.slamStop(); return "slam" }
        if (!engine.canSpin()) { engine.refill(); auto = 0; sounds.play("no_credits"); syncHud(message = "FREE REFILL: +2,000 PLAY CREDITS"); return "refill" }
        val before = engine.credits
        val d = engine.spin(); if (!d.ok) return "no"
        last = d
        if (auto > 0) auto--
        animator.startSpin(toSpin(d))
        syncHud(shown = before - d.bet); busy = true
        return "spin"
    }
    override fun bet(step: Int): Boolean {
        if (busy) return false
        engine.setBetIndex(if (step == 99) Dragon.BETS.size - 1 else engine.betIndex + step); sounds.play("click"); syncHud(); return true
    }
    override fun toggleAuto() { auto = if (auto > 0) 0 else 25; sounds.play("click"); autoLeft = auto; if (auto > 0 && !busy) spin() }

    private fun holdEvent(e: String) {
        when (e) {
            "respin" -> sounds.play("spin", 0.5f, 1.3f)
            "land" -> { sounds.play("chip", 1f, 0.8f); sounds.play("scatter_stop"); onFx(Fx.COINS); setMood(MachineController.Mood.WIN, 0.8) }
            "miss" -> sounds.play("reel_stop", 0.6f)
            "grand" -> { sounds.play("big_win"); sounds.play("thunder"); onFx(Fx.LIGHTNING); onFx(Fx.BIG_WIN); onFx(Fx.COINS); setMood(MachineController.Mood.BIG, 4.0); renderer.burst(80) }
            "collect" -> {}
            "tick" -> sounds.play("chip", 0.8f, 1.0f + 0.04f * (hold?.collectIdx ?: 0).coerceAtMost(12))
            "total" -> { sounds.play("big_win"); sounds.play("coins"); onFx(Fx.BIG_WIN); onFx(Fx.COINS); setMood(MachineController.Mood.BIG, 3.0); renderer.burst(60) }
        }
        freeLeft = hold?.left ?: 0
    }

    override fun update(dt: Double): Boolean {
        if (pending >= 0) { pending -= dt; if (pending < 0 && !busy && auto > 0) spin() }
        var changed = animator.update(dt)
        hold?.let { h ->
            h.update(dt, ::holdEvent); changed = true
            if (h.stage == HoldWin.Stage.DONE) { hold = null; finish() }
        }
        if (renderer.dirty || changed || renderer.animating()) { renderer.draw(); changed = true }
        moodT -= dt
        if (moodT <= 0) mood = when { animator.phase == ReelAnimator.Phase.SPIN -> MachineController.Mood.SPIN; hold != null -> MachineController.Mood.FREE; else -> MachineController.Mood.IDLE }
        busy = animator.busy || hold != null
        // the topper: jackpot meters shimmer a few times a second, flare while a jackpot coin is on screen
        topperT += dt
        if (topperDirty || topperT > 0.2) { topperT = 0.0; topperDirty = false; topperArt.draw(engine.bet, System.nanoTime() / 1e9, hold?.let { h -> jackpotLit(h) }); topperFresh = true }
        return changed
    }
    private fun jackpotLit(h: HoldWin): String? = when {
        h.res.grand && h.stage.ordinal >= HoldWin.Stage.GRAND.ordinal -> "GRAND"
        h.locked.values.any { it == Dragon.MAJOR } -> "MAJOR"
        h.locked.values.any { it == Dragon.MINOR } -> "MINOR"
        h.locked.values.any { it == Dragon.MINI } -> "MINI"
        else -> null
    }
    private var topperFresh = true
    override fun topperChanged(): Boolean { val f = topperFresh; topperFresh = false; return f }
    override fun shownMatchesEngine() = engine.lastResult.let { it != null && animator.visibleGrid() == it.grid }
}
