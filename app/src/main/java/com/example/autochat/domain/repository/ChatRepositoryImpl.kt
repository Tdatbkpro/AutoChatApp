package com.example.autochat.domain.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import com.example.autochat.domain.model.MediaItem
import com.example.autochat.domain.model.Message
import com.example.autochat.llm.LlmEngine
import com.example.autochat.llm.PromptBuilder
import com.example.autochat.llm.SyncManager
import com.example.autochat.remote.api.ChatApi
import com.example.autochat.remote.api.RagApi
import com.example.autochat.remote.dto.request.ChatRequest
import com.example.autochat.remote.dto.request.EditMessageRequest
import com.example.autochat.remote.dto.request.OfflineMessageSync
import com.example.autochat.remote.dto.response.MessageResponse
import com.example.autochat.remote.dto.response.QuickReplyItem
import com.example.autochat.remote.dto.response.SessionResponse
import com.example.autochat.remote.dto.response.StructuredDataResponse
import com.example.autochat.ui.phone.BranchManager
import com.example.autochat.websocket.WebSocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.withContext
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

fun MessageResponse.toDomain(): Message = Message(
    id              = id,
    sessionId       = sessionId,
    content         = content,
    sender          = sender,
    timestamp       = parseTimestamp(createdAt),
    extraData       = extraData,
    branchId        = branchId,         // ✅
    parentMessageId = parentMessageId,  // ✅
)

fun SessionEntity.toDomain() = ChatSession(
    id = id,
    userId = userId,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isPinned = isPinned,
    endpoint = "ask"
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
    private val chatApi: ChatApi,
    private val ragApi: RagApi,
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
    private val streamClient = OkHttpClient.Builder()
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

    // ── Sessions ──────────────────────────────────────────────────────────────

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    override fun getSessionsFlow(): Flow<List<ChatSession>> = _sessions.asStateFlow()

    private val messagesCache = mutableMapOf<String, MutableList<Message>>()
    private val messagesFlows = mutableMapOf<String, MutableSharedFlow<List<Message>>>()

    init {
        CoroutineScope(Dispatchers.IO).launch { refreshSessions() }
        setupWebSocketListener()
    }

    // ── refreshSessions — FIX CHÍNH ──────────────────────────────────────────
    //
    // Bug cũ: chỉ lấy session Room có isSynced=false → session online (isSynced=true)
    //         hoặc session chưa có trong Room bị mất hoàn toàn khi offline.
    //
    // Fix:
    //   1. Luôn load TẤT CẢ session từ Room trước → hiển thị ngay, không chờ network.
    //   2. Thử gọi server để lấy server sessions.
    //   3. Merge: server sessions được cache vào Room (upsert), rồi merge với local.
    //   4. Nếu server lỗi → dùng toàn bộ Room (bao gồm cả session đã sync trước đó).

    private suspend fun refreshSessions() {
        // ── Bước 1: Hiển thị Room ngay lập tức (không chờ network) ─────────
        val allLocalSessions = database.sessionDao().getAllSessions()
            .first()
            .map { it.toDomain() }
            .sortedWith(
                compareByDescending<ChatSession> { it.isPinned }
                    .thenByDescending { it.updatedAt }
            )

        if (allLocalSessions.isNotEmpty()) {
            _sessions.value = allLocalSessions
            Log.d("ChatRepo", "refreshSessions: loaded ${allLocalSessions.size} local sessions")
        }

        // ── Bước 2: Thử fetch server ─────────────────────────────────────────
        try {
            val token = getAccessToken()
            val serverSessions = chatApi.getSessions("Bearer $token").map { it.toDomain() }

            // ── Bước 3: Cache server sessions vào Room (upsert) ──────────────
            // Để lần sau offline vẫn còn đủ data
            serverSessions.forEach { session ->
                val existing = database.sessionDao().getSession(session.id)
                if (existing == null) {
                    database.sessionDao().insert(
                        SessionEntity(
                            id        = session.id,
                            userId    = session.userId.ifEmpty { AppState.currentUserId },
                            title     = session.title,
                            createdAt = session.createdAt,
                            updatedAt = session.updatedAt,
                            isPinned  = session.isPinned,
                            isSynced  = true
                        )
                    )
                } else {
                    // Cập nhật title / pin / updatedAt từ server
                    database.sessionDao().insert(
                        existing.copy(
                            title     = session.title,
                            updatedAt = session.updatedAt,
                            isPinned  = session.isPinned,
                            isSynced  = true
                        )
                    )
                }
            }

            // ── Bước 4: Merge server + local-only (chưa sync) ────────────────
            val localOnlySessions = database.sessionDao().getAllSessions()
                .first()
                .filter { !it.isSynced }
                .map { it.toDomain() }

            _sessions.value = (serverSessions + localOnlySessions)
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<ChatSession> { it.isPinned }
                        .thenByDescending { it.updatedAt }
                )

            Log.d("ChatRepo",
                "refreshSessions: ${serverSessions.size} from server + ${localOnlySessions.size} local-only"
            )

        } catch (e: Exception) {
            // ── Bước 4 (offline path): dùng toàn bộ Room ─────────────────────
            // Bao gồm cả session đã sync (isSynced=true) từ lần online trước
            Log.w("ChatRepo", "refreshSessions offline: ${e.message}")

            val allLocal = database.sessionDao().getAllSessions()
                .first()
                .map { it.toDomain() }
                .sortedWith(
                    compareByDescending<ChatSession> { it.isPinned }
                        .thenByDescending { it.updatedAt }
                )

            _sessions.value = allLocal
            Log.d("ChatRepo", "refreshSessions: offline fallback, ${allLocal.size} local sessions")
        }
    }
    override fun getMessagesFlow(sessionId: String, branchId: String?): Flow<List<Message>> = flow {
        val sharedFlow = messagesFlows.getOrPut(sessionId) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 64)
        }

        // ── Thử load từ Room trước để hiển thị ngay (không chờ network) ───────
        val cachedLocal = database.messageDao().getMessagesList(sessionId)
        if (cachedLocal.isNotEmpty()) {
            val localMessages = cachedLocal
                .map { entity ->
                    Message(
                        id        = entity.id,
                        sessionId = entity.sessionId,
                        content   = entity.content,
                        sender    = entity.sender,
                        timestamp = entity.timestamp,
                        extraData = null
                    )
                }
                .sortedBy { it.timestamp }
            emit(localMessages)
            Log.d("ChatRepo", "getMessagesFlow: emitted ${localMessages.size} cached messages")
        }

        // ── Thử fetch server ──────────────────────────────────────────────────
        try {
            if (!AppState.isConnectServer) {
                Log.d("ChatRepo", "getMessagesFlow: fallback to Room for $sessionId")
                database.messageDao().getMessages(sessionId).collect { entities ->
                    val messages = entities
                        .map { entity ->
                            Message(
                                id        = entity.id,
                                sessionId = entity.sessionId,
                                content   = entity.content,
                                sender    = entity.sender,
                                timestamp = entity.timestamp,
                                extraData = null
                            )
                        }
                        .sortedBy { it.timestamp }
                    emit(messages)
                }
            }
            else {
                val token = getAccessToken()
                val serverMessages = chatApi.getMessages("Bearer $token", sessionId,branchId  = branchId?.takeIf { it != sessionId })
                    .map { it.toDomain() }
                    .sortedBy { it.timestamp }
                    .toMutableList()

                // Cache server messages vào Room (upsert) để dùng offline lần sau
                cacheMessagesToRoom(sessionId, serverMessages)

                loadPivotsForSession(sessionId, activeBranch = branchId ?: sessionId)

                messagesCache[sessionId] = serverMessages
                sharedFlow.emit(serverMessages.toList())
                emit(serverMessages.toList())

                Log.d("ChatRepo", "getMessagesFlow: loaded ${serverMessages.size} from server")

                // Tiếp tục collect real-time updates
                sharedFlow.collect { updated ->
                    emit(updated.sortedBy { it.timestamp })
                }
            }

        } catch (e: Exception) {
            Log.w("ChatRepo", "getMessagesFlow server failed: ${e.message}")
        }

        // ── Fallback Room nếu server lỗi ─────────────────────────────────────

    }
    private suspend fun loadPivotsForSession(sessionId: String, activeBranch: String? = null) {
        try {
            val token  = getAccessToken()
            val pivots = chatApi.getSessionPivots("Bearer $token", sessionId)

            pivots.forEach { pivot ->
                val allBranchIds = pivot.branchIds
                if (allBranchIds.isEmpty()) return@forEach

                // ← Dùng activeBranch được truyền vào, không dùng BranchManager
                val activeBranchId = activeBranch?.ifEmpty { sessionId } ?: sessionId

                val branchInfoList = allBranchIds.mapIndexed { index, item ->
                    BranchManager.BranchInfo(
                        branchId  = item.branchId,
                        index     = index,
                        total     = allBranchIds.size,
                        createdAt = item.createdAt.toString(),
                    )
                }

                val originalBranchInfo = BranchManager.BranchInfo(
                    branchId  = sessionId,
                    index     = 0,
                    total     = branchInfoList.size + 1,
                    createdAt = "",
                )
                val allBranches = if (branchInfoList.none { it.branchId == sessionId }) {
                    listOf(originalBranchInfo) + branchInfoList.map {
                        it.copy(index = it.index + 1, total = branchInfoList.size + 1)
                    }
                } else branchInfoList

                val activeIndex = allBranches.indexOfFirst { it.branchId == activeBranchId }
                    .coerceAtLeast(0)

                BranchManager.onBranchCreated(
                    pivotMessageId = pivot.pivotId,
                    newBranchInfo  = allBranches[activeIndex],  // ← đúng nhánh active
                    allBranches    = allBranches,
                )
            }
        } catch (e: Exception) {
            Log.w("ChatRepo", "loadPivots failed: ${e.message}")
        }
    }

    /** Upsert danh sách messages vào Room để dùng offline */
    private suspend fun cacheMessagesToRoom(sessionId: String, messages: List<Message>) {
        try {
            messages.forEach { msg ->
                val existing = database.messageDao().getMessageById(msg.id)
                if (existing == null) {
                    database.messageDao().insert(
                        MessageEntity(
                            id        = msg.id,
                            sessionId = msg.sessionId,
                            content   = msg.content,
                            sender    = msg.sender,
                            timestamp = msg.timestamp,
                            extraData = null,
                            isSynced  = true,
                            isOffline = false
                        )
                    )
                }
            }
            Log.d("ChatRepo", "cacheMessagesToRoom: cached ${messages.size} messages for $sessionId")
        } catch (e: Exception) {
            Log.w("ChatRepo", "cacheMessagesToRoom failed: ${e.message}")
        }
    }

    // ── Stream ────────────────────────────────────────────────────────────────

    override fun streamMessage(
        sessionId: String?,
        content: String,
        endpoint: String,
        branchId: String? ,
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
            put("endpoint", endpoint)
            put("branch_id", branchId ?: "")
            put("bot_message", null as String?)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$chatBaseUrl/chat/send/stream")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = streamClient.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                trySend(ChatRepository.StreamChunk.Error(e.message ?: "Network error"))
                close(e)
            }

            override fun onResponse(call: Call, response: Response) {
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
                                "done" -> {
                                    val fullResponse = obj.optString("full_response", "")
                                    val botDetailObj = obj.optJSONObject("bot_detail")
                                    val botMessage = if (botDetailObj != null) {
                                        Message(
                                            id = "bot_${System.currentTimeMillis()}",
                                            sessionId = sessionId ?: "",
                                            content = fullResponse,
                                            sender = "bot",
                                            timestamp = System.currentTimeMillis(),
                                            extraData = parseJsonObject(botDetailObj)
                                        )
                                    } else null
                                    trySend(ChatRepository.StreamChunk.Done(fullResponse, botMessage))
                                }
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

    // ── WebSocket listener setup ──────────────────────────────────────────────

    private fun setupWebSocketListener() {
        webSocketManager.addListener(object : WebSocketManager.WebSocketListener {
            override fun onNewMessage(message: Message) {
                CoroutineScope(Dispatchers.Main).launch {
                    val existing = _sessions.value.find { it.id == message.sessionId }
                    if (existing != null) {
                        _sessions.value = _sessions.value.map {
                            if (it.id == message.sessionId) it.copy(updatedAt = message.timestamp) else it
                        }.sortedWith(
                            compareByDescending<ChatSession> { it.isPinned }
                                .thenByDescending { it.updatedAt }
                        )
                    }
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
            override fun onSyncCompleted(sessionId: String) {
                CoroutineScope(Dispatchers.Main).launch {
                    Log.d("ChatRepo", "🔄 Auto-refreshing sessions after sync for: $sessionId")

                    // Refresh sessions list
                    refreshSessions()

                    // Cũng refresh messages của session đó nếu đang mở
                    messagesFlows[sessionId]?.let { flow ->
                        launch {
                            try {
                                val token = getAccessToken()
                                val serverMessages = chatApi.getMessages("Bearer $token", sessionId)
                                    .map { it.toDomain() }
                                    .sortedBy { it.timestamp }
                                    .toMutableList()

                                cacheMessagesToRoom(sessionId, serverMessages)
                                messagesCache[sessionId] = serverMessages
                                flow.emit(serverMessages.toList())
                            } catch (e: Exception) {
                                Log.w("ChatRepo", "Failed to refresh messages after sync: ${e.message}")
                            }
                        }
                    }
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
                    refreshSessions()
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
                    refreshSessions()
                    _realtimeEvents.emit(ChatRepository.RealtimeEvent.Error(error))
                }
            }
            override fun onBranchCreated(
                sessionId: String, branchId: String, pivotMessageId: String,
                branchIndex: Int, branchTotal: Int,
            ) {
                CoroutineScope(Dispatchers.Main).launch {
                    _realtimeEvents.emit(
                        ChatRepository.RealtimeEvent.BranchCreated(
                            sessionId      = sessionId,
                            branchId       = branchId,
                            pivotMessageId = pivotMessageId,
                            branchInfo     = ChatRepository.BranchInfoData(
                                branchId  = branchId,
                                index     = branchIndex,
                                total     = branchTotal,
                                createdAt = System.currentTimeMillis(),
                            )
                        )
                    )
                }
            }
            override fun onSessionCreated(
                sessionId: String, title: String,
                endpoint: String, createdAt: Long, updatedAt: Long
            ) {
                CoroutineScope(Dispatchers.Main).launch {
                    val existing = _sessions.value.find { it.id == sessionId }
                    if (existing == null) {
                        _sessions.value = (listOf(
                            ChatSession(
                                id        = sessionId,
                                userId    = AppState.currentUserId,
                                title     = title,
                                createdAt = createdAt,
                                updatedAt = updatedAt,
                                endpoint  = endpoint
                            )
                        ) + _sessions.value).sortedWith(
                            compareByDescending<ChatSession> { it.isPinned }
                                .thenByDescending { it.updatedAt }
                        )
                    }
                    refreshSessions()
                }
            }
        })
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun getAccessToken(): String {
        AppState.accessToken?.takeIf { it.isNotEmpty() }?.let { return it }
        val prefs = dataStore.data.first()
        return prefs[ACCESS_TOKEN] ?: throw Exception("Not logged in")
    }

    suspend fun onSessionUpdated(session: ChatSession) {
        val existing = _sessions.value.find { it.id == session.id }
        if (existing == null) {
            _sessions.value = (listOf(session) + _sessions.value)
                .sortedWith(
                    compareByDescending<ChatSession> { it.isPinned }
                        .thenByDescending { it.updatedAt }
                )
        }
    }

    private fun parseJsonObject(json: org.json.JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        json.keys().forEach { key ->
            map[key] = when (val v = json.get(key)) {
                is org.json.JSONObject -> parseJsonObject(v)
                is org.json.JSONArray  -> parseJsonArray(v)
                org.json.JSONObject.NULL -> null
                else -> v
            }
        }
        return map
    }

    private fun parseJsonArray(arr: org.json.JSONArray): List<Any?> {
        return (0 until arr.length()).map { i ->
            when (val v = arr.get(i)) {
                is org.json.JSONObject -> parseJsonObject(v)
                is org.json.JSONArray  -> parseJsonArray(v)
                org.json.JSONObject.NULL -> null
                else -> v
            }
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    override suspend fun getMessages(sessionId: String): List<Message> {
        return chatApi.getMessages("Bearer ${getAccessToken()}", sessionId)
            .map { it.toDomain() }
    }
    override suspend fun editMessage(
        sessionId:  String,
        messageId:  String,
        newContent: String,
        endpoint:   String,
    ): ChatRepository.EditMessageResult {
        val token    = getAccessToken()
        val response = chatApi.editMessage(
            token     = "Bearer $token",
            sessionId = sessionId,
            req       = EditMessageRequest(
                sessionId  = sessionId,
                messageId  = messageId,
                newContent = newContent,
                endpoint   = endpoint,
            )
        )
        return ChatRepository.EditMessageResult(
            newBranchId   = response.newBranchId,
            userMessageId = response.userMessageId,
            botMessageId  = response.botMessageId,
            userMessage   = response.userMessage.toDomain(),
            botMessage    = response.botMessage.toDomain(),
            branchInfo    = ChatRepository.BranchInfoData(
                branchId  = response.branchInfo.branchId,
                index     = response.branchInfo.index,
                total     = response.branchInfo.total,
                createdAt = response.branchInfo.createdAt,
            )
        )
    }

    override suspend fun getBranchMessages(
        sessionId: String,
        branchId:  String,
    ): List<Message> {
        val token = getAccessToken()

        // Nhánh gốc → dùng getMessages bình thường
        if (branchId == sessionId) {
            return chatApi.getMessages(
                token     = "Bearer $token",
                sessionId = sessionId,
                branchId  = null,
            ).map { it.toDomain() }
        }

        // Nhánh mới → gọi endpoint chuyên dụng có merge context
        return chatApi.getBranchMessages(    // ← GET /branches/{branch_id}/messages
            token     = "Bearer $token",
            sessionId = sessionId,
            branchId  = branchId,
        ).map { it.toDomain() }
    }

    override suspend fun getBranchesAtMessage(
        sessionId: String,
        messageId: String,
    ): List<ChatRepository.BranchInfoData> {
        val token = getAccessToken()
        return chatApi.getBranchesAtMessage(
            token     = "Bearer $token",
            sessionId = sessionId,
            messageId = messageId,
        ).map {
            ChatRepository.BranchInfoData(
                branchId  = it.branchId,
                index     = it.index,
                total     = it.total,
                createdAt = it.createdAt,
            )
        }
    }
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
                message    = content,
                sessionId  = sessionId,
                endpoint   = endpoint,
                botMessage = botMessage
            )
        )
        val existingSession = _sessions.value.find { it.id == response.sessionId }
        if (existingSession == null) {
            val newSession = ChatSession(
                id        = response.sessionId,
                userId    = AppState.currentUserId,
                title     = response.sessionTitle,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            _sessions.value = (listOf(newSession) + _sessions.value)
                .sortedWith(
                    compareByDescending<ChatSession> { it.isPinned }
                        .thenByDescending { it.updatedAt }
                )
        } else {
            _sessions.value = _sessions.value.map {
                if (it.id == response.sessionId)
                    it.copy(updatedAt = System.currentTimeMillis())
                else it
            }.sortedWith(
                compareByDescending<ChatSession> { it.isPinned }
                    .thenByDescending { it.updatedAt }
            )
        }

        val botMsg = response.botMessage.toDomain().let { msg ->
            if (response.botDetail != null) msg.copy(extraData = response.botDetail) else msg
        }

        return ChatRepository.SendMessageResult(
            sessionId    = response.sessionId,
            sessionTitle = response.sessionTitle,
            botMessage   = botMsg,
            userMessage  = response.userMessage.toDomain()
        )
    }

    override suspend fun deleteSession(sessionId: String) {
        database.messageDao().deleteBySession(sessionId)
        database.sessionDao().getSession(sessionId)?.let {
            database.sessionDao().delete(it)
        }
        _sessions.value = _sessions.value.filter { it.id != sessionId }
        try {
            chatApi.deleteSession("Bearer ${getAccessToken()}", sessionId)
        } catch (e: Exception) {
            Log.e("ChatRepo", "deleteSession server failed: ${e.message}")
        }
    }

    override suspend fun deleteMessages(sessionId: String) {
        try {
            chatApi.deleteMessages(
                token      = "Bearer ${getAccessToken()}",
                sessionId  = sessionId,
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
                    token      = "Bearer ${getAccessToken()}",
                    sessionId  = sessionId,
                    messageIds = ids
                )
            }
        } catch (e: Exception) {
            Log.e("ChatRepo", "deleteMessagePair: ${e.message}")
        }
    }

    override suspend fun updateSessionTitle(sessionId: String, newTitle: String) {
        _sessions.value = _sessions.value.map {
            if (it.id == sessionId) it.copy(title = newTitle) else it
        }
        database.sessionDao().updateTitle(sessionId, newTitle, System.currentTimeMillis())
        try {
            chatApi.updateSessionTitle(
                "Bearer ${getAccessToken()}",
                sessionId,
                mapOf("title" to newTitle)
            )
        } catch (e: Exception) {
            Log.e("ChatRepo", "updateSessionTitle server failed: ${e.message}")
        }
    }

    override suspend fun togglePinSession(sessionId: String) {
        val currentPinned = _sessions.value.find { it.id == sessionId }?.isPinned ?: false
        _sessions.value = _sessions.value.map {
            if (it.id == sessionId) it.copy(isPinned = !currentPinned) else it
        }.sortedWith(
            compareByDescending<ChatSession> { it.isPinned }
                .thenByDescending { it.updatedAt }
        )
        database.sessionDao().togglePin(sessionId)
        try {
            chatApi.togglePinSession("Bearer ${getAccessToken()}", sessionId)
        } catch (e: Exception) {
            Log.e("ChatRepo", "togglePinSession server failed: ${e.message}")
        }
    }

    override suspend fun initRagSession(sessionId: String) {
        try {
            val response = chatApi.initRagSession("Bearer ${getAccessToken()}", sessionId)
            if (!response.isSuccessful) {
                Log.e("initRagSession", "❌ RAG init failed: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("initRagSession", "❌ Network error: ${e.message}")
        }
    }
    override suspend fun syncBranchToRag(sessionId: String, branchId: String) {
        try {
            val token = getAccessToken()
            chatApi.syncBranchToRag("Bearer $token", sessionId, branchId)
            Log.d("ChatRepo", "syncBranchToRag: ok $sessionId / $branchId")
        } catch (e: Exception) {
            Log.w("ChatRepo", "syncBranchToRag failed (non-critical): ${e.message}")
        }
    }

    // ── RAG ───────────────────────────────────────────────────────────────────

    override suspend fun getArticleById(articleId: Int): Article? {
        return try {
            val response = ragApi.getArticle(articleId)
            if (response.isSuccessful) {
                response.body()?.let { body ->
                    Article(
                        id           = body.id,
                        title        = body.title ?: "",
                        description  = body.description,
                        content      = body.content,
                        category     = body.category,
                        url          = body.url,
                        publishedDate = body.publishedDate,
                        mediaItems   = body.mediaItems?.map {
                            MediaItem(url = it.url, type = it.type, caption = it.caption, description = it.description)
                        },
                        author    = body.author,
                        thumbnail = body.thumbnail
                    )
                }
            } else null
        } catch (e: Exception) {
            Log.e("ChatRepo", "getArticleById($articleId): ${e.message}", e)
            null
        }
    }

    override suspend fun getStructuredData(query: String): StructuredDataResponse? {
        return try {
            val response = ragApi.getStructuredData(query)
            if (response.isSuccessful) response.body()
            else StructuredDataResponse(hasData = false)
        } catch (e: Exception) {
            StructuredDataResponse(hasData = false)
        }
    }

    override suspend fun getQuickReplies(location: String?): List<QuickReplyItem> {
        return try {
            val response = ragApi.getQuickReplies(location = location)
            if (response.isSuccessful) response.body()?.items ?: emptyList()
            else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getNextArticle(
        currentId: Int,
        category: String?,
        seenIds: List<Int>
    ): Article? {
        return try {
            val excludeIds = (seenIds + currentId).distinct().joinToString(",")
            val response = ragApi.getNextArticle(
                currentId  = currentId,
                category   = category,
                excludeIds = excludeIds.ifEmpty { null }
            )
            if (response.isSuccessful && response.body()?.found == true) {
                response.body()?.article?.let { a ->
                    Article(
                        id           = a.id,
                        title        = a.title ?: "",
                        content      = a.content,
                        description  = a.description,
                        publishedDate = a.publishedDate,
                        category     = a.category,
                        url          = a.url,
                        author       = a.author,
                        thumbnail    = a.thumbnail,
                        mediaItems   = a.mediaItems?.map {
                            MediaItem(url = it.url, type = it.type, caption = it.caption, description = it.description)
                        }
                    )
                }
            } else null
        } catch (e: Exception) {
            Log.e("ChatRepo", "getNextArticle: ${e.message}")
            null
        }
    }

    // ── Offline / LLM ─────────────────────────────────────────────────────────

    override suspend fun sendOfflineMessage(
        sessionId: String?,
        content: String,
        onToken: (String) -> Unit
    ): Result<ChatRepository.SendMessageResult> {
        return try {
            if (!llmEngine.isLoaded()) {
                return Result.failure(Exception("Model chưa được load"))
            }

            val finalSessionId = sessionId ?: UUID.randomUUID().toString()

            val existingSession = database.sessionDao().getSession(finalSessionId)
            if (existingSession == null) {
                val sessionEntity = SessionEntity(
                    id        = finalSessionId,
                    userId    = AppState.currentUserId.ifEmpty { "offline_user" },
                    title     = content.take(30).ifEmpty { "Chat mới" },
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    isPinned  = false,
                    isSynced  = false
                )
                database.sessionDao().insert(sessionEntity)
                val newSession = sessionEntity.toDomain()
                _sessions.value = (listOf(newSession) + _sessions.value)
                    .distinctBy { it.id }
                    .sortedWith(
                        compareByDescending<ChatSession> { it.isPinned }
                            .thenByDescending { it.updatedAt }
                    )
            }

            val userMsgId = UUID.randomUUID().toString()
            database.messageDao().insert(
                MessageEntity(
                    id        = userMsgId,
                    sessionId = finalSessionId,
                    content   = content,
                    sender    = "user",
                    timestamp = System.currentTimeMillis(),
                    extraData = null,
                    isSynced  = false,
                    isOffline = true
                )
            )

            val history = database.messageDao().getMessagesList(finalSessionId)
            val prompt  = buildPromptForModel(history, content)
            val botMsgId = UUID.randomUUID().toString()
            val response = StringBuilder()

            val generateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            var generateDone  = false

            generateScope.launch {
                try {
                    llmEngine.generate(prompt) { token ->
                        response.append(token)
                        CoroutineScope(Dispatchers.Main).launch { onToken(token) }
                    }
                } catch (e: Exception) {
                    Log.d("ChatRepo", "generate stopped: ${e.message}")
                } finally {
                    val finalContent = response.toString().ifEmpty { "..." }
                    database.messageDao().insert(
                        MessageEntity(
                            id        = botMsgId,
                            sessionId = finalSessionId,
                            content   = finalContent,
                            sender    = "bot",
                            timestamp = System.currentTimeMillis(),
                            extraData = null,
                            isSynced  = false,
                            isOffline = true
                        )
                    )
                    database.sessionDao().getSession(finalSessionId)?.let {
                        database.sessionDao().insert(it.copy(updatedAt = System.currentTimeMillis()))
                    }
                    if (AppState.isConnectServer) {
                        syncMessagesRealtime(finalSessionId, userMsgId, botMsgId, content, finalContent)
                    }
                    generateDone = true
                }
            }

            withContext(kotlinx.coroutines.NonCancellable) {
                var waited = 0
                while (!generateDone && waited < 300) {
                    delay(100); waited++
                }
            }

            val finalContent = response.toString().ifEmpty { "..." }
            Result.success(
                ChatRepository.SendMessageResult(
                    sessionId    = finalSessionId,
                    sessionTitle = content.take(30),
                    userMessage  = Message(
                        id        = userMsgId,
                        sessionId = finalSessionId,
                        content   = content,
                        sender    = "user",
                        timestamp = System.currentTimeMillis()
                    ),
                    botMessage = Message(
                        id        = botMsgId,
                        sessionId = finalSessionId,
                        content   = finalContent,
                        sender    = "bot",
                        timestamp = System.currentTimeMillis()
                    )
                )
            )
        } catch (e: Exception) {
            Log.e("ChatRepo", "sendOfflineMessage error: ${e.message}", e)
            Result.failure(e)
        }
    }
    private fun syncMessagesRealtime(
        sessionId: String,
        userMsgId: String,
        botMsgId: String,
        userContent: String,
        botContent: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!AppState.isConnectServer) return@launch
                val token = try { getAccessToken() } catch (e: Exception) { return@launch }
                val authHeader = "Bearer $token"
                val sessionTitle = database.sessionDao().getSession(sessionId)?.title ?: "Offline chat"

                // Sync user message
                val userOk = runCatching {
                    val r = chatApi.syncSingleMessage(
                        token = authHeader,
                        body = OfflineMessageSync(
                            id = userMsgId,
                            sessionId = sessionId,
                            content = userContent,
                            sender = "user",
                            timestamp = System.currentTimeMillis(),
                            sessionTitle = sessionTitle,
                            extraData = null
                        )
                    )
                    if (r.isSuccessful || r.code() == 400) {
                        database.messageDao().markAsSynced(listOf(userMsgId))
                        true
                    } else false
                }.getOrElse { false }

                delay(500)

                // Sync bot message
                val botOk = runCatching {
                    val r = chatApi.syncSingleMessage(
                        token = authHeader,
                        body = OfflineMessageSync(
                            id = botMsgId,
                            sessionId = sessionId,
                            content = botContent,
                            sender = "bot",
                            timestamp = System.currentTimeMillis(),
                            sessionTitle = sessionTitle,
                            extraData = null
                        )
                    )
                    if (r.isSuccessful || r.code() == 400) {
                        database.messageDao().markAsSynced(listOf(botMsgId))
                        true
                    } else false
                }.getOrElse { false }

                if (userOk && botOk) {
                    database.sessionDao().markAsSynced(listOf(sessionId))

                    // ✅ THÊM: Force refresh sessions ngay sau khi sync thành công
                    refreshSessions()
                    Log.d("ChatRepo", "✅ Sync completed and sessions refreshed for: $sessionId")
                }
            } catch (e: Exception) {
                Log.w("ChatRepo", "syncMessagesRealtime failed: ${e.message}")
            }
        }
    }

    override fun getOfflineMessagesFlow(sessionId: String): Flow<List<MessageEntity>> =
        database.messageDao().getMessages(sessionId)

    override suspend fun syncOfflineMessages() {
        syncManager.syncPendingMessages()
    }
    override suspend fun getLatestBranchId(sessionId: String): String {
        return try {
            val token = getAccessToken()
            val result = chatApi.getLatestBranch("Bearer $token", sessionId)
            result["branch_id"] ?: sessionId
        } catch (e: Exception) {
            sessionId
        }
    }
    override suspend fun saveBotMessage(sessionId: String, content: String, messageId: String) {
        database.messageDao().insert(
            MessageEntity(
                id        = messageId,
                sessionId = sessionId,
                content   = content,
                sender    = "bot",
                timestamp = System.currentTimeMillis(),
                extraData = null,
                isSynced  = false,
                isOffline = true
            )
        )
    }

    // ── Prompt builders ───────────────────────────────────────────────────────

    private fun buildPromptForModel(messages: List<MessageEntity>, currentMsg: String): String {
        val modelId = llmEngine.getCurrentModelId() ?: "qwen"
        val history = messages.takeLast(6)
        return PromptBuilder.build(modelId, history, currentMsg)
    }
}