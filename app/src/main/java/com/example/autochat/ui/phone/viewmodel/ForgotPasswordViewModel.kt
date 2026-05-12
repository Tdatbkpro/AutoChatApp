package com.example.autochat.ui.phone.adapter.com.example.autochat.ui.phone.viewmodel

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
class ForgotPasswordViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        data class OtpSent(val email: String) : UiState()
        object ResetSuccess : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun sendResetOtp(email: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                authRepository.sendOtp(email, "reset_password")
                _uiState.value = UiState.OtpSent(email)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    if (e.message?.contains("400") == true)
                        "Email chưa được đăng ký"
                    else "Có lỗi xảy ra, vui lòng thử lại"
                )
            }
        }
    }

    fun resetPassword(email: String, resetToken: String, newPassword: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                authRepository.resetPassword(email, resetToken, newPassword)
                _uiState.value = UiState.ResetSuccess
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    if (e.message?.contains("400") == true)
                        "OTP không hợp lệ hoặc đã hết hạn"
                    else "Có lỗi xảy ra, vui lòng thử lại"
                )
            }
        }
    }

    fun resetState() { _uiState.value = UiState.Idle }
}