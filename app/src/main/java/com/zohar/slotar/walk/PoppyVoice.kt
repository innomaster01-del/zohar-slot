package com.zohar.slotar.walk

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Poppy's little voice, like the desktop pet's: a few quick triangle-wave chirps ("bi-di-bi") when she says something,
 * a giggle when she is happy, a yawn, a snore. Synthesised on the phone, no sound files.
 */
class PoppyVoice(boy: Boolean) {
    private val rate = 22050
    private val base = if (boy) 470.0 else 560.0
    @Volatile var muted = false
    private var seed = 7L
    private fun rnd(a: Double, b: Double): Double { seed = (seed * 6364136223846793005L + 1442695040888963407L); return a + ((seed ushr 11).toDouble() / (1L shl 53)) * (b - a) }

    private fun tone(out: FloatArray, at: Int, f0: Double, f1: Double, dur: Double, vol: Double) {
        val n = (dur * rate).toInt(); var ph = 0.0
        for (i in 0 until n) {
            val k = at + i; if (k >= out.size) break
            val t = i.toDouble() / n; val f = f0 * Math.pow(f1 / f0, t)
            ph += f / rate
            val tri = 4 * abs(ph - Math.floor(ph + 0.5)) - 1                       // triangle wave
            val env = if (t < 0.12) t / 0.12 else (1 - t) / 0.88
            out[k] += (tri * env * vol).toFloat()
        }
    }
    private fun play(seconds: Double, fill: (FloatArray) -> Unit) {
        if (muted) return
        thread(name = "poppy-voice") {
            runCatching {
                val buf = FloatArray((seconds * rate).toInt()); fill(buf)
                val pcm = ShortArray(buf.size) { (buf[it].coerceIn(-1f, 1f) * 32000).toInt().toShort() }
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                t.write(pcm, 0, pcm.size); t.play(); Thread.sleep((seconds * 1000).toLong() + 80); t.release()
            }
        }
    }

    /** "bi-di-bi": [n] quick chirps around her voice. */
    fun chirp(n: Int = 3) = play(n * 0.085 + 0.1) { b -> for (i in 0 until n) { val f = base * rnd(0.85, 1.35); tone(b, (i * 0.085 * rate).toInt(), f, f * rnd(0.8, 1.25), 0.075, 0.33) } }
    fun giggle() = play(0.45) { b -> for (i in 0 until 5) tone(b, (i * 0.07 * rate).toInt(), base * 1.4 + i * 60, base * 1.7 + i * 60, 0.06, 0.28) }
    fun yawn() = play(0.9) { b -> tone(b, 0, base * 0.9, base * 0.45, 0.8, 0.25) }
    fun snore() = play(1.0) { b -> for (i in b.indices) { val t = i.toDouble() / b.size; b[i] = (sin(2 * PI * 48 * i / rate) * sin(PI * t) * 0.12 * (0.6 + 0.4 * sin(2 * PI * 22 * i / rate))).toFloat() } }
    fun boing() = play(0.3) { b -> tone(b, 0, 220.0, 660.0, 0.22, 0.3) }
}
