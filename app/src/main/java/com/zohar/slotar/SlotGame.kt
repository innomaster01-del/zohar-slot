package com.zohar.slotar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zohar.slotar.ar.MachineController
import com.zohar.slotar.audio.SlotSounds
import com.zohar.slotar.engine.SlotEngine
import com.zohar.slotar.engine.SpinResult
import com.zohar.slotar.engine.ZOHAR
import com.zohar.slotar.screen.Hud
import com.zohar.slotar.screen.ReelAnimator
import com.zohar.slotar.screen.ReelRenderer

/** Effects the AR scene plays around the machine. */
enum class Fx { LIGHTNING, COINS, BIG_WIN, FREE_SPINS }

/**
 * The playable machine without the 3D: engine (decides) → animator + renderer (shows) → sounds and AR effects.
 * PLAY MONEY ONLY: credits are free and refill when empty; nothing can be bought or cashed out.
 */
class SlotGame(private val sounds: SlotSounds, private val onFx: (Fx) -> Unit) {
    val engine = SlotEngine(ZOHAR, 2000)
    private var auto = 0
    private var pending = -1.0
    private var moodT = 0.0
    var mood = MachineController.Mood.IDLE; private set

    // Compose-observable HUD
    var credits by mutableLongStateOf(engine.credits); private set
    var bet by mutableLongStateOf(engine.totalBetAmount); private set
    var busy by mutableStateOf(false); private set
    var inFree by mutableStateOf(false); private set
    var freeLeft by mutableIntStateOf(0); private set
    var autoLeft by mutableIntStateOf(0); private set

    private val listener = object : ReelAnimator.Listener {
        override fun onSpinStart(r: SpinResult) { sounds.spinStart() }
        override fun onReelStop(index: Int, hasScatter: Boolean) {
            sounds.play(if (hasScatter) "scatter_stop" else "reel_stop")
            if (index == ZOHAR.reels.size - 1) sounds.spinEnd()
            if (index < ZOHAR.reels.size - 1 && animator.reels[index + 1].antic && hasScatter) sounds.play("anticipation", 0.7f)
        }
        override fun onResult(r: SpinResult, overlay: ReelAnimator.Overlay.Kind?) {
            val x = r.totalWin.toDouble() / r.totalBet
            when {
                overlay == ReelAnimator.Overlay.Kind.FS_INTRO -> { sounds.play("free_spins"); sounds.play("thunder", 0.9f); onFx(Fx.LIGHTNING); onFx(Fx.FREE_SPINS); setMood(MachineController.Mood.FREE, 3.0) }
                overlay == ReelAnimator.Overlay.Kind.EPIC || overlay == ReelAnimator.Overlay.Kind.MEGA -> {
                    sounds.play("big_win"); sounds.play("thunder", 0.8f); sounds.play("coins"); onFx(Fx.LIGHTNING); onFx(Fx.BIG_WIN); onFx(Fx.COINS); setMood(MachineController.Mood.BIG, 5.0)
                }
                overlay == ReelAnimator.Overlay.Kind.BIG -> { sounds.play("big_win"); sounds.play("coins"); onFx(Fx.BIG_WIN); onFx(Fx.COINS); setMood(MachineController.Mood.BIG, 3.5) }
                x >= 5 -> { sounds.play("win_small"); sounds.play("coins", 0.7f); onFx(Fx.COINS); setMood(MachineController.Mood.WIN, 2.0) }
                r.totalWin > 0 -> { sounds.play("win_small"); setMood(MachineController.Mood.WIN, 1.5) }
            }
        }
        override fun onFsOutro(r: SpinResult) { sounds.play("big_win"); sounds.play("coins"); onFx(Fx.COINS); onFx(Fx.BIG_WIN); setMood(MachineController.Mood.BIG, 3.0) }
        override fun onDone(r: SpinResult) { syncHud(); if (auto > 0 || engine.inFreeSpins) pending = 0.45 }
    }
    val animator = ReelAnimator(ZOHAR, listener)
    val renderer = ReelRenderer(animator)

    init { syncHud() }

    private fun setMood(m: MachineController.Mood, secs: Double) { mood = m; moodT = secs }
    private fun syncHud(creditsShown: Long = engine.credits, message: String = "") {
        credits = engine.credits; bet = engine.totalBetAmount; inFree = engine.inFreeSpins; freeLeft = engine.freeLeft; busy = animator.busy; autoLeft = auto
        renderer.hud = Hud(creditsShown, engine.totalBetAmount, engine.inFreeSpins, engine.freeLeft, engine.freeWon, message)
    }

    /** SPIN (or slam-stop while spinning). */
    fun spin(): String {
        if (animator.busy) { animator.slamStop(); return "slam" }
        if (!engine.canSpin()) { engine.refill(); auto = 0; sounds.play("no_credits"); syncHud(message = "קיבלת 2,000 קרדיטים חינם"); return "refill" }
        val before = engine.credits
        val r = engine.spin(); if (!r.ok) return "no"
        if (auto > 0 && r.mode == "base") auto--
        animator.startSpin(r)
        syncHud(creditsShown = before - r.cost)                  // the win counts up on screen; credits settle on done
        busy = true
        return "spin"
    }
    fun bet(step: Int): Boolean {
        if (animator.busy || engine.inFreeSpins) return false
        engine.setBetIndex(if (step == 99) ZOHAR.lineBets.size - 1 else engine.betIndex + step); sounds.play("click"); syncHud(); return true
    }
    fun toggleAuto() { auto = if (auto > 0) 0 else 25; sounds.play("click"); autoLeft = auto; if (auto > 0 && !animator.busy) spin() }

    /** Advance the game; returns true when the screen bitmap changed. */
    fun update(dt: Double): Boolean {
        if (pending >= 0) { pending -= dt; if (pending < 0 && !animator.busy && (auto > 0 || engine.inFreeSpins)) spin() }
        val changed = animator.update(dt)
        if (changed) renderer.draw()
        moodT -= dt
        if (moodT <= 0) mood = when {
            animator.phase == ReelAnimator.Phase.SPIN -> MachineController.Mood.SPIN
            engine.inFreeSpins -> MachineController.Mood.FREE
            else -> MachineController.Mood.IDLE
        }
        busy = animator.busy
        return changed
    }
}
