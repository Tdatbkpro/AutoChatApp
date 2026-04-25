package com.example.autochat.ui.phone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Observe sessions flow
        viewModelScope.launch {
            chatRepository.getSessionsFlow().collect { sessionList ->
                _sessions.value = sessionList
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                chatRepository.deleteSession(sessionId)
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Không thể xóa: ${e.message}"
                android.util.Log.e("HistoryViewModel", "Delete error: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                chatRepository.updateSessionTitle(sessionId, newTitle)
                _error.value = null
                android.util.Log.d("HistoryViewModel", "Renamed session $sessionId to $newTitle")
            } catch (e: Exception) {
                _error.value = "Không thể đổi tên: ${e.message}"
                android.util.Log.e("HistoryViewModel", "Rename error: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun togglePinSession(sessionId: String) {
        viewModelScope.launch {
            try {
                chatRepository.togglePinSession(sessionId)
                _error.value = null
                android.util.Log.d("HistoryViewModel", "Toggled pin for session $sessionId")
            } catch (e: Exception) {
                _error.value = "Lỗi: ${e.message}"
                android.util.Log.e("HistoryViewModel", "Pin toggle error: ${e.message}")
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}