package com.example.autochat.ui.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.AppState
import com.example.autochat.R
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.domain.model.Message
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MyChatScreen(carContext: CarContext) : Screen(carContext), TextToSpeech.OnInitListener {

    // ── SharedPreferences keys ────────────────────────────────────────────
    companion object {
        const val PREFS_NAME        = "tts_settings"
        const val KEY_TTS_SPEED     = "tts_speed"     // Float: 0.5 – 2.0, default 1.0
        const val KEY_TTS_PITCH     = "tts_pitch"     // Float: 0.5 – 2.0, default 1.0
        const val KEY_TTS_LOCALE    = "tts_locale"    // String: "vi-VN" | "en-US"
    }

    private val prefs: SharedPreferences by lazy {
        carContext.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Repositories ──────────────────────────────────────────────────────

    private val chatRepository: ChatRepository by lazy {
        EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            ChatEntryPoint::class.java
        ).chatRepository()
    }

    private val readHistoryRepository by lazy {
        EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            ChatEntryPoint::class.java
        ).readHistoryRepository()
    }

    // ── State ─────────────────────────────────────────────────────────────
    private lateinit var navReceiver: BroadcastReceiver
    private val messages = mutableListOf<Message>()
    private var tts: TextToSpeech = TextToSpeech(carContext, this)
    private var ttsReady = false
    private var pendingSpeak: String? = null
    private var isMicOpen = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentObserveJob: kotlinx.coroutines.Job? = null
    private var isSending = false
    private var isWaitingResponse = false
    private var showMessageOptions = false
    private var selectedMessage: Message? = null
    private var cachedLocation: String? = null
    private var quickReplies: List<QuickReplyItem> = emptyList()
    private var isLoadingQuickReplies = false
    private val articleSlotMap = mutableMapOf<Int, Int>()

    // TTS sentence tracking
    private var ttsSentences: List<String> = emptyList()
    private var ttsCurrentIndex: Int = 0
    var onTtsDone: (() -> Unit)? = null
    // MyChatScreen.kt — thêm property
    var currentChatSessionScreen: ChatSessionScreen? = null

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        AppState.chatScreen = this
        AppState.currentSession?.let { session ->
            observeMessages(session.id)
        }
        navReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val navQuery    = intent.getStringExtra("nav_query") ?: return
                val displayName = intent.getStringExtra("display_name") ?: navQuery
                handler.post { navigateTo(navQuery, displayName) }
            }
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Context.RECEIVER_NOT_EXPORTED else 0
        carContext.registerReceiver(
            navReceiver,
            IntentFilter("com.example.autochat.START_NAVIGATION"),
            flags
        )
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (AppState.chatScreen === this@MyChatScreen) {
                    AppState.chatScreen = null
                }
                tts.stop()
                tts.shutdown()
                scope.cancel()
                try { carContext.unregisterReceiver(navReceiver) } catch (_: Exception) {}
            }
        })
        loadQuickReplies()
    }

    // ── TTS init ──────────────────────────────────────────────────────────
    fun onDrivingStateChanged(isDriving: Boolean) {
        // Invalidate để cập nhật UI khi trạng thái lái xe thay đổi
        invalidate()
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyTtsSettings()
            ttsReady = true

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    val idx = utteranceId.removePrefix("sentence_").toIntOrNull() ?: return
                    ttsCurrentIndex = idx
                }
                override fun onDone(utteranceId: String) {
                    val idx = utteranceId.removePrefix("sentence_").toIntOrNull() ?: return
                    if (idx >= ttsSentences.lastIndex) {
                        handler.post {
                            ttsCurrentIndex = 0
                            ttsSentences = emptyList()
                            onTtsDone?.invoke()
                        }
                    }
                }
                @Deprecated("Deprecated in API 21")
                override fun onError(utteranceId: String) {
                    android.util.Log.e("TTS", "Error on utterance: $utteranceId")
                }
            })

            pendingSpeak?.let { speakText(it); pendingSpeak = null }
        }
    }

    /**
     * Áp dụng cài đặt TTS từ SharedPreferences vào engine hiện tại.
     * Gọi sau onInit và mỗi khi user thay đổi setting.
     */
    private fun applyTtsSettings() {
        val speed  = prefs.getFloat(KEY_TTS_SPEED,  1.0f)
        val pitch  = prefs.getFloat(KEY_TTS_PITCH,  1.0f)
        val locale = prefs.getString(KEY_TTS_LOCALE, "vi-VN") ?: "vi-VN"

        tts.setSpeechRate(speed)
        tts.setPitch(pitch)

        val parts   = locale.split("-")
        val langTag = if (parts.size >= 2) Locale(parts[0], parts[1]) else Locale(parts[0])
        val result  = tts.setLanguage(langTag)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            android.util.Log.w("TTS", "Ngôn ngữ không hỗ trợ: $locale, fallback vi-VN")
            tts.language = Locale("vi", "VN")
        }
    }

    // ── Template routing ──────────────────────────────────────────────────
    override fun onGetTemplate(): Template {
        // MyChatScreen không còn là root screen
        // Chỉ dùng khi được push trực tiếp từ code cũ (backward compat)
        return when {
            showMessageOptions && selectedMessage != null -> buildMessageOptionsTemplate()
            messages.isEmpty() -> buildQuickRepliesAsList()
            else -> buildChatTemplate()
        }
    }

    // ── Quick replies ─────────────────────────────────────────────────────

    private fun loadQuickReplies() {
        if (isLoadingQuickReplies) return
        isLoadingQuickReplies = true
        scope.launch {
            try {
                val location = cachedLocation
                    ?: withContext(Dispatchers.IO) {
                        LocationHelper.getProvinceName(carContext.applicationContext)
                    }.also { cachedLocation = it }
                    ?: "hưng yên"
                val items = chatRepository.getQuickReplies(location = location)
                if (items.isNotEmpty()) {
                    quickReplies = deduplicateWithHistory(items)
                    rebuildArticleSlotMap()
                    if (messages.isEmpty()) invalidate()
                }
            } catch (e: Exception) {
                android.util.Log.w("CHAT", "loadQuickReplies: ${e.message}")
            } finally {
                isLoadingQuickReplies = false
            }
        }
    }

    private suspend fun deduplicateWithHistory(
        items: List<QuickReplyItem>
    ): List<QuickReplyItem> {
        val readIds = try {
            readHistoryRepository.getAllReadIds().toSet()
        } catch (e: Exception) {
            android.util.Log.w("CHAT", "dedup: không đọc được Room")
            return items
        }
        if (readIds.isEmpty()) return items

        val result   = mutableListOf<QuickReplyItem>()
        val usedIds  = items.mapNotNull { it.id }.toMutableSet()

        for (item in items) {
            val articleId = item.id
            if (articleId == null || articleId !in readIds) {
                result.add(item)
                continue
            }

            val excludeIds = (readIds + usedIds).toList()
            val next = try {
                chatRepository.getNextArticle(
                    currentId  = articleId,
                    category   = item.category,
                    seenIds    = excludeIds
                )
            } catch (e: Exception) { null }

            if (next != null && next.id != null) {  // ✅ Kiểm tra next.id != null
                usedIds.add(next.id)                 // ✅ Bây giờ an toàn vì đã check null
                result.add(QuickReplyItem(
                    label    = next.title,
                    id       = next.id,
                    category = next.category ?: item.category,
                    icon     = item.icon
                ))
            } else {
                result.add(item)
            }
        }
        return result
    }

    private fun rebuildArticleSlotMap() {
        articleSlotMap.clear()
        quickReplies.take(6).forEachIndexed { index, item ->
            item.id?.let { articleSlotMap[it] = index }
        }
    }

    fun refreshGridSlot(consumedArticleId: Int, category: String?) {
        val slotIndex = articleSlotMap[consumedArticleId] ?: return
        scope.launch {
            try {
                val excludeIds = readHistoryRepository.getAllReadIds()
                val newItem    = chatRepository.getNextArticle(
                    currentId = consumedArticleId,
                    category  = category,
                    seenIds   = excludeIds
                ) ?: return@launch

                val updated = quickReplies.toMutableList()
                if (slotIndex < updated.size) {
                    updated[slotIndex] = QuickReplyItem(
                        label    = newItem.title,
                        id       = newItem.id,
                        category = newItem.category ?: category ?: "",
                        icon     = "📰"
                    )
                    quickReplies = updated
                    rebuildArticleSlotMap()
                    if (messages.isEmpty()) invalidate()
                }
            } catch (e: Exception) {
                android.util.Log.w("CHAT", "refreshGridSlot error: ${e.message}")
            }
        }
    }

    private fun sendQuickQuery(item: QuickReplyItem, slotIndex: Int) {
        if (isSending) {
            CarToast.makeText(carContext, "⏳ Đang tải, vui lòng đợi...", CarToast.LENGTH_SHORT).show()
            return
        }
        isSending = true; isWaitingResponse = true; invalidate()

        scope.launch {
            try {
                if (item.id != null) {
                    val article = chatRepository.getArticleById(item.id)
                    isWaitingResponse = false; isSending = false

                    if (article != null && article.id != null) {  // ✅ Check article.id != null
                        val content = buildString {
                            if (!article.description.isNullOrBlank()) append(article.description).append("\n\n")
                            if (!article.content.isNullOrBlank()) append(article.content)
                        }.ifBlank { item.label }

                        articleSlotMap[article.id] = slotIndex  // ✅ Bây giờ an toàn
                        screenManager.push(
                            NewsDetailScreen(
                                carContext      = carContext,
                                chatScreen      = this@MyChatScreen,
                                articleId       = article.id,  // ✅ Đã check null ở trên
                                fallbackTitle   = article.title.ifBlank { item.label },
                                fallbackContent = content,
                                allowAutoAdvance  = true,
                                onArticleConsumed = { consumedId, cat -> refreshGridSlot(consumedId, cat) }
                            )
                        )
                    } else {
                        CarToast.makeText(carContext, "Không tải được bài báo", CarToast.LENGTH_SHORT).show()
                    }
                    invalidate()

                } else {
                    val query = item.defaultQuery ?: when (item.category.lowercase()) {
                        "gold"    -> "giá vàng hôm nay"
                        "fuel"    -> "giá xăng dầu hôm nay"
                        "weather" -> "thời tiết hôm nay"
                        else      -> item.label
                    }
                    val structured = chatRepository.getStructuredData(query)
                    isWaitingResponse = false; isSending = false

                    val displayText = if (structured?.hasData == true && !structured.text.isNullOrBlank())
                        structured.text
                    else
                        "Không có dữ liệu cho \"${item.label}\" lúc này."

                    screenManager.push(
                        MessageDetailScreen(
                            carContext      = carContext,
                            chatScreen      = this@MyChatScreen,
                            messageContent  = displayText,
                            messageTime     = item.label
                        )
                    )
                    invalidate()
                }
            } catch (e: Exception) {
                android.util.Log.e("CHAT", "sendQuickQuery error: ${e.message}")
                CarToast.makeText(carContext, "Lỗi tải dữ liệu", CarToast.LENGTH_SHORT).show()
                invalidate()
            } finally {
                isSending = false; isWaitingResponse = false; invalidate()
            }
        }
    }

    // ── Quick replies list template ───────────────────────────────────────

    private fun buildQuickRepliesAsList(): Template {
        data class FallbackItem(val label: String, val category: String)
        val fallback = listOf(
            FallbackItem("💰 Giá vàng hôm nay",  "gold"),
            FallbackItem("⛽ Giá xăng hôm nay",   "fuel"),
            FallbackItem("🌤️ Thời tiết hôm nay", "weather"),
            FallbackItem("⚽ Tin thể thao",        "the-thao"),
            FallbackItem("💹 Tin kinh tế",         "kinh-doanh"),
            FallbackItem("📰 Tin thời sự",         "thoi-su"),
        )

        val listBuilder  = ItemList.Builder()
        val itemsToShow  = quickReplies.takeIf { it.isNotEmpty() }

        if (itemsToShow != null) {
            itemsToShow.take(6).forEachIndexed { slotIndex, item ->
                val title = buildString {
                    if (item.icon.isNotEmpty()) append("${item.icon} ")
                    append(if (item.id != null) item.category else item.label)
                }.take(200)

                val subtitle = if (item.id != null) item.label else when (item.category.lowercase()) {
                    "gold"    -> "Giá vàng trong nước & thế giới"
                    "fuel"    -> "Giá xăng dầu mới nhất"
                    "weather" -> "Dự báo thời tiết ${cachedLocation} hôm nay"
                    else      -> item.label
                }
                val maxLen     = 60
                val splitIndex = if (subtitle.length <= maxLen) subtitle.length
                else subtitle.indexOf(' ', maxLen).takeIf { it != -1 } ?: subtitle.length
                val line1 = subtitle.substring(0, splitIndex).trim()
                val line2 = subtitle.substring(splitIndex).trim()

                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(title)
                        .addText(line1)
                        .addText(line2)
                        .setOnClickListener { sendQuickQuery(item, slotIndex) }
                        .build()
                )
            }
        } else {
            fallback.forEachIndexed { slotIndex, f ->
                val syntheticItem = QuickReplyItem(label = f.label, id = null, category = f.category, icon = "")
                listBuilder.addItem(
                    Row.Builder()
                        .setTitle(f.label)
                        .setOnClickListener { sendQuickQuery(syntheticItem, slotIndex) }
                        .build()
                )
            }
        }

        // Thêm row Settings TTS ở cuối danh sách
        listBuilder.addItem(buildTtsSettingsRow())

        val actionStrip = buildCommonActionStrip()

        return ListTemplate.Builder()
            .setTitle("💬 AI Chatbot · Tin vắn")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(listBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }

    // ── Chat template ─────────────────────────────────────────────────────

    private fun buildChatTemplate(): Template {
        val itemListBuilder = ItemList.Builder()
        val sessionTitle    = AppState.currentSession?.title ?: "Chat mới"

        itemListBuilder.addItem(
            Row.Builder()
                .setTitle("💬 $sessionTitle")
                .addText("${messages.size} tin nhắn")
                .build()
        )

        if (isWaitingResponse) {
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle("🤖 AI đang trả lời...")
                    .addText("⏳ Vui lòng chờ")
                    .build()
            )
        }

        messages.takeLast(10).forEach { msg ->
            val timeStr = formatTime(msg.timestamp)
            if (msg.sender == "bot") {
                val hasNewsList = extractNewsExtraData(msg) != null
                if (hasNewsList) {
                    val newsCount = getNewsCount(msg)
                    itemListBuilder.addItem(
                        Row.Builder()
                            .setTitle("🤖 Bot • $timeStr")
                            .addText(msg.content.take(60) + if (msg.content.length > 60) "..." else "")
                            .addText("📰 $newsCount bài • Nhấn để xem danh sách")
                            .setBrowsable(true)
                            .setOnClickListener {
                                val extraData = extractNewsExtraData(msg)
                                if (extraData != null) screenManager.push(NewsListScreen(carContext, this, extraData))
                            }
                            .build()
                    )
                } else {
                    itemListBuilder.addItem(
                        Row.Builder()
                            .setTitle("🤖 Bot • $timeStr")
                            .addText(msg.content.take(120) + if (msg.content.length > 120) "..." else "")
                            .apply {
                                if (msg.content.length > MessageDetailScreen.MIN_LENGTH_FOR_DETAIL)
                                    setBrowsable(true)
                            }
                            .setOnClickListener {
                                if (!isMicOpen) {
                                    if (msg.content.length > MessageDetailScreen.MIN_LENGTH_FOR_DETAIL) {
                                        screenManager.push(
                                            MessageDetailScreen(carContext, this@MyChatScreen, msg.content, timeStr)
                                        )
                                    } else {
                                        speakText(msg.content)
                                        CarToast.makeText(carContext, "🔊 Đang đọc...", CarToast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .build()
                    )
                }
            } else {
                itemListBuilder.addItem(
                    Row.Builder()
                        .setTitle("👤 Bạn • $timeStr")
                        .addText(msg.content.take(120) + if (msg.content.length > 120) "..." else "")
                        .setOnClickListener {
                            selectedMessage = msg; showMessageOptions = true; invalidate()
                        }
                        .build()
                )
            }
        }

        // Settings TTS thay cho row Hướng dẫn
        itemListBuilder.addItem(buildTtsSettingsRow())

        return ListTemplate.Builder()
            .setTitle("💬 ${AppState.currentSession?.title?.take(30) ?: "AI Chatbot"}")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(buildCommonActionStrip())
            .build()
    }

    // ── TTS Settings row & screen ─────────────────────────────────────────

    /**
     * Row gắn vào cuối list; nhấn vào mở màn TTS Settings.
     */
    private fun buildTtsSettingsRow(): Row {
        val speed  = prefs.getFloat(KEY_TTS_SPEED, 1.0f)
        val pitch  = prefs.getFloat(KEY_TTS_PITCH, 1.0f)
        val locale = prefs.getString(KEY_TTS_LOCALE, "vi-VN") ?: "vi-VN"
        val lang   = if (locale == "vi-VN") "Tiếng Việt" else "English"

        return Row.Builder()
            .setTitle("⚙️ Cài đặt giọng đọc")
            .addText("Ngôn ngữ: $lang  •  Tốc độ: ${speed.fmt()}x  •  Cao độ: ${pitch.fmt()}x")
            .setBrowsable(true)
            .setOnClickListener { screenManager.push(TtsSettingsScreen(carContext, this)) }
            .build()
    }

    /**
     * Màn hình chọn cài đặt TTS theo từng bước dùng MessageTemplate chuỗi.
     * Android Auto chỉ cho 2 Action / MessageTemplate nên ta dùng nhiều bước.
     */
    inner class TtsSettingsScreen(ctx: CarContext, private val parent: MyChatScreen) : Screen(ctx) {

        // Bước: "speed" | "pitch" | "locale" | "done"
        private var step = "main"

        override fun onGetTemplate(): Template = when (step) {
            "speed"  -> buildSpeedTemplate()
            "pitch"  -> buildPitchTemplate()
            "locale" -> buildLocaleTemplate()
            else     -> buildMainTemplate()
        }

        // ── Main menu ──────────────────────────────────────────────────────
        private fun buildMainTemplate(): Template {
            val speed  = prefs.getFloat(KEY_TTS_SPEED, 1.0f)
            val pitch  = prefs.getFloat(KEY_TTS_PITCH, 1.0f)
            val locale = prefs.getString(KEY_TTS_LOCALE, "vi-VN") ?: "vi-VN"
            val lang   = if (locale == "vi-VN") "Tiếng Việt" else "English"

            val list = ItemList.Builder()
                .addItem(Row.Builder().setTitle("🌐 Ngôn ngữ giọng đọc").addText("Hiện tại: $lang")
                    .setBrowsable(true).setOnClickListener { step = "locale"; invalidate() }.build())
                .addItem(Row.Builder().setTitle("⏩ Tốc độ đọc").addText("Hiện tại: ${speed.fmt()}x  (0.5 – 2.0)")
                    .setBrowsable(true).setOnClickListener { step = "speed"; invalidate() }.build())
                .addItem(Row.Builder().setTitle("🎵 Cao độ giọng").addText("Hiện tại: ${pitch.fmt()}x  (0.5 – 2.0)")
                    .setBrowsable(true).setOnClickListener { step = "pitch"; invalidate() }.build())
                .build()

            return ListTemplate.Builder()
                .setTitle("⚙️ Cài đặt giọng đọc")
                .setHeaderAction(Action.BACK)
                .setSingleList(list)
                .build()
        }

        // ── Locale ────────────────────────────────────────────────────────
        private fun buildLocaleTemplate(): Template {
            val current = prefs.getString(KEY_TTS_LOCALE, "vi-VN") ?: "vi-VN"
            return MessageTemplate.Builder(
                "Chọn ngôn ngữ giọng đọc\nHiện tại: ${if (current == "vi-VN") "Tiếng Việt" else "English"}"
            )
                .setTitle("🌐 Ngôn ngữ")
                .setHeaderAction(Action.BACK)
                .addAction(Action.Builder().setTitle("🇻🇳 Tiếng Việt")
                    .setOnClickListener { saveLocale("vi-VN") }.build())
                .addAction(Action.Builder().setTitle("🇬🇧 English")
                    .setOnClickListener { saveLocale("en-US") }.build())
                .build()
        }

        // ── Speed ─────────────────────────────────────────────────────────
        private fun buildSpeedTemplate(): Template {
            val cur = prefs.getFloat(KEY_TTS_SPEED, 1.0f)
            val list = ItemList.Builder()

            // ✅ Hiển thị tất cả các mức trong ListTemplate
            listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.7f, 2.0f).forEach { speed ->
                val label = when (speed) {
                    0.5f -> "🐢 Rất chậm (0.5x)"
                    0.7f -> "🐌 Chậm (0.7x)"
                    1.0f -> "🚶 Bình thường (1.0x)"
                    1.3f -> "⚡ Hơi nhanh (1.3x)"
                    1.5f -> "🏃 Nhanh (1.5x)"
                    2.0f -> "🔥 Rất nhanh (2.0x)"
                    else -> "${speed}x"
                }

                list.addItem(
                    Row.Builder()
                        .setTitle(label)
                        .apply {
                            if (abs(speed - cur) < 0.05f) addText("✓ Đang chọn")
                        }
                        .setOnClickListener {
                            saveSpeed(speed)
                            screenManager.pop()
                        }
                        .build()
                )
            }

            return ListTemplate.Builder()
                .setTitle("⏩ Chọn tốc độ đọc")
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build())
                .build()
        }

        private fun buildPitchTemplate(): Template {
            val cur = prefs.getFloat(KEY_TTS_PITCH, 1.0f)
            val list = ItemList.Builder()

            listOf(0.5f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f).forEach { pitch ->
                val label = when (pitch) {
                    0.5f -> "🔉 Rất trầm (0.5x)"
                    0.8f -> "🔈 Trầm (0.8x)"
                    1.0f -> "🔊 Bình thường (1.0x)"
                    1.3f -> "📢 Cao (1.3x)"
                    1.6f -> "📣 Rất cao (1.6x)"
                    2.0f -> "🔔 Cực cao (2.0x)"
                    else -> "${pitch}x"
                }

                list.addItem(
                    Row.Builder()
                        .setTitle(label)
                        .apply {
                            if (abs(pitch - cur) < 0.05f) addText("✓ Đang chọn")
                        }
                        .setOnClickListener {
                            savePitch(pitch)
                            screenManager.pop()
                        }
                        .build()
                )
            }

            return ListTemplate.Builder()
                .setTitle("🎵 Chọn cao độ giọng")
                .setHeaderAction(Action.BACK)
                .setSingleList(list.build())
                .build()
        }

        // ── Save helpers ──────────────────────────────────────────────────

        private fun saveLocale(locale: String) {
            prefs.edit().putString(KEY_TTS_LOCALE, locale).apply()
            parent.applyTtsSettings()
            CarToast.makeText(carContext,
                "✅ Đã đổi: ${if (locale == "vi-VN") "Tiếng Việt" else "English"}",
                CarToast.LENGTH_SHORT).show()
        }

        private fun saveSpeed(speed: Float) {
            prefs.edit().putFloat(KEY_TTS_SPEED, speed).apply()
            parent.applyTtsSettings()
            CarToast.makeText(carContext, "✅ Tốc độ: ${speed.fmt()}x", CarToast.LENGTH_SHORT).show()
        }

        private fun savePitch(pitch: Float) {
            prefs.edit().putFloat(KEY_TTS_PITCH, pitch).apply()
            parent.applyTtsSettings()
            CarToast.makeText(carContext, "✅ Cao độ: ${pitch.fmt()}x", CarToast.LENGTH_SHORT).show()

        }
    }

    private fun Float.fmt(): String =
        if (this == kotlin.math.floor(this.toDouble()).toFloat()) "%.0f".format(this)
        else "%.2f".format(this).trimEnd('0')

    // ── Message options ───────────────────────────────────────────────────

    private fun buildMessageOptionsTemplate(): Template {
        val msg = selectedMessage ?: return buildChatTemplate()
        return MessageTemplate.Builder(
            buildString {
                append("Tin nhắn của bạn:\n")
                append(msg.content.take(120))
                if (msg.content.length > 120) append("...")
                append("\n\nChọn hành động:")
            }
        )
            .setTitle("Tùy chọn tin nhắn")
            .setHeaderAction(Action.BACK)
            .addAction(Action.Builder().setTitle("Đọc to")
                .setOnClickListener {
                    speakText(msg.content)
                    CarToast.makeText(carContext, "Đang đọc...", CarToast.LENGTH_SHORT).show()
                    showMessageOptions = false; selectedMessage = null; invalidate()
                }.build())
            .addAction(Action.Builder().setTitle("Xóa tin nhắn")
                .setOnClickListener { deleteUserMessage(msg) }
                .build())
            .build()
    }

    // ── ActionStrip dùng chung ────────────────────────────────────────────

    private fun buildCommonActionStrip(): ActionStrip =
        ActionStrip.Builder()
            .addAction(Action.Builder()
                .setIcon(CarIcon.Builder(
                    IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_recent_history)
                ).build())
                .setOnClickListener { screenManager.push(HistoryScreen(carContext, this)) }
                .build())
            .addAction(Action.Builder()
                .setTitle("🎤 Nói")
                .setOnClickListener {
                    isMicOpen = true; tts.stop()
                    handler.postDelayed({ isMicOpen = false }, 15000)
                    handler.postDelayed({
                        val vs = VoiceSearchScreen(carContext, this@MyChatScreen, null)
                        AppState.voiceScreen = vs
                        screenManager.push(vs)
                    }, 500)
                }
                .build())
            .build()

    // ── Send message (FIX: không xóa temp, merge kết quả vào list) ───────

//    fun addUserMessage(text: String, botMessage: String?, endpoint: String = AppState.currentEndpoint) {
//        if (isSending) {
//            CarToast.makeText(carContext, "⏳ Đang gửi...", CarToast.LENGTH_SHORT).show()
//            return
//        }
//        isMicOpen = false; isSending = true; isWaitingResponse = true
//
//        val tempId = "temp_${System.currentTimeMillis()}"
//        val tempUserMsg = Message(
//            id = tempId, sessionId = AppState.currentSession?.id ?: "",
//            content = text, sender = "user", timestamp = System.currentTimeMillis()
//        )
//        messages.add(tempUserMsg); invalidate()
//
//        scope.launch {
//            try {
//                val result = chatRepository.sendMessage(
//                    sessionId  = AppState.currentSession?.id,
//                    content    = text,
//                    endpoint   = endpoint,          // ← dùng endpoint đúng
//                    botMessage = botMessage
//                )
//
//                if (AppState.currentSession?.id != result.sessionId) {
//                    AppState.currentSession = ChatSession(
//                        id = result.sessionId, userId = AppState.currentUserId,
//                        title = result.sessionTitle,
//                        createdAt = System.currentTimeMillis(),
//                        updatedAt = System.currentTimeMillis()
//                    )
//                    // Push ChatSessionScreen sau khi có session mới
//                    screenManager.push(
//                        ChatSessionScreen(
//                            carContext = carContext,
//                            chatScreen = this@MyChatScreen,
//                            sessionId = result.sessionId,
//                            endpoint = endpoint
//                        )
//                    )
//                    observeMessages(result.sessionId)
//                }
//
//                val realUserMsg = tempUserMsg.copy(id = result.userMessage.id ?: tempId, sessionId = result.sessionId)
//                val botMsg = result.botMessage.copy(sessionId = result.sessionId)
//                messages.removeAll { it.id == tempId }
//                if (messages.none { it.id == realUserMsg.id }) messages.add(realUserMsg)
//                if (messages.none { it.id == botMsg.id }) messages.add(botMsg)
//                isWaitingResponse = false
//
//                val extraDataType = extractTypeExtraData(botMsg)
//                when (extraDataType) {
//                    "news_list" -> {
//                        val extraData = extractNewsExtraData(botMsg)
//                        if (extraData != null) {
//                            invalidate()
//                            screenManager.push(NewsListScreen(carContext, this@MyChatScreen, extraData))
//                        }
//                    }
//                    "navigation" -> {
//                        val navData = extractNavigationData(botMsg)
//                        val navQuery = navData?.get("nav_query") as? String
//                        val displayName = navData?.get("target") as? String
//                        if (navQuery != null && displayName != null) {
//                            speakText(botMsg.content); invalidate()
//                            showNavigationConfirmDialog(navQuery, displayName)
//                        }
//                    }
//                    else -> { speakText(botMsg.content); invalidate() }
//                }
//            } catch (e: Exception) {
//                messages.removeAll { it.id == tempId }
//                isWaitingResponse = false
//                CarToast.makeText(carContext, "Lỗi kết nối!", CarToast.LENGTH_SHORT).show()
//                invalidate()
//            } finally {
//                isSending = false
//            }
//        }
//    }
fun sendMessageOnly(
    text: String,
    botMessage: String?,
    endpoint: String = AppState.currentEndpoint
) {
    if (isSending) return
    isSending = true
    isMicOpen = false

    val existingSessionId = AppState.currentSession?.id

    scope.launch {
        try {
            val result = chatRepository.sendMessage(
                sessionId  = existingSessionId,
                content    = text,
                endpoint   = endpoint,
                botMessage = botMessage
            )

            // Cập nhật session nếu mới tạo
            if (existingSessionId != result.sessionId) {
                AppState.currentSession = ChatSession(
                    id        = result.sessionId,
                    userId    = AppState.currentUserId,
                    title     = result.sessionTitle,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    endpoint  = endpoint
                )
                AppState.currentEndpoint = endpoint
            }

            // Thông báo cho ChatSessionScreen hiển thị kết quả
            currentChatSessionScreen?.onSessionReady(
                realSessionId = result.sessionId,
                botMessage    = result.botMessage
            )

        } catch (e: Exception) {
            android.util.Log.e("CHAT", "sendMessageOnly error: ${e.message}")
            CarToast.makeText(carContext, "Lỗi kết nối!", CarToast.LENGTH_SHORT).show()

            // Nếu session screen chưa có data thật → pop nó đi
            if (AppState.currentSession == null) {
                currentChatSessionScreen = null
                // Không pop ở đây — để user tự back, tránh double-pop
            }
        } finally {
            isSending = false
        }
    }
}
    fun addUserMessage(
        text: String,
        botMessage: String?,
        endpoint: String = AppState.currentEndpoint
    ) {
        // Giữ lại cho backward compat (gọi từ SearchTemplate trong VoiceSearchScreen cũ)
        // Thực ra flow mới sẽ không vào đây nữa — VoiceSearchScreen dùng sendAndNavigate()
        if (isSending) {
            CarToast.makeText(carContext, "⏳ Đang gửi...", CarToast.LENGTH_SHORT).show()
            return
        }
        isMicOpen = false; isSending = true

        val existingSessionId = AppState.currentSession?.id
        val isAlreadyInSession = currentChatSessionScreen != null

        if (!isAlreadyInSession) {
            val sessionScreen = ChatSessionScreen(
                carContext     = carContext,
                chatScreen     = this@MyChatScreen,
                sessionId      = existingSessionId ?: "pending_${System.currentTimeMillis()}",
                endpoint       = endpoint,
                pendingMessage = text
            )
            currentChatSessionScreen = sessionScreen
            screenManager.push(sessionScreen)
        } else {
            currentChatSessionScreen!!.addPendingMessage(text)
        }

        sendMessageOnly(text, botMessage, endpoint)
    }
    // ── Helpers ───────────────────────────────────────────────────────────
    private fun extractTypeExtraData(msg: Message): String? =
        (msg.extraData?.get("type") as? String)

    @Suppress("UNCHECKED_CAST")
    private fun extractNewsExtraData(msg: Message): Map<String, Any?>? {
        val extra = msg.extraData ?: return null
        if (extra["type"] as? String != "news_list") return null
        val items = extra["news_items"] as? List<*> ?: return null
        if (items.isEmpty()) return null
        return extra
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractNavigationData(msg: Message): Map<String, Any?>? {
        val extra = msg.extraData ?: return null
        if (extra["type"] as? String != "navigation") return null
        val navData = extra["navigation"] as? Map<String, Any?> ?: return null
        return if (navData["nav_query"] != "") navData else null
    }

    private fun getNewsCount(msg: Message): Int =
        (msg.extraData?.get("news_items") as? List<*>)?.size ?: 0

    private fun deleteUserMessage(message: Message) {
        val msgIndex   = messages.indexOf(message)
        val nextBotMsg = messages.getOrNull(msgIndex + 1)?.takeIf { it.sender == "bot" }
        scope.launch {
            try {
                val sessionId = AppState.currentSession?.id ?: return@launch
                chatRepository.deleteMessagePair(
                    sessionId     = sessionId,
                    userMessageId = message.id,
                    botMessageId  = nextBotMsg?.id
                )
                CarToast.makeText(carContext, "Đã xóa tin nhắn", CarToast.LENGTH_SHORT).show()
                showMessageOptions = false; selectedMessage = null
            } catch (e: Exception) {
                CarToast.makeText(carContext, "Lỗi xóa tin nhắn!", CarToast.LENGTH_SHORT).show()
                showMessageOptions = false; selectedMessage = null; invalidate()
            }
        }
    }

    private fun formatTime(timestamp: Long): String {
        return try {
            val date = Date(timestamp); val now = Date()
            val sdf  = if (date.year == now.year && date.month == now.month && date.date == now.date)
                SimpleDateFormat("HH:mm", Locale("vi"))
            else SimpleDateFormat("dd/MM HH:mm", Locale("vi"))
            sdf.format(date)
        } catch (e: Exception) { "--:--" }
    }

    fun navigateTo(navQuery: String, displayName: String = navQuery) {
        try {
            val intent = Intent(CarContext.ACTION_NAVIGATE).apply {
                data = Uri.parse("geo:0,0?q=${Uri.encode(navQuery)}")
            }
            carContext.startCarApp(intent)
            CarToast.makeText(carContext, "🗺️ Đang dẫn đường: $displayName", CarToast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("CHAT", "navigateTo: ${e.message}")
            CarToast.makeText(carContext, "Không thể mở Maps", CarToast.LENGTH_SHORT).show()
        }
    }

    private fun showNavigationConfirmDialog(navQuery: String, displayName: String) {
        screenManager.push(object : Screen(carContext) {
            override fun onGetTemplate(): Template =
                MessageTemplate.Builder("Bạn có muốn chỉ đường đến\n\"$displayName\" không?")
                    .setTitle("🗺️ Chỉ đường")
                    .setHeaderAction(Action.BACK)
                    .addAction(Action.Builder().setTitle("✅ Có, dẫn đường")
                        .setOnClickListener { screenManager.pop(); navigateTo(navQuery, displayName) }.build())
                    .addAction(Action.Builder().setTitle("❌ Không")
                        .setOnClickListener { screenManager.pop() }.build())
                    .build()
        })
    }

    private fun showReadDialog(summaryContent: String, extraData: Map<String, Any?>) {
        val newsCount   = (extraData["news_items"] as? List<*>)?.size ?: 0
        val previewText = "Tìm thấy $newsCount bài báo liên quan.\n\n" +
                summaryContent.take(120) + if (summaryContent.length > 120) "..." else ""
        screenManager.push(object : Screen(carContext) {
            override fun onGetTemplate(): Template =
                MessageTemplate.Builder(previewText)
                    .setTitle("📰 Có $newsCount bài báo")
                    .setHeaderAction(Action.BACK)
                    .addAction(Action.Builder().setTitle("🔊 Đọc tóm tắt")
                        .setOnClickListener { speakText(summaryContent); screenManager.pop() }.build())
                    .addAction(Action.Builder().setTitle("Bỏ qua")
                        .setOnClickListener { screenManager.pop() }.build())
                    .build()
        })
    }

    fun observeMessages(sessionId: String) {
        currentObserveJob?.cancel()
        currentObserveJob = scope.launch {
            chatRepository.getMessagesFlow(sessionId).collect { list ->
                withContext(Dispatchers.Main) {
                    // Merge: giữ các tin nhắn optimistic chưa có trong DB
                    val dbIds    = list.map { it.id }.toSet()
                    val optimistic = messages.filter { it.id.startsWith("temp_") && it.id !in dbIds }
                    messages.clear()
                    messages.addAll(list)
                    messages.addAll(optimistic)
                    messages.sortBy { it.timestamp }
                    invalidate()
                }
            }
        }
    }

    fun clearMessages() {
        messages.clear(); AppState.currentSession = null
        isSending = false; showMessageOptions = false; selectedMessage = null
        invalidate()
    }

    fun loadSession(sessionId: String) {
        currentObserveJob?.cancel()
        scope.launch {
            currentObserveJob = scope.launch {
                chatRepository.getMessagesFlow(sessionId).collect { list ->
                    messages.clear(); messages.addAll(list); invalidate()
                }
            }
        }
    }

    // ── TTS public API ────────────────────────────────────────────────────

    fun speakText(text: String) {
        if (isMicOpen) return
        if (!ttsReady) { pendingSpeak = text; return }
        handler.removeCallbacksAndMessages(null)
        ttsSentences   = splitIntoSentences(text)
        ttsCurrentIndex = 0
        enqueueSentencesFrom(0)
    }

    fun pauseSpeak() {
        handler.removeCallbacksAndMessages(null)
        if (ttsReady) tts.stop()
    }

    fun resumeSpeak(text: String) {
        if (isMicOpen) return
        if (!ttsReady) { pendingSpeak = text; return }
        handler.removeCallbacksAndMessages(null)
        if (ttsSentences.isNotEmpty() && ttsCurrentIndex < ttsSentences.size)
            enqueueSentencesFrom(ttsCurrentIndex)
        else
            speakText(text)
    }

    fun stopSpeak() {
        handler.removeCallbacksAndMessages(null)
        if (ttsReady) tts.stop()
        pendingSpeak = null; ttsSentences = emptyList(); ttsCurrentIndex = 0
    }

    fun isTtsSpeaking(): Boolean = ttsReady && tts.isSpeaking

    private fun enqueueSentencesFrom(fromIndex: Int) {
        if (!ttsReady || ttsSentences.isEmpty()) return
        val audioManager = carContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val audioAttrs   = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusRequest = android.media.AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
            .setAudioAttributes(audioAttrs)
            .setWillPauseWhenDucked(false)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { }
            .build()
        audioManager.requestAudioFocus(focusRequest)
        tts.setAudioAttributes(audioAttrs)
        val params = android.os.Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        handler.postDelayed({
            if (isMicOpen) return@postDelayed
            ttsSentences.forEachIndexed { i, sentence ->
                if (i < fromIndex) return@forEachIndexed
                val mode = if (i == fromIndex) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts.speak(sentence, mode, params, "sentence_$i")
            }
        }, 400)
    }

    private fun splitIntoSentences(text: String): List<String> {
        val raw    = text.split(Regex("(?<=[.!?।\\n])\\s+|\\n{2,}"))
            .map { it.trim() }.filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        for (sentence in raw) {
            if (sentence.length <= 200) { result.add(sentence); continue }
            var remaining = sentence
            while (remaining.length > 200) {
                val cut = remaining.lastIndexOf(' ', 200).takeIf { it > 50 } ?: 200
                result.add(remaining.substring(0, cut))
                remaining = remaining.substring(cut).trim()
            }
            if (remaining.isNotBlank()) result.add(remaining)
        }
        return result
    }
}