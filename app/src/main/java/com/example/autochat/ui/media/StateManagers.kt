package com.example.autochat.ui.media

import android.content.Context
import android.util.Log
import com.example.autochat.data.repository.ReadHistoryRepository
import com.example.autochat.domain.model.Message
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.domain.repository.ChatRepository
import com.example.autochat.remote.dto.response.QuickReplyItem
import com.example.autochat.util.LocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// ChatMessageManager
//   Mirrors the messages / isSending / isWaitingResponse state in MyChatScreen.
//   Also holds historyMessages cache for HistoryDetailScreen.
// ─────────────────────────────────────────────────────────────────────────────

class ChatMessageManager {

    val messages       = mutableListOf<Message>()
    var isSending      = false
    var isWaitingReply = false

    /** sessionId → list of messages; used by HISTORY_DETAIL browse node */
    val historyMessages = mutableMapOf<String, List<Message>>()

    private var observeJob: Job? = null

    /**
     * Start (or restart) observing a session's messages from Room.
     * Mirrors MyChatScreen.observeMessages.
     *
     * FIX #4: thêm [onUpdate] callback — được gọi mỗi khi messages thay đổi.
     * AutoChatMediaService dùng callback này để notifyChildrenChanged(CHAT_ROOT_ID)
     * ngay khi data có, thay vì phải polling hoặc notify thủ công.
     */
    fun observeSession(
        sessionId      : String,
        chatRepository : ChatRepository,
        scope          : CoroutineScope,
        onUpdate       : (() -> Unit)? = null
    ) {
        observeJob?.cancel()
        observeJob = scope.launch {
            chatRepository.getMessagesFlow(sessionId).collect { list ->
                withContext(Dispatchers.Main) {
                    val dbIds      = list.map { it.id }.toSet()
                    val optimistic = messages.filter { it.id.startsWith("temp_") && it.id !in dbIds }
                    messages.clear()
                    messages.addAll(list)
                    messages.addAll(optimistic)
                    messages.sortBy { it.timestamp }
                    onUpdate?.invoke()   // ← FIX #4: notify caller
                }
            }
        }
    }

    /** Mirror of MyChatScreen.clearMessages */
    fun clear() {
        messages.clear()
        isSending      = false
        isWaitingReply = false
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// QuickReplyManager
//   Mirrors MyChatScreen quickReplies / articleSlotMap / deduplicateWithHistory.
// ─────────────────────────────────────────────────────────────────────────────

class QuickReplyManager {

    var items           = listOf<QuickReplyItem>()
    private val slotMap = mutableMapOf<Int, Int>()   // articleId → slotIndex

    /**
     * Load quick replies, deduplicate against read history.
     * Mirrors MyChatScreen.loadQuickReplies + deduplicateWithHistory.
     */
    suspend fun load(
        context         : Context,
        chatRepository  : ChatRepository,
        readHistoryRepo : ReadHistoryRepository
    ) {
        try {
            val location = withContext(Dispatchers.IO) {
                LocationHelper.getProvinceName(context.applicationContext)
            } ?: "hưng yên"

            val raw = chatRepository.getQuickReplies(location = location)
            if (raw.isNotEmpty()) {
                items = deduplicateWithHistory(raw, chatRepository, readHistoryRepo)
                rebuildSlotMap()
            }
        } catch (e: Exception) {
            Log.w("QR_MANAGER", "load: ${e.message}")
        }
    }

    /**
     * Refresh a single slot after an article has been consumed.
     * Mirrors MyChatScreen.refreshGridSlot.
     */
    suspend fun refreshSlot(
        slotIndex       : Int,
        consumedId      : Int,
        category        : String?,
        chatRepository  : ChatRepository,
        readHistoryRepo : ReadHistoryRepository
    ) {
        try {
            val excludeIds = readHistoryRepo.getAllReadIds()
            val next = chatRepository.getNextArticle(
                currentId = consumedId,
                category  = category,
                seenIds   = excludeIds
            ) ?: return

            val updated = items.toMutableList()
            if (slotIndex < updated.size) {
                updated[slotIndex] = QuickReplyItem(
                    label    = next.title,
                    id       = next.id,
                    category = next.category ?: category ?: "",
                    icon     = "📰"
                )
                items = updated
                rebuildSlotMap()
            }
        } catch (e: Exception) {
            Log.w("QR_MANAGER", "refreshSlot: ${e.message}")
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /** Mirror of MyChatScreen.deduplicateWithHistory */
    private suspend fun deduplicateWithHistory(
        raw             : List<QuickReplyItem>,
        chatRepository  : ChatRepository,
        readHistoryRepo : ReadHistoryRepository
    ): List<QuickReplyItem> {
        val readIds = try {
            readHistoryRepo.getAllReadIds().toSet()
        } catch (e: Exception) {
            return raw
        }
        if (readIds.isEmpty()) return raw

        val result  = mutableListOf<QuickReplyItem>()
        val usedIds = raw.mapNotNull { it.id }.toMutableSet()

        for (item in raw) {
            val articleId = item.id
            if (articleId == null || articleId !in readIds) {
                result.add(item); continue
            }
            val excludeIds = (readIds + usedIds).toList()
            val next = try {
                chatRepository.getNextArticle(
                    currentId = articleId,
                    category  = item.category,
                    seenIds   = excludeIds
                )
            } catch (e: Exception) { null }

            if (next != null) {
                usedIds.add(next.id)
                result.add(QuickReplyItem(
                    label    = next.title,
                    id       = next.id,
                    category = next.category ?: item.category,
                    icon     = item.icon
                ))
            } else {
                result.add(item)
            }
        }
        return result
    }

    private fun rebuildSlotMap() {
        slotMap.clear()
        items.take(6).forEachIndexed { index, item ->
            item.id?.let { slotMap[it] = index }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SessionListManager
//   Mirrors HistoryScreen sessions list.
// ─────────────────────────────────────────────────────────────────────────────

class SessionListManager {

    var sessions = listOf<ChatSession>()
        private set

    private var collectJob: Job? = null

    /**
     * Observe liên tục — dùng khi khởi động service
     */
    fun observe(chatRepository: ChatRepository, scope: CoroutineScope) {
        collectJob?.cancel()
        collectJob = scope.launch {
            try {
                chatRepository.getSessionsFlow().collect { list ->
                    withContext(Dispatchers.Main) {
                        sessions = list.sortedByDescending { it.updatedAt }
                    }
                }
            } catch (e: Exception) {
                Log.e("SESSION_MANAGER", "observe: ${e.message}")
            }
        }
    }

    /**
     * Lấy một lần duy nhất (first()) — dùng khi cần await kết quả xong mới tiếp tục
     */
    suspend fun reload(chatRepository: ChatRepository) {
        try {
            val list = chatRepository.getSessionsFlow().firstOrNull() ?: return
            withContext(Dispatchers.Main) {
                sessions = list.sortedByDescending { it.updatedAt }
            }
        } catch (e: Exception) {
            Log.e("SESSION_MANAGER", "reload: ${e.message}")
        }
    }

    // Giữ load(scope) cũ để không break chỗ khác
    fun load(chatRepository: ChatRepository, scope: CoroutineScope? = null) {
        if (scope != null) observe(chatRepository, scope)
    }
}