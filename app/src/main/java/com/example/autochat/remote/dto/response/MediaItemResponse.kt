package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class MediaItemResponse(
    @SerializedName("url")
    val url: String?,

    @SerializedName("type")
    val type: String?,

    @SerializedName("caption")
    val caption: String?,

    @SerializedName("description")
    val description: String?
)