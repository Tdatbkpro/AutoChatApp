package com.example.autochat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [Index("sessionId")]  // ✅ Bỏ foreign key
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val content: String,
    val sender: String,
    val timestamp: Long,
    val extraData: String? = null,
    val isSynced: Boolean = false,
    val isOffline: Boolean = false
)