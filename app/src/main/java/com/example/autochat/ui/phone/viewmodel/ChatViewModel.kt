package com.example.autochat.ui.phone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autochat.AppState
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.domain.model.Message
import com.example.autochat.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val sessionTitle: String = ""
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentSessionId: String? = null

    fun loadSession(sessionId: String) {
        android.util.Log.d("ChatViewModel", "loadSession: $sessionId")
        currentSessionId = sessionId

        // ✅ Sync messages từ backend
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)

                _uiState.value = _uiState.value.copy(isLoading = false)
                android.util.Log.d("ChatViewModel", "Sync completed")
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Sync error: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Lỗi sync: ${e.message}"
                )
            }
        }

        // ✅ Observe messages từ Room
        viewModelScope.launch {
            chatRepository.getMessagesFlow(sessionId).collect { messages ->
                android.util.Log.d("ChatViewModel", "Messages collected: ${messages.size}")
                _uiState.value = _uiState.value.copy(messages = messages)
            }
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val result = chatRepository.sendMessage(
                    sessionId = currentSessionId,
                    content = content
                )

                android.util.Log.d("ChatViewModel", "Message sent, sessionId: ${result.sessionId}")

                // Cập nhật session ID nếu mới
                if (currentSessionId != result.sessionId) {
                    currentSessionId = result.sessionId
                    AppState.currentSession = ChatSession(
                        id = result.sessionId,
                        userId = AppState.currentUserId,
                        title = result.sessionTitle,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    sessionTitle = result.sessionTitle
                )
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Send error: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Lỗi: ${e.message}"
                )
            }
        }
    }

    fun startNewChat() {
        currentSessionId = null
        _uiState.value = ChatUiState()
        AppState.currentSession = null
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}