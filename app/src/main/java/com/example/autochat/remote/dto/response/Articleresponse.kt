package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class ArticleResponse(
    val id: Int,
    val title: String?,
    val content: String?,
    val description: String?,
    @SerializedName("published_date") val publishedDate: String?,
    val author : String?,
    val category: String?,
    val url: String?,
    val thumbnail : String?
)