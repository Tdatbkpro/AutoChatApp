package com.example.autochat.remote.api

import com.example.autochat.remote.dto.response.ArticleResponse
import com.example.autochat.remote.dto.response.NextArticleResponse
import com.example.autochat.remote.dto.response.QuickRepliesResponse
import com.example.autochat.remote.dto.response.StructuredDataResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface RagApi {

    /** Chi tiết bài báo theo ID. */
    @GET("chat/article/{articleId}")
    suspend fun getArticle(
        @Path("articleId") articleId: Int
    ): Response<ArticleResponse>

    /**
     * Structured data (giá vàng, xăng, thời tiết).
     * GET /chat/structured-data/{query}
     */
    @GET("chat/structured-data/{query}")
    suspend fun getStructuredData(
        @Path("query") query: String
    ): Response<StructuredDataResponse>

    /**
     * Danh sách ô grid. Cache 5 phút phía server.
     * [location] dùng cho thời tiết (vd: "Hà Nội").
     */
    @GET("quick-replies")
    suspend fun getQuickReplies(
        @Query("location") location: String? = null
    ): Response<QuickRepliesResponse>

    @GET("chat/next-article")
    suspend fun getNextArticle(
        @Query("current_id") currentId: Int,
        @Query("category") category: String? = null,
        @Query("exclude_ids") excludeIds: String? = null
    ): Response<NextArticleResponse>
}