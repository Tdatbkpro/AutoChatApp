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

}