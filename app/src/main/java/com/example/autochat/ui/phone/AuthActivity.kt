package com.example.autochat.ui.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
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
    private var pinCountdown: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkAlreadyLoggedIn()
        setupTabs()
        setupSubmitButton()
        setupPinButton()
        setupLogoutButton()
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
                if (user != null) showLoggedInState(user.username)
            }
        }
    }

    private fun setupTabs() {
        binding.tabLogin.setOnClickListener { switchMode(true) }
        binding.tabRegister.setOnClickListener { switchMode(false) }
    }

    private fun switchMode(loginMode: Boolean) {
        isLoginMode = loginMode
        binding.tvError.visibility = View.GONE
        hideKeyboard()
        if (loginMode) {
            binding.tabLogin.setBackgroundColor(0xFF2D2D4E.toInt())
            binding.tabLogin.setTextColor(0xFFFFFFFF.toInt())
            binding.tabRegister.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.tabRegister.setTextColor(0xFF888899.toInt())
            binding.tilUsername.visibility = View.GONE
            binding.btnSubmit.text = "Đăng nhập"
            binding.etEmail.requestFocus()

        } else {
            binding.tabRegister.setBackgroundColor(0xFF2D2D4E.toInt())
            binding.tabRegister.setTextColor(0xFFFFFFFF.toInt())
            binding.tabLogin.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            binding.tabLogin.setTextColor(0xFF888899.toInt())
            binding.tilUsername.visibility = View.VISIBLE
            binding.btnSubmit.text = "Đăng ký"
            binding.etUsername.requestFocus()

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
                password.length < 6 -> showError("Mật khẩu tối thiểu 6 ký tự")
                !isLoginMode && username.isEmpty() -> showError("Vui lòng nhập tên hiển thị")
                else -> if (isLoginMode) doLogin(email, password)
                else doRegister(email, username, password)
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
                    showLoggedInState(user.username)
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
                    showLoggedInState(user.username)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    showError(parseError(e.message))
                }
            }
        }
    }

    private fun showLoggedInState(username: String) {
        // ✅ Ẩn form, hiện PIN section
        binding.tabContainer.visibility = View.GONE
        binding.tilUsername.visibility = View.GONE
        binding.tilEmail.visibility = View.GONE
        binding.tilPassword.visibility = View.GONE
        binding.btnSubmit.visibility = View.GONE
        binding.tvError.visibility = View.GONE

        // ✅ Hiện PIN section
        binding.divider.visibility = View.VISIBLE
        binding.tvPinTitle.visibility = View.VISIBLE
        binding.tvPinSubtitle.visibility = View.VISIBLE
        binding.btnGetPin.visibility = View.VISIBLE
        binding.btnLogout.visibility = View.VISIBLE

        // Cập nhật tagline
        binding.tvTagline.text = "Xin chào, $username!"

        // Mở MainActivity
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun setupPinButton() {
        binding.btnGetPin.setOnClickListener { generatePin() }
    }

    private fun generatePin() {
        binding.btnGetPin.isEnabled = false
        binding.btnGetPin.text = "Dang tao PIN..."

        scope.launch {
            try {
                // ✅ Phone gọi server tạo PIN
                val pin = authRepository.generatePin()
                withContext(Dispatchers.Main) {
                    showPin(pin)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.btnGetPin.isEnabled = true
                    binding.btnGetPin.text = "Tao PIN cho xe"
                    showError("Loi tao PIN: ${parseError(e.message)}")
                }
            }
        }
    }

    private fun showPin(pin: String) {
        binding.pinCard.visibility = View.VISIBLE
        // Hiển thị PIN dạng "1 2 3 4 5 6"
        binding.tvPin.text = pin.toCharArray().joinToString("  ")
        binding.btnGetPin.text = "Tạo PIN mới"

        // Countdown 5 phút
        pinCountdown?.cancel()
        pinCountdown = object : CountDownTimer(300_000, 1000) {
            override fun onTick(ms: Long) {
                val m = ms / 60000
                val s = (ms % 60000) / 1000
                binding.tvPinExpiry.text = "Hết hạn sau %d:%02d".format(m, s)
            }
            override fun onFinish() {
                binding.tvPin.text = "- - - - - -"
                binding.tvPinExpiry.text = "PIN đã hết hạn"
                binding.btnGetPin.isEnabled = true
                binding.btnGetPin.text = "Tạo PIN mới"
            }
        }.start()

        // Enable lại sau 5s (tránh spam)
        binding.root.postDelayed({
            binding.btnGetPin.isEnabled = true
        }, 5000)
    }

    private fun setupLogoutButton() {
        binding.btnLogout.setOnClickListener {
            scope.launch {
                authRepository.logout()
                withContext(Dispatchers.Main) {
                    pinCountdown?.cancel()
                    // Reset về form login
                    recreate()
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !show
        binding.btnSubmit.alpha = if (show) 0.6f else 1f
    }

    private fun showError(msg: String) {
        binding.tvError.text = msg
        binding.tvError.visibility = View.VISIBLE
    }

    private fun parseError(msg: String?): String = when {
        msg?.contains("401") == true -> "Email hoặc mật khẩu không đúng"
        msg?.contains("400") == true -> "Email đã tồn tại"
        msg?.contains("timeout") == true -> "Không kết nối được server"
        msg?.contains("Unable to resolve") == true -> "Không có kết nối mạng"
        else -> "Lỗi kết nối server"
    }

    override fun onDestroy() {
        super.onDestroy()
        pinCountdown?.cancel()
        scope.cancel()
    }
}