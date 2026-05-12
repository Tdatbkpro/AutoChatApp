package com.example.autochat.ui.phone.fragment

import android.app.Application
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.autochat.tts.TTSManager
import com.example.autochat.tts.TtsEngine
import com.example.autochat.tts.TtsPrefs
import com.example.autochat.tts.TtsSettings
import com.example.autochat.tts.TtsVoice
import com.example.autochat.tts.VieNeuMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TtsSettingsViewModel @Inject constructor(
    application: Application,
    val ttsManager: TTSManager,
) : AndroidViewModel(application) {

    private val context get() = getApplication<Application>()

    // ── State ─────────────────────────────────────────────────────────────────
    val voices        = MutableLiveData<List<TtsVoice>>(emptyList())
    val voicesLoading = MutableLiveData(false)
    val voicesError   = MutableLiveData<String?>(null)

    val isRecording      = MutableLiveData(false)
    val recordSeconds    = MutableLiveData(0)
    val recordedFilePath = MutableLiveData<String?>(null)

    val transcript       = MutableLiveData("")   // auto-fill từ SpeechRecognizer
    val transcribing     = MutableLiveData(false)

    val settings = MutableLiveData(TtsPrefs.load(context))

    private var mediaRecorder: MediaRecorder? = null
    private var previewPlayer: MediaPlayer? = null
    private var recordTimerJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null

    // ── Load voices ───────────────────────────────────────────────────────────
    fun loadVoices() {
        viewModelScope.launch {
            voicesLoading.value = true
            voicesError.value   = null
            val result = ttsManager.fetchPresetVoices()
            if (result.isEmpty()) {
                voicesError.value = "Không tải được danh sách giọng"
            } else {
                voices.value = result
            }
            voicesLoading.value = false
        }
    }

    // ── Ghi âm ───────────────────────────────────────────────────────────────
    fun startRecording() {
        val file = File(context.filesDir, "tts_ref_voice_${System.currentTimeMillis()}.m4a")
        recordedFilePath.value = file.absolutePath

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(24000)
            setAudioChannels(1)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        isRecording.value = true
        recordSeconds.value = 0

        recordTimerJob = viewModelScope.launch {
            val MAX_SEC = 10
            while ((recordSeconds.value ?: 0) < MAX_SEC) {
                delay(1000)
                recordSeconds.value = (recordSeconds.value ?: 0) + 1
            }
            // Tự dừng sau 10s
            stopRecording()
        }
    }

    fun stopRecording() {
        recordTimerJob?.cancel()
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e("TTS_RECORD", "Stop error: ${e.message}")
        }
        isRecording.value = false

        // Auto transcribe sau khi ghi xong
        recordedFilePath.value?.let { path ->
            val file = File(path)
            if (file.exists() && file.length() > 0) {
                startTranscription()
            }
        }
    }

    // ── Transcription bằng SpeechRecognizer ──────────────────────────────────
    private fun startTranscription() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        transcribing.value = true

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.getOrNull(0) ?: ""
                transcript.postValue(text)
                transcribing.postValue(false)
                Log.d("TTS_TRANSCRIPT", "Result: $text")
            }
            override fun onError(error: Int) {
                transcribing.postValue(false)
                Log.e("TTS_TRANSCRIPT", "Error: $error")
            }
            override fun onReadyForSpeech(p: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(p: Float) {}
            override fun onBufferReceived(p: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(p: android.os.Bundle?) {}
            override fun onEvent(p: Int, p2: android.os.Bundle?) {}
        })

        // Transcribe từ file audio đã ghi
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    // ── Preview audio đã ghi ──────────────────────────────────────────────────
    fun playRecordedAudio() {
        val path = recordedFilePath.value ?: return
        previewPlayer?.release()
        previewPlayer = MediaPlayer().apply {
            setDataSource(path)
            prepare()
            start()
            setOnCompletionListener { release(); previewPlayer = null }
        }
    }

    fun deleteRecordedAudio() {
        previewPlayer?.release()
        previewPlayer = null
        recordedFilePath.value?.let { File(it).delete() }
        recordedFilePath.value = null
        transcript.value = ""
        settings.value = settings.value?.copy(refAudioPath = null)
    }

    // ── Save settings ─────────────────────────────────────────────────────────
    fun saveSettings(
        engine: TtsEngine,
        vieNeuMode: VieNeuMode,
        selectedVoiceId: String?,
        selectedVoiceName: String?,
        refText: String,
        voiceName: String?,   // ← thêm
        pitch: Int,           // ← thêm
        speed: Float,         // ← thêm
        volume: Int,          // ← thêm
    ) {
        val refAudioPath = recordedFilePath.value ?: settings.value?.refAudioPath
        val newSettings = TtsSettings(
            engine          = engine,
            vieNeuMode      = vieNeuMode,
            presetVoiceId   = selectedVoiceId,
            presetVoiceName = selectedVoiceName,
            refAudioPath    = refAudioPath,
            refText         = refText,
            voiceName       = voiceName,
            pitch           = pitch,
            speed           = speed,
            volume          = volume,
        )
        TtsPrefs.save(context, newSettings)
        ttsManager.reloadSettings()
        settings.value = newSettings
    }

    fun formatSeconds(s: Int): String = "%02d:%02d".format(s / 60, s % 60)

    override fun onCleared() {
        super.onCleared()
        recordTimerJob?.cancel()
        mediaRecorder?.release()
        previewPlayer?.release()
        speechRecognizer?.destroy()
    }
}