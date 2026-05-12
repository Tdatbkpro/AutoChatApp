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

    // Messages từ DB
    private val dbMessages = mutableListOf<Message>()

    // Trạng thái chờ bot
    private var isWaitingBot = false
    private var botCountWhenSent = 0
    private var pendingUserText: String? = null

    init {
        AppState.currentEndpoint = endpoint

        if (pendingMessage != null) {
            isWaitingBot = true
            botCountWhenSent = 0
            pendingUserText = pendingMessage
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
                if (!sessionId.startsWith("pending_") && !isObserving) {
                    startObserving()
                }
                invalidate()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (chatScreen.currentChatSessionScreen === this@ChatSessionScreen) {
                    chatScreen.currentChatSessionScreen = null
                }
                stopObserving()
                scope.cancel()
            }
        })
    }

    private fun startObserving() {
        if (isObserving) return
        isObserving = true

        observeJob?.cancel()
        observeJob = scope.launch {
            chatRepository.getMessagesFlow(sessionId).collect { list ->
                withContext(Dispatchers.Main) {
                    dbMessages.clear()
                    dbMessages.addAll(list)

                    if (isWaitingBot) {
                        val newBotCount = list.count { it.sender == "bot" }
                        if (newBotCount > botCountWhenSent) {
                            isWaitingBot = false
                            pendingUserText = null
                        }
                    }

                    invalidate()
                }
            }
        }
    }

    private fun stopObserving() {
        observeJob?.cancel()
        observeJob = null
        isObserving = false
    }

    fun addPendingMessage(text: String) {
        botCountWhenSent = dbMessages.count { it.sender == "bot" }
        pendingUserText = text
        isWaitingBot = true
        invalidate()
    }

    fun onSessionReady(realSessionId: String, botMessage: Message) {
        val wasPlaceholder = sessionId.startsWith("pending_")
        sessionId = realSessionId

        if (wasPlaceholder) {
            AppState.currentSession = AppState.currentSession?.copy(id = realSessionId)
            botCountWhenSent = 0
            isObserving = false
            observeJob?.cancel()
            startObserving()
        } else {
            // Hỏi tiếp: bot đã reply rồi, tắt spinner luôn
            isWaitingBot = false
            pendingUserText = null
            invalidate()
        }

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
                isWaitingBot = false
                pendingUserText = null
                invalidate()
                chatScreen.speakText(botMessage.content)
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        val endpointLabel = if (endpoint == "ask") "💬 Chat" else "📰 Tin tức"

        if (dbMessages.isEmpty() && !isWaitingBot) {
            listBuilder.setNoItemsMessage("Chưa có tin nhắn nào")
        }

        // Hiện DB messages
        dbMessages.takeLast(6).forEach { msg ->
            val time = formatTime(msg.timestamp)
            when {
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

        // Hiện temp messages ở cuối khi đang chờ bot
        if (isWaitingBot) {
            // User temp — chỉ hiện nếu chưa có trong DB
            pendingUserText?.let { text ->
                val alreadyInDb = dbMessages.any { it.sender == "user" && it.content == text }
                if (!alreadyInDb) {
                    listBuilder.addItem(
                        Row.Builder()
                            .setTitle("👤 Bạn")
                            .addText(text.take(100))
                            .build()
                    )
                }
            }
            // Bot spinner
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🤖 AI đang xử lý...")
                    .addText("⏳ Vui lòng chờ trong giây lát")
                    .build()
            )
        }

        val actionStripBuilder = ActionStrip.Builder()

        if (!isWaitingBot) {
            actionStripBuilder.addAction(
                Action.Builder()
                    .setTitle("🎤 Hỏi tiếp")
                    .setOnClickListener { openVoice() }
                    .build()
            )
        }

        actionStripBuilder.addAction(
            Action.Builder()
                .setIcon(
                    CarIcon.Builder(
                        IconCompat.createWithResource(carContext, R.drawable.ic_home)
                    ).build()
                )
                .setOnClickListener { screenManager.popToRoot() }
                .build()
        )

        val actionStrip = actionStripBuilder.build()

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
        chatScreen.currentChatSessionScreen = this
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
                    .addAction(
                        Action.Builder().setTitle("✅ Có")
                            .setOnClickListener {
                                screenManager.pop()
                                chatScreen.navigateTo(navQuery, displayName)
                            }.build()
                    )
                    .addAction(
                        Action.Builder().setTitle("❌ Không")
                            .setOnClickListener { screenManager.pop() }.build()
                    )
                    .build()
        })
    }

    private fun formatTime(ts: Long) = try {
        val sdf = SimpleDateFormat("HH:mm", Locale("vi"))
        sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        sdf.format(Date(ts))
    } catch (_: Exception) {
        "--:--"
    }
}