package com.example.autochat.domain.repository

import com.example.autochat.domain.model.User
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    fun getCurrentUserFlow(): Flow<User?>
    suspend fun login(email: String, password: String): User
    suspend fun register(email: String, username: String, password: String): User
    suspend fun logout()
    suspend fun generatePin(): String        // Phone tạo PIN
    suspend fun verifyPin(pin: String): User // Xe nhập PIN
    suspend fun refreshTokenIfNeeded()
    suspend fun refreshCarTokenIfNeeded()

    suspend fun sendOtp(email: String, purpose: String)
    suspend fun verifyOtp(email: String, purpose: String, otp: String) : VerifyOtpResult
    suspend fun resetPassword(email: String, resetToken : String, newPassword: String)
    suspend fun loginWithGoogle(idToken: String): User

}

sealed class VerifyOtpResult {
    data class Success(val resetToken: String? = null) : VerifyOtpResult()
    data class Error(val message: String) : VerifyOtpResult()
}