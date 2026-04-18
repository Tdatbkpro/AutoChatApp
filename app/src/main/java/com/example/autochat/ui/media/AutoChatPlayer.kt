package com.example.autochat.ui.media

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AutoChatPlayer
 *
 * ExoPlayer được expose trực tiếp để dùng trong MediaLibrarySession.Builder.
 *
 * Chiến lược hiện full content trên AA Now Playing:
 *   - Chia text thành các câu ngắn (≤ MAX_SENTENCE_LEN ký tự)
 *   - Synthesize mỗi câu thành file WAV riêng (song song trên IO thread)
 *   - Load tất cả vào ExoPlayer như một playlist
 *   - AA hiện từng câu làm **title** khi phát → user đọc được full nội dung
 */
@OptIn(UnstableApi::class)
class AutoChatPlayer(private val context: Context) {

    companion object {
        const val PREFS_NAME     = "tts_settings"
        const val KEY_TTS_SPEED  = "tts_speed"
        const val KEY_TTS_PITCH  = "tts_pitch"
        const val KEY_TTS_LOCALE = "tts_locale"
        private const val TAG    = "TTS_PLAYER"

        /**
         * Độ dài tối đa mỗi câu (ký tự).
         * AA Now Playing hiện ~50-70 ký tự trên title mà không cắt.
         * Đặt 60 để an toàn với cả tiếng Việt (ký tự rộng hơn).
         */
        // AA wraps TITLE (2 dòng) nhưng CẮT subtitle → đặt câu vào title
        private const val MAX_SENTENCE_LEN = 76
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ExoPlayer expose ra ngoài để MediaLibrarySession.Builder dùng trực tiếp
    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
                .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
                .build(),
            /* handleAudioFocus= */ true
        )
        .build()

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var pendingSpeak: Pair<String, String>? = null  // text, title
    private val handler = Handler(Looper.getMainLooper())

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                applySettings()
                pendingSpeak?.let { (text, title) ->
                    speakText(text, title)
                    pendingSpeak = null
                }
                Log.d(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                Log.d(TAG, "ExoPlayer state=$state, isPlaying=${exoPlayer.isPlaying}")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "ExoPlayer isPlaying=$isPlaying")
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.message}", error)
            }
        })
    }

    // ── Playback ───────────────────────────────────────────────────────────

    /**
     * Load một URI đơn vào ExoPlayer và phát ngay.
     * Dùng cho playback file có sẵn (không phải TTS).
     */
    fun loadAndPlay(uri: android.net.Uri, title: String = "AutoChat 🤖", subtitle: String = "") {
        Log.d(TAG, "loadAndPlay single: $uri")
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title.ifBlank { "AutoChat 🤖" })
                    .setSubtitle(subtitle)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // ── TTS API ────────────────────────────────────────────────────────────

    /**
     * Synthesize [text] thành playlist các câu ngắn → phát qua ExoPlayer.
     *
     * Mỗi câu được synthesize thành file WAV riêng, rồi load vào ExoPlayer
     * như một playlist. AA Now Playing hiện từng câu làm title → user đọc
     * được full nội dung khi theo dõi màn hình.
     *
     * @param text    nội dung cần đọc
     * @param title   header hiện ở dòng 1 (lớn) trên AA — nên là chủ đề ngắn
     * @param onReady callback tùy chọn khi file đầu tiên sẵn sàng
     */
    fun speakText(
        text: String,
        title: String = "AutoChat 🤖",
        artworkUri: android.net.Uri? = null,   // ← thêm
        onReady: ((File) -> Unit)? = null
    ) {
        if (!ttsReady) {
            pendingSpeak = Pair(text, title)
            return
        }
        Log.d(TAG, "speakText: length=${text.length}, title='$title'")

        Thread {
            val outFile = File(context.cacheDir, "tts_${System.currentTimeMillis()}.wav")
            val success = synthesizeToFileSync(text, outFile)

            if (!success || !outFile.exists() || outFile.length() == 0L) {
                Log.e(TAG, "speakText: synthesis failed")
                return@Thread
            }

            handler.post {
                onReady?.invoke(outFile)
                loadAndPlay(
                    uri        = fileToUri(outFile),
                    title      = title,
                    subtitle   = text,
                    artworkUri = artworkUri   // ← truyền xuống
                )
            }
        }.start()
    }

    /**
     * Tạo ExoPlayer playlist từ danh sách (wavFile, sentenceText).
     *
     * Với mỗi item:
     *   title    = [overallTitle]   → dòng 1 AA (ổn định, cho biết ngữ cảnh)
     *   subtitle = [sentenceText]   → dòng 2 AA (ngắn ≤ MAX_SENTENCE_LEN → không bị cắt)
     *
     * Khi ExoPlayer tự chuyển item, AA cập nhật subtitle → user thấy từng câu.
     */
    fun loadAndPlay(
        uri: android.net.Uri,
        title: String = "AutoChat 🤖",
        subtitle: String = "",
        artworkUri: android.net.Uri? = null   // ← thêm
    ) {
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title.ifBlank { "AutoChat 🤖" })
                    .setSubtitle(subtitle)
                    .setArtworkUri(artworkUri)   // ← thêm
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build()
            )
            .build()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /**
     * Synthesize text → file đồng bộ (blocking).
     * Chỉ gọi từ background thread.
     */
    fun synthesizeToFileSync(text: String, outputFile: File): Boolean {
        if (!ttsReady) return false

        val chunks = splitText(text)
        Log.d(TAG, "synthesizeToFileSync: ${chunks.size} chunks, total=${text.length} chars")

        if (chunks.size == 1) {
            return synthesizeChunkSync(chunks[0], outputFile)
        }

        // Nhiều chunk → synthesize tuần tự (không song song)
        val tempFiles = mutableListOf<File>()
        try {
            for ((i, chunk) in chunks.withIndex()) {
                val tempFile = File(outputFile.parent, "tmp_chunk_${i}_${System.currentTimeMillis()}.wav")
                Log.d(TAG, "Synthesizing chunk $i/${chunks.size-1}, length=${chunk.length}")
                val success = synthesizeChunkSync(chunk, tempFile)
                if (!success) {
                    Log.e(TAG, "chunk $i failed")
                    return false
                }
                tempFiles.add(tempFile)
            }
            mergeWavFiles(tempFiles, outputFile)
            val success = outputFile.exists() && outputFile.length() > 0
            Log.d(TAG, "merge done: success=$success, size=${outputFile.length()}")
            return success
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
        }
    }
    private val synthesizeLock = java.util.concurrent.Semaphore(1)
    private fun synthesizeChunkSync(text: String, outputFile: File): Boolean {
        synthesizeLock.acquire() // block nếu có synthesis khác đang chạy
        try {
            val uttId = "chunk_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
            val latch  = java.util.concurrent.CountDownLatch(1)
            val doneFlag = java.util.concurrent.atomic.AtomicBoolean(false)

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) {
                    if (utteranceId == uttId) {
                        doneFlag.set(true)
                        latch.countDown()
                    }
                }
                override fun onError(utteranceId: String) {
                    if (utteranceId == uttId) {
                        Log.e(TAG, "synthesizeChunkSync onError: $utteranceId")
                        latch.countDown()
                    }
                }
            })

            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uttId)
            }

            val result = tts.synthesizeToFile(text, params, outputFile, uttId)
            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "synthesizeToFile() returned: $result")
                return false
            }

            val completed = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                Log.e(TAG, "synthesizeChunkSync timeout after 20s")
                return false
            }

            // Đợi thêm 100ms để đảm bảo file flush xong
            Thread.sleep(100)

            val success = doneFlag.get() && outputFile.exists() && outputFile.length() > 0
            Log.d(TAG, "chunk done: success=$success, done=${doneFlag.get()}, size=${outputFile.length()}")
            return success
        } finally {
            synthesizeLock.release()
        }
    }

    private fun splitText(text: String, maxChars: Int = 3500): List<String> {
        if (text.length <= maxChars) return listOf(text)

        val chunks = mutableListOf<String>()
        var remaining = text.trim()

        while (remaining.length > maxChars) {
            // Cắt tại dấu câu gần nhất
            var cutAt = maxChars
            val punctuation = listOf(". ", ".\n", "! ", "? ", "\n\n", "\n")
            for (p in punctuation) {
                val idx = remaining.lastIndexOf(p, maxChars)
                if (idx > maxChars / 2) {
                    cutAt = idx + p.length
                    break
                }
            }
            chunks.add(remaining.substring(0, cutAt).trim())
            remaining = remaining.substring(cutAt).trim()
        }
        if (remaining.isNotBlank()) chunks.add(remaining)
        return chunks
    }

    private fun mergeWavFiles(files: List<File>, output: File) {
        try {
            // Đọc tất cả PCM data (bỏ qua WAV header 44 bytes của mỗi file)
            val allPcm = mutableListOf<Byte>()
            var sampleRate = 44100
            var channels   = 1
            var bitDepth   = 16

            files.forEach { file ->
                val bytes = file.readBytes()
                if (bytes.size > 44) {
                    // Đọc params từ header của file đầu
                    if (allPcm.isEmpty()) {
                        sampleRate = readInt(bytes, 24)
                        channels   = readShort(bytes, 22).toInt()
                        bitDepth   = readShort(bytes, 34).toInt()
                    }
                    // Bỏ 44 bytes header, lấy PCM data
                    bytes.drop(44).forEach { allPcm.add(it) }
                }
            }

            // Ghi WAV header mới + toàn bộ PCM
            val pcmBytes  = allPcm.toByteArray()
            val totalSize = 36 + pcmBytes.size

            output.outputStream().use { out ->
                // WAV Header
                out.write("RIFF".toByteArray())
                out.write(intToBytes(totalSize))
                out.write("WAVE".toByteArray())
                out.write("fmt ".toByteArray())
                out.write(intToBytes(16))                              // subchunk size
                out.write(shortToBytes(1))                            // PCM
                out.write(shortToBytes(channels.toShort()))
                out.write(intToBytes(sampleRate))
                out.write(intToBytes(sampleRate * channels * bitDepth / 8)) // byte rate
                out.write(shortToBytes((channels * bitDepth / 8).toShort())) // block align
                out.write(shortToBytes(bitDepth.toShort()))
                out.write("data".toByteArray())
                out.write(intToBytes(pcmBytes.size))
                out.write(pcmBytes)
            }
            Log.d(TAG, "mergeWavFiles: ${files.size} files → ${output.length()} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "mergeWavFiles error: ${e.message}", e)
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset+1].toInt() and 0xFF) shl 8) or
                ((bytes[offset+2].toInt() and 0xFF) shl 16) or
                ((bytes[offset+3].toInt() and 0xFF) shl 24)

    private fun readShort(bytes: ByteArray, offset: Int): Short =
        ((bytes[offset].toInt() and 0xFF) or
                ((bytes[offset+1].toInt() and 0xFF) shl 8)).toShort()

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToBytes(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )

    fun stopSpeak() {
        if (ttsReady) tts.stop()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        pendingSpeak = null
    }

    fun pauseSpeak() = exoPlayer.pause()

    fun resumeSpeak(text: String) {
        if (exoPlayer.mediaItemCount > 0) exoPlayer.play()
        else speakText(text)
    }

    fun isTtsSpeaking(): Boolean = exoPlayer.isPlaying

    // ── Settings ───────────────────────────────────────────────────────────

    fun applySettings(speed: Float? = null, pitch: Float? = null, locale: String? = null) {
        if (speed  != null) prefs.edit().putFloat(KEY_TTS_SPEED,  speed).apply()
        if (pitch  != null) prefs.edit().putFloat(KEY_TTS_PITCH,  pitch).apply()
        if (locale != null) prefs.edit().putString(KEY_TTS_LOCALE, locale).apply()
        if (!ttsReady) return

        tts.setSpeechRate(prefs.getFloat(KEY_TTS_SPEED, 1.0f))
        tts.setPitch(prefs.getFloat(KEY_TTS_PITCH, 1.0f))

        val lc    = prefs.getString(KEY_TTS_LOCALE, "vi-VN") ?: "vi-VN"
        val parts = lc.split("-")
        val loc   = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
        val res   = tts.setLanguage(loc)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Locale $lc not supported, fallback to vi-VN")
            tts.language = Locale("vi", "VN")
        }
    }

    fun getSpeed()  = prefs.getFloat(KEY_TTS_SPEED,  1.0f).fmt()
    fun getPitch()  = prefs.getFloat(KEY_TTS_PITCH,  1.0f).fmt()
    fun getLocale() = prefs.getString(KEY_TTS_LOCALE, "vi-VN") ?: "vi-VN"

    // ── Lifecycle ──────────────────────────────────────────────────────────

    fun releasePlayer() {
        stopSpeak()
        tts.shutdown()
        exoPlayer.release()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Chia text thành danh sách câu ngắn ≤ [MAX_SENTENCE_LEN] ký tự.
     *
     * Thuật toán:
     * 1. Tách theo dấu câu kết thúc (. ! ? \n)
     * 2. Nếu câu vẫn dài hơn MAX_SENTENCE_LEN → tách tiếp theo dấu phẩy / chấm phẩy
     * 3. Nếu vẫn dài → cắt cứng theo từ gần nhất
     */
    private fun splitIntoSentences(text: String): List<String> {
        // Bước 1: tách theo dấu câu kết thúc
        val rawSentences = text
            .replace(Regex("([.!?])\\s+"), "$1\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        // Bước 2+3: chia nhỏ câu dài
        val result = mutableListOf<String>()
        for (sentence in rawSentences) {
            if (sentence.length <= MAX_SENTENCE_LEN) {
                result.add(sentence)
            } else {
                result.addAll(splitLongSentence(sentence))
            }
        }
        return result.ifEmpty { listOf(text) }
    }

    /**
     * Chia một câu dài theo dấu phẩy/chấm phẩy, rồi theo từ nếu vẫn dài.
     */
    private fun splitLongSentence(sentence: String): List<String> {
        // Thử tách theo dấu phẩy / chấm phẩy
        val parts = sentence.split(Regex("[,;،،]"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (parts.size > 1 && parts.all { it.length <= MAX_SENTENCE_LEN }) {
            return parts
        }

        // Cắt theo từ
        val words  = sentence.split(" ")
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (word in words) {
            if (current.isNotEmpty() && current.length + word.length + 1 > MAX_SENTENCE_LEN) {
                chunks.add(current.toString().trim())
                current.clear()
            }
            if (current.isNotEmpty()) current.append(" ")
            current.append(word)
        }
        if (current.isNotEmpty()) chunks.add(current.toString().trim())
        return chunks.ifEmpty { listOf(sentence) }
    }

    fun fileToUri(file: File): android.net.Uri =
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

    private fun Float.fmt(): String =
        if (this == kotlin.math.floor(this.toDouble()).toFloat()) "%.0f".format(this)
        else "%.2f".format(this).trimEnd('0')
}