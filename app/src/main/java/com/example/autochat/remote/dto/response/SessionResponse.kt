package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class  SessionResponse(
    val id: String,
    val title: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String,
    @SerializedName("message_count") val messageCount: Int = 0,  // ✅ Thêm message_count
    val endpoint: String = "news",
    @SerializedName("is_pinned") val isPinned : Boolean = false
)