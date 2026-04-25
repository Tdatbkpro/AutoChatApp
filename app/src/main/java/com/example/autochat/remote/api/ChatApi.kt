package com.example.autochat.remote.api

import com.example.autochat.remote.dto.request.ChatRequest
import com.example.autochat.remote.dto.response.ArticleResponse
import com.example.autochat.remote.dto.response.ChatResponse
import com.example.autochat.remote.dto.response.MessageResponse
import com.example.autochat.remote.dto.response.SessionResponse
import retrofit2.Response
import retrofit2.http.*

interface ChatApi {
    @POST("chat/send")
    suspend fun sendMessage(
        @Header("Authorization") token: String,
        @Body req: ChatRequest
    ): ChatResponse

    @GET("chat/sessions")
    suspend fun getSessions(
        @Header("Authorization") token: String
    ): List<SessionResponse>

    @GET("chat/sessions/{id}/messages")
    suspend fun getMessages(
        @Header("Authorization") token: String,
        @Path("id") sessionId: String
    ): List<MessageResponse>

    @DELETE("chat/sessions/{id}")
    suspend fun deleteSession(
        @Header("Authorization") token: String,
        @Path("id") sessionId: String
    )

    @DELETE("chat/sessions/{session_id}/messages")
    suspend fun deleteMessages(
        @Header("Authorization") token: String,
        @Path("session_id") sessionId: String,
        @Query("message_ids") messageIds: List<String>  // ✅ Query params
    )

    // ChatApi.kt
    @PATCH("chat/session/{sessionId}")
    suspend fun updateSessionTitle(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: String,
        @Body request: Map<String, String>
    ): Response<Unit>
    @GET("chat/article/{articleId}")
    suspend fun getArticle(
        @Path("articleId") articleId: Int
    ): Response<ArticleResponse>

    @POST("chat/sessions/{id}/init-rag")
    suspend fun initRagSession(
        @Header("Authorization") token: String,
        @Path("id") sessionId: String
    ) : Response<Unit>
    // Trong file ChatApi.kt

    @PATCH("chat/sessions/{session_id}/pin")
    suspend fun togglePinSession(
        @Header("Authorization") token: String,
        @Path("session_id") sessionId: String
    ): Response<Map<String, Any>>

    @POST("chat/sync/offline-messages")
    suspend fun syncOfflineMessages(
        @Body messages: List<Map<String, Any>>
    ): Response<Map<String, Any>>
}