package com.example.autochat.ui.phone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class VoiceActivity : Activity() {

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var statusLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("VOICE_DEBUG", "VoiceActivity onCreate, action: ${intent?.action}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // Xin permission notification (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    102
                )
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#DD000000"))
        }
        statusLabel = TextView(this).apply {
            text = "Dang khoi dong..."
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }
        layout.addView(statusLabel)
        setContentView(layout)

        // ✅ Kiểm tra intent action từ DHU
        when (intent?.action) {
            "android.speech.action.VOICE_SEARCH_HANDS_FREE",
            "android.intent.action.VOICE_COMMAND" -> {
                Log.e("VOICE_DEBUG", "Nhan voice intent tu DHU!")
                statusLabel.text = "Nhan duoc tu DHU mic!"
                Handler(Looper.getMainLooper()).postDelayed({
                    startListening()
                }, 300)
            }
            else -> {
                // Gọi từ VoiceReceiver như cũ
                Handler(Looper.getMainLooper()).postDelayed({
                    startListening()
                }, 300)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.e("VOICE_DEBUG", "onNewIntent action: ${intent?.action}")
        setIntent(intent)
        startListening()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendResult("Thiet bi khong ho tro nhan dien giong noi")
            return
        }

        statusLabel.text = "Hay noi..."
        Log.e("VOICE_DEBUG", "startListening()")

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                statusLabel.text = "Dang nghe..."
                Log.e("VOICE_DEBUG", "onReadyForSpeech")
            }

            override fun onBeginningOfSpeech() {
                statusLabel.text = "Dang ghi am..."
            }

            override fun onEndOfSpeech() {
                statusLabel.text = "Dang xu ly..."
            }

            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.getOrNull(0) ?: ""
                Log.e("VOICE_DEBUG", "Ket qua: $text")
                statusLabel.text = "Nhan duoc: $text"
                sendResult(text)
            }

            override fun onError(error: Int) {
                Log.e("VOICE_DEBUG", "onError: $error")
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Khong nghe ro"
                    SpeechRecognizer.ERROR_NETWORK -> "Loi mang"
                    SpeechRecognizer.ERROR_AUDIO -> "Loi microphone"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Het thoi gian"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Thieu quyen mic"
                    else -> "Loi $error"
                }
                statusLabel.text = "Loi: $msg"
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
        )
    }

    private fun sendResult(text: String) {
        sendBroadcast(Intent("com.example.autochat.VOICE_RESULT").apply {
            putExtra("voice_text", text)
        })
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 800)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }
}