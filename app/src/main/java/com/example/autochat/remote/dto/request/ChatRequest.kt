package com.example.autochat.remote.dto.request

import com.google.gson.annotations.SerializedName

data class ChatRequest(
    val message: String,
    @SerializedName("session_id") val sessionId: String? = null,
    @SerializedName("is_follow_up") val isFollowUp: Boolean = false,
    @SerializedName("bot_message") val botMessage: String? = null
)
