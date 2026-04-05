package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.car.app.model.signin.InputSignInMethod
import androidx.car.app.model.signin.SignInTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.AppState
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.repository.AuthRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SignInScreen(carContext: CarContext) : Screen(carContext) {

    private val authRepository: AuthRepository by lazy {
        EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            ChatEntryPoint::class.java
        ).authRepository()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var instruction = "Nhập mã PIN 6 số từ điện thoại để đăng nhập"
    private var errorMessage: String? = null
    private var isLoading = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val inputMethod = InputSignInMethod.Builder(
            object : InputCallback {
                override fun onInputTextChanged(text: String) {
                    if (errorMessage != null) {
                        errorMessage = null
                        invalidate()
                    }
                }

                override fun onInputSubmitted(text: String) {
                    when {
                        text.isBlank() -> {
                            errorMessage = "Vui lòng nhập mã PIN"
                            invalidate()
                        }
                        text.length != 6 -> {
                            errorMessage = "Mã PIN phải có đúng 6 chữ số"
                            invalidate()
                        }
                        !isLoading -> verifyPin(text)
                    }
                }
            }
        )
            .setHint("📱 Nhập PIN 6 số")
            .setInputType(InputSignInMethod.INPUT_TYPE_DEFAULT)
            .setKeyboardType(InputSignInMethod.KEYBOARD_NUMBER)
            .setShowKeyboardByDefault(true)
            .apply {
                errorMessage?.let { setErrorMessage(" $it") }
            }
            .build()

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Hướng dẫn")
                    .setOnClickListener(
                        ParkedOnlyOnClickListener.create {
                            instruction = buildString {
                                append(" Cách đăng nhập:\n\n")
                                append("1Mở ứng dụng trên điện thoại\n")
                                append("2️Đăng nhập tài khoản\n")
                                append("3️Nhấn 'Tạo PIN cho xe'\n")
                                append("4️Nhập mã PIN 6 số vào đây")
                            }
                            invalidate()
                        }
                    )
                    .build()
            )
            .build()

        val templateBuilder = SignInTemplate.Builder(inputMethod)
            .setTitle("AutoChat")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip)

        templateBuilder.setInstructions(
            when {
                isLoading -> " Đang xác thực mã PIN..."
                errorMessage != null -> instruction
                else -> instruction
            }
        )

        return templateBuilder.build()
    }

    private fun verifyPin(pin: String) {
        isLoading = true
        errorMessage = null
        instruction = " Đang xác thực..."
        invalidate()

        scope.launch {
            try {
                val user = authRepository.verifyPin(pin)
                withContext(Dispatchers.Main) {
                    AppState.accessToken = user.accessToken
                    AppState.refreshToken = user.refreshToken
                    AppState.currentUserId = user.id
                    AppState.username = user.username

                    val chatScreen = MyChatScreen(carContext)
                    AppState.chatScreen = chatScreen

                    CarToast.makeText(
                        carContext,
                        "🎉 Chào mừng ${user.username}!",
                        CarToast.LENGTH_LONG
                    ).show()

                    screenManager.popToRoot()
                    screenManager.push(chatScreen)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoading = false
                    errorMessage = when {
                        e.message?.contains("400") == true -> " Mã PIN không đúng hoặc đã hết hạn"
                        e.message?.contains("401") == true -> " Mã PIN không hợp lệ"
                        e.message?.contains("404") == true -> " Không tìm thấy thông tin đăng nhập"
                        e.message?.contains("timeout") == true -> " Lỗi kết nối mạng, vui lòng thử lại"
                        else -> " Lỗi xác thực: ${e.message?.take(50) ?: "Không xác định"}"
                    }
                    instruction = buildString {
                        append(" Tạo mã PIN mới trên điện thoại\n")
                        append(" Sau đó nhập mã 6 số vào đây")
                    }
                    invalidate()
                }
            }
        }
    }
}