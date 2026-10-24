package com.zohar.slotar.blackjack

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** The dealer's voice: the phone's own English text-to-speech (offline when the voice is installed). */
class Voice(context: Context, private val onTalking: (Boolean) -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    var muted = false
    private var n = 0
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale.US)
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            // a calm, slightly low male dealer
            runCatching { tts.voices?.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }?.firstOrNull { it.name.contains("male", true) && !it.name.contains("female", true) }?.let { tts.voice = it } }
            tts.setPitch(0.92f); tts.setSpeechRate(1.02f)
        }
    }
    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { onTalking(true) } }
            override fun onDone(id: String?) { main.post { onTalking(false) } }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { onTalking(false) } }
        })
    }
    fun say(text: String, flush: Boolean = true) {
        if (muted || !ready) { onTalking(true); main.postDelayed({ onTalking(false) }, (300 + text.length * 55).toLong()); return }
        tts.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "u${n++}")
    }
    override fun close() { runCatching { tts.stop(); tts.shutdown() } }
}
