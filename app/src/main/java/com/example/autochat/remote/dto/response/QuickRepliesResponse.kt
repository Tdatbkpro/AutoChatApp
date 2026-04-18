package com.example.autochat.remote.dto.response

import com.google.gson.annotations.SerializedName

data class QuickRepliesResponse(
    val items: List<QuickReplyItem>,
    val cached: Boolean = false
)

/**
 * Một ô trong GridTemplate.
 *
 * Phân loại theo [id] và [category]:
 *  - id == null + category in {gold, fuel, weather} → getStructuredData
 *  - id != null                                     → getArticleById(id) → NewsDetailScreen
 *  - id == null + category khác                     → getStructuredData (fallback text)
 *
 * [defaultQuery] là câu query dự phòng khi gọi structured data.
 */
data class QuickReplyItem(
    val label: String,
    val id: Int? = null,
    val category: String = "",
    val icon: String = "",
    @SerializedName("default_query") val defaultQuery: String? = null
)