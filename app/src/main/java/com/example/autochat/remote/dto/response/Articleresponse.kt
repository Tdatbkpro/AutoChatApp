// data/api/model/ArticleResponse.kt
package com.example.autochat.remote.dto.response

import com.example.autochat.remote.dto.response.MediaItemResponse
import com.google.gson.annotations.SerializedName

data class ArticleResponse(
    @SerializedName("id")
    val id: Int?,

    @SerializedName("title")
    val title: String?,

    @SerializedName("description")
    val description: String?,

    @SerializedName("content")
    val content: String?,

    @SerializedName("category")
    val category: String?,

    @SerializedName("url")
    val url: String?,

    @SerializedName("published_date")
    val publishedDate: String?,

    @SerializedName("media_items")
    val mediaItems: List<MediaItemResponse>?,

    @SerializedName("author")
    val author: String?,

    @SerializedName("thumbnail")
    val thumbnail: String?
)

