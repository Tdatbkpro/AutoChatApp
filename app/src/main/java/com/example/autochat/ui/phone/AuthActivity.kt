package com.example.autochat.ui.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import com.example.autochat.R
import com.example.autochat.databinding.ActivityAuthBinding
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.repository.AuthRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val authRepository: AuthRepository by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            ChatEntryPoint::class.java
        ).authRepository()
    }

    private var isLoginMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkAlreadyLoggedIn()
    }

    private fun setupUI() {
        setupTabs()
        setupSubmitButton()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val view = currentFocus
        if (view != null) {
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    private fun checkAlreadyLoggedIn() {
        scope.launch {
            val user = authRepository.getCurrentUserFlow().first()
            withContext(Dispatchers.Main) {
                if (user != null) {
                    navigateToMain()
                }
            }
        }
    }

    private fun setupTabs() {
        binding.tabLogin.setOnClickListener {
            if (!isLoginMode) switchMode(true)
        }
        binding.tabRegister.setOnClickListener {
            if (isLoginMode) switchMode(false)
        }
    }

    private fun switchMode(loginMode: Boolean) {
        isLoginMode = loginMode
        binding.tvError.visibility = View.GONE
        hideKeyboard()

        if (loginMode) {
            // Switch to Login
            binding.tabLogin.setBackgroundResource(R.drawable.bg_tab_selected)
            binding.tabLogin.setTextColor(0xFFFFFFFF.toInt())
            binding.tabRegister.setBackgroundResource(R.drawable.bg_tab_unselected)
            binding.tabRegister.setTextColor(0xFF8B8BA7.toInt())

            binding.tilUsername.visibility = View.GONE
            binding.btnSubmit.text = "Đăng nhập"

            binding.etEmail.requestFocus()
            binding.etEmail.setText("")
            binding.etPassword.setText("")
        } else {
            // Switch to Register
            binding.tabRegister.setBackgroundResource(R.drawable.bg_tab_selected)
            binding.tabRegister.setTextColor(0xFFFFFFFF.toInt())
            binding.tabLogin.setBackgroundResource(R.drawable.bg_tab_unselected)
            binding.tabLogin.setTextColor(0xFF8B8BA7.toInt())

            binding.tilUsername.visibility = View.VISIBLE
            binding.btnSubmit.text = "Đăng ký"

            binding.etUsername.requestFocus()
            binding.etEmail.setText("")
            binding.etPassword.setText("")
            binding.etUsername.setText("")
        }
    }

    private fun setupSubmitButton() {
        binding.btnSubmit.setOnClickListener {
            hideKeyboard()

            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val username = binding.etUsername.text.toString().trim()

            when {
                email.isEmpty() -> showError("Vui lòng nhập email")
                !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    showError("Email không hợp lệ")
                password.length < 6 -> showError("Mật khẩu tối thiểu 6 ký tự")
                !isLoginMode && username.isEmpty() -> showError("Vui lòng nhập tên hiển thị")
                !isLoginMode && username.length < 3 -> showError("Tên hiển thị tối thiểu 3 ký tự")
                else -> {
                    if (isLoginMode) doLogin(email, password)
                    else doRegister(email, username, password)
                }
            }
        }
    }

    private fun doLogin(email: String, password: String) {
        showLoading(true)
        scope.launch {
            try {
                val user = authRepository.login(email, password)
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    navigateToMain()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    showError(parseError(e.message))
                }
            }
        }
    }

    private fun doRegister(email: String, username: String, password: String) {
        showLoading(true)
        scope.launch {
            try {
                val user = authRepository.register(email, username, password)
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    navigateToMain()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    showError(parseError(e.message))
                }
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !show
        binding.btnSubmit.alpha = if (show) 0.7f else 1f
        binding.btnSubmit.text = if (show) {
            if (isLoginMode) "Đang đăng nhập..." else "Đang đăng ký..."
        } else {
            if (isLoginMode) "Đăng nhập" else "Đăng ký"
        }
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE

        // Auto hide error after 3 seconds
        binding.root.postDelayed({
            if (binding.tvError.text == msg) {
                binding.tvError.visibility = View.GONE
            }
        }, 3000)
    }

    private fun parseError(msg: String?): String = when {
        msg?.contains("401") == true -> "Email hoặc mật khẩu không đúng"
        msg?.contains("409") == true -> "Email đã tồn tại"
        msg?.contains("400") == true -> "Dữ liệu không hợp lệ"
        msg?.contains("timeout") == true -> "Không thể kết nối đến server"
        msg?.contains("Unable to resolve") == true -> "Không có kết nối mạng"
        msg?.contains("Network") == true -> "Lỗi kết nối mạng"
        else -> "Có lỗi xảy ra, vui lòng thử lại"
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}