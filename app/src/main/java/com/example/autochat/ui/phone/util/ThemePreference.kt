// util/ThemePreference.kt
package com.example.autochat.ui.phone.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.example.autochat.ui.phone.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemePreference @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_NIGHT_MODE = "night_mode"
    }

    fun getThemeMode(): ThemeMode {
        val saved = prefs.getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        return ThemeMode.fromNightMode(saved)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putInt(KEY_NIGHT_MODE, mode.nightMode) }
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    /** Call once in Application.onCreate() to restore saved preference */
    fun applyOnStartup() {
        AppCompatDelegate.setDefaultNightMode(getThemeMode().nightMode)
    }
}