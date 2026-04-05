package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.AppState
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.domain.model.Message
import com.example.autochat.domain.repository.ChatRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryDetailScreen(
    carContext: CarContext,
    private var session: ChatSession,
    private val chatScreen: MyChatScreen
) : Screen(carContext) {

    private val chatRepository: ChatRepository by lazy {
        EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            ChatEntryPoint::class.java
        ).chatRepository()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var messages = listOf<Message>()
    private var page = 0
    private val pageSize = 4
    private var isDeleting = false
    private var isRenaming = false
    private var showDeleteConfirm = false
    private var showRenameDialog = false
    private var newTitle: String = ""
    private var deleteTargetUserMsgId: String? = null
    private var deleteBotMsgId: String? = null
    private var showMessageOptions = false
    private var selectedUserMessage: Message? = null
    private var selectedBotMessage: Message? = null

    init {
        loadMessages()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
        })
    }

    private fun loadMessages() {
        scope.launch {
            chatRepository.getMessagesFlow(session.id).collect { list ->
                messages = list
                invalidate()
            }
        }
    }

    private fun formatTime(timestamp: Long): String {
        return try {
            val date = Date(timestamp)
            val now = Date()
            val sdf = if (date.year == now.year && date.month == now.month && date.date == now.date) {
                SimpleDateFormat("HH:mm", Locale("vi"))
            } else {
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi"))
            }
            sdf.format(date)
        } catch (e: Exception) {
            "--:--"
        }
    }

    override fun onGetTemplate(): Template {
        return when {
            showRenameDialog -> buildRenameSearchTemplate()  // ✅ THÊM DÒNG NÀY
            showDeleteConfirm -> buildDeleteConfirmTemplate()
            showMessageOptions && selectedUserMessage != null -> buildMessageOptionsTemplate()
            else -> buildMessageListTemplate()
        }
    }

    private fun buildMessageListTemplate(): Template {
        val itemListBuilder = ItemList.Builder()

        val totalPages = maxOf(1, (messages.size + pageSize - 1) / pageSize)
        val start = page * pageSize
        val end = minOf(start + pageSize, messages.size)
        val pageMessages = if (messages.isEmpty()) emptyList()
        else messages.subList(start, end)

        if (messages.isEmpty()) {
            itemListBuilder.setNoItemsMessage("Chưa có tin nhắn nào")
        }

        // Header thông tin
        if (page == 0 && messages.isNotEmpty()) {
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle("📌 Chủ đề: ${session.title}")
                    .addText("${messages.size} tin nhắn")
                    .setOnClickListener {
                        showRenameDialog = true
                        newTitle = session.title
                        invalidate()
                    }
                    .build()
            )
        }

        pageMessages.forEach { msg ->
            val timeStr = formatTime(msg.timestamp)

            if (msg.sender == "bot") {
                itemListBuilder.addItem(
                    Row.Builder()
                        .setTitle("🤖 Bot • $timeStr")
                        .addText(msg.content)
                        .setOnClickListener {
                            chatScreen.speakText(msg.content)
                            CarToast.makeText(carContext, "Đang đọc...", CarToast.LENGTH_SHORT).show()
                        }
                        .build()
                )
            } else {
                val msgIndex = messages.indexOf(msg)
                val nextBotMsg = messages.getOrNull(msgIndex + 1)
                    ?.takeIf { it.sender == "bot" }

                itemListBuilder.addItem(
                    Row.Builder()
                        .setTitle("👤 Bạn • $timeStr")
                        .addText(msg.content)
                        .setOnClickListener {
                            selectedUserMessage = msg
                            selectedBotMessage = nextBotMsg
                            showMessageOptions = true
                            invalidate()
                        }
                        .build()
                )
            }
        }

        // Điều hướng trang
        if (page > 0 || end < messages.size) {
            val navText = buildString {
                if (page > 0) append("◀ Trang trước")
                if (page > 0 && end < messages.size) append("   ")
                if (end < messages.size) append("Trang sau ▶")
            }

            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle(navText)
                    .setOnClickListener {
                        if (end < messages.size) page++
                        else if (page > 0) page--
                        invalidate()
                    }
                    .build()
            )
        }

        // Footer thông tin trang
        if (totalPages > 1) {
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle("📍 Trang ${page + 1}/$totalPages")
                    .build()
            )
        }

        // Action Strip - Thêm action sửa tên
        val actionStrip = ActionStrip.Builder()

            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(
                                carContext, android.R.drawable.ic_menu_delete
                            )
                        ).build()
                    )
                    .setOnClickListener {
                        deleteTargetUserMsgId = null
                        deleteBotMsgId = null
                        showDeleteConfirm = true
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(
                                carContext, android.R.drawable.ic_menu_send
                            )
                        ).build()
                    )
                    .setOnClickListener {
                        AppState.currentSession = session
                        chatScreen.loadSession(session.id)
                        screenManager.push(chatScreen)
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("💬 ${session.title.take(25)}")
            .setHeaderAction(Action.BACK)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }

    private fun buildRenameSearchTemplate(): Template {
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                // Không cần xử lý
            }

            override fun onSearchSubmitted(searchText: String) {
                val newTitle = searchText.trim()
                if (newTitle.isNotBlank() && newTitle != session.title) {
                    renameSession(newTitle)
                }
                showRenameDialog = false
                invalidate()
            }
        })
            .setHeaderAction(Action.BACK)  // ✅ Có setHeaderAction
            .setSearchHint("Nhập tên mới...")  // ✅ Có setSearchHint
            .setInitialSearchText(session.title)  // ✅ Có setInitialSearchText
            .setShowKeyboardByDefault(true)
            .build()
    }

    private fun buildMessageOptionsTemplate(): Template {
        val userMsg = selectedUserMessage ?: return buildMessageListTemplate()
        val botMsg = selectedBotMessage

        val optionsMsg = buildString {
            append("Tin nhắn của bạn:\n")
            append("${userMsg.content.take(80)}")
            if (userMsg.content.length > 80) append("...")
            append("\n\nChọn hành động:")
        }

        return MessageTemplate.Builder(optionsMsg)
            .setTitle("Tùy chọn tin nhắn")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Đọc to")
                    .setOnClickListener {
                        chatScreen.speakText(userMsg.content)
                        CarToast.makeText(carContext, "🔊 Đang đọc...", CarToast.LENGTH_SHORT).show()
                        showMessageOptions = false
                        selectedUserMessage = null
                        selectedBotMessage = null
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Xóa")
                    .setOnClickListener {
                        deleteTargetUserMsgId = userMsg.id
                        deleteBotMsgId = botMsg?.id
                        showDeleteConfirm = true
                        showMessageOptions = false
                        invalidate()
                    }
                    .build()
            )
            .build()
    }

    private fun buildDeleteConfirmTemplate(): Template {
        val isDeletePair = deleteTargetUserMsgId != null
        val confirmMsg = if (isDeletePair) {
            "Xóa câu hỏi này và phản hồi của bot?\n\nHành động này không thể hoàn tác!"
        } else {
            "Đoạn chat ${session.title.take(30)} sẽ xóa tất cả tin nhắn và không thể khôi phục!"
        }

        return MessageTemplate.Builder(confirmMsg)
            .setTitle(if (isDeletePair) "🗑 Xóa tin nhắn" else "🗑 Xóa đoạn chat")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Hủy")
                    .setOnClickListener {
                        showDeleteConfirm = false
                        deleteTargetUserMsgId = null
                        deleteBotMsgId = null
                        selectedUserMessage = null
                        selectedBotMessage = null
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Xóa")
                    .setOnClickListener {
                        if (isDeletePair) deleteMessagePair()
                        else deleteSession()
                    }
                    .build()
            )
            .build()
    }

    // Hàm đổi tên session
    private fun renameSession(newTitle: String) {
        if (isRenaming) return
        isRenaming = true

        scope.launch {
            try {
                chatRepository.updateSessionTitle(session.id, newTitle)
                session.title = newTitle

                CarToast.makeText(carContext, "✅ Đã đổi tên thành: $newTitle", CarToast.LENGTH_LONG).show()
                invalidate()
            } catch (e: Exception) {
                android.util.Log.e("RENAME", "renameSession error: ${e.message}")
                CarToast.makeText(carContext, "Lỗi đổi tên! Thử lại.", CarToast.LENGTH_SHORT).show()
            } finally {
                isRenaming = false
            }
        }
    }

    private fun refreshMessages() {
        scope.launch {
            chatRepository.getMessagesFlow(session.id).collect { list ->
                messages = list
                invalidate()
            }
        }
    }

    private fun deleteMessagePair() {
        if (isDeleting) return
        isDeleting = true
        val userMsgId = deleteTargetUserMsgId ?: return
        val botMsgId = deleteBotMsgId

        scope.launch {
            try {
                chatRepository.deleteMessagePair(
                    sessionId = session.id,
                    userMessageId = userMsgId,
                    botMessageId = botMsgId
                )

                CarToast.makeText(carContext, "🗑 Đã xóa tin nhắn", CarToast.LENGTH_SHORT).show()

                showDeleteConfirm = false
                deleteTargetUserMsgId = null
                deleteBotMsgId = null
                selectedUserMessage = null
                selectedBotMessage = null
                refreshMessages()

                val newTotalPages = maxOf(1, (messages.size + pageSize - 1) / pageSize)
                if (page >= newTotalPages) page = maxOf(0, newTotalPages - 1)

            } catch (e: Exception) {
                android.util.Log.e("DELETE", "deleteMessagePair error: ${e.message}")
                CarToast.makeText(carContext, "Lỗi xóa tin nhắn!", CarToast.LENGTH_SHORT).show()
                showDeleteConfirm = false
            } finally {
                isDeleting = false
                invalidate()
            }
        }
    }

    private fun deleteSession() {
        if (isDeleting) return
        isDeleting = true

        scope.launch {
            try {
                chatRepository.deleteSession(session.id)

                if (AppState.currentSession?.id == session.id) {
                    AppState.currentSession = null
                    chatScreen.clearMessages()
                }

                CarToast.makeText(carContext, "🗑 Đã xóa đoạn chat", CarToast.LENGTH_SHORT).show()
                screenManager.pop()

            } catch (e: Exception) {
                android.util.Log.e("DELETE", "deleteSession error: ${e.message}")
                CarToast.makeText(carContext, "Lỗi xóa. Thử lại!", CarToast.LENGTH_SHORT).show()
                isDeleting = false
                showDeleteConfirm = false
                invalidate()
            }
        }
    }
}