package com.example.autochat.remote.api

import com.example.autochat.remote.dto.request.ChatRequest
import com.example.autochat.remote.dto.request.EditMessageRequest
import com.example.autochat.remote.dto.request.OfflineMessageSync
import com.example.autochat.remote.dto.request.SyncOfflineRequest
import com.example.autochat.remote.dto.response.ArticleResponse
import com.example.autochat.remote.dto.response.BranchInfoResponse
import com.example.autochat.remote.dto.response.ChatResponse
import com.example.autochat.remote.dto.response.EditMessageResponse
import com.example.autochat.remote.dto.response.MessageResponse
import com.example.autochat.remote.dto.response.PivotResponse
import com.example.autochat.remote.dto.response.SessionResponse
import com.example.autochat.remote.dto.response.SyncOfflineResponse
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
        @Path("id") sessionId: String,
        @Query("limit")     limit:    Int    = 50,
        @Query("offset")    offset:   Int    = 0,
        @Query("branch_id") branchId: String? = null,
    ): List<MessageResponse>

    @DELETE("chat/sessions/{id}")
    suspend fun deleteSession(
        @Header("Authorization") token: String,
        @Path("id") sessionId: String
    )

    @GET("chat/sessions/{sessionId}/pivots")
    suspend fun getSessionPivots(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: String,
    ): List<PivotResponse>

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
        @Header("Authorization") token: String,
        @Body body: SyncOfflineRequest
    ): Response<SyncOfflineResponse>

    @POST("chat/sync/message")
    suspend fun syncSingleMessage(
        @Header("Authorization") token: String,
        @Body body: OfflineMessageSync
    ): Response<OfflineMessageSync>

    @POST("chat/sessions/{sessionId}/edit-message")
    suspend fun editMessage(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: String,
        @Body req: EditMessageRequest,
    ): EditMessageResponse

    @GET("chat/sessions/{sessionId}/branches/{branchId}/messages")
    suspend fun getBranchMessages(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: String,
        @Path("branchId") branchId: String,
    ): List<MessageResponse>

    @GET("chat/sessions/{sessionId}/messages/{messageId}/branches")
    suspend fun getBranchesAtMessage(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: String,
        @Path("messageId") messageId: String,
    ): List<BranchInfoResponse>

    @POST("chat/sessions/{sessionId}/branches/{branchId}/sync_to_rag")
    suspend fun syncBranchToRag(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: String,
        @Path("branchId") branchId: String,
    ): Response<Unit>
    @GET("chat/sessions/{sessionId}/latest-branch")
    suspend fun getLatestBranch(
        @Header("Authorization") token: String,
        @Path("sessionId") sessionId: String,
    ): Map<String, String>
}