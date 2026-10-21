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
        for (n in listOf("click", "lever", "spin", "reel_stop", "scatter_stop", "anticipation", "win_small", "big_win", "free_spins", "coins", "thunder", "no_credits"))
            runCatching { context.assets.openFd("sounds/$n.wav").use { ids[n] = pool.load(it, 1) } }
    }

    fun play(name: String, volume: Float = 1f, rate: Float = 1f): Int {
        if (muted) return 0
        val id = ids[name] ?: return 0
        return pool.play(id, volume, volume, 1, 0, rate)
    }
    fun spinStart() { spinStream = play("spin", 0.8f) }
    fun spinEnd() { if (spinStream != 0) pool.stop(spinStream); spinStream = 0 }
    override fun close() = pool.release()
}
