package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class ChatResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("session_title") val sessionTitle: String,
    @SerializedName("user_message") val userMessage: MessageResponse,
    @SerializedName("bot_message") val botMessage: MessageResponse,
    @SerializedName("bot_detail")    val botDetail: Map<String, Any?>? = null
)