package com.example.autochat.domain.repository

import com.example.autochat.remote.dto.response.GeminiKeyStatusResponse

interface GeminiKeyRepository {
    suspend fun getStatus(): Result<GeminiKeyStatusResponse>
    suspend fun saveKey(apiKey: String): Result<Unit>
    suspend fun deleteKey(): Result<Unit>
}