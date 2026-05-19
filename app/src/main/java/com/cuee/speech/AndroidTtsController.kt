package com.cuee.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class AndroidTtsController(context: Context) : TextToSpeech.OnInitListener {
    private val locale = Locale.forLanguageTag(SpeechController.KO_KR)
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pendingText: String? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = locale
            tts?.setSpeechRate(1.0f)
            pendingText?.let { speak(it) }
            pendingText = null
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
            pendingText = text
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cuee-tts")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        ready = false
        pendingText = null
        tts?.shutdown()
        tts = null
    }
}
