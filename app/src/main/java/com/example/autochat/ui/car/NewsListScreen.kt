package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.repository.ChatRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Hiển thị danh sách bài báo từ extra_data của bot message.
 * Mỗi item gồm số thứ tự + title + description (2 dòng).
 * Nhấn vào item → push NewsDetailScreen để xem chi tiết + TTS.
 */
class NewsListScreen(
    carContext: CarContext,
    private val chatScreen: MyChatScreen,
    private val extraData: Map<String, Any?>
) : Screen(carContext) {

    data class NewsItem(
        val number: Int,
        val title: String,
        val description: String,
        val articleId: Int?,
        val url: String?
    )

    private val chatRepository: ChatRepository by lazy {
        EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            ChatEntryPoint::class.java
        ).chatRepository()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val newsItems: List<NewsItem> = parseNewsItems(extraData)
    private var page = 0
    private val pageSize = 4

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }

    /**
     * Parse extra_data (đã được convert từ Map<String, Any?>) thành list NewsItem.
     * extra_data cấu trúc: { type: "news_list", content: "...", news_items: [...] }
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseNewsItems(data: Map<String, Any?>): List<NewsItem> {
        return try {
            val rawItems = data["news_items"] as? List<*> ?: return emptyList()
            rawItems.mapIndexedNotNull { index, rawItem ->
                val item = rawItem as? Map<*, *> ?: return@mapIndexedNotNull null
                NewsItem(
                    number = (item["number"] as? Number)?.toInt() ?: (index + 1),
                    title = item["title"] as? String ?: "Không có tiêu đề",
                    description = item["description"] as? String ?: "",
                    articleId = (item["article_id"] as? Number)?.toInt(),
                    url = item["url"] as? String
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("NEWS_LIST", "parseNewsItems error: ${e.message}")
            emptyList()
        }
    }

    override fun onGetTemplate(): Template {
        val itemListBuilder = ItemList.Builder()
        val totalPages = maxOf(1, (newsItems.size + pageSize - 1) / pageSize)
        val start = page * pageSize
        val end = minOf(start + pageSize, newsItems.size)

        if (newsItems.isEmpty()) {
            itemListBuilder.setNoItemsMessage("Không có bài báo nào")
        } else {
            newsItems.subList(start, end).forEach { item ->
                // Cắt description để hiển thị gọn trong 1 dòng phụ
                val shortDesc = if (item.description.length > 80)
                    item.description.take(80) + "..."
                else item.description

                itemListBuilder.addItem(
                    Row.Builder()
                        .setTitle("${item.number}. ${item.title.take(50)}")
                        .addText(shortDesc)
                        .setBrowsable(true)
                        .setOnClickListener {
                            if (item.articleId != null) {
                                // Có article_id → fetch chi tiết từ server
                                screenManager.push(
                                    NewsDetailScreen(
                                        carContext = carContext,
                                        chatScreen = chatScreen,
                                        articleId = item.articleId,
                                        fallbackTitle = item.title,
                                        fallbackContent = buildFallbackContent(item)
                                    )
                                )
                            } else {
                                // Không có ID → dùng dữ liệu có sẵn
                                screenManager.push(
                                    NewsDetailScreen(
                                        carContext = carContext,
                                        chatScreen = chatScreen,
                                        articleId = null,
                                        fallbackTitle = item.title,
                                        fallbackContent = buildFallbackContent(item)
                                    )
                                )
                            }
                        }
                        .build()
                )
            }

            // Phân trang
            if (page > 0 || end < newsItems.size) {
                val navTitle = buildString {
                    if (page > 0) append("◀ Trang trước")
                    if (page > 0 && end < newsItems.size) append("   |   ")
                    if (end < newsItems.size) append("Trang sau ▶")
                }
                itemListBuilder.addItem(
                    Row.Builder()
                        .setTitle(navTitle)
                        .setOnClickListener {
                            if (end < newsItems.size) page++
                            else if (page > 0) page--
                            invalidate()
                        }
                        .build()
                )
            }

            // Footer trang
            if (totalPages > 1) {
                itemListBuilder.addItem(
                    Row.Builder()
                        .setTitle("Trang ${page + 1}/$totalPages • ${newsItems.size} bài")
                        .build()
                )
            }
        }

        return ListTemplate.Builder()
            .setTitle("Danh sách tin tức (${newsItems.size})")
            .setHeaderAction(Action.BACK)
            .setSingleList(itemListBuilder.build())
            .build()
    }

    private fun buildFallbackContent(item: NewsItem): String {
        return buildString {
            append(item.title)
            append("\n\n")
            if (item.description.isNotBlank()) {
                append(item.description)
            }
        }
    }
}