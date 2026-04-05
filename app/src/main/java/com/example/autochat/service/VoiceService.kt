package com.example.autochat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.autochat.R

class VoiceService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var silenceTimer: Runnable? = null
    private var partialText = ""
    private var resultSent = false
    companion object {
        const val CHANNEL_ID = "voice_channel"
        const val ACTION_START = "START_LISTENING"
        const val ACTION_STOP = "STOP_LISTENING"
        const val ACTION_INIT = "INIT_ONLY" // ✅ Thêm mới
        fun isMiui(): Boolean {
            return !Build.MANUFACTURER
                .equals("xiaomi", ignoreCase = true)
                .not()
        }
    }



    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification("San sang lang nghe..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                resultSent = false  // Reset khi start mới
                startListening()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY  // ✅ Đổi từ START_STICKY sang NOT_STICKY
    }

    private var isListening = false  // ✅ Thêm flag

    private fun startListening() {
        // ✅ Nếu đang nghe thì destroy trước
        if (isListening) {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            isListening = false
            Handler(Looper.getMainLooper()).postDelayed({
                doStartListening()
            }, 500) // Đợi 500ms rồi mới start lại
            return
        }
        doStartListening()
    }

    private fun doStartListening() {
        // ✅ Bắt buộc chạy trên main thread
        Handler(Looper.getMainLooper()).post {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                sendResult("Khong ho tro")
                return@post
            }

            isListening = true
            updateNotification("Dang lang nghe...")

            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    broadcastStatus("Hay noi...")
                }
                override fun onBeginningOfSpeech() {
                    broadcastStatus("Dang ghi am...")
                    cancelSilenceTimer()
                }
                override fun onEndOfSpeech() {
                    broadcastStatus("Dang xu ly...")
                    isListening = false
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.getOrNull(0) ?: return
                    partialText = partial
                    broadcastPartial(partial)
                    resetSilenceTimer()
                }
                override fun onResults(results: Bundle) {
                    isListening = false
                    cancelSilenceTimer()
                    val text = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.getOrNull(0) ?: ""
                    sendResult(text)
                }
                override fun onError(error: Int) {
                    isListening = false
                    cancelSilenceTimer()
                    Log.e("VOICE_DEBUG", "onError: $error")
                    when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                            speechRecognizer?.destroy()
                            speechRecognizer = null
                            Handler(Looper.getMainLooper()).postDelayed({
                                doStartListening()
                            }, 1000)
                        }
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        SpeechRecognizer.ERROR_NO_MATCH -> {
                            if (partialText.isNotBlank()) sendResult(partialText)
                            else { broadcastStatus("TIMEOUT"); stopSelf() }
                        }
                        else -> {
                            broadcastStatus("Loi $error")
                            stopSelf()
                        }
                    }
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer?.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                }
            )

            resetSilenceTimer()
            Log.e("VOICE_DEBUG", "startListening() called on main thread")
        }
    }

    private fun resetSilenceTimer() {
        cancelSilenceTimer()
        silenceTimer = Runnable {
            Log.e("VOICE_DEBUG", "Silence timeout 3s")
            if (partialText.isNotBlank()) {
                sendResult(partialText)
            } else {
                broadcastStatus("TIMEOUT")
                stopSelf()
            }
        }
        handler.postDelayed(silenceTimer!!, 3000)
    }

    private fun cancelSilenceTimer() {
        silenceTimer?.let { handler.removeCallbacks(it) }
        silenceTimer = null
    }

    private fun sendResult(text: String) {
        if (resultSent) return  // ✅ Chỉ gửi 1 lần
        resultSent = true

        applicationContext.sendBroadcast(
            Intent("com.example.autochat.VOICE_RESULT").apply {
                putExtra("voice_text", text)
            }
        )
        handler.postDelayed({ stopSelf() }, 500)
    }

    private fun broadcastStatus(status: String) {
        applicationContext.sendBroadcast(
            Intent("com.example.autochat.VOICE_STATUS").apply {
                putExtra("status", status)
            }
        )
    }

    private fun broadcastPartial(text: String) {
        applicationContext.sendBroadcast(
            Intent("com.example.autochat.VOICE_PARTIAL").apply {
                putExtra("partial_text", text)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Chatbot")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Recognition",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelSilenceTimer()
        speechRecognizer?.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}