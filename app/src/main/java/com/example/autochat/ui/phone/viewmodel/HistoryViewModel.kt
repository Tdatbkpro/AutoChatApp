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

    init {
        // ✅ Observe từ Room (sẽ tự động cập nhật khi có thay đổi)
        viewModelScope.launch {
            chatRepository.getSessionsFlow().collect { sessionList ->
                _sessions.value = sessionList
            }
        }

    }



    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                chatRepository.deleteSession(sessionId)
                // ✅ Flow sẽ tự động cập nhật, không cần làm gì thêm
            } catch (e: Exception) {
                android.util.Log.e("HistoryViewModel", "Delete error: ${e.message}")
            }
        }
    }

}