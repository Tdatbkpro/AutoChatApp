// model/ThemeMode.kt
package com.example.autochat.ui.phone.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import com.example.autochat.R

enum class ThemeMode(
    val nightMode: Int,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,   // icon dùng trên main row và option row
) {
    LIGHT(
        nightMode = AppCompatDelegate.MODE_NIGHT_NO,
        labelRes  = R.string.theme_light,
        iconRes   = R.drawable.ic_sun,
    ),
    DARK(
        nightMode = AppCompatDelegate.MODE_NIGHT_YES,
        labelRes  = R.string.theme_dark,
        iconRes   = R.drawable.ic_moon,
    ),
    SYSTEM(
        nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        labelRes  = R.string.theme_system,
        iconRes   = R.drawable.ic_system,
    );

    companion object {
        fun fromNightMode(nightMode: Int) =
            entries.find { it.nightMode == nightMode } ?: SYSTEM
    }
}