package com.example.autochat.ui.phone

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.autochat.databinding.FragmentChatBinding
import java.io.File

class AudioPlayerController(private val binding: FragmentChatBinding) {

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var currentFilePath: String? = null

    private val updateProgress = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            if (!mp.isPlaying) return
            val current = mp.currentPosition
            val total   = mp.duration
            if (total > 0) {
                binding.waveformView.setProgress(current.toFloat() / total)
                binding.tvPlayerCurrent.text = formatTime(current)
            }
            handler.postDelayed(this, 80)
        }
    }

    fun playAudio(filePath: String, label: String = "VieNeu TTS") {
        stop()
        showBar(label)
        currentFilePath = filePath
        binding.waveformView.apply {
            generateBars(count = 80)
            setProgress(0.3f, animate = false)
            isPlaying = true
        }

        mediaPlayer = MediaPlayer().apply {
            setDataSource(filePath)
            prepare()
            start()
            binding.tvPlayerDuration.text = formatTime(duration)
            binding.btnPlayerPlay.setImageResource(android.R.drawable.ic_media_pause)
            setOnCompletionListener { onCompleted() }
        }
        isPlaying = true
        handler.post(updateProgress)

        // Seek
        binding.waveformView.onSeek = { ratio ->
            mediaPlayer?.let { mp ->
                mp.seekTo((ratio * mp.duration).toInt())
                binding.waveformView.setProgress(ratio)
                binding.tvPlayerCurrent.text = formatTime((ratio * mp.duration).toInt())
            }
        }

        // Play/Pause button
        binding.btnPlayerPlay.setOnClickListener {
            if (isPlaying) pause() else resume()
        }

        // Close button
        binding.btnPlayerClose.setOnClickListener {
            deleteCurrentFile()  // ← xóa file khi ấn X
            stop()
            hideBar()
        }
    }
    private fun deleteCurrentFile() {
        currentFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                file.delete()
                Log.d("AudioPlayer", "Deleted: $path")
            }
            currentFilePath = null
        }
    }

    private fun pause() {
        mediaPlayer?.pause()
        isPlaying = false
        binding.btnPlayerPlay.setImageResource(android.R.drawable.ic_media_play)
        handler.removeCallbacks(updateProgress)
    }

    private fun resume() {
        mediaPlayer?.start()
        isPlaying = true
        binding.btnPlayerPlay.setImageResource(android.R.drawable.ic_media_pause)
        handler.post(updateProgress)
    }

    private fun onCompleted() {
        isPlaying = false
        binding.btnPlayerPlay.setImageResource(android.R.drawable.ic_media_play)
        binding.waveformView.setProgress(1f)
        handler.removeCallbacks(updateProgress)
    }

    fun stop() {
        handler.removeCallbacks(updateProgress)
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
    }

    private fun showBar(label: String) {
        binding.audioPlayerBar.visibility = android.view.View.VISIBLE
        binding.tvPlayerLabel.text = label
        binding.tvPlayerCurrent.text = "00:00"
        binding.tvPlayerDuration.text = "00:00"
    }

    private fun hideBar() {
        stop()
        binding.audioPlayerBar.visibility = android.view.View.GONE
    }

    fun destroy() { stop() }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }
}