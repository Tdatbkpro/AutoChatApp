package com.example.autochat.remote.dto.request

import com.google.gson.annotations.SerializedName

data class EditMessageRequest(
    @SerializedName("session_id") val sessionId:  String,
    @SerializedName("message_id") val messageId:  String,
    @SerializedName("new_content") val newContent: String,
    val endpoint:    String = "ask",
)