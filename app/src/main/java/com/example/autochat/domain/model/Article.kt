// domain/model/Article.kt
package com.example.autochat.domain.model

data class Article(
    val id: Int? = null,
    val title: String = "",
    val description: String? = null,
    val content: String? = null,
    val category: String? = null,
    val url: String? = null,
    val publishedDate: String? = null,
    val mediaItems: List<MediaItem>? = null,
    val author: String? = null,
    val thumbnail: String? = null
)