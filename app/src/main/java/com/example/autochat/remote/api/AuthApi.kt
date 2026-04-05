package com.example.autochat.remote.api

import com.example.autochat.remote.dto.request.ConfirmPinRequest
import com.example.autochat.remote.dto.request.LoginRequest
import com.example.autochat.remote.dto.request.RefreshRequest
import com.example.autochat.remote.dto.request.RegisterRequest
import com.example.autochat.remote.dto.response.PinResponse
import com.example.autochat.remote.dto.response.TokenResponse
import retrofit2.http.*

interface AuthApi {
    @POST("auth/register")
    suspend fun register(@Body req: RegisterRequest): TokenResponse

    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): TokenResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body req: RefreshRequest): TokenResponse

    // ✅ Phone gọi để tạo PIN (cần JWT)
    @POST("auth/car/request-pin")
    suspend fun requestCarPin(
        @Header("Authorization") token: String
    ): PinResponse

    // ✅ Xe gọi để xác thực PIN → nhận JWT
    @POST("auth/car/verify-pin")
    suspend fun verifyCarPin(@Body req: ConfirmPinRequest): TokenResponse
}