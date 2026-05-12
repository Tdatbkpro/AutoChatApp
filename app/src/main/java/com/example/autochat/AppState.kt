package com.example.autochat

import ai.onnxruntime.BuildConfig
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.ui.car.MyChatScreen
import com.example.autochat.ui.car.VoiceSearchScreen
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object AppState {
    var chatScreen: MyChatScreen? = null
    var currentBranchId: String? = null
    var isConnectServer: Boolean = false
    var voiceScreen: VoiceSearchScreen? = null
    var currentSession: ChatSession? = null
    var currentEndpoint: String = "news"
    var streamingSessionId: String? = null
    var streamingContent: String = ""

    var currentSessionId: String? = null
        set(value) {
            val old = field
            field = value
            // FIX: Chỉ log ở debug build
            if (BuildConfig.DEBUG) {
                android.util.Log.d("AppState", "currentSessionId: $old → $value")
            }
        }

    // FIX: AtomicReference đảm bảo thread-safety giữa OkHttp thread,
    // Main thread, IO thread khi đọc/ghi token đồng thời
    private val _accessToken  = AtomicReference<String?>(null)
    private val _refreshToken = AtomicReference<String?>(null)
    private val _currentUserId = AtomicReference("local")
    private val _username = AtomicReference("")
    private val _userEmail = AtomicReference("")
    private val _isDriving = AtomicBoolean(false)

    var accessToken: String?
        get() = _accessToken.get()
        set(value) { _accessToken.set(value) }

    var refreshToken: String?
        get() = _refreshToken.get()
        set(value) { _refreshToken.set(value) }

    var currentUserId: String
        get() = _currentUserId.get()
        set(value) { _currentUserId.set(value) }

    var username: String
        get() = _username.get()
        set(value) { _username.set(value) }

    var userEmail: String
        get() = _userEmail.get()
        set(value) { _userEmail.set(value) }

    val isLoggedIn: Boolean get() = !accessToken.isNullOrEmpty()

    var isDriving: Boolean
        get() = _isDriving.get()
        private set(value) { _isDriving.set(value) }

    // FIX: compareAndSet tránh notify thừa khi nhiều thread gọi cùng lúc
    fun updateDrivingState(driving: Boolean) {
        if (_isDriving.compareAndSet(!driving, driving)) {
            if (BuildConfig.DEBUG) android.util.Log.d("APPSTATE", "Driving: $driving")
            chatScreen?.onDrivingStateChanged(driving)
        }
    }

    val sessionExpired = MutableStateFlow(false)

    fun logout() {
        _accessToken.set(null)
        _refreshToken.set(null)
        _currentUserId.set("local")
        _username.set("")
        currentSession = null
        chatScreen = null
        voiceScreen = null
        sessionExpired.value = true
        if (BuildConfig.DEBUG) android.util.Log.d("APPSTATE", "Logged out")
    }

    // FIX: Không bao giờ log token đầy đủ, chỉ log prefix để debug
    fun logState() {
        if (!BuildConfig.DEBUG) return
        android.util.Log.d("APPSTATE", "userId=$currentUserId | token=${accessToken?.take(10)}…")
    }
}