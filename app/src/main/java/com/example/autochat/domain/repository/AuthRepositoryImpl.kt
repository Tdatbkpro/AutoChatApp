package com.example.autochat.domain.repository

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authApi: AuthApi,
    private val dataStore: DataStore<Preferences>
) : AuthRepository {

    companion object {
        val USER_ID = stringPreferencesKey("user_id")
        val EMAIL = stringPreferencesKey("email")
        val USERNAME = stringPreferencesKey("username")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    }

    override fun getCurrentUserFlow(): Flow<User?> = dataStore.data.map { prefs ->
        val userId = prefs[USER_ID] ?: return@map null
        User(
            id = userId,
            email = prefs[EMAIL] ?: "",
            username = prefs[USERNAME] ?: "",
            accessToken = prefs[ACCESS_TOKEN] ?: "",
            refreshToken = prefs[REFRESH_TOKEN] ?: ""
        )
    }

    override suspend fun login(email: String, password: String): User {
        val response = authApi.login(LoginRequest(email, password))
        saveUserToDataStore(
            userId = response.userId,
            email = email,
            username = response.username,
            accessToken = response.accessToken,
            refreshToken = response.refreshToken
        )
        // ✅ Cập nhật AppState
        AppState.currentUserId = response.userId
        AppState.username = response.username
        AppState.accessToken = response.accessToken
        AppState.refreshToken = response.refreshToken

        android.util.Log.e("AUTH", "Login success - userId: ${AppState.currentUserId}")

        return User(
            id = response.userId,
            email = email,
            username = response.username,
            accessToken = response.accessToken,
            refreshToken = response.refreshToken
        )
    }

    override suspend fun register(email: String, username: String, password: String): User {
        val response = authApi.register(RegisterRequest(email, username, password))
        saveUserToDataStore(
            userId = response.userId,
            email = email,
            username = response.username,
            accessToken = response.accessToken,
            refreshToken = response.refreshToken
        )
        // ✅ Cập nhật AppState
        AppState.currentUserId = response.userId
        AppState.username = response.username
        AppState.accessToken = response.accessToken
        AppState.refreshToken = response.refreshToken

        android.util.Log.e("AUTH", "Register success - userId: ${AppState.currentUserId}")

        return User(
            id = response.userId,
            email = email,
            username = response.username,
            accessToken = response.accessToken,
            refreshToken = response.refreshToken
        )
    }

    override suspend fun logout() {
        dataStore.edit { prefs ->
            prefs.remove(USER_ID)
            prefs.remove(EMAIL)
            prefs.remove(USERNAME)
            prefs.remove(ACCESS_TOKEN)
            prefs.remove(REFRESH_TOKEN)
        }
        // ✅ Clear AppState
        AppState.logout()
        android.util.Log.e("AUTH", "Logout - cleared AppState")
    }

    override suspend fun generatePin(): String {
        val token = getAccessToken()
        val response = authApi.requestCarPin("Bearer $token")
        return response.pin
    }

    override suspend fun verifyPin(pin: String): User {
        val response = authApi.verifyCarPin(ConfirmPinRequest(pin))
        saveUserToDataStore(
            userId = response.userId,
            email = response.email,
            username = response.username,
            accessToken = response.accessToken,
            refreshToken = response.refreshToken
        )
        // ✅ Cập nhật AppState
        AppState.currentUserId = response.userId
        AppState.username = response.username
        AppState.accessToken = response.accessToken
        AppState.refreshToken = response.refreshToken

        android.util.Log.e("AUTH", "Verify PIN success - userId: ${AppState.currentUserId}")

        return User(
            id = response.userId,
            email = response.email,
            username = response.username,
            accessToken = response.accessToken,
            refreshToken = response.refreshToken
        )
    }

    override suspend fun refreshTokenIfNeeded() {
        val prefs = dataStore.data.first()
        val refreshToken = prefs[REFRESH_TOKEN] ?: return
        try {
            val response = authApi.refresh(RefreshRequest(refreshToken))
            dataStore.edit { prefs ->
                prefs[ACCESS_TOKEN] = response.accessToken
            }
            // ✅ Cập nhật AppState
            AppState.accessToken = response.accessToken
        } catch (e: Exception) {
            logout()
        }
    }

    private suspend fun getAccessToken(): String {
        val prefs = dataStore.data.first()
        return prefs[ACCESS_TOKEN] ?: throw Exception("Not logged in")
    }

    private suspend fun saveUserToDataStore(
        userId: String,
        email: String,
        username: String,
        accessToken: String,
        refreshToken: String
    ) {
        dataStore.edit { prefs ->
            prefs[USER_ID] = userId
            prefs[EMAIL] = email
            prefs[USERNAME] = username
            prefs[ACCESS_TOKEN] = accessToken
            prefs[REFRESH_TOKEN] = refreshToken
        }
    }
}