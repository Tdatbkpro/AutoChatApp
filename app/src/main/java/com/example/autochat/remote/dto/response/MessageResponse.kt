package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class MessageResponse(
    val id: String,
    @SerializedName("session_id") val sessionId: String,
    val content: String,
    val sender: String,
    @SerializedName("created_at") val createdAt: String,
    // extra_data từ server là JSON object tùy ý
    // Gson sẽ deserialize thành Map<String, Any> tự động khi dùng kiểu Any
    @SerializedName("extra_data") val extraData: Map<String, Any?>? = null
)