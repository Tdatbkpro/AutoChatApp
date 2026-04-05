package com.example.autochat.domain.model

data class Message(
    val id: String,
    val sessionId: String,
    val content: String,
    val sender: String,  // "user" hoặc "bot"
    val timestamp: Long,
    val isSynced: Boolean = false
)