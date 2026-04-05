package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class MessageResponse(
    val id: String,
    @SerializedName("session_id") val sessionId: String,
    val content: String,
    val sender: String,
    @SerializedName("created_at") val createdAt: String  // ✅ Sửa lại thành createdAt
)