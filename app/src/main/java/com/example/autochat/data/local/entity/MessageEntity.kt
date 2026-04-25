package com.example.autochat.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val content: String,
    val sender: String,
    val timestamp: Long,
    val extraData: String? = null,
    val isSynced: Boolean = false,
    val isOffline: Boolean = false // Đánh dấu tin nhắn từ local LLM
)