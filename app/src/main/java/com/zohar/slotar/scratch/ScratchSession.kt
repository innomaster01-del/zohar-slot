package com.zohar.slotar.scratch

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zohar.slotar.Fx
import com.zohar.slotar.audio.SlotSounds

/** Play-money wallet for the scratch cards (free refill when empty). */
object PlayWallet : Wallet { override var credits: Long = 2000 }

/**
 * One player's scratch-card session: buy → scratch (finger / virtual hand) → reveal → pay out + effects.
 * The engine fixes the outcome at purchase; this class only drives presentation.
 */
class ScratchSession(private val sounds: SlotSounds, private val onFx: (Fx) -> Unit) {
    val engine = ScratchEngine(PlayWallet)
    val renderer = ScratchRenderer()
    var tier by mutableStateOf(Tier.GOLD)
    var credits by mutableLongStateOf(PlayWallet.credits); private set
    var freeCards by mutableIntStateOf(0); private set
    var hasCard by mutableStateOf(false); private set
    var finished by mutableStateOf(false); private set
    var lastWin by mutableLongStateOf(0L); private set
    var message by mutableStateOf(""); private set
    private var shownCredits = PlayWallet.credits
    private var lastPx: FloatArray? = null
    private var scratchSoundT = 0f

    init { sync() }
    private fun sync() { credits = shownCredits; freeCards = engine.freeCardsFor(tier) }

    fun selectTier(t: Tier) { if (hasCard && !finished) return; tier = t; sounds.play("click"); sync() }

    /** Buy (or redeem a free card). The win is credited at once but shown only when revealed. */
    fun buy(): Boolean {
        if (hasCard && !finished) return false
        if (!engine.canBuy(tier)) {
            if (engine.freeCardsFor(tier) == 0 && PlayWallet.credits < tier.price) { PlayWallet.credits += 2000; message = "FREE REFILL: +2,000 PLAY CREDITS"; sounds.play("no_credits") }
            if (!engine.canBuy(tier)) return false
        }
        val before = PlayWallet.credits
        val card = engine.buy(tier) ?: return false
        shownCredits = before - (if (card.paidWithFreeCard) 0 else tier.price)
        renderer.load(card); hasCard = true; finished = false; lastWin = 0; lastPx = null
        message = if (card.paidWithFreeCard) "FREE CARD · SCRATCH IT!" else "SCRATCH THE SILVER AREAS"
        sounds.play("click"); sync(); return true
    }

    /** Scratch at a card pixel (null = finger lifted / not on the card). */
    fun scratchAt(px: FloatArray?) {
        if (!hasCard || finished) { lastPx = null; return }
        if (px == null) { lastPx = null; return }
        val from = lastPx ?: px
        renderer.scratch(from[0], from[1], px[0], px[1])
        if (scratchSoundT <= 0f) { sounds.play("scratch", 0.7f); scratchSoundT = 0.12f }
        lastPx = px
    }
    fun revealAll() { if (hasCard && !finished) { renderer.revealAll(); sounds.play("scratch", 0.9f) } }

    /** Per frame; returns true if the card picture changed (upload it). */
    fun update(dt: Float): Boolean {
        scratchSoundT -= dt
        val changed = renderer.update(dt)
        if (renderer.finished && !finished) {
            finished = true
            val c = renderer.card!!
            lastWin = c.totalWin; shownCredits = PlayWallet.credits
            val x = c.totalWin.toDouble() / c.tier.price
            when {
                x >= 50 -> { sounds.play("big_win"); sounds.play("thunder", 0.8f); sounds.play("coins"); onFx(Fx.LIGHTNING); onFx(Fx.BIG_WIN); onFx(Fx.COINS) }
                x >= 10 -> { sounds.play("big_win"); sounds.play("coins"); onFx(Fx.BIG_WIN); onFx(Fx.COINS) }
                x > 0 -> { sounds.play("win_small"); sounds.play("coins", 0.7f); onFx(Fx.COINS) }
            }
            if (c.freeCard) { sounds.play("free_spins"); onFx(Fx.FREE_SPINS) }
            message = when { c.totalWin > 0 && c.freeCard -> "WIN ${c.totalWin} + FREE CARD!"; c.totalWin > 0 -> "YOU WIN ${c.totalWin}!"; c.freeCard -> "FREE CARD!"; else -> "NO WIN THIS TIME" }
            sync()
        }
        return changed
    }

    /** One free play-money card a day (Silver). */
    fun claimDaily(prefs: android.content.SharedPreferences): Boolean {
        val today = java.time.LocalDate.now().toString()
        if (prefs.getString("daily", "") == today) { message = "DAILY CARD ALREADY CLAIMED TODAY"; return false }
        prefs.edit().putString("daily", today).apply()
        engine.grantFreeCard(Tier.SILVER); tier = Tier.SILVER
        message = "DAILY FREE CARD ADDED"; sync(); return true
    }
}
