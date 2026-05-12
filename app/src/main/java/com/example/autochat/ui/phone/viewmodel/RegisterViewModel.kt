package com.example.autochat.ui.phone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autochat.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        // OTP đã gửi → navigate sang OtpFragment
        data class OtpSent(val email: String) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun sendRegisterOtp(email: String, username: String, password: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                authRepository.sendOtp(email, "register")
                _uiState.value = UiState.OtpSent(email)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(parseError(e.message))
            }
        }
    }

    fun resetState() { _uiState.value = UiState.Idle }

    private fun parseError(msg: String?) = when {
        msg?.contains("400") == true -> "Email đã được đăng ký"
        msg?.contains("timeout") == true -> "Không thể kết nối server"
        else -> "Có lỗi xảy ra, vui lòng thử lại"
    }
}