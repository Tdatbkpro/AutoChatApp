package com.example.autochat

import com.example.autochat.domain.model.ChatSession
import com.example.autochat.ui.car.MyChatScreen
import com.example.autochat.ui.car.VoiceSearchScreen

object AppState {
    var chatScreen: MyChatScreen? = null
    var voiceScreen: VoiceSearchScreen? = null
    var currentSession: ChatSession? = null
    var currentSessionId : String? = null
    // Auth
    var accessToken: String? = null
    var refreshToken: String? = null
    var currentUserId: String = "local"
    var username: String = ""

    val isLoggedIn: Boolean get() = !accessToken.isNullOrEmpty()

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