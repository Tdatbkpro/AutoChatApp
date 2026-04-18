package com.example.autochat.ui.car

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class MediaTtsHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context, this)
    private var ready = false
    private var pending: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("vi", "VN")
            tts.setSpeechRate(1.0f)
            ready = true
            pending?.let { speak(it); pending = null }
        }
    }

    fun speak(text: String) {
        if (!ready) { pending = text; return }
        val audioAttrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val focusReq = android.media.AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttrs)
            .setOnAudioFocusChangeListener { }
            .build()
        audioManager.requestAudioFocus(focusReq)
        tts.setAudioAttributes(audioAttrs)

        // Tách câu đơn giản
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }.filter { it.isNotBlank() }
        sentences.forEachIndexed { i, s ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(s, mode, null, "tts_$i")
        }
    }

    fun stop() {
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.stop(); tts.shutdown()
    }
}