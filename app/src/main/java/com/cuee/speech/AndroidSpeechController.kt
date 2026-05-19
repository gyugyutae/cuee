package com.cuee.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

interface SpeechController {
    fun startListening(localeTag: String = KO_KR)
    fun stopListening()
    fun destroy()

    companion object {
        const val KO_KR = "ko-KR"
    }
}

class AndroidSpeechController(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (SpeechError) -> Unit
) : SpeechController {
    private val appContext = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false

    override fun startListening(localeTag: String) {
        val normalizedLocaleTag = SpeechController.KO_KR
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(listener)
            speechRecognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, normalizedLocaleTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, normalizedLocaleTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        listening = true
        recognizer.startListening(intent)
    }

    override fun stopListening() {
        if (!listening) return
        listening = false
        speechRecognizer?.cancel()
    }

    override fun destroy() {
        listening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            listening = false
        }

        override fun onError(error: Int) {
            listening = false
            onError(SpeechError.fromRecognizerError(error))
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val spokenText = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (spokenText.isBlank()) {
                onError(SpeechError.NO_MATCH)
            } else {
                onResult(spokenText)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

enum class SpeechError {
    NO_MATCH,
    UNAVAILABLE,
    CANCELLED;

    companion object {
        fun fromRecognizerError(error: Int): SpeechError {
            return when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NO_MATCH
                SpeechRecognizer.ERROR_CLIENT -> CANCELLED
                else -> UNAVAILABLE
            }
        }
    }
}
