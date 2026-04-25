package com.example.autochat.domain.repository

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.autochat.AppState
import com.example.autochat.data.local.AppDatabase
import com.example.autochat.data.local.entity.MessageEntity
import com.example.autochat.data.local.entity.SessionEntity
import com.example.autochat.domain.model.Article
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.domain.model.Message
import com.example.autochat.llm.LlmEngine
import com.example.autochat.llm.SyncManager
import com.example.autochat.remote.api.ChatApi
import com.example.autochat.remote.api.RagApi
import com.example.autochat.remote.dto.request.ChatRequest
import com.example.autochat.remote.dto.response.MessageResponse
import com.example.autochat.remote.dto.response.QuickReplyItem
import com.example.autochat.remote.dto.response.SessionResponse
import com.example.autochat.remote.dto.response.StructuredDataResponse
import com.example.autochat.websocket.WebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import retrofit2.Retrofit
import java.util.UUID
import javax.inject.Named

// ── Mappers ───────────────────────────────────────────────────────────────────

fun SessionResponse.toDomain(): ChatSession = ChatSession(
    id = id,
    userId = "",
    title = title,
    isPinned = isPinned,
    createdAt = parseTimestamp(createdAt),
    updatedAt = parseTimestamp(updatedAt),
    endpoint = endpoint,
)

/**
 * Map MessageResponse → Message domain.
 *
 * extraData được Gson deserialize tự động từ Map<String, Any?> trong DTO.
 * Lưu ý: Gson map JSON number thành Double (kể cả article_id nguyên).
 * → Luôn dùng (value as? Number)?.toInt() khi cần Int, không cast thẳng as? Int.
 */
fun MessageResponse.toDomain(): Message = Message(
    id = id,
    sessionId = sessionId,
    content = content,
    sender = sender,
    timestamp = parseTimestamp(createdAt),
    extraData = extraData
)

private fun parseTimestamp(str: String): Long = try {
    val clean = str.split(".")[0].split("Z")[0]
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        .parse(clean)?.time ?: System.currentTimeMillis()
} catch (e: Exception) {
    System.currentTimeMillis()
}

// ── Repository ────────────────────────────────────────────────────────────────

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatApi: ChatApi,   // server-chat port 8001, cần JWT
    private val ragApi: RagApi,     // server-rag  port 8000, không cần JWT
    private val dataStore: DataStore<Preferences>,
    private val webSocketManager: WebSocketManager,
    @Named("chat") private val chatRetrofit: Retrofit,
    private val database: AppDatabase,
    private val llmEngine: LlmEngine,
    private val syncManager: SyncManager
) : ChatRepository {

    companion object {
        private val ACCESS_TOKEN = stringPreferencesKey("access_token")
    }
    private val chatBaseUrl = chatRetrofit.baseUrl().toString().trimEnd('/')
    private val streamClient = okhttp3.OkHttpClient.Builder()
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val _realtimeEvents = MutableSharedFlow<ChatRepository.RealtimeEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val realtimeEvents: Flow<ChatRepository.RealtimeEvent> =
        _realtimeEvents.asSharedFlow()

    override fun streamMessage(
        sessionId: String?,
        content: String,
        endpoint: String
    ): Flow<ChatRepository.StreamChunk> = callbackFlow {

        val token = runCatching {
            kotlinx.coroutines.runBlocking { getAccessToken() }
        }.getOrElse {
            trySend(ChatRepository.StreamChunk.Error("Not logged in"))
            close(); return@callbackFlow
        }

        val body = org.json.JSONObject().apply {
            put("message", content)
            put("session_id", sessionId ?: "")
            put("endpoint", endpoint)           // ← truyền đúng endpoint
            put("bot_message", null as String?)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = okhttp3.Request.Builder()
            .url("$chatBaseUrl/chat/send/stream")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = streamClient.newCall(request)

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                trySend(ChatRepository.StreamChunk.Error(e.message ?: "Network error"))
                close(e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val source = response.body?.source() ?: run {
                        trySend(ChatRepository.StreamChunk.Error("Empty response"))
                        close(); return
                    }
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data: ")) continue
                        try {
                            val obj = org.json.JSONObject(line.removePrefix("data: "))
                            when (obj.optString("type")) {
                                "token" -> trySend(
                                    ChatRepository.StreamChunk.Token(obj.getString("text"))
                                )
                                "done" -> trySend(
                                    ChatRepository.StreamChunk.Done(
                                        obj.optString("full_response", "")
                                    )
                                )
                                "meta" -> trySend(
                                    ChatRepository.StreamChunk.Meta(
                                        sessionId    = obj.getString("session_id"),
                                        sessionTitle = obj.getString("session_title"),

                                    )
                                )
                                "error" -> trySend(
                                    ChatRepository.StreamChunk.Error(
                                        obj.optString("message", "Unknown error")
                                    )
                                )
                            }
                        } catch (_: Exception) {}
                    }
                } finally {
                    close()
                }
            }
        })

        awaitClose { call.cancel() }
    }

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    override fun getSessionsFlow(): Flow<List<ChatSession>> = _sessions.asStateFlow()

    private val messagesCache = mutableMapOf<String, MutableList<Message>>()
    private val messagesFlows = mutableMapOf<String, MutableSharedFlow<List<Message>>>()
    private var refreshJob: Job? = null

    init {
        // Tự động refresh sessions mỗi 5 giây
        refreshJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                refreshSessions()
                delay(5000)
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(15_000)
                try {
                    syncManager.syncPendingMessages()
                } catch (e: Exception) {
                    Log.e("ChatRepo", "Auto sync failed: ${e.message}")
                }
            }
        }
        webSocketManager.addListener(object : WebSocketManager.WebSocketListener {
            override fun onNewMessage(message: Message) {
                CoroutineScope(Dispatchers.Main).launch {
                    Log.e("ChatRepo", "onNewMessage: id=${message.id} sender=${message.sender} sessionId=${message.sessionId}")
                    Log.e("ChatRepo", "onNewMessage: currentSessionId=${AppState.currentSessionId}")

                    // STREAMING_ID chỉ emit event, KHÔNG add vào cache
//                    if (message.id == "streaming_placeholder") {
//                        _realtimeEvents.emit(ChatRepository.RealtimeEvent.NewMessage(message))
//                        return@launch
//                    }

                    val list = messagesCache.getOrPut(message.sessionId) { mutableListOf() }
                    if (list.none { it.id == message.id }) {
                        list.add(message)
                        list.sortBy { it.timestamp }
                        messagesFlows[message.sessionId]?.emit(list.toList())
                        _realtimeEvents.emit(ChatRepository.RealtimeEvent.NewMessage(message))
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
                    messagesCache[sessionId]?.let { list ->
                        list.removeAll { it.id in messageIds }
                        messagesFlows[sessionId]?.emit(list.toList())
                    }
                    _realtimeEvents.emit(
                        ChatRepository.RealtimeEvent.MessagesDeleted(sessionId, messageIds)
                    )
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

            override fun onPong() {}

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

    // ── Internal helpers ──────────────────────────────────────────────────────

    private suspend fun getAccessToken(): String {
        AppState.accessToken?.takeIf { it.isNotEmpty() }?.let { return it }
        val prefs = dataStore.data.first()
        return prefs[ACCESS_TOKEN] ?: throw Exception("Not logged in")
    }

    private suspend fun refreshSessions() {
        try {
            val token = getAccessToken()
            _sessions.value = chatApi.getSessions("Bearer $token").map { it.toDomain() }
        } catch (e: Exception) {
            // silent fail — retry tự động sau 5s
        }
    }

    // ── ChatRepository: Messages ──────────────────────────────────────────────

    override fun getMessagesFlow(sessionId: String): Flow<List<Message>> = flow {
//        AppState.currentSessionId = sessionId
//        webSocketManager.joinSession(sessionId)

        val sharedFlow = messagesFlows.getOrPut(sessionId) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 64)
        }

        try {
            val token = getAccessToken()
            val messages = chatApi.getMessages("Bearer $token", sessionId)
                .map { it.toDomain() }
                .sortedBy { it.timestamp }
                .toMutableList()

            messagesCache[sessionId] = messages
            sharedFlow.emit(messages.toList())
            emit(messages.toList())

            sharedFlow.collect { updated ->
                emit(updated.sortedBy { it.timestamp })
            }
        } catch (e: Exception) {
            emit(emptyList())
        }
    }
    override suspend fun getMessages(sessionId: String): List<Message> {
        return chatApi.getMessages("Bearer ${getAccessToken()}", sessionId)
            .map { it.toDomain() }
    }

    // ── ChatRepository: Send / Delete ─────────────────────────────────────────

    override suspend fun sendMessage(
        sessionId: String?,
        content: String,
        endpoint: String,
        botMessage: String?
    ): ChatRepository.SendMessageResult {
        val token = getAccessToken()
        val response = chatApi.sendMessage(
            token = "Bearer $token",
            req = ChatRequest(
                message = content,
                sessionId = sessionId,
                endpoint = endpoint,
                botMessage = botMessage
            )
        )
        refreshSessions()

        val botMsg = response.botMessage.toDomain().let { msg ->
            if (response.botDetail != null) msg.copy(extraData = response.botDetail) else msg
        }

        return ChatRepository.SendMessageResult(
            sessionId = response.sessionId,
            sessionTitle = response.sessionTitle,
            botMessage = botMsg,
            userMessage = response.userMessage.toDomain()
        )
    }

    override suspend fun deleteSession(sessionId: String) {
        try {
            chatApi.deleteSession("Bearer ${getAccessToken()}", sessionId)
            refreshSessions()
        } catch (e: Exception) {
            Log.e("ChatRepo", "deleteSession: ${e.message}")
        }
    }

    override suspend fun deleteMessages(sessionId: String) {
        try {
            chatApi.deleteMessages(
                token = "Bearer ${getAccessToken()}",
                sessionId = sessionId,
                messageIds = emptyList()
            )
        } catch (e: Exception) {
            Log.e("ChatRepo", "deleteMessages: ${e.message}")
        }
    }

    override suspend fun deleteMessagePair(
        sessionId: String,
        userMessageId: String,
        botMessageId: String?
    ) {
        try {
            val ids = listOfNotNull(userMessageId, botMessageId)
            if (ids.isNotEmpty()) {
                chatApi.deleteMessages(
                    token = "Bearer ${getAccessToken()}",
                    sessionId = sessionId,
                    messageIds = ids
                )
            }
        } catch (e: Exception) {
            Log.e("ChatRepo", "deleteMessagePair: ${e.message}")
        }
    }

    override suspend fun updateSessionTitle(sessionId: String, newTitle: String) {
        try {
            chatApi.updateSessionTitle(
                "Bearer ${getAccessToken()}",
                sessionId,
                mapOf("title" to newTitle)
            )
            refreshSessions()
        } catch (e: Exception) {
            Log.e("ChatRepo", "updateSessionTitle: ${e.message}")
            throw e
        }
    }

    override suspend fun initRagSession(sessionId: String) {
        try {
            val response = chatApi.initRagSession("Bearer ${getAccessToken()}", sessionId)

            if (response.isSuccessful) {
                Log.d("initRagSession", "✅ RAG session initialized successfully")
            } else {
                Log.e("initRagSession", "❌ RAG init failed: ${response.code()} - ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            Log.e("initRagSession", "❌ Network error: ${e.message}")
        }
    }

    // ── ChatRepository: RAG ───────────────────────────────────────────────────

    /**
     * Lấy chi tiết bài báo từ RAG server (port 8000).
     *
     * Flow:
     *   Android app ──► ragApi.getArticle(id)
     *                    ──► GET http://10.177.243.218:8000/chat/article/{id}
     *
     * Server-chat (8001) không có endpoint này nên phải gọi thẳng RAG.
     * Không cần JWT — RAG server không yêu cầu auth cho endpoint này.
     *
     * @return Article hoặc null nếu 404 / network error.
     *         Caller (ArticleDetailFragment / NewsDetailScreen) sẽ dùng
     *         fallbackTitle + fallbackDescription thay thế khi nhận null.
     */
    override suspend fun getArticleById(articleId: Int): Article? {
        return try {
            val response = ragApi.getArticle(articleId)
            if (response.isSuccessful) {
                response.body()?.let { body ->
                    Article(
                        id = body.id,
                        title = body.title ?: "",
                        content = body.content,
                        description = body.description,
                        publishedDate = body.publishedDate,
                        category = body.category,
                        url = body.url,
                        author = body.author,
                        thumbnail = body.thumbnail
                    )
                }
            } else {
                Log.w("ChatRepo", "getArticleById($articleId): HTTP ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e("ChatRepo", "getArticleById($articleId): ${e.message}")
            null
        }
    }



    override suspend fun getStructuredData(
        query: String
    ): StructuredDataResponse? {
        return try {
            val response = ragApi.getStructuredData(query)
            if (response.isSuccessful) {
                response.body()
            } else {
                Log.w("ChatRepo", "getStructuredData '$query': HTTP ${response.code()}")
                com.example.autochat.remote.dto.response.StructuredDataResponse(hasData = false)
            }
        } catch (e: Exception) {
            Log.e("ChatRepo", "getStructuredData: ${e.message}")
            com.example.autochat.remote.dto.response.StructuredDataResponse(hasData = false)
        }
    }

    override suspend fun getQuickReplies(
        location: String?
    ): List<QuickReplyItem> {
        return try {
            val response = ragApi.getQuickReplies(location = location)
            if (response.isSuccessful) {
                response.body()?.items ?: emptyList()
            } else {
                Log.w("ChatRepo", "getQuickReplies: HTTP ${response.code()}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("ChatRepo", "getQuickReplies: ${e.message}")
            emptyList()
        }
    }
    override suspend fun getNextArticle(
        currentId: Int,
        category: String?,
        seenIds: List<Int>
    ): Article? {
        android.util.Log.e("ChatRepo", "=== getNextArticle called ===")
        android.util.Log.e("ChatRepo", "currentId: $currentId")
        android.util.Log.e("ChatRepo", "category: $category")
        android.util.Log.e("ChatRepo", "seenIds: $seenIds")

        return try {
            val excludeIds = (seenIds + currentId).distinct().joinToString(",")
            android.util.Log.e("ChatRepo", "excludeIds: $excludeIds")

            android.util.Log.e("ChatRepo", "Calling API: /chat/next-article?current_id=$currentId&category=$category&exclude_ids=$excludeIds")

            val response = ragApi.getNextArticle(
                currentId = currentId,
                category = category,
                excludeIds = excludeIds.ifEmpty { null }
            )

            android.util.Log.e("ChatRepo", "API Response code: ${response.code()}")
            android.util.Log.e("ChatRepo", "API Response body: ${response.body()}")

            if (response.isSuccessful) {
                val body = response.body()
                android.util.Log.e("ChatRepo", "body?.found: ${body?.found}")
                android.util.Log.e("ChatRepo", "body?.article: ${body?.article}")

                if (body?.found == true) {
                    body.article?.let { a ->
                        Article(
                            id = a.id,
                            title = a.title ?: "",
                            content = a.content,
                            description = a.description,
                            publishedDate = a.publishedDate,
                            category = a.category,
                            url = a.url,
                            author = a.author,
                            thumbnail = a.thumbnail
                        )
                    }
                } else null
            } else {
                android.util.Log.e("ChatRepo", "HTTP Error: ${response.code()} - ${response.errorBody()?.string()}")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatRepo", "Exception in getNextArticle", e)
            null
        }
    }
    // Thêm vào ChatRepositoryImpl

    override suspend fun togglePinSession(sessionId: String) {
        try {
            val response = chatApi.togglePinSession(
                "Bearer ${getAccessToken()}",
                sessionId
            )
            if (response.isSuccessful) {
                Log.d("ChatRepo", "togglePinSession: success")
                refreshSessions() // Refresh danh sách sessions
            } else {
                Log.e("ChatRepo", "togglePinSession: HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("ChatRepo", "togglePinSession: ${e.message}")
            throw e
        }
    }
    override suspend fun sendOfflineMessage(
        sessionId: String?,
        content: String,
        onToken: (String) -> Unit
    ): Result<ChatRepository.SendMessageResult> {
        return try {
            if (!llmEngine.isLoaded()) {
                return Result.failure(Exception("Model chưa được load"))
            }

            val isNewSession = sessionId == null
            val finalSessionId = sessionId ?: UUID.randomUUID().toString()
            val sessionTitle = content.take(30)

            // Tạo session mới nếu chưa có
            if (isNewSession) {
                database.sessionDao().insert(
                    SessionEntity(
                        id = finalSessionId,
                        userId = AppState.currentUserId,
                        title = sessionTitle,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        isSynced = false
                    )
                )
            } else {
                // Kiểm tra session có tồn tại không, nếu không thì tạo
                val existing = database.sessionDao().getSession(finalSessionId)
                if (existing == null) {
                    database.sessionDao().insert(
                        SessionEntity(
                            id = finalSessionId,
                            userId = AppState.currentUserId,
                            title = sessionTitle,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            isSynced = false
                        )
                    )
                }
            }

            // Lưu user message
            val userMsgId = UUID.randomUUID().toString()
            val userTimestamp = System.currentTimeMillis()
            database.messageDao().insert(
                MessageEntity(
                    id = userMsgId,
                    sessionId = finalSessionId,
                    content = content,
                    sender = "user",
                    timestamp = userTimestamp,
                    isSynced = false,
                    isOffline = true
                )
            )

            // Lấy history KHÔNG bao gồm message vừa insert
            val history = database.messageDao().getMessagesList(finalSessionId)
                .filter { it.id != userMsgId }

            val prompt = buildPromptForModel(history, content)
            Log.d("ChatRepo", "Prompt: $prompt")

            // Generate
            val response = StringBuilder()
            val generateResult = llmEngine.generate(prompt) { token ->
                response.append(token)
                onToken(token)
            }

            generateResult.onFailure { e ->
                return Result.failure(Exception("Generate lỗi: ${e.message}"))
            }

            val botContent = response.toString().trim()
            if (botContent.isEmpty()) {
                return Result.failure(Exception("Model không trả về kết quả"))
            }

            // Lưu bot message
            val botMsgId = UUID.randomUUID().toString()
            val botTimestamp = System.currentTimeMillis()
            database.messageDao().insert(
                MessageEntity(
                    id = botMsgId,
                    sessionId = finalSessionId,
                    content = botContent,
                    sender = "bot",
                    timestamp = botTimestamp,
                    isSynced = false,
                    isOffline = true
                )
            )

            // Cập nhật session updatedAt
            database.sessionDao().getSession(finalSessionId)?.let {
                database.sessionDao().insert(it.copy(updatedAt = botTimestamp))
            }

            Log.d("ChatRepo", "✅ Saved: session=$finalSessionId user=$userMsgId bot=$botMsgId")

            Result.success(
                ChatRepository.SendMessageResult(
                    sessionId = finalSessionId,
                    sessionTitle = database.sessionDao().getSession(finalSessionId)?.title ?: sessionTitle,
                    userMessage = Message(
                        id = userMsgId,
                        sessionId = finalSessionId,
                        content = content,
                        sender = "user",
                        timestamp = userTimestamp
                    ),
                    botMessage = Message(
                        id = botMsgId,
                        sessionId = finalSessionId,
                        content = botContent,
                        sender = "bot",
                        timestamp = botTimestamp
                    )
                )
            )
        } catch (e: Exception) {
            Log.e("ChatRepo", "sendOfflineMessage error: ${e.message}", e)
            Result.failure(e)
        }
    }


    // Get messages từ Room (cho offline)
    override fun getOfflineMessagesFlow(sessionId: String): Flow<List<MessageEntity>> {
        return database.messageDao().getMessages(sessionId)
    }

    private fun buildPromptForModel(messages: List<MessageEntity>, currentMsg: String): String {
        val modelId = llmEngine.getCurrentModelId() ?: "default"

        return when (modelId) {
            "llama-3.2-1b" -> buildLlamaPrompt(messages, currentMsg)
            "qwen2.5-1.5b", "qwen2.5-0.5b" -> buildQwenPrompt(messages, currentMsg)
            else -> buildDefaultPrompt(messages, currentMsg)
        }
    }

    private fun buildLlamaPrompt(messages: List<MessageEntity>, currentMsg: String): String {
        val sb = StringBuilder()
        sb.append("<|begin_of_text|>")
        sb.append("<|start_header_id|>system<|end_header_id|>\n")
        sb.append("Bạn là trợ lý AI ngắn gọn. Trả lời tối đa 3-4 câu, đi thẳng vào vấn đề.\n")  // ✅ Giới hạn
        sb.append("<|eot_id|>")

        messages.takeLast(4).forEach { msg ->  // ✅ Giảm xuống 4
            val role = if (msg.sender == "user") "user" else "assistant"
            sb.append("<|start_header_id|>$role<|end_header_id|>\n")
            sb.append(msg.content)
            sb.append("<|eot_id|>")
        }

        sb.append("<|start_header_id|>user<|end_header_id|>\n")
        sb.append(currentMsg)
        sb.append("<|eot_id|>")
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n")
        return sb.toString()
    }

    private fun buildQwenPrompt(messages: List<MessageEntity>, currentMsg: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\nBạn là trợ lý AI ngắn gọn. Trả lời tối đa 3-4 câu, đi thẳng vào vấn đề.\n<|im_end|>\n")  // ✅ Giới hạn

        messages.takeLast(4).forEach { msg ->  // ✅ Giảm xuống 4
            val role = if (msg.sender == "user") "user" else "assistant"
            sb.append("<|im_start|>$role\n${msg.content}\n<|im_end|>\n")
        }

        sb.append("<|im_start|>user\n$currentMsg\n<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun buildDefaultPrompt(messages: List<MessageEntity>, currentMsg: String): String {
        val sb = StringBuilder()
        sb.append("System: Bạn là trợ lý AI ngắn gọn. Trả lời tối đa 3-4 câu, đi thẳng vào vấn đề.\n\n")  // ✅ Giới hạn

        messages.takeLast(4).forEach { msg ->  // ✅ Giảm xuống 4
            val role = if (msg.sender == "user") "User" else "Assistant"
            sb.append("$role: ${msg.content}\n")
        }

        sb.append("User: $currentMsg\n")
        sb.append("Assistant: ")
        return sb.toString()
    }
}