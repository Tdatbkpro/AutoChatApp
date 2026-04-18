package com.example.autochat.domain.model

data class Message(
    val id: String,
    val sessionId: String,
    val content: String,
    val sender: String,      // "user" hoặc "bot"
    val timestamp: Long,
    val isSynced: Boolean = false,
    // extra_data từ server: { type: "news_list", content: "...", news_items: [...] }
    // Được lưu dạng Map<String, Any?> sau khi deserialize từ JSON
    val extraData: Map<String, Any?>? = null,
)