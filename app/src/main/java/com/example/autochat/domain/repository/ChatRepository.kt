package com.example.autochat.domain.repository

import com.example.autochat.data.local.entity.MessageEntity
import com.example.autochat.domain.model.Article
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.domain.model.Message
import com.example.autochat.remote.dto.response.QuickReplyItem
import com.example.autochat.remote.dto.response.StructuredDataResponse
import kotlinx.coroutines.flow.Flow
import retrofit2.http.Query

interface ChatRepository {

    sealed class RealtimeEvent {
        data class NewMessage(val message: Message) : RealtimeEvent()
        data class SessionDeleted(val sessionId: String) : RealtimeEvent()
        data class MessagesDeleted(val sessionId: String, val messageIds: List<String>) : RealtimeEvent()
        data class Typing(val userId: String, val isTyping: Boolean) : RealtimeEvent()
        data class BotProcessing(var  sessionId: String) : RealtimeEvent()
        object Connected : RealtimeEvent()
        object Disconnected : RealtimeEvent()
        data class Joined(val sessionId: String) : RealtimeEvent()

        data class Error(val error: String) : RealtimeEvent()
        data class BranchCreated(
            val sessionId:      String,
            val branchId:       String,
            val pivotMessageId: String,
            val branchInfo:     BranchInfoData,
        ) : RealtimeEvent()
    }
    data class EditMessageResult(
        val newBranchId:   String,
        val userMessageId: String,
        val botMessageId:  String,
        val userMessage:   Message,
        val botMessage:    Message,
        val branchInfo:    BranchInfoData,
    )

    data class BranchInfoData(
        val branchId:  String,
        val index:     Int,
        val total:     Int,
        val createdAt: Long,
    )
    val realtimeEvents: Flow<RealtimeEvent>
    sealed class StreamChunk {
        data class Token(val text: String) : StreamChunk()
        data class Done(
            val fullResponse: String,
            val botMessage: Message? = null,   // ← ở đây
        ) : StreamChunk()
        data class Meta(
            val sessionId: String,
            val sessionTitle: String,
        ) : StreamChunk()
        data class Error(val message: String) : StreamChunk()
    }

    // Sửa signature — thêm endpoint
    fun streamMessage(
        sessionId: String?,
        content: String,
        endpoint: String = "ask",
        branchId: String? = null,
    ): Flow<StreamChunk>
    fun getSessionsFlow(): Flow<List<ChatSession>>
    fun getMessagesFlow(
        sessionId: String,
        branchId: String? = null,   // ✅ Thêm
    ): Flow<List<Message>>

    suspend fun sendMessage(
        sessionId: String?,
        content: String,
        endpoint: String = "news",
        botMessage: String? = null
    ): SendMessageResult

    suspend fun deleteSession(sessionId: String)
    suspend fun deleteMessages(sessionId: String)
    suspend fun deleteMessagePair(sessionId: String, userMessageId: String, botMessageId: String?)
    suspend fun updateSessionTitle(sessionId: String, newTitle: String)
    suspend fun initRagSession(sessionId: String)
    suspend fun getArticleById(articleId: Int): Article?
    suspend fun getQuickReplies(location: String?): List<QuickReplyItem>
    suspend fun getNextArticle(currentId: Int, category: String?, seenIds: List<Int> = emptyList()): Article?
    suspend fun getStructuredData(query: String): StructuredDataResponse?
    suspend fun getMessages(sessionId: String): List<Message>
    // Thêm vào interface ChatRepository
    fun getOfflineMessagesFlow(sessionId: String): Flow<List<MessageEntity>>
    suspend fun syncOfflineMessages()
    suspend fun sendOfflineMessage(
        sessionId: String?,
        content: String,
        onToken: (String) -> Unit
    ): Result<SendMessageResult>
    suspend fun togglePinSession(sessionId: String)
    suspend fun saveBotMessage(sessionId: String, content: String, messageId: String)
    suspend fun editMessage(
        sessionId:  String,
        messageId:  String,
        newContent: String,
        endpoint:   String,
    ): EditMessageResult
    suspend fun syncBranchToRag(sessionId: String, branchId: String)
    /** Lấy messages đầy đủ của 1 nhánh (context trước + messages nhánh) */
    suspend fun getBranchMessages(
        sessionId: String,
        branchId:  String,
    ): List<Message>
    suspend fun getLatestBranchId(sessionId: String): String
    /** Lấy danh sách nhánh tại 1 pivot message (cho navigator) */
    suspend fun getBranchesAtMessage(
        sessionId:  String,
        messageId:  String,
    ): List<BranchInfoData>
    data class SendMessageResult(
        val userMessage: Message,
        val sessionId: String,
        val sessionTitle: String,
        val botMessage: Message
    )
}