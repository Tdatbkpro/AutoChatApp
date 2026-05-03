package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class MessageResponse(
    @SerializedName("id")                 val id: String,
    @SerializedName("session_id")         val sessionId: String,
    @SerializedName("content")            val content: String,
    @SerializedName("sender")             val sender: String,
    @SerializedName("created_at")         val createdAt: String,
    @SerializedName("extra_data")         val extraData: Map<String, Any?>? = null,

    // ✅ Thêm 2 field mới — nullable, server cũ không trả thì = null
    @SerializedName("branch_id")          val branchId: String? = null,
    @SerializedName("parent_message_id")  val parentMessageId: String? = null,
)