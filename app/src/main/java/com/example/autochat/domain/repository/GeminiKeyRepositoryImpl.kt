package com.example.autochat.domain.repository

import com.example.autochat.AppState
import com.example.autochat.remote.api.GeminiKeyApi
import com.example.autochat.remote.dto.request.GeminiKeyRequest
import com.example.autochat.remote.dto.response.GeminiKeyStatusResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiKeyRepositoryImpl @Inject constructor(
    private val api: GeminiKeyApi
) : GeminiKeyRepository {

    private fun authHeader(): String = "Bearer ${AppState.accessToken}"

    override suspend fun getStatus(): Result<GeminiKeyStatusResponse> = runCatching {
        api.getStatus(authHeader())
    }

    override suspend fun saveKey(apiKey: String): Result<Unit> = runCatching {
        api.saveKey(authHeader(), GeminiKeyRequest(apiKey = apiKey))
    }

    override suspend fun deleteKey(): Result<Unit> = runCatching {
        api.deleteKey(authHeader())
    }
}