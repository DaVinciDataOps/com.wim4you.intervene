package com.wim4you.intervene.proximitychat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.wim4you.intervene.SecureLog
import java.util.Locale

class SpeechInputHelper(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            onListeningChanged(false)
        }

        override fun onError(error: Int) {
            onListeningChanged(false)
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Speech client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition timed out"
                SpeechRecognizer.ERROR_NO_MATCH -> "Could not understand speech"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
                SpeechRecognizer.ERROR_SERVER -> "Speech server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                else -> "Speech recognition failed"
            }
            if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                SecureLog.w(TAG, "Speech recognition error: $error")
            }
            onError(message)
        }

        override fun onResults(results: Bundle?) {
            onListeningChanged(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                onResult(text)
            } else {
                onError("Could not understand speech")
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    init {
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(recognitionListener)
            }
        }
        textToSpeech = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                textToSpeech?.language = Locale.getDefault()
            }
        }
    }

    fun startListening() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            onError("Speech recognition is not available on this device")
            return
        }
        onListeningChanged(true)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        recognizer.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        onListeningChanged(false)
    }

    fun speak(text: String) {
        if (!ttsReady) return
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chat_message")
    }

    fun shutdown() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        textToSpeech?.shutdown()
        textToSpeech = null
    }

    private companion object {
        const val TAG = "SpeechInputHelper"
    }
}
