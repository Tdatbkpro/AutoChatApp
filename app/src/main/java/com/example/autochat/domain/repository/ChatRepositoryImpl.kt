package com.example.autochat.domain.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.autochat.AppState
import com.example.autochat.remote.api.ChatApi
import com.example.autochat.remote.dto.request.ChatRequest
import com.example.autochat.remote.dto.response.MessageResponse
import com.example.autochat.remote.dto.response.SessionResponse
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.domain.model.Message
import com.example.autochat.websocket.WebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

fun SessionResponse.toDomain(): ChatSession {
    return ChatSession(
        id = id,
        userId = "", // User ID sẽ được set từ context
        title = title,
        createdAt = parseTimestamp(createdAt),
        updatedAt = parseTimestamp(updatedAt)
    )
}

fun MessageResponse.toDomain(): Message {
    return Message(
        id = id,
        sessionId = sessionId,
        content = content,
        sender = sender,
        timestamp = parseTimestamp(createdAt)
    )
}

private fun parseTimestamp(timestampStr: String): Long {
    return try {
        // Xử lý timestamp format: "2024-01-01T12:00:00" hoặc "2024-01-01T12:00:00.000Z"
        val cleanStr = timestampStr.split(".")[0].split("Z")[0]
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        format.parse(cleanStr)?.time ?: System.currentTimeMillis()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatApi: ChatApi,
    private val dataStore: DataStore<Preferences>,
    private val webSocketManager: WebSocketManager
) : ChatRepository {

    companion object {
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
    }

    private val _realtimeEvents = MutableSharedFlow<ChatRepository.RealtimeEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val realtimeEvents: Flow<ChatRepository.RealtimeEvent> = _realtimeEvents.asSharedFlow()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    override fun getSessionsFlow(): Flow<List<ChatSession>> = _sessions.asStateFlow()

    private val messagesCache = mutableMapOf<String, MutableList<Message>>()
    private val messagesFlows = mutableMapOf<String, MutableSharedFlow<List<Message>>>()

    private var refreshJob: Job? = null

    init {
        // Start session refresh job
        refreshJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                refreshSessions()
                delay(5000)
            }
        }

        // Setup WebSocket listeners
        webSocketManager.addListener(object : WebSocketManager.WebSocketListener {
            override fun onNewMessage(message: Message) {
                CoroutineScope(Dispatchers.Main).launch {
                    Log.d("ChatRepository", "onNewMessage: sender=${message.sender}, timestamp=${message.timestamp}")

                    val sessionMessages = messagesCache.getOrPut(message.sessionId) { mutableListOf() }

                    // ✅ KIỂM TRA TRÙNG LẶP
                    if (sessionMessages.none { it.id == message.id }) {
                        sessionMessages.add(message)
                        // ✅ SORT NGAY LẬP TỨC
                        sessionMessages.sortBy { it.timestamp }

                        Log.d("ChatRepository", "After add: ${sessionMessages.map { "${it.sender}:${it.timestamp}" }}")

                        // ✅ EMIT SORTED
                        messagesFlows[message.sessionId]?.emit(sessionMessages.toList())
                        _realtimeEvents.emit(ChatRepository.RealtimeEvent.NewMessage(message))
                    } else {
                        Log.d("ChatRepository", "Message already exists: ${message.id}")
                    }
                }
            }

            override fun onSessionDeleted(sessionId: String) {
                CoroutineScope(Dispatchers.Main).launch {
                    messagesCache.remove(sessionId)
                    messagesFlows.remove(sessionId)
                    _realtimeEvents.emit(ChatRepository.RealtimeEvent.SessionDeleted(sessionId))
                    refreshSessions()
                }
            }

            override fun onMessagesDeleted(sessionId: String, messageIds: List<String>) {
                CoroutineScope(Dispatchers.Main).launch {
                    val sessionMessages = messagesCache[sessionId]
                    if (sessionMessages != null) {
                        sessionMessages.removeAll { it.id in messageIds }
                        messagesFlows[sessionId]?.emit(sessionMessages.toList())
                    }
                    _realtimeEvents.emit(ChatRepository.RealtimeEvent.MessagesDeleted(sessionId, messageIds))
                }
            }

            override fun onTyping(userId: String, isTyping: Boolean) {
                CoroutineScope(Dispatchers.Main).launch {
                    _realtimeEvents.emit(ChatRepository.RealtimeEvent.Typing(userId, isTyping))
                }
            }

            override fun onConnected() {
                CoroutineScope(Dispatchers.Main).launch {
                    _realtimeEvents.emit(ChatRepository.RealtimeEvent.Connected)
                }
            }

            override fun onDisconnected() {
                CoroutineScope(Dispatchers.Main).launch {
                    _realtimeEvents.emit(ChatRepository.RealtimeEvent.Disconnected)
                }
            }

            override fun onPong() {
                // Handle pong if needed
            }

            override fun onJoined(sessionId: String) {
                CoroutineScope(Dispatchers.Main).launch {
                    _realtimeEvents.emit(ChatRepository.RealtimeEvent.Joined(sessionId))
                }
            }

            override fun onError(error: String) {
                CoroutineScope(Dispatchers.Main).launch {
                    _realtimeEvents.emit(ChatRepository.RealtimeEvent.Error(error))
                }
            }
        })
    }

    private suspend fun getAccessToken(): String {
        if (!AppState.accessToken.isNullOrEmpty()) {
            return AppState.accessToken!!
        }
        val prefs = dataStore.data.first()
        return prefs[ACCESS_TOKEN] ?: throw Exception("Not logged in")
    }

    private suspend fun refreshSessions() {
        try {
            val token = getAccessToken()
            val sessions = chatApi.getSessions("Bearer $token")
            _sessions.value = sessions.map { it.toDomain() }
        } catch (e: Exception) {
            // Silent fail
        }
    }

    private suspend fun emitSortedMessages(sessionId: String) {
        val sessionMessages = messagesCache[sessionId] ?: return
        val sorted = sessionMessages.sortedBy { it.timestamp }
        messagesFlows[sessionId]?.emit(sorted)
    }

    override fun getMessagesFlow(sessionId: String): Flow<List<Message>> = flow {
        AppState.currentSessionId = sessionId

        webSocketManager.joinSession(sessionId)

        val flow = messagesFlows.getOrPut(sessionId) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 64)
        }

        try {
            val token = getAccessToken()
            val messages = chatApi.getMessages("Bearer $token", sessionId)
            val messageList = messages.map { it.toDomain() }.toMutableList()

            // ✅ SORT THEO THỜI GIAN
            messageList.sortBy { it.timestamp }
            messagesCache[sessionId] = messageList

            // ✅ EMIT SORTED
            flow.emit(messageList.toList())
            emit(messageList.toList())

            // ✅ COLLECT VÀ LUÔN SORT
            flow.collect { updatedMessages ->
                val sorted = updatedMessages.sortedBy { it.timestamp }
                emit(sorted)
            }

        } catch (e: Exception) {
            emit(emptyList())
        }
    }

    override suspend fun sendMessage(
        sessionId: String?,
        content: String
    ): ChatRepository.SendMessageResult {
        val token = getAccessToken()
        val response = chatApi.sendMessage(
            token = "Bearer $token",
            req = ChatRequest(message = content, sessionId = sessionId)
        )

        // Update sessions after sending
        refreshSessions()

        return ChatRepository.SendMessageResult(
            sessionId = response.sessionId,
            sessionTitle = response.sessionTitle,
            botMessage = response.botMessage.toDomain()
        )
    }

    override suspend fun deleteSession(sessionId: String) {
        try {
            val token = getAccessToken()
            chatApi.deleteSession("Bearer $token", sessionId)
            refreshSessions()
        } catch (e: Exception) {
            // Handle error
        }
    }

    override suspend fun deleteMessages(sessionId: String) {
        try {
            val token = getAccessToken()
            chatApi.deleteMessages(
                token = "Bearer $token",
                sessionId = sessionId,
                messageIds = emptyList()
            )
        } catch (e: Exception) {
            // Handle error
        }
    }

    override suspend fun deleteMessagePair(
        sessionId: String,
        userMessageId: String,
        botMessageId: String?
    ) {
        try {
            val token = getAccessToken()
            val ids = listOfNotNull(userMessageId, botMessageId)
            if (ids.isNotEmpty()) {
                chatApi.deleteMessages(
                    token = "Bearer $token",
                    sessionId = sessionId,
                    messageIds = ids
                )
            }
        } catch (e: Exception) {
            // Handle error
        }
    }

    override suspend fun updateSessionTitle(sessionId: String, newTitle: String) {
        try {
            val token = getAccessToken()
            chatApi.updateSessionTitle("Bearer $token", sessionId,mapOf(
                "title" to newTitle
            ))
            refreshSessions()
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "updateSessionTitle error: ${e.message}")
            throw e
        }
    }
}
