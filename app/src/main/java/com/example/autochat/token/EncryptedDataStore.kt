package com.example.autochat.ui.phone.adapter.com.example.autochat.token

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

// EncryptedTokenStorage.kt
@Singleton
class EncryptedTokenStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Khóa mã hóa được lưu trong Android Keystore, không nằm trên disk
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_token_prefs",         // tên file
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .apply()
    }

    fun getAccessToken(): String? = prefs.getString("access_token", null)
    fun getRefreshToken(): String? = prefs.getString("refresh_token", null)
    fun saveCarTokens(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString("car_access_token", accessToken)
            .putString("car_refresh_token", refreshToken)
            .apply()
    }

    fun getCarAccessToken(): String? = prefs.getString("car_access_token", null)
    fun getCarRefreshToken(): String? = prefs.getString("car_refresh_token", null)
    fun saveUserId(userId: String) = prefs.edit { putString("user_id", userId) }

    fun saveUserEmail(userEmail : String) = prefs.edit { putString("user_email", userEmail) }
    fun saveUsername(username: String) = prefs.edit { putString("username", username) }
    fun getUserId(): String = prefs.getString("user_id", "local") ?: "local"
    fun getUsername(): String = prefs.getString("username", "") ?: ""
    fun getUserEmail(): String = prefs.getString("user_email", "") ?: ""
    fun clearPhoneTokens() {
        prefs.edit {
            remove("access_token")
                .remove("refresh_token")
                .remove("user_id")
                .remove("username")
        }
        // car_access_token và car_refresh_token vẫn còn
    }
    // EncryptedTokenStorage.kt — thêm clearCarTokens
    fun clearCarTokens() {
        prefs.edit {
            remove("car_access_token")
                .remove("car_refresh_token")
        }
    }
    // Xóa tất cả (kể cả token xe) — dùng khi uninstall hoặc reset
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}