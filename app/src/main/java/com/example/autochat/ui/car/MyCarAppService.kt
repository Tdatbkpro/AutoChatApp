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
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.AppState
import com.example.autochat.di.ChatEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import androidx.core.net.toUri

class MyCarAppService : CarAppService() {

    private lateinit var navReceiver: BroadcastReceiver
    private var activeCarContext: androidx.car.app.CarContext? = null
    private var currentSession: CarSession? = null

    // ── Inner class khai báo TRƯỚC các override ─────────────────────────────
    inner class CarSession : Session() {
        private lateinit var chatScreen: MyChatScreen
        private lateinit var homeScreen: HomeScreen
        private lateinit var voiceResultReceiver: BroadcastReceiver
        private lateinit var voiceStatusReceiver: BroadcastReceiver
        private lateinit var voicePartialReceiver: BroadcastReceiver
        private var lastVoiceResult = ""
        private var lastVoiceTime   = 0L

        private val drivingCheckHandler  = Handler(Looper.getMainLooper())
        private val drivingCheckRunnable = object : Runnable {
            override fun run() {
                checkDrivingState()
                drivingCheckHandler.postDelayed(this, 1000)
            }
        }

        fun handleQuery(query: String) {
            Log.e("CAR_SESSION", "handleQuery: $query")
            AppState.voiceScreen?.onVoiceResult(query)
                ?: AppState.chatScreen?.addUserMessage(
                    query, botMessage = null, endpoint = AppState.currentEndpoint
                )
        }

        override fun onCreateScreen(intent: Intent): Screen {
            var isLoggedIn = false
            runBlocking {
                try {
                    val authRepo = EntryPointAccessors.fromApplication(
                        applicationContext, ChatEntryPoint::class.java
                    ).authRepository()
                    val user = authRepo.getCurrentUserFlow().firstOrNull()
                    if (user != null && user.accessToken.isNotEmpty()) {
                        AppState.accessToken   = user.accessToken
                        AppState.refreshToken  = user.refreshToken
                        AppState.currentUserId = user.id
                        AppState.username      = user.username
                        isLoggedIn = true
                        Log.d("CAR_DEBUG", "Restored user: ${user.id}")
                    } else {

                    }
                } catch (e: Exception) {
                    Log.e("CAR_DEBUG", "Auth error: ${e.message}")
                }
            }
            return if (isLoggedIn) {
                chatScreen = MyChatScreen(carContext)
                AppState.chatScreen = chatScreen
                homeScreen = HomeScreen(carContext)
                activeCarContext = carContext
                registerReceivers()
                homeScreen.lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) { startDrivingCheck() }
                    override fun onStop(owner: LifecycleOwner)  { stopDrivingCheck() }
                })
                checkDrivingState()
                homeScreen
            } else {
                SignInScreen(carContext)
            }
        }

        override fun onNewIntent(intent: Intent) {
            super.onNewIntent(intent)
            Log.e("CAR_NEW_INTENT", "=== onNewIntent ===")
            Log.e("CAR_NEW_INTENT", "action=${intent.action}")
            intent.extras?.keySet()?.forEach {
                Log.e("CAR_NEW_INTENT", "[$it] = ${intent.extras?.get(it)}")
            }
            val query = intent.getStringExtra(android.app.SearchManager.QUERY)
                ?: intent.getStringExtra("query")
                ?: intent.getStringExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE)
                ?: intent.getStringExtra("android.intent.extra.TITLE")
                ?: return
            if (query.isBlank()) return
            handleQuery(query)
        }

        override fun onCarConfigurationChanged(newConfiguration: Configuration) {
            checkDrivingState()
        }

        @SuppressLint("WrongConstant")
        private fun checkDrivingState() {
            try {
                val cm = carContext.getCarService(ConstraintManager::class.java)
                AppState.updateDrivingState(!cm.isAppDrivenRefreshEnabled)
            } catch (e: Exception) {
                AppState.updateDrivingState(false)
            }
        }

        private fun startDrivingCheck() {
            stopDrivingCheck()
            drivingCheckHandler.post(drivingCheckRunnable)
        }

        private fun stopDrivingCheck() {
            drivingCheckHandler.removeCallbacks(drivingCheckRunnable)
        }

        private fun registerReceivers() {
            val mainHandler = Handler(Looper.getMainLooper())
            val appContext  = applicationContext

            voiceResultReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val text = intent.getStringExtra("voice_text") ?: return
                    val now  = System.currentTimeMillis()
                    if (text == lastVoiceResult && now - lastVoiceTime < 2000) return
                    lastVoiceResult = text; lastVoiceTime = now
                    Log.d("CAR_DEBUG", "VOICE_RESULT: $text")
                    mainHandler.post {
                        AppState.voiceScreen?.onVoiceResult(text)
                            ?: AppState.chatScreen?.addUserMessage(text, null)
                    }
                }
            }
            voicePartialReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val partial = intent.getStringExtra("partial_text") ?: return
                    mainHandler.post { AppState.voiceScreen?.updatePartial(partial) }
                }
            }
            voiceStatusReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val status = intent.getStringExtra("status") ?: return
                    mainHandler.post {
                        if (status == "TIMEOUT") AppState.voiceScreen?.onTimeout()
                        else AppState.voiceScreen?.updateStatus(status)
                    }
                }
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                RECEIVER_NOT_EXPORTED else 0
            appContext.registerReceiver(voiceResultReceiver,
                IntentFilter("com.example.autochat.VOICE_RESULT"), flags)
            appContext.registerReceiver(voiceStatusReceiver,
                IntentFilter("com.example.autochat.VOICE_STATUS"), flags)
            appContext.registerReceiver(voicePartialReceiver,
                IntentFilter("com.example.autochat.VOICE_PARTIAL"), flags)
            Log.d("CAR_DEBUG", "Receivers registered")
        }
    }

    // ── Service-level methods ────────────────────────────────────────────────
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreate() {
        super.onCreate()
        navReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val navQuery = intent.getStringExtra("nav_query") ?: return
                val carCtx = activeCarContext ?: run {
                    Log.w("CAR_SVC", "activeCarContext null"); return
                }
                try {
                    carCtx.startCarApp(
                        Intent(androidx.car.app.CarContext.ACTION_NAVIGATE).apply {
                            data = "geo:0,0?q=${android.net.Uri.encode(navQuery)}".toUri()
                        }
                    )
                } catch (e: Exception) {
                    Log.e("CAR_SVC", "Nav failed: ${e.message}")
                }
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            RECEIVER_NOT_EXPORTED else 0
        registerReceiver(navReceiver, IntentFilter("com.example.autochat.START_NAVIGATION"), flags)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e("CAR_START_CMD", "=== onStartCommand ===")
        Log.e("CAR_START_CMD", "action=${intent?.action}")
        intent?.extras?.keySet()?.forEach {
            Log.e("CAR_START_CMD", "[$it] = ${intent.extras?.get(it)}")
        }
        val query = intent?.getStringExtra(android.app.SearchManager.QUERY)
            ?: intent?.getStringExtra("query")
        if (!query.isNullOrBlank()) {
            Log.e("CAR_START_CMD", "Query nhận được: $query")
            Handler(Looper.getMainLooper()).postDelayed({
                currentSession?.handleQuery(query)
            }, 300)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        try { unregisterReceiver(navReceiver) } catch (_: Exception) {}
        activeCarContext = null
        currentSession = null
        super.onDestroy()
    }

    override fun onCreateSession(): Session {
        return CarSession().also { currentSession = it }
    }
}