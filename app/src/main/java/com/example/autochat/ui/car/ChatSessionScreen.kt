package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.AppState
import com.example.autochat.R
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.model.Message
import com.example.autochat.domain.repository.ChatRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatSessionScreen(
    carContext: CarContext,
    private val chatScreen: MyChatScreen,
    private var sessionId: String,
    private val endpoint: String = AppState.currentEndpoint,
    private val pendingMessage: String? = null
) : Screen(carContext) {

    private val chatRepository: ChatRepository by lazy {
        EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            ChatEntryPoint::class.java
        ).chatRepository()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null
    private var isObserving = false

    private val dbMessages = mutableListOf<Message>()
    private val pendingMessages = mutableListOf<Message>()
    private var isWaitingBot = false

    // Snapshot của DB messages tại thời điểm gửi — để detect user msg mới đã vào DB
    private var pendingUserText: String? = null


    // Sửa displayMessages:
    private val displayMessages: List<Message> get() {
        val result = mutableListOf<Message>()
        result.addAll(dbMessages)
        for (pending in pendingMessages) {
            if (pending.id.contains("pending_user")) {
                val alreadyInDb = dbMessages.any {
                    it.sender == "user" && it.content == pending.content
                }
                android.util.Log.d("TYPING", "pending_user '${pending.content.take(20)}' alreadyInDb=$alreadyInDb")
                if (!alreadyInDb) result.add(pending)
            } else {
                result.add(pending)
            }
        }
        return result.sortedBy { it.timestamp }
    }

    init {
        AppState.currentEndpoint = endpoint

        if (pendingMessage != null) {
            isWaitingBot = true
            val now = System.currentTimeMillis()
            pendingMessages.add(Message(
                id = "pending_user_init",
                sessionId = sessionId,
                content = pendingMessage,
                sender = "user",
                timestamp = now
            ))
            pendingMessages.add(Message(
                id = "pending_bot_init",
                sessionId = sessionId,
                content = "⏳ AI đang trả lời...",
                sender = "bot",
                timestamp = now + 1
            ))
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppState.currentEndpoint = endpoint
                if (!sessionId.startsWith("pending_")) {
                    startObserving()
                }
            }

            override fun onResume(owner: LifecycleOwner) {
                AppState.currentEndpoint = endpoint
                if (!sessionId.startsWith("pending_")) {
                    startObserving()
                }
                // Khi quay lại screen (sau VoiceSearchScreen pop), re-render ngay
                invalidate()
            }

            override fun onStop(owner: LifecycleOwner) {
                stopObserving()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (chatScreen.currentChatSessionScreen === this@ChatSessionScreen) {
                    chatScreen.currentChatSessionScreen = null
                }
                scope.cancel()
            }
        })
    }

    private fun startObserving() {
        if (isObserving) return
        isObserving = true
        android.util.Log.d("ChatSessionScreen", "startObserving: $sessionId")

        observeJob?.cancel()
        observeJob = scope.launch {
            chatRepository.getMessagesFlow(sessionId).collect { list ->
                withContext(Dispatchers.Main) {
                    android.util.Log.d("ChatSessionScreen",
                        "DB update: ${list.size} messages, isWaiting=$isWaitingBot")

                    val previousDbSize = dbMessages.size
                    dbMessages.clear()
                    dbMessages.addAll(list)

                    // Trong startObserving(), thay toàn bộ đoạn xử lý isWaitingBot:

                    // Trong startObserving(), thay đoạn isWaitingBot:
                    if (isWaitingBot) {
                        val hasRealBotMsg = list.any {
                            it.sender == "bot" && !it.id.startsWith("pending_")
                        }

                        android.util.Log.d("TYPING", "hasRealBotMsg=$hasRealBotMsg, listSize=${list.size}, pendingSize=${pendingMessages.size}")

                        if (hasRealBotMsg) {
                            pendingMessages.removeAll { it.id.contains("pending_bot") }
                            isWaitingBot = false
                        }

                        val hasRealUserMsg = list.any {
                            it.sender == "user" &&
                                    !it.id.startsWith("pending_") &&
                                    it.content == pendingUserText
                        }
                        if (hasRealUserMsg) {
                            pendingMessages.removeAll { it.id.contains("pending_user") }
                            pendingUserText = null
                        }
                    }

                    invalidate()
                }
            }
        }
    }

    private fun stopObserving() {
        android.util.Log.d("ChatSessionScreen", "stopObserving")
        observeJob?.cancel()
        observeJob = null
        isObserving = false
    }

    /**
     * Gọi từ VoiceSearchScreen (hoặc bất kỳ nơi nào) khi user vừa gửi tin nhắn mới.
     * Hiện pending user + pending bot spinner ngay lập tức.
     */
    fun addPendingMessage(text: String) {
        android.util.Log.d("ChatSessionScreen", "addPendingMessage: $text")
        android.util.Log.d("TYPING", "addPendingMessage: $text")
        val now = System.currentTimeMillis()
        isWaitingBot = true
        pendingUserText = text

        pendingMessages.clear()
        pendingMessages.add(Message(
            id = "pending_user_$now",
            sessionId = sessionId,
            content = text,
            sender = "user",
            timestamp = now
        ))
        pendingMessages.add(Message(
            id = "pending_bot_$now",
            sessionId = sessionId,
            content = "⏳ AI đang trả lời...",
            sender = "bot",
            timestamp = now + 1
        ))

        // QUAN TRỌNG: Đảm bảo đang observe để nhận DB updates
        if (!sessionId.startsWith("pending_") && !isObserving) {
            startObserving()
        }
        invalidate()
    }

    /**
     * Gọi khi session mới được tạo (lần đầu chat) và bot đã reply.
     */
    fun onSessionReady(realSessionId: String, botMessage: Message) {
        android.util.Log.d("ChatSessionScreen", "onSessionReady: $realSessionId")

        val wasPlaceholder = sessionId.startsWith("pending_")
        sessionId = realSessionId
        isWaitingBot = true

        if (wasPlaceholder) {
            AppState.currentSession = AppState.currentSession?.copy(id = realSessionId)
        }

        // ✅ Force restart observing với sessionId mới
        isObserving = false  // reset để startObserving chạy được
        observeJob?.cancel()
        startObserving()

        val type = botMessage.extraData?.get("type") as? String
        when (type) {
            "news_list" -> {
                val extra = botMessage.extraData ?: run { invalidate(); return }
                invalidate()
                screenManager.push(NewsListScreen(carContext, chatScreen, extra))
            }
            "navigation" -> {
                val nav = botMessage.extraData?.get("navigation") as? Map<*, *>
                val navQuery = nav?.get("nav_query") as? String
                val displayName = nav?.get("target") as? String
                invalidate()
                if (navQuery != null && displayName != null) {
                    chatScreen.speakText(botMessage.content)
                    showNavigationConfirmDialog(navQuery, displayName)
                }
            }
            else -> {
                invalidate()
                chatScreen.speakText(botMessage.content)
            }
        }
    }

    override fun onGetTemplate(): Template {
        android.util.Log.d("ChatSessionScreen", """
            onGetTemplate:
            - dbMessages: ${dbMessages.size}
            - pendingMessages: ${pendingMessages.size}
            - displayMessages: ${displayMessages.size}
            - isWaitingBot: $isWaitingBot
        """.trimIndent())

        val listBuilder = ItemList.Builder()
        val endpointLabel = if (endpoint == "ask") "💬 Chat" else "📰 Tin tức"
        val msgs = displayMessages

        if (msgs.isEmpty()) {
            listBuilder.setNoItemsMessage("Chưa có tin nhắn nào")
        }

        // Ưu tiên: lấy db messages mới nhất + toàn bộ pending
        val dbPart = msgs.filter { !it.id.startsWith("pending_") }.takeLast(6)
        val pendingPart = msgs.filter { it.id.startsWith("pending_") }
        val toShow = (dbPart + pendingPart).takeLast(6)

        toShow.forEach { msg ->
            val time = formatTime(msg.timestamp)
            when {
                msg.id.startsWith("pending_user") -> {
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle("👤 Bạn · $time")
                            .addText(msg.content.take(100))
                            .build()
                    )
                }

                msg.id.startsWith("pending_bot") -> {
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle("🤖 AI đang xử lý...")
                            .addText("⏳ Vui lòng chờ trong giây lát")
                            .build()
                    )
                }

                msg.sender == "bot" -> {
                    val hasNews = (msg.extraData?.get("type") as? String) == "news_list"
                    if (hasNews) {
                        val count = (msg.extraData?.get("news_items") as? List<*>)?.size ?: 0
                        listBuilder.addItem(
                            Row.Builder()
                                .setTitle("🤖 Bot · $time")
                                .addText(msg.content.take(60))
                                .addText("📰 $count bài · Nhấn để xem")
                                .setBrowsable(true)
                                .setOnClickListener {
                                    val extra = msg.extraData ?: return@setOnClickListener
                                    screenManager.push(NewsListScreen(carContext, chatScreen, extra))
                                }
                                .build()
                        )
                    } else {
                        listBuilder.addItem(
                            Row.Builder()
                                .setTitle("🤖 Bot · $time")
                                .addText(msg.content.take(120))
                                .apply {
                                    if (msg.content.length > MessageDetailScreen.MIN_LENGTH_FOR_DETAIL)
                                        setBrowsable(true)
                                }
                                .setOnClickListener {
                                    if (msg.content.length > MessageDetailScreen.MIN_LENGTH_FOR_DETAIL) {
                                        screenManager.push(
                                            MessageDetailScreen(carContext, chatScreen, msg.content, time)
                                        )
                                    } else {
                                        chatScreen.speakText(msg.content)
                                    }
                                }
                                .build()
                        )
                    }
                }

                else -> {
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle("👤 Bạn · $time")
                            .addText(msg.content.take(100))
                            .build()
                    )
                }
            }
        }

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("🎤 Hỏi tiếp")
                    .setOnClickListener { openVoice() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(CarIcon.Builder(
                        IconCompat.createWithResource(carContext, R.drawable.ic_home)
                    ).build())
                    .setOnClickListener {
                        // ✅ Về HomeScreen thay vì HistoryScreen
                        screenManager.popToRoot()
                    }
                    .build()
            )
            .build()

        val title = buildString {
            append(endpointLabel)
            AppState.currentSession?.title?.take(20)?.let { append(" · $it") }
        }

        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }

    private fun openVoice() {
        screenManager.push(
            VoiceSearchScreen(
                carContext = carContext,
                chatScreen = chatScreen,
                botMessage = null
            )
        )
    }

    private fun showNavigationConfirmDialog(navQuery: String, displayName: String) {
        screenManager.push(object : Screen(carContext) {
            override fun onGetTemplate(): Template =
                MessageTemplate.Builder("Bạn có muốn chỉ đường đến\n\"$displayName\" không?")
                    .setTitle("🗺️ Chỉ đường")
                    .setHeaderAction(Action.BACK)
                    .addAction(Action.Builder().setTitle("✅ Có")
                        .setOnClickListener {
                            screenManager.pop()
                            chatScreen.navigateTo(navQuery, displayName)
                        }.build())
                    .addAction(Action.Builder().setTitle("❌ Không")
                        .setOnClickListener { screenManager.pop() }.build())
                    .build()
        })
    }

    private fun formatTime(ts: Long) = try {
        SimpleDateFormat("HH:mm", Locale("vi")).format(Date(ts))
    } catch (_: Exception) { "--:--" }
}