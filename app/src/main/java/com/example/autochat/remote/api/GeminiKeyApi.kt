package com.example.autochat.remote.api

import com.example.autochat.remote.dto.request.GeminiKeyRequest
import com.example.autochat.remote.dto.response.GeminiKeyStatusResponse
import retrofit2.Response
import retrofit2.http.*

interface GeminiKeyApi {


    @POST("user/gemini-key")
    suspend fun saveKey(
        @Header("Authorization") token: String,
        @Body req: GeminiKeyRequest
    ): retrofit2.Response<Unit>

    @DELETE("user/gemini-key")
    suspend fun deleteKey(
        @Header("Authorization") token: String
    ): Response<Unit>

    @GET("user/gemini-key/status")
    suspend fun getStatus(
        @Header("Authorization") token: String
    ): GeminiKeyStatusResponse
}