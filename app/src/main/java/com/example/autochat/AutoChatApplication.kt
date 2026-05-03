package com.example.autochat

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.autochat.domain.repository.AuthRepository
import com.example.autochat.domain.repository.HasCodeExecutor


@HiltAndroidApp
class AutoChatApplication : Application(), HasCodeExecutor
{

    @Inject
    lateinit var authRepository: AuthRepository

    // ✅ Inject vào property riêng, override bằng getter
    @Inject
    lateinit var _codeExecutor: CodeExecutor

    override val codeExecutor: CodeExecutor
        get() = _codeExecutor

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                authRepository.getCurrentUserFlow().collect { user ->
                    if (user != null && user.accessToken.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            AppState.currentUserId = user.id
                            AppState.username      = user.username
                            AppState.accessToken   = user.accessToken
                            AppState.refreshToken  = user.refreshToken
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("APP", "Error restoring user: ${e.message}")
            }
        }
    }
}