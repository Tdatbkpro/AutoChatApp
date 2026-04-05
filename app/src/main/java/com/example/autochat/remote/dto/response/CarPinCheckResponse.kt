package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class CarPinCheckResponse(
    val confirmed: Boolean,
    @SerializedName("access_token") val accessToken: String? = null,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    @SerializedName("user_id") val userId: String? = null,
    val username: String? = null
)