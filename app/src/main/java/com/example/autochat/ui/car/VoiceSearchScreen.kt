package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.AppState

class VoiceSearchScreen(
    carContext: CarContext,
    private val chatScreen: MyChatScreen,
    private val botMessage: String?,
    private val onFinished: (() -> Unit)? = null
) : Screen(carContext) {

    private var displayText = "Dang nghe...\nHay noi cau hoi cua ban"
    private var useVoiceMode = true
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var lastInvalidateTime = 0L
    private val MIN_INVALIDATE_INTERVAL = 1000L

    init {
        AppState.voiceScreen = this
        startVoiceService()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                AppState.voiceScreen = null
                stopVoiceService()
            }
        })
    }

    override fun onGetTemplate(): Template {
        return if (useVoiceMode) buildVoiceTemplate()
        else buildSearchTemplate()
    }

    private fun buildVoiceTemplate(): Template {
        return MessageTemplate.Builder(displayText)
            .setTitle("Đang nghe")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Bàn Phím")
                    .setOnClickListener {
                        useVoiceMode = false
                        stopVoiceService()
                        safeInvalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Thu lại")
                    .setOnClickListener {
                        displayText = "Dang nghe...\nHay noi cau hoi cua ban"
                        safeInvalidate()
                        stopVoiceService()
                        handler.postDelayed({ startVoiceService() }, 300)
                    }
                    .build()
            )
            .build()
    }

    private fun buildSearchTemplate(): Template {
        return SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) {}
                override fun onSearchSubmitted(searchText: String) {
                    if (searchText.isNotBlank()) {
                        sendAndNavigate(searchText)
                    }
                }
            }
        )
            .setInitialSearchText("")
            .setSearchHint("Gõ hoặc ấn mic nói câu hỏi...")
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(true)
            .build()
    }

    private fun safeInvalidate() {
        val now = System.currentTimeMillis()
        if (now - lastInvalidateTime >= MIN_INVALIDATE_INTERVAL) {
            lastInvalidateTime = now
            invalidate()
        }
    }

    fun updateStatus(status: String) {
        handler.post {
            android.util.Log.e("VOICE_SCREEN", "updateStatus: $status")
            displayText = status
            safeInvalidate()
        }
    }

    fun updatePartial(text: String) {
        handler.post {
            android.util.Log.e("VOICE_SCREEN", "updatePartial: $text")
            if (text.isNotBlank()) {
                displayText = "Dang nghe...\n\"$text\""
                safeInvalidate()
            }
        }
    }

    fun onVoiceResult(text: String) {
        handler.post {
            if (text.isNotBlank()) {
                sendAndNavigate(text)
            } else {
                screenManager.pop()
            }
            onFinished?.invoke()
        }
    }

    fun onTimeout() {
        handler.post {
            screenManager.pop()
            onFinished?.invoke()
        }
    }

    /**
     * Điểm duy nhất xử lý "có kết quả" — dù từ voice hay keyboard.
     *
     * Thứ tự QUAN TRỌNG:
     * 1. Kiểm tra đang trong ChatSessionScreen chưa
     * 2a. NẾU đang trong session → addPendingMessage ngay, rồi pop VoiceScreen,
     *     API call chạy ngầm trong MyChatScreen
     * 2b. NẾU chưa có session → push ChatSessionScreen (với pendingMessage),
     *     rồi pop VoiceScreen khỏi stack (ChatSession ở dưới sẽ hiện),
     *     API call chạy ngầm
     *
     * KHÔNG gọi screenManager.pop() trước addUserMessage vì push/pop
     * trên cùng một ScreenManager là synchronous với back-stack —
     * pop trước sẽ khiến push sau đổ lên sai màn hình.
     */
    private fun sendAndNavigate(text: String) {
        val existingSession = chatScreen.currentChatSessionScreen

        if (existingSession != null) {
            // ── Đang trong ChatSessionScreen ──────────────────────────────
            // 1. Hiện pending ngay trên session screen
            existingSession.addPendingMessage(text)
            // 2. Pop voice screen → user thấy ChatSessionScreen với spinner
            screenManager.pop()
            // 3. Gửi API (không cần push screen mới)
            chatScreen.sendMessageOnly(text, botMessage)
        } else {
            // ── Chưa có session, đang ở HomeScreen ───────────────────────
            val endpoint = AppState.currentEndpoint
            val sessionId = AppState.currentSession?.id ?: "pending_${System.currentTimeMillis()}"

            // 1. Tạo ChatSessionScreen với pending message hiển thị ngay
            val sessionScreen = ChatSessionScreen(
                carContext     = carContext,
                chatScreen     = chatScreen,
                sessionId      = sessionId,
                endpoint       = endpoint,
                pendingMessage = text
            )
            chatScreen.currentChatSessionScreen = sessionScreen

            // 2. Push ChatSessionScreen (user thấy pending ngay lập tức)
            screenManager.pop()

            // PUSH SAU - thêm ChatSessionScreen
            screenManager.push(sessionScreen)

            // 4. Gửi API ngầm
            chatScreen.sendMessageOnly(text, botMessage)
        }
    }

    private fun startVoiceService() {
        val intent = android.content.Intent(carContext, com.example.autochat.service.VoiceService::class.java).apply {
            action = com.example.autochat.service.VoiceService.ACTION_START
        }
        carContext.startForegroundService(intent)
    }

    private fun stopVoiceService() {
        carContext.stopService(
            android.content.Intent(carContext, com.example.autochat.service.VoiceService::class.java)
        )
    }
}