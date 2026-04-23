package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.AppState
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.repository.ChatRepository
import com.example.autochat.remote.dto.response.QuickReplyItem
import com.example.autochat.util.LocationHelper
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NewsEntryScreen(
    carContext: CarContext,
    private val chatScreen: MyChatScreen
) : Screen(carContext) {

    private val chatRepository: ChatRepository by lazy {
        EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            ChatEntryPoint::class.java
        ).chatRepository()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var quickReplies: List<QuickReplyItem> = emptyList()
    private var isLoading = false
    private var cachedLocation: String? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
        })
        loadQuickReplies()
    }

    private fun loadQuickReplies() {
        if (isLoading) return
        isLoading = true
        scope.launch {
            try {
                val location = cachedLocation
                    ?: withContext(Dispatchers.IO) {
                        LocationHelper.getProvinceName(carContext.applicationContext)
                    }.also { cachedLocation = it }
                    ?: "hà nội"
                quickReplies = chatRepository.getQuickReplies(location = location)
                invalidate()
            } catch (e: Exception) {
                android.util.Log.w("NEWS_ENTRY", e.message ?: "")
            } finally {
                isLoading = false
            }
        }
    }

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        if (isLoading) {
            listBuilder.addItem(
                Row.Builder().setTitle("⏳ Đang tải tin vắn...").build()
            )
        } else if (quickReplies.isEmpty()) {
            listBuilder.setNoItemsMessage("Không có tin tức lúc này")
        } else {
            quickReplies.take(6).forEach { item ->
                val title = buildString {
                    if (item.icon.isNotEmpty()) append("${item.icon} ")
                    append(if (item.id != null) item.category else item.label)
                }.take(200)

                val subtitle = if (item.id != null) item.label
                else when (item.category.lowercase()) {
                    "gold"    -> "Giá vàng trong nước & thế giới"
                    "fuel"    -> "Giá xăng dầu mới nhất"
                    "weather" -> "Thời tiết hôm nay"
                    else      -> item.label
                }

                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(title)
                        .addText(subtitle.take(80))
                        .setBrowsable(true)
                        .setOnClickListener { onItemClick(item) }
                        .build()
                )
            }
        }

        // ActionStrip: mic để hỏi news
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("🎤 Hỏi")
                    .setOnClickListener {
                        AppState.currentEndpoint = "news"
                        screenManager.push(
                            VoiceSearchScreen(
                                carContext = carContext,
                                chatScreen = chatScreen,
                                botMessage = null,
                                onFinished = {
                                    // Sau khi VoiceSearchScreen pop, push ChatSessionScreen
                                    AppState.currentSession?.let { session ->
                                        screenManager.push(
                                            ChatSessionScreen(
                                                carContext = carContext,
                                                chatScreen = chatScreen,
                                                sessionId = session.id,
                                                endpoint = "news"
                                            )
                                        )
                                    }
                                }
                            )
                        )
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle("📰 Tin tức")
            .setHeaderAction(Action.BACK)
            .setSingleList(listBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }

    private fun onItemClick(item: QuickReplyItem) {
        scope.launch {
            try {
                if (item.id != null) {
                    val article = chatRepository.getArticleById(item.id)
                    if (article != null) {
                        screenManager.push(
                            NewsDetailScreen(
                                carContext = carContext,
                                chatScreen = chatScreen,
                                articleId = article.id,
                                fallbackTitle = article.title,
                                fallbackContent = buildString {
                                    if (!article.description.isNullOrBlank()) append(article.description).append("\n\n")
                                    if (!article.content.isNullOrBlank()) append(article.content)
                                },
                                allowAutoAdvance = true,
                                onArticleConsumed = { _, _ -> }
                            )
                        )
                    }
                } else {
                    val query = item.defaultQuery ?: item.label
                    val structured = chatRepository.getStructuredData(query)
                    val displayText = if (structured?.hasData == true && !structured.text.isNullOrBlank())
                        structured.text
                    else "Không có dữ liệu lúc này."

                    screenManager.push(
                        MessageDetailScreen(
                            carContext = carContext,
                            chatScreen = chatScreen,
                            messageContent = displayText,
                            messageTime = item.label
                        )
                    )
                }
            } catch (e: Exception) {
                CarToast.makeText(carContext, "Lỗi tải dữ liệu", CarToast.LENGTH_SHORT).show()
            }
        }
    }
}