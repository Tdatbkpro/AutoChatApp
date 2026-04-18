package com.example.autochat.domain.repository

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
        object Connected : RealtimeEvent()
        object Disconnected : RealtimeEvent()
        data class Joined(val sessionId: String) : RealtimeEvent()
        data class Error(val error: String) : RealtimeEvent()
    }

    val realtimeEvents: Flow<RealtimeEvent>

    fun getSessionsFlow(): Flow<List<ChatSession>>
    fun getMessagesFlow(sessionId: String): Flow<List<Message>>

    suspend fun sendMessage(sessionId: String?, content: String, isFollowUp : Boolean = false, botMessage: String?): SendMessageResult
    suspend fun deleteSession(sessionId: String)
    suspend fun deleteMessages(sessionId: String)
    suspend fun deleteMessagePair(sessionId: String, userMessageId: String, botMessageId: String?)
    suspend fun updateSessionTitle(sessionId: String, newTitle: String)
    suspend fun initRagSession(sessionId : String)
    suspend fun getArticleById(articleId : Int) : Article?
    suspend fun getQuickReplies(location: String?): List<QuickReplyItem>
    suspend fun getNextArticle(
        currentId: Int,
        category: String?,
        seenIds: List<Int> = emptyList()
    ): Article?
    suspend fun getStructuredData(query: String) : StructuredDataResponse?
    suspend fun getMessages(sessionId: String) : List<Message>
    data class SendMessageResult(
        val userMessage : Message,
        val sessionId: String,
        val sessionTitle: String,
        val botMessage: Message
    )

}