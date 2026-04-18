package com.example.autochat.domain.model

data class Article(
    val id: Int,
    val title: String,
    val content: String?,
    val description: String?,
    val publishedDate: String?,
    val category: String?,
    val url: String?,
    val author : String?
)