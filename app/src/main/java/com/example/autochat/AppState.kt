package com.example.autochat

import com.example.autochat.domain.model.ChatSession
import com.example.autochat.ui.car.MyChatScreen
import com.example.autochat.ui.car.VoiceSearchScreen

object AppState {
    var serverBaseUrl : String = "http://192.168.1.118:8001"
    var chatScreen: MyChatScreen? = null
    var voiceScreen: VoiceSearchScreen? = null
    var currentSession: ChatSession? = null
    var currentSessionId: String? = null
        set(value) {
            val old = field  // gán ra biến local trước
            field = value
            android.util.Log.e("AppState", "currentSessionId changed: $old → $value", Exception("stacktrace"))
        }
    // Auth
    var accessToken: String? = null
    var refreshToken: String? = null
    var currentUserId: String = "local"
    var username: String = ""
    var streamingSessionId: String? = null      // session đang streaming
    var streamingContent: String = ""
    var currentEndpoint: String = "news"
    val isLoggedIn: Boolean get() = !accessToken.isNullOrEmpty()
    var isDriving: Boolean = false
        private set  // Chỉ cho phép cập nhật nội bộ qua method

    // ✅ Method để cập nhật trạng thái lái xe
    fun updateDrivingState(driving: Boolean) {
        if (isDriving != driving) {
            isDriving = driving
            android.util.Log.d("APPSTATE", "🚗 Driving state changed: $isDriving")

            // Notify tất cả screens đang active
            chatScreen?.onDrivingStateChanged(isDriving)
        }
    }
    fun logout() {
        accessToken = null
        refreshToken = null
        currentUserId = "local"
        username = ""
        currentSession = null
        chatScreen = null
        voiceScreen = null
        android.util.Log.e("APPSTATE", "Logout - state cleared")
    }

    // ✅ Thêm method để log state
    fun logState() {
        android.util.Log.e("APPSTATE", "======= AppState =======")
        android.util.Log.e("APPSTATE", "userId: $currentUserId")
        android.util.Log.e("APPSTATE", "username: $username")
        android.util.Log.e("APPSTATE", "accessToken: ${accessToken?.take(20)}...")
        android.util.Log.e("APPSTATE", "========================")
    }
}