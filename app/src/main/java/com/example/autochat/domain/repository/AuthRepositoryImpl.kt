package com.example.autochat.domain.repository

import ai.onnxruntime.BuildConfig
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.autochat.AppState
import com.example.autochat.remote.api.AuthApi
import com.example.autochat.remote.dto.request.ConfirmPinRequest
import com.example.autochat.remote.dto.request.LoginRequest
import com.example.autochat.remote.dto.request.RefreshRequest
import com.example.autochat.remote.dto.request.RegisterRequest
import com.example.autochat.domain.model.User
import com.example.autochat.remote.dto.request.GoogleAuthRequest
import com.example.autochat.remote.dto.request.ResetPasswordRequest
import com.example.autochat.remote.dto.request.SendOtpRequest
import com.example.autochat.remote.dto.request.VerifyOtpRequest
import com.example.autochat.ui.phone.adapter.com.example.autochat.token.EncryptedTokenStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val tokenStorage: EncryptedTokenStorage,
    private val dataStore: DataStore<Preferences>
) : AuthRepository {

    companion object {
        val USER_ID = stringPreferencesKey("user_id")
        val EMAIL = stringPreferencesKey("email")
        val USERNAME = stringPreferencesKey("username")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    }

    override fun getCurrentUserFlow(): Flow<User?> = dataStore.data.map {
        // FIX: Đọc từ EncryptedSharedPreferences thay vì DataStore
        val userId = tokenStorage.getUserId().ifEmpty { return@map null }
        User(
            id           = userId,
            email        = tokenStorage.getUserEmail(),   // không cần email ở đây
            username     = tokenStorage.getUsername(),
            accessToken  = tokenStorage.getAccessToken() ?: return@map null,
            refreshToken = tokenStorage.getRefreshToken() ?: return@map null
        )
    }

    override suspend fun login(email: String, password: String): User {
        val response = authApi.login(LoginRequest(email, password))
        saveAndUpdateState(response.userId, email, response.username,
            response.accessToken, response.refreshToken)
        return User(response.userId, email, response.username,
            response.accessToken, response.refreshToken)
    }

    override suspend fun register(email: String, username: String, password: String): User {
        val response = authApi.register(RegisterRequest(email, username, password))
        saveAndUpdateState(response.userId, email, response.username,
            response.accessToken, response.refreshToken)
        return User(response.userId, email, response.username,
            response.accessToken, response.refreshToken)
    }

    override suspend fun logout() {
        tokenStorage.clearPhoneTokens()           // ← xóa token mã hóa
        dataStore.edit { it.clear() }     // ← xóa các prefs khác nếu có
        AppState.logout()
    }

    override suspend fun generatePin(): String {
        val token = getAccessToken()
        val response = authApi.requestCarPin("Bearer $token")
        return response.pin
    }

    override suspend fun verifyPin(pin: String): User {
        val response = authApi.verifyCarPin(ConfirmPinRequest(pin))
        tokenStorage.saveCarTokens(response.accessToken, response.refreshToken)
        saveAndUpdateState(response.userId, response.email, response.username,
            response.accessToken, response.refreshToken)
        return User(response.userId, response.email, response.username,
            response.accessToken, response.refreshToken)
    }
    // AuthRepositoryImpl.kt
    override suspend fun refreshCarTokenIfNeeded() {
        val accessToken  = tokenStorage.getCarAccessToken()  ?: throw Exception("No car token")
        val refreshToken = tokenStorage.getCarRefreshToken() ?: throw Exception("No car refresh token")

        AppState.accessToken   = accessToken
        AppState.refreshToken  = refreshToken

        if (!isTokenExpired(accessToken)) return

        try {
            val response = authApi.refresh(RefreshRequest(refreshToken))
            tokenStorage.saveCarTokens(response.accessToken, response.refreshToken)
            AppState.accessToken = response.accessToken
        } catch (e: Exception) {
            tokenStorage.clearCarTokens()  // ← xóa token xe hết hạn
            throw Exception("Car session expired")
        }
    }
    override suspend fun refreshTokenIfNeeded() {
        val accessToken = tokenStorage.getAccessToken() ?: throw Exception("No token")
        val refreshToken = tokenStorage.getRefreshToken() ?: throw Exception("No refresh token")

        AppState.accessToken = accessToken
        AppState.refreshToken = refreshToken
        AppState.currentUserId = tokenStorage.getUserId()
        AppState.username = tokenStorage.getUsername()
        AppState.userEmail = tokenStorage.getUserEmail()

        if (!isTokenExpired(accessToken)) return

        try {
            val response = authApi.refresh(RefreshRequest(refreshToken))
            tokenStorage.saveTokens(response.accessToken, refreshToken)
            AppState.accessToken = response.accessToken
        } catch (e: Exception) {
            logout()
            throw Exception("Session expired")
        }
    }

    private fun isTokenExpired(token: String): Boolean {
        return try {
            val payload = token.split(".")[1]
            val decoded = android.util.Base64.decode(
                payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            )
            val exp = org.json.JSONObject(String(decoded)).getLong("exp")
            System.currentTimeMillis() / 1000 > exp - 60
        } catch (e: Exception) {
            true
        }
    }

    private suspend fun getAccessToken(): String {
        // FIX: Đọc từ EncryptedSharedPreferences thay vì DataStore
        return tokenStorage.getAccessToken() ?: throw Exception("Not logged in")
    }

    private suspend fun saveAndUpdateState(
        userId: String,
        email: String,
        username: String,
        accessToken: String,
        refreshToken: String
    ) {
        tokenStorage.saveTokens(accessToken, refreshToken)
        tokenStorage.saveUserId(userId)
        tokenStorage.saveUsername(username)
        tokenStorage.saveUserEmail(email)

        // Cập nhật AppState tập trung một chỗ, không lặp code
        AppState.currentUserId = userId
        AppState.username = username
        AppState.accessToken = accessToken
        AppState.refreshToken = refreshToken
        AppState.userEmail = email
        // FIX: Không log token, chỉ log userId ở debug
        if (BuildConfig.DEBUG) android.util.Log.d("AUTH", "Auth success: $userId")
    }

    override suspend fun sendOtp(email: String, purpose: String) {
        authApi.sendOtp(SendOtpRequest(email, purpose))
    }

    override suspend fun verifyOtp(email: String, purpose: String, otp: String): VerifyOtpResult {
        return try {
            val response = authApi.verifyOtp(VerifyOtpRequest(email, purpose, otp))
            Log.d("VERIFY_OTP", "Response: $response")
            if (response.valid) {
                VerifyOtpResult.Success(resetToken = response.resetToken)
            } else {
                VerifyOtpResult.Error(response.message ?: "Lỗi")
            }
        } catch (e: Exception) {
            VerifyOtpResult.Error(e.message ?: "Lỗi xác thực OTP")
        }
    }

    override suspend fun resetPassword(email: String, resetToken: String, newPassword: String) {
        authApi.resetPassword(ResetPasswordRequest(email, resetToken, newPassword))
    }

    override suspend fun loginWithGoogle(idToken: String): User {
        val response = authApi.googleAuth(GoogleAuthRequest(idToken))
        saveAndUpdateState(
            response.userId, response.email, response.username,
            response.accessToken, response.refreshToken
        )
        return User(response.userId, response.email, response.username,
            response.accessToken, response.refreshToken)
    }
}