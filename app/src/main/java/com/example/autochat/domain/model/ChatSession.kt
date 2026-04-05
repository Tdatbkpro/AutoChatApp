package com.example.autochat.domain.model

data class ChatSession(
    val id: String,
    val userId: String,
    var title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isSynced: Boolean = false  // ✅ Đã sync lên Firestore chưa
)