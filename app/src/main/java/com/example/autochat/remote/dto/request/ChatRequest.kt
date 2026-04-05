package com.example.autochat.remote.dto.request

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    val message: String,
    @SerializedName("session_id") val sessionId: String? = null
)
