package com.example.autochat.models

enum class MessageType {
    USER,
    BOT
}

data class Message(
    val content: String,
    val type: MessageType,
    val timestamp: Long
)