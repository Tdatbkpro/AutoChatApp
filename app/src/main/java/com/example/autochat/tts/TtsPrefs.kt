package com.example.autochat.tts

import android.content.Context
import androidx.core.content.edit

data class TtsVoice(
    val id: String,
    val name: String
)

enum class TtsEngine { GOOGLE, VIENEU, EDGE, SYSTEM }
enum class VieNeuMode { PRESET, CLONE }

data class TtsSettings(
    val engine: TtsEngine = TtsEngine.GOOGLE,
    val vieNeuMode: VieNeuMode = VieNeuMode.PRESET,
    val presetVoiceId: String? = null,
    val presetVoiceName: String? = null,
    val refAudioPath: String? = null,
    val refText: String? = null,
    val voiceName: String? = null,   // ← tên giọng clone
    val pitch: Int = 0,              // ← -10..10
    val speed: Float = 1.0f,         // ← 0.5..2.0
    val volume: Int = 100,           // ← 0..100
)

object TtsPrefs {
    fun save(ctx: Context, s: TtsSettings) {
        ctx.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE).edit {
            putString("engine", s.engine.name)
            putString("vieneu_mode", s.vieNeuMode.name)
            putString("preset_voice_id", s.presetVoiceId)
            putString("preset_voice_name", s.presetVoiceName)
            putString("ref_audio_path", s.refAudioPath)
            putString("ref_text", s.refText)
            putString("voice_name", s.voiceName)
            putInt("pitch", s.pitch)
            putFloat("speed", s.speed)
            putInt("volume", s.volume)
        }
    }

    fun load(ctx: Context): TtsSettings {
        val p = ctx.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
        return TtsSettings(
            engine          = TtsEngine.valueOf(p.getString("engine", "GOOGLE")!!),
            vieNeuMode      = VieNeuMode.valueOf(p.getString("vieneu_mode", "PRESET")!!),
            presetVoiceId   = p.getString("preset_voice_id", null),
            presetVoiceName = p.getString("preset_voice_name", null),
            refAudioPath    = p.getString("ref_audio_path", null),
            refText         = p.getString("ref_text", null),
            voiceName       = p.getString("voice_name", null),
            pitch           = p.getInt("pitch", 0),
            speed           = p.getFloat("speed", 1.0f),
            volume          = p.getInt("volume", 100),
        )
    }
}