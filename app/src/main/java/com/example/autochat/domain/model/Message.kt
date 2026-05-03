package com.example.autochat.domain.model

data class Message(
    val id: String,
    val sessionId: String,
    val content: String,
    val sender: String,
    val timestamp: Long,
    val isSynced: Boolean = false,
    val extraData: Map<String, Any?>? = null,

    // ✅ Branch fields — nullable vì data cũ không có
    val branchId: String? = null,
    val parentMessageId: String? = null,
)