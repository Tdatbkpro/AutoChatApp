package com.example.autochat.ui.car

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.AppState
import com.example.autochat.di.ChatEntryPoint
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

class MyChatScreen(carContext: CarContext) : Screen(carContext), TextToSpeech.OnInitListener {

    private val chatRepository: ChatRepository by lazy {
        EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            ChatEntryPoint::class.java
        ).chatRepository()
    }

    private val messages = mutableListOf<Message>()
    private var tts: TextToSpeech = TextToSpeech(carContext, this)
    private var ttsReady = false
    private var pendingSpeak: String? = null
    private var isMicOpen = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentObserveJob: kotlinx.coroutines.Job? = null
    private var isSending = false
    private var showMessageOptions = false
    private var selectedMessage: Message? = null

    init {
        AppState.chatScreen = this

        AppState.currentSession?.let { session ->
            observeMessages(session.id)
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                tts.stop()
                tts.shutdown()
                scope.cancel()
            }
        })

    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("vi", "VN")
            ttsReady = true
            pendingSpeak?.let { speakText(it); pendingSpeak = null }
        }
    }

    override fun onGetTemplate(): Template {
        return when {
            showMessageOptions && selectedMessage != null -> buildMessageOptionsTemplate()
            else -> buildChatTemplate()
        }
    }

    private fun buildChatTemplate(): Template {
        val itemListBuilder = ItemList.Builder()

        if (messages.isEmpty()) {
            itemListBuilder.setNoItemsMessage("🎤 Nhấn nút Micro để bắt đầu trò chuyện")
        } else {
            // Header thông tin
            if (messages.isNotEmpty()) {
                val sessionTitle = AppState.currentSession?.title ?: "Chat mới"
                val messageCount = messages.size

                itemListBuilder.addItem(
                    Row.Builder()
                        .setTitle("💬 Đang trò chuyện với : ")
                        .addText("Chủ đề : $sessionTitle")
                        .addText("$messageCount tin nhắn")
                        .build()
                )
            }

            // Hiển thị 6 tin nhắn gần nhất
            messages.takeLast(6).forEach { msg ->
                val timeStr = formatTime(msg.timestamp)

                if (msg.sender == "bot") {
                    // Tin nhắn Bot - Nhấn để đọc
                    itemListBuilder.addItem(
                        Row.Builder()
                            .setTitle("🤖 Bot • $timeStr")
                            .addText(msg.content)
                            .setOnClickListener {
                                if (!isMicOpen) {
                                    speakText(msg.content)
                                    CarToast.makeText(carContext, "🔊 Đang đọc...", CarToast.LENGTH_SHORT).show()
                                }
                            }
                            .build()
                    )
                } else {
                    // Tin nhắn User - Nhấn để hiện menu xóa
                    val msgIndex = messages.indexOf(msg)
                    val nextBotMsg = messages.getOrNull(msgIndex + 1)
                        ?.takeIf { it.sender == "bot" }

                    itemListBuilder.addItem(
                        Row.Builder()
                            .setTitle("👤 Bạn • $timeStr")
                            .addText(msg.content)
                            .setOnClickListener {
                                selectedMessage = msg
                                showMessageOptions = true
                                invalidate()
                            }
                            .build()
                    )
                }
            }
        }

        // Footer hướng dẫn
        if (messages.isNotEmpty()) {
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle("Hướng dẫn")
                    .addText("• Nhấn tin nhắn Bot để đọc lại")
                    .addText("• Nhấn tin nhắn của bạn để xóa")
                    .build()
            )
        }

        // Action Strip
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(
                                carContext, android.R.drawable.ic_menu_recent_history
                            )
                        ).build()
                    )
                    .setOnClickListener {
                        screenManager.push(HistoryScreen(carContext, this))
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("🎤 Nói")
                    .setOnClickListener {
                        isMicOpen = true
                        tts.stop()
                        handler.postDelayed({ isMicOpen = false }, 15000)
                        handler.postDelayed({
                            val vs = VoiceSearchScreen(carContext, this@MyChatScreen)
                            AppState.voiceScreen = vs
                            screenManager.push(vs)
                        }, 500)
                    }
                    .build()
            )
            .build()

        val title = when {
            messages.isEmpty() -> "💬 AI Chatbot"
            else -> "💬 ${AppState.currentSession?.title?.take(20) ?: "AI Chatbot"}"
        }

        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }

    private fun buildMessageOptionsTemplate(): Template {
        val msg = selectedMessage ?: return buildChatTemplate()

        val optionsMsg = buildString {
            append("Tin nhắn của bạn:\n")
            append("${msg.content.take(80)}")
            if (msg.content.length > 80) append("...")
            append("\n\nChọn hành động:")
        }

        return MessageTemplate.Builder(optionsMsg)
            .setTitle("⚙Tùy chọn tin nhắn")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Đọc to")
                    .setOnClickListener {
                        speakText(msg.content)
                        CarToast.makeText(carContext, "Đang đọc...", CarToast.LENGTH_SHORT).show()
                        showMessageOptions = false
                        selectedMessage = null
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Xóa tin nhắn")
                    .setOnClickListener {
                        deleteUserMessage(msg)
                    }
                    .build()
            )
            .build()
    }

    private fun deleteUserMessage(message: Message) {
        // Tìm bot message response (tin nhắn kế tiếp nếu là bot)
        val msgIndex = messages.indexOf(message)
        val nextBotMsg = messages.getOrNull(msgIndex + 1)
            ?.takeIf { it.sender == "bot" }

        scope.launch {
            try {
                val sessionId = AppState.currentSession?.id ?: return@launch

                chatRepository.deleteMessagePair(
                    sessionId = sessionId,
                    userMessageId = message.id,
                    botMessageId = nextBotMsg?.id
                )

                CarToast.makeText(carContext, "Đã xóa tin nhắn", CarToast.LENGTH_SHORT).show()

                showMessageOptions = false
                selectedMessage = null

            } catch (e: Exception) {
                android.util.Log.e("CHAT", "deleteMessage error: ${e.message}")
                CarToast.makeText(carContext, "Lỗi xóa tin nhắn!", CarToast.LENGTH_SHORT).show()
                showMessageOptions = false
                selectedMessage = null
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
                SimpleDateFormat("dd/MM HH:mm", Locale("vi"))
            }
            sdf.format(date)
        } catch (e: Exception) {
            "--:--"
        }
    }

    fun addUserMessage(text: String) {
        if (isSending) {
            android.util.Log.e("CHAT", "Already sending, ignore")
            CarToast.makeText(carContext, "⏳ Đang gửi, vui lòng đợi...", CarToast.LENGTH_SHORT).show()
            return
        }
        isMicOpen = false
        isSending = true

        // Hiển thị user message ngay
        val tempId = "temp_${System.currentTimeMillis()}"
        messages.add(
            Message(
                id = tempId,
                sessionId = AppState.currentSession?.id ?: "",
                content = text,
                sender = "user",
                timestamp = System.currentTimeMillis()
            )
        )
        invalidate()

        scope.launch {
            try {
                val result = chatRepository.sendMessage(
                    sessionId = AppState.currentSession?.id,
                    content = text
                )

                // Cập nhật session và observe messages
                if (AppState.currentSession?.id != result.sessionId) {
                    AppState.currentSession = com.example.autochat.domain.model.ChatSession(
                        id = result.sessionId,
                        userId = AppState.currentUserId,
                        title = result.sessionTitle,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    observeMessages(result.sessionId)
                }

                // Xóa temp message
                messages.removeAll { it.id == tempId }

                // Đọc bot message
                speakText(result.botMessage.content)

            } catch (e: Exception) {
                android.util.Log.e("CHAT", "sendMessage error: ${e.message}")
                // Xóa temp, hiện thông báo lỗi
                messages.removeAll { it.id == tempId }
                messages.add(
                    Message(
                        id = "error_${System.currentTimeMillis()}",
                        sessionId = AppState.currentSession?.id ?: "",
                        content = " Lỗi kết nối server. Vui lòng thử lại!",
                        sender = "bot",
                        timestamp = System.currentTimeMillis()
                    )
                )
                CarToast.makeText(carContext, "Lỗi kết nối, thử lại sau!", CarToast.LENGTH_SHORT).show()
                invalidate()
            } finally {
                isSending = false
            }
        }
    }

    fun observeMessages(sessionId: String) {
        currentObserveJob?.cancel()
        currentObserveJob = scope.launch {
            chatRepository.getMessagesFlow(sessionId).collect { list ->
                messages.clear()
                messages.addAll(list)
                invalidate()
            }
        }
    }

    fun clearMessages() {
        messages.clear()
        AppState.currentSession = null
        isSending = false
        showMessageOptions = false
        selectedMessage = null
        invalidate()
    }

    fun loadSession(sessionId: String) {
        currentObserveJob?.cancel()

        scope.launch {


            currentObserveJob = scope.launch {
                chatRepository.getMessagesFlow(sessionId).collect { list ->
                    messages.clear()
                    messages.addAll(list)
                    invalidate()
                }
            }
        }
    }

    fun speakText(text: String) {
        if (isMicOpen) return
        if (!ttsReady) {
            pendingSpeak = text
            return
        }

        val audioManager = carContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusRequest = android.media.AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
            .setAudioAttributes(audioAttributes)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { }
            .build()
        audioManager.requestAudioFocus(focusRequest)
        tts.setAudioAttributes(audioAttributes)

        val params = android.os.Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        handler.postDelayed({
            if (!isMicOpen) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "tts_${System.currentTimeMillis()}")
            }
        }, 300)
    }
}