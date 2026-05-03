// domain/model/MediaItem.kt
package com.example.autochat.domain.model

import com.google.gson.annotations.SerializedName

data class MediaItem(
    @SerializedName("url")
    val url: String?,

    @SerializedName("type")
    val type: String?, // "image" hoặc "video"

    @SerializedName("caption")
    val caption: String?,

    @SerializedName("description")
    val description: String?
)