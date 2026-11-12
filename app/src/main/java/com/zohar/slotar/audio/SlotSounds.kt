package com.zohar.slotar.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** Casino sounds (synthesized WAVs in assets/sounds, made by tools/make_sounds.py). */
class SlotSounds(context: Context) : AutoCloseable {
    private val pool = SoundPool.Builder().setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
    private val ids = HashMap<String, Int>()
    private var spinStream = 0
    var muted = false; set(v) { field = v; if (v) pool.autoPause() else pool.autoResume() }

    init {
        for (n in listOf("dice_shake", "dice_wall", "dice_hit", "plinko_peg", "plinko_land", "click", "lever", "spin", "reel_stop", "scatter_stop", "anticipation", "win_small", "big_win", "free_spins", "coins", "thunder", "no_credits", "scratch", "card_slide", "card_flip", "chip", "chips_stack", "shuffle", "ball_roll", "ball_drop", "jet_loop", "jet_takeoff", "boom", "cash_out"))
            runCatching { context.assets.openFd("sounds/$n.wav").use { ids[n] = pool.load(it, 1) } }
    }

    fun play(name: String, volume: Float = 1f, rate: Float = 1f): Int {
        if (muted) return 0
        val id = ids[name] ?: return 0
        return pool.play(id, volume, volume, 1, 0, rate)
    }
    /** A sound that repeats until stopped (the jet engine); returns its stream, 0 if muted or missing. */
    fun loop(name: String, volume: Float = 1f, rate: Float = 1f): Int { if (muted) return 0; val id = ids[name] ?: return 0; return pool.play(id, volume, volume, 1, -1, rate) }
    /** Change a playing stream's pitch and speed (0.5 … 2.0) and loudness. */
    fun tune(stream: Int, rate: Float, volume: Float) { if (stream != 0) { pool.setRate(stream, rate.coerceIn(0.5f, 2f)); pool.setVolume(stream, volume, volume) } }
    fun stop(stream: Int) { if (stream != 0) pool.stop(stream) }
    fun spinStart() { spinStream = play("spin", 0.8f) }
    fun spinEnd() { if (spinStream != 0) pool.stop(spinStream); spinStream = 0 }
    override fun close() = pool.release()
}
