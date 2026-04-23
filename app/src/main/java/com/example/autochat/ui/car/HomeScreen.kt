package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import com.example.autochat.AppState
import com.example.autochat.R

class HomeScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val grid = GridTemplate.Builder()
            .setTitle("AI Chatbot")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(
                ItemList.Builder()
                    .addItem(buildGridItem(R.drawable.ic_mic, "Chat ngay", "Hỏi bất cứ điều gì") {
                        AppState.currentEndpoint = "ask"
                        getChatScreen().currentChatSessionScreen = null
                        AppState.currentSession = null
                        screenManager.push(
                            VoiceSearchScreen(carContext, getChatScreen(), botMessage = null, onFinished = {
                                invalidate()
                            })
                        )

                    })
                    .addItem(buildGridItem(R.drawable.ic_news, "Tin tức", "Đọc tin mới nhất") {
                        AppState.currentEndpoint = "news"
                        screenManager.push(NewsEntryScreen(carContext, getChatScreen()))
                    })
                    .addItem(buildGridItem(R.drawable.ic_history, "Lịch sử", "Xem chat cũ") {
                        screenManager.push(HistoryScreen(carContext, getChatScreen()))
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