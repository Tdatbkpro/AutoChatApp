package com.example.autochat.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "read_history",
    indices = [Index(value = ["articleId"], unique = true)]
)
data class ReadHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0,
    val articleId: Int,
    val title: String,
    val category: String?,
    val readAt: Long = System.currentTimeMillis()
)