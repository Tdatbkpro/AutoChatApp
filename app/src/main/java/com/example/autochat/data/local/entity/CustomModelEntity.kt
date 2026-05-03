// data/local/entity/CustomModelEntity.kt
package com.example.autochat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_models")
data class CustomModelEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String,
    val sizeMB: Long,
    val downloadUrl: String,
    val filename: String,
    val filePath: String? = null,
    val isDownloaded: Boolean = false,
    val downloadedSizeMB: Long = 0,
    val isVerified: Boolean = false,  // Đã verify chưa
    val modelFormat: String = "GGUF", // GGUF, ONNX, etc.
    val requiredRAM: Long = 0,        // RAM tối thiểu (MB)
    val addedAt: Long = System.currentTimeMillis()
)