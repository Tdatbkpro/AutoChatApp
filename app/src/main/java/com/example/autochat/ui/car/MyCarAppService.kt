package com.example.autochat.ui.car

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.validation.HostValidator
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.info.Speed
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.AppState
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.ui.car.MyChatScreen
import com.google.gson.internal.GsonBuildConfig
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

class MyCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return object : Session() {
            private var lastVoiceResult = ""
            private var lastVoiceTime = 0L
            private lateinit var chatScreen: MyChatScreen
            private lateinit var voiceResultReceiver: BroadcastReceiver
            private lateinit var voiceStatusReceiver: BroadcastReceiver
            private lateinit var voicePartialReceiver: BroadcastReceiver
            private val drivingCheckHandler = Handler(Looper.getMainLooper())
            private val drivingCheckRunnable = object : Runnable {
                override fun run() {
                    checkDrivingState()
                    drivingCheckHandler.postDelayed(this, 1000) // Check mỗi 1 giây
                }
            }
                    override fun onCreateScreen(intent: Intent): Screen {
                        var isLoggedIn = false
                        runBlocking {
                            try {
                                val authRepository = EntryPointAccessors.fromApplication(
                                    applicationContext,
                                    ChatEntryPoint::class.java
                                ).authRepository()

                                val user = authRepository.getCurrentUserFlow().firstOrNull()
                                if (user != null && user.accessToken.isNotEmpty()) {
                                    AppState.accessToken = user.accessToken
                                    AppState.refreshToken = user.refreshToken
                                    AppState.currentUserId = user.id
                                    AppState.username = user.username
                                    isLoggedIn = true
                                    android.util.Log.d("CAR_DEBUG", "Restored user: ${user.id}")
                                } else {

                                }
                            } catch (e: Exception) {
                                Log.e("CAR_DEBUG", "Error: ${e.message}")
                            }
                        }

                        val screen = if (isLoggedIn) {
                            chatScreen = MyChatScreen(carContext)
                            AppState.chatScreen = chatScreen
                            registerReceivers()

                            // ✅ Thêm lifecycle observer để quản lý driving check
                            chatScreen.lifecycle.addObserver(object : DefaultLifecycleObserver {
                                override fun onStart(owner: LifecycleOwner) {
                                    startDrivingCheck()
                                }

                                override fun onStop(owner: LifecycleOwner) {
                                    stopDrivingCheck()
                                }
                            })

                            chatScreen
                        } else {
                            SignInScreen(carContext)
                        }

                        // ✅ Check trạng thái lái xe ngay khi tạo screen
                        checkDrivingState()

                        return screen
                    }
            @SuppressLint("WrongConstant")
            private fun checkDrivingState() {
                try {
                    val constraintManager = carContext.getCarService(ConstraintManager::class.java)

                    // ✅ Cách 1: Dùng isAppDrivenRefreshEnabled (API 6+)
                    val refreshEnabled = constraintManager.isAppDrivenRefreshEnabled
                    val isDriving = !refreshEnabled  // false = refresh disabled → xe đang chạy

                    android.util.Log.i("CAR_DEBUG", "🚗 refreshEnabled: $refreshEnabled → isDriving: $isDriving")
                    AppState.updateDrivingState(isDriving)

                } catch (e: Exception) {
                    android.util.Log.e("CAR_DEBUG", "checkDrivingState error: ${e.message}")
                    AppState.updateDrivingState(false)
                }
            }
            private fun startDrivingCheck() {
                stopDrivingCheck() // Đảm bảo không có duplicate
                drivingCheckHandler.post(drivingCheckRunnable)
                Log.d("CAR_DEBUG", "🚗 Started driving state monitoring")
            }


            private fun stopDrivingCheck() {
                drivingCheckHandler.removeCallbacks(drivingCheckRunnable)
                Log.d("CAR_DEBUG", "🚗 Stopped driving state monitoring")
            }
            private fun registerReceivers() {
                val mainHandler = Handler(Looper.getMainLooper())
                val appContext = applicationContext

                voiceResultReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val text = intent.getStringExtra("voice_text") ?: return
                        val now = System.currentTimeMillis()

                        if (text == lastVoiceResult && now - lastVoiceTime < 2000) {
                            Log.e("CAR_DEBUG", "Duplicate VOICE_RESULT ignored: $text")
                            return
                        }

                        lastVoiceResult = text
                        lastVoiceTime = now

                        Log.e("CAR_DEBUG", "VOICE_RESULT: $text")
                        mainHandler.post {
                            AppState.voiceScreen?.onVoiceResult(text)
                                ?: AppState.chatScreen?.addUserMessage(text,null)
                        }
                    }
                }

                voicePartialReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val partial = intent.getStringExtra("partial_text") ?: return
                        Log.e("CAR_DEBUG", "VOICE_PARTIAL: $partial")
                        mainHandler.post {
                            AppState.voiceScreen?.updatePartial(partial)
                        }
                    }
                }

                voiceStatusReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val status = intent.getStringExtra("status") ?: return
                        Log.e("CAR_DEBUG", "VOICE_STATUS: $status")
                        mainHandler.post {
                            if (status == "TIMEOUT") AppState.voiceScreen?.onTimeout()
                            else AppState.voiceScreen?.updateStatus(status)
                        }
                    }
                }

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    RECEIVER_NOT_EXPORTED
                } else {
                    0
                }

                appContext.registerReceiver(
                    voiceResultReceiver,
                    IntentFilter("com.example.autochat.VOICE_RESULT"),
                    flags
                )
                appContext.registerReceiver(
                    voiceStatusReceiver,
                    IntentFilter("com.example.autochat.VOICE_STATUS"),
                    flags
                )
                appContext.registerReceiver(
                    voicePartialReceiver,
                    IntentFilter("com.example.autochat.VOICE_PARTIAL"),
                    flags
                )

                Log.e("CAR_DEBUG", "Receivers registered")
            }

            override fun onCarConfigurationChanged(
                newConfiguration: Configuration
            ) {
                checkDrivingState()
            }

        }
    }
}