package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import com.example.autochat.AppState
import com.example.autochat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class HomeScreen(carContext: CarContext) : Screen(carContext) {
    // HomeScreen.kt hoặc MyChatScreen.kt
    init {
        CoroutineScope(Dispatchers.Main).launch {
            AppState.sessionExpired.collect { expired ->
                if (expired) {
                    AppState.sessionExpired.value = false
                    screenManager.popToRoot()
                    screenManager.push(SignInScreen(carContext))
                }
            }
        }
    }
    override fun onGetTemplate(): Template {
        val grid = GridTemplate.Builder()
            .setTitle("AI Chatbot")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(
                ItemList.Builder()
                    .addItem(buildGridItem(R.drawable.ic_mic, "Chat ngay", "Hỏi bất cứ điều gì") {
                        AppState.currentEndpoint = "ask"
                        val chatScreen = getChatScreen()
                        chatScreen.currentChatSessionScreen = null
                        AppState.currentSession = null

                        val voiceScreen = VoiceSearchScreen(
                            carContext  = carContext,
                            chatScreen  = chatScreen,
                            botMessage  = null,
                            onFinished  = null
                        )

                        // HomeScreen tự xử lý push ChatSessionScreen sau khi có kết quả voice
                        voiceScreen.onResultFromHome = { text ->
                            val sessionId = "pending_${System.currentTimeMillis()}"
                            val sessionScreen = ChatSessionScreen(
                                carContext     = carContext,
                                chatScreen     = chatScreen,
                                sessionId      = sessionId,
                                endpoint       = AppState.currentEndpoint,
                                pendingMessage = text
                            )
                            chatScreen.currentChatSessionScreen = sessionScreen
                            screenManager.push(sessionScreen)
                            chatScreen.sendMessageOnly(text, null)
                        }

                        screenManager.push(voiceScreen)
                    })
                    .apply {
                        AppState.currentSession?.let { session ->
                            addItem(buildGridItem(R.drawable.ic_forward, "Tiếp tục chat",
                                session.title.take(20)
                            ) {
                                val chatScreen = getChatScreen()
                                val sessionScreen = ChatSessionScreen(
                                    carContext = carContext,
                                    chatScreen = chatScreen,
                                    sessionId  = session.id,
                                    endpoint   = session.endpoint
                                )
                                chatScreen.currentChatSessionScreen = sessionScreen
                                screenManager.push(sessionScreen)
                            })
                        }
                    }
                    .addItem(buildGridItem(R.drawable.ic_news, "Tin tức", "Đọc tin mới nhất") {
                        AppState.currentEndpoint = "news"
                        screenManager.push(NewsEntryScreen(carContext, getChatScreen()))
                    })


                    .addItem(buildGridItem(R.drawable.ic_history, "Lịch sử", "Xem chat cũ") {
                        screenManager.push(HistoryScreen(carContext, getChatScreen()))
                    })
                    .addItem(buildGridItem(R.drawable.ic_ai_model, "Model AI", "Chọn model offline") {
                        screenManager.push(ModelSelectScreen(carContext, getChatScreen()))
                    })
                    .addItem(buildGridItem(R.drawable.ic_settings, "Cài đặt", "Giọng đọc TTS") {
                        screenManager.push(getChatScreen().TtsSettingsScreen(carContext, getChatScreen()))
                    })
                    .build()
            )
            .build()

        return grid
    }

    private fun buildGridItem(
        iconResId: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): GridItem {
        val icon = CarIcon.Builder(
            IconCompat.createWithResource(carContext, iconResId)
        ).build()

        return GridItem.Builder()
            .setTitle(title)
            .setText(subtitle)
            .setImage(icon)
            .setOnClickListener(onClick)
            .build()
    }

    private fun getChatScreen(): MyChatScreen {
        return AppState.chatScreen ?: MyChatScreen(carContext).also {
            AppState.chatScreen = it
        }
    }
}