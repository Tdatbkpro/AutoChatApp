package com.example.autochat.remote.dto.request

import com.google.gson.annotations.SerializedName

data class GeminiKeyRequest(
    @SerializedName("api_key") val apiKey: String)