package com.example.autochat.ui.phone.viewmodel

import android.util.Log
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
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        object Success : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                authRepository.login(email, password)
                _uiState.value = UiState.Success
            } catch (e: Exception) {
                _uiState.value = UiState.Error(parseError(e.message))
            }
        }
    }

    fun loginWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                authRepository.loginWithGoogle(idToken)
                _uiState.value = UiState.Success
            } catch (e: retrofit2.HttpException) {
                // ✅ Đọc error body từ server
                val errorBody = e.response()?.errorBody()?.string() ?: "No body"
                Log.e("Google error", "HTTP ${e.code()}: $errorBody")
                _uiState.value = UiState.Error("$errorBody")
            } catch (e: Exception) {
                Log.e("Google error", "${e.javaClass.simpleName}: ${e.message}")
                _uiState.value = UiState.Error("${e.message}")
            }
        }
    }

    fun resetState() { _uiState.value = UiState.Idle }

    private fun parseError(msg: String?) = when {
        msg?.contains("401") == true -> "Email hoặc mật khẩu không đúng"
        msg?.contains("403") == true -> "Tài khoản đã bị khoá"
        msg?.contains("timeout") == true -> "Không thể kết nối server"
        else -> "Có lỗi xảy ra, vui lòng thử lại"
    }
}