package com.example.autochat.tts

import android.R.attr.label
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class TTSManager @Inject constructor(
    private val context: Context,
    @Named("ttsOkHttpClient") private val client: OkHttpClient,        // ← inject OkHttpClient từ DI
    @Named("ttsBaseUrl") private val baseUrl: String,  // ← inject URL
) {
    private var settings: TtsSettings = TtsPrefs.load(context)
    private var googleTts: TextToSpeech? = null
    private var googleReady = false
    private var mediaPlayer: MediaPlayer? = null
    var onAudioReadyBefore: ((filePath: String, label: String) -> Unit)? = null

    var isSpeaking = false
    var onStart: (() -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onAudioReady: ((filePath: String, label: String) -> Unit)? = null

    fun reloadSettings() {
        settings = TtsPrefs.load(context)
    }

    fun initGoogle() {
        googleTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                googleTts?.language = Locale("vi", "VN")
                googleReady = true
            }
        }
    }
    suspend fun speak(text: String) {
        if (isSpeaking) stop()
        isSpeaking = true
        onStart?.invoke()
        when (settings.engine) {
            TtsEngine.GOOGLE -> speakGoogle(text)
            TtsEngine.VIENEU -> speakVieNeu(text)
            TtsEngine.EDGE,
            TtsEngine.SYSTEM -> speakGoogle(text)  // fallback về Google TTS API
        }
    }
    fun clearTtsCache() {
        context.cacheDir.listFiles { file ->
            file.name.startsWith("tts_") ||
                    file.name.startsWith("google_tts_") ||
                    file.name.startsWith("preview_")
        }?.forEach { it.delete() }
    }
    private suspend fun speakGoogle(text: String) {
        if (!googleReady) { initGoogle(); delay(1000) }

        // Lưu ra file thay vì đọc thẳng
        val outputFile = File(context.cacheDir, "google_tts_${System.currentTimeMillis()}.wav")
        googleTts?.setPitch(1.0f + settings.pitch * 0.1f)
        googleTts?.setSpeechRate(settings.speed)
        suspendCancellableCoroutine { cont ->
            val params = android.os.Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "save_tts")
            }

            googleTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    isSpeaking = false
                    // File đã được lưu → gọi onAudioReady
                    if (outputFile.exists() && outputFile.length() > 0) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onAudioReady?.invoke(outputFile.absolutePath, "Google TTS")
                            onDone?.invoke()
                        }
                    } else {
                        onDone?.invoke()
                    }
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onError(id: String?) {
                    isSpeaking = false
                    onDone?.invoke()
                    if (cont.isActive) cont.resume(Unit)
                }
            })

            // synthesizeToFile thay vì speak
            googleTts?.synthesizeToFile(
                text,
                params,
                outputFile,
                "save_tts"
            )

            cont.invokeOnCancellation { stop() }
        }
    }
    suspend fun previewVoice(voiceId: String, onAudioReady: (String) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/tts/preview-voice/$voiceId")
                    .post("".toRequestBody())
                    .build()

                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) return@withContext

                val tmpFile = File(context.cacheDir, "preview_${voiceId}.wav")
                tmpFile.writeBytes(resp.body!!.bytes())

                withContext(Dispatchers.Main) {
                    Log.d("TTS_DEBUG", "invoking onAudioReady: $onAudioReady")
                    onAudioReadyBefore?.invoke(tmpFile.absolutePath, label.toString())
                    onAudioReady?.invoke(tmpFile.absolutePath)
                    isSpeaking = false
                    onDone?.invoke()
                }
            } catch (e: Exception) {
                Log.e("TTS", "Preview error: ${e.message}")
            }
            // preview không ảnh hưởng isSpeaking — đúng, không cần sửa
        }
    }
    private suspend fun speakVieNeu(text: String) {
        withContext(Dispatchers.IO) {
            try {
                Log.d("TTS_DEBUG", "mode=${settings.vieNeuMode}, voiceId=${settings.presetVoiceId}, refAudio=${settings.refAudioPath}")
                Log.d("TTS_DEBUG", "speakVieNeu() start, mode=${settings.vieNeuMode}")
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("text", text)

                when (settings.vieNeuMode) {
                    VieNeuMode.CLONE -> {
                        val path = settings.refAudioPath
                        if (!path.isNullOrEmpty()) {
                            val file = File(path)
                            if (file.exists() && file.length() > 0) {
                                val mimeType = when {
                                    path.endsWith(".mp3") -> "audio/mpeg"
                                    path.endsWith(".m4a") -> "audio/mp4"
                                    path.endsWith(".ogg") -> "audio/ogg"
                                    else                 -> "audio/wav"
                                }
                                body.addFormDataPart(
                                    "ref_audio", file.name,
                                    file.asRequestBody(mimeType.toMediaType())
                                )
                                body.addFormDataPart("ref_text", settings.refText?.takeIf { it.isNotBlank() } ?: " ")
                            }
                        }
                    }
                    VieNeuMode.PRESET -> {
                        // Preset voice
                        settings.presetVoiceId?.let {
                            body.addFormDataPart("voice_id", it)
                        }
                    }
                }

                val req = Request.Builder()
                    .url("$baseUrl/tts/synthesize")
                    .post(body.build())
                    .build()
                Log.d("TTS_DEBUG", "speakVieNeu() request sent")
                val resp = client.newCall(req).execute()
                Log.d("TTS_DEBUG", "speakVieNeu() response=${resp.code}")
                if (!resp.isSuccessful) { isSpeaking = false; onDone?.invoke(); return@withContext }

                val tmpFile = File(context.cacheDir, "tts_${System.currentTimeMillis()}.wav")
                tmpFile.writeBytes(resp.body!!.bytes())

                withContext(Dispatchers.Main) {

                    val label = when (settings.vieNeuMode) {
                        VieNeuMode.PRESET -> settings.presetVoiceName ?: "VieNeu TTS"
                        VieNeuMode.CLONE  -> "Giọng của tôi"
                    }
                    onAudioReadyBefore?.invoke(tmpFile.absolutePath, label)
                    if (onAudioReady != null) {
                        onAudioReady?.invoke(tmpFile.absolutePath, label)
                        // AudioPlayerController tự quản lý playback
                        // TTSManager chỉ cần reset trạng thái của mình
                        isSpeaking = false
                        onDone?.invoke()
                    } else {
                        playAudio(tmpFile.absolutePath)
                        // isSpeaking và onDone sẽ được gọi bởi playAudio() khi hoàn thành
                    }
                }
            } catch (e: Exception) {
                Log.e("TTS_DEBUG", "speakVieNeu() error: ${e.message}", e)

            } finally {
                isSpeaking = false
                withContext(Dispatchers.Main) {
                    onDone?.invoke()
                }
            }
        }
    }

    // Fetch danh sách voices từ server
    suspend fun fetchPresetVoices(): List<TtsVoice> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/tts/voices").get().build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val json = org.json.JSONObject(resp.body!!.string())
            val arr = json.getJSONArray("voices")
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                TtsVoice(id = obj.getString("id"), name = obj.getString("name"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun playAudio(path: String) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            setDataSource(path)
            prepare(); start()
            setOnCompletionListener { isSpeaking = false; onDone?.invoke(); release(); mediaPlayer = null }
            setOnErrorListener { _, _, _ -> isSpeaking = false; onDone?.invoke(); true }
        }
    }

    fun stop() {
        googleTts?.stop()
        mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
        isSpeaking = false; onDone?.invoke()
    }

    fun destroy() { stop(); googleTts?.shutdown(); googleTts = null }
}