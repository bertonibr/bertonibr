package com.bertonibr.pdfstudio.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Leitura em voz alta com o TextToSpeech do Android (pt-BR quando disponível). */
class Speech(context: Context, private val onStateChange: (Boolean) -> Unit) {

    private var ready = false
    private var pending: String? = null
    private var lastId: String? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            val ptBr = Locale("pt", "BR")
            if (tts.isLanguageAvailable(ptBr) >= TextToSpeech.LANG_AVAILABLE) tts.setLanguage(ptBr)
            pending?.let { speak(it) }
            pending = null
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onStateChange(true)
            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastId) onStateChange(false)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onStateChange(false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = onStateChange(false)
        })
    }

    fun speak(text: String) {
        if (!ready) {
            pending = text
            return
        }
        tts.stop()
        val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        val chunks = chunk(text.replace(Regex("\\s+"), " ").trim(), max)
        chunks.forEachIndexed { i, c ->
            val id = "u$i-${System.nanoTime()}"
            if (i == chunks.lastIndex) lastId = id
            tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    fun stop() {
        pending = null
        tts.stop()
        onStateChange(false)
    }

    fun shutdown() = tts.shutdown()

    /** Divide o texto em pedaços, preferindo quebrar no fim de frases. */
    private fun chunk(text: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', ';'), max)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out += rest.trim()
        return out
    }
}
