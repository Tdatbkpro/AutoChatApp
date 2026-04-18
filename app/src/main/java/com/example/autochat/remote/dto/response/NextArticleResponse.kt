package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

/**
 * Response từ GET /chat/next-article
 */
data class NextArticleResponse(
    val found: Boolean,
    val article: ArticleResponse?
)