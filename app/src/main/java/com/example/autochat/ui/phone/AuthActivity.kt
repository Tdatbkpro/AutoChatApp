package com.example.autochat.ui.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.example.autochat.R
import com.example.autochat.databinding.ActivityAuthBinding
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.repository.AuthRepository
import com.example.autochat.ui.phone.util.ThemePreference
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class AuthActivity : AppCompatActivity() {
    @Inject lateinit var themePreference: ThemePreference
    private val authRepository: AuthRepository by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext, ChatEntryPoint::class.java
        ).authRepository()
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        themePreference.applyOnStartup()
        setContentView(R.layout.activity_auth)
        checkAlreadyLoggedIn()
    }

    private fun checkAlreadyLoggedIn() {
        lifecycleScope.launch {
            val user = authRepository.getCurrentUserFlow().first()
            if (user == null) return@launch
            try {
                authRepository.refreshTokenIfNeeded()
                navigateToMain()
            } catch (e: Exception) {
                // token hết hạn → ở lại Auth
            }
        }
    }

    fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}