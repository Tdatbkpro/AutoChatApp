package com.example.autochat.ui.phone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.autochat.domain.repository.AuthRepository
import com.example.autochat.domain.repository.VerifyOtpResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OtpViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        // Register xong → về Login
        object RegisterSuccess : UiState()
        // Reset password OTP xong → sang đặt mật khẩu mới, truyền otp đã verify
        data class ResetOtpVerified(val email: String, val resetToken: String) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun verifyOtp(email: String, purpose: String, otp: String,
                  username: String = "", password: String = "") {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val result = authRepository.verifyOtp(email, purpose, otp)
                when {
                    result is VerifyOtpResult.Success && purpose == "register" -> {
                        authRepository.register(email, username, password)
                        _uiState.value = UiState.RegisterSuccess
                    }
                    result is VerifyOtpResult.Success && purpose == "reset_password" -> {
                        _uiState.value = UiState.ResetOtpVerified(
                            email = email,
                            resetToken = result.resetToken ?: ""  // ← Lấy resetToken
                        )
                    }
                    result is VerifyOtpResult.Error -> {
                        _uiState.value = UiState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    if (e.message?.contains("400") == true) "OTP không đúng hoặc đã hết hạn"
                    else "Có lỗi xảy ra, vui lòng thử lại"
                )
            }
        }
    }

    fun resendOtp(email: String, purpose: String) {
        viewModelScope.launch {
            try { authRepository.sendOtp(email, purpose) } catch (_: Exception) {}
        }
    }

    fun resetState() { _uiState.value = UiState.Idle }
}