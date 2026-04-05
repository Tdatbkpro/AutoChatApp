package com.example.autochat.ui.car

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
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.autochat.AppState
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.ui.car.MyChatScreen
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
                            null
                        }
                    } catch (e: Exception) {
                        Log.e("CAR_DEBUG", "Error: ${e.message}")
                    }
                }

                return if (isLoggedIn) {
                    val screen = MyChatScreen(carContext)
                    AppState.chatScreen = screen
                    registerReceivers()
                    screen
                } else {
                    SignInScreen(carContext)
                }
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
                                ?: AppState.chatScreen?.addUserMessage(text)
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
            ) {}
        }
    }
}