package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.di.ChatEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.car.app.constraints.ConstraintManager
import com.example.autochat.AppState

/**
 * Màn hình chi tiết bài báo trên Android Auto.
 *
 * ── Tham số [allowAutoAdvance] ────────────────────────────────────────
 *
 *  true  → Đến từ Grid (MyChatScreen) hoặc auto-advance chain.
 *           Sau khi TTS xong: fetch bài tiếp theo, hiện DIALOG kèm title bài đó.
 *           User chủ động chọn "▶ Đọc bài này" hoặc "✕ Thôi".
 *
 *  false → Đến từ NewsListScreen (lịch sử chat).
 *           Sau khi TTS xong: chỉ toast "Đã đọc xong", KHÔNG gợi ý bài tiếp.
 *
 * ── TTS 3 trạng thái ──────────────────────────────────────────────────
 *  IDLE      → [▶ Play]
 *  SPEAKING  → [⏸ Pause]  [⏮ Stop]
 *  PAUSED    → [▶ Resume]  [⏮ Stop]
 */
class NewsDetailScreen(
    carContext: CarContext,
    private val chatScreen: MyChatScreen,
    private val articleId: Int?,
    private val fallbackTitle: String,
    private val fallbackContent: String,
    /**
     * true  = từ Grid / auto-advance → hỏi đọc bài tiếp theo sau khi TTS xong.
     * false = từ NewsListScreen      → chỉ đọc bài này, không gợi ý bài tiếp.
     */
    private val allowAutoAdvance: Boolean = false,
    /** Callback về MyChatScreen khi user back → refresh slot grid. */
    val onArticleConsumed: ((consumedId: Int, category: String?) -> Unit)? = null,
    /** ID đã xem trong session — kế thừa để không lặp khi advance. */
    inheritedSeenIds: List<Int> = emptyList()
) : Screen(carContext) {

    private enum class LoadState { LOADING, LOADED }
    private enum class TtsState  { IDLE, SPEAKING, PAUSED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var loadState          = LoadState.LOADING
    private var articleTitle       = fallbackTitle
    private var articleDescription : String? = null
    private var articleContent     = fallbackContent
    private var articleDate        : String? = null
    private var articleCategory    : String? = null
    private var articleAuthor      : String? = null  // ✅ Thêm trường author

    private var ttsState           = TtsState.IDLE
    private var currentTtsText     = ""
    private var isAutoAdvancing    = false

    private val seenArticleIds = mutableListOf<Int>().also { it.addAll(inheritedSeenIds) }
       // ── Init ──────────────────────────────────────────────────────────────

    init {
        articleId?.let { seenArticleIds.add(it) }

        lifecycle.addObserver(object : DefaultLifecycleObserver {

            override fun onStart(owner: LifecycleOwner) {
                chatScreen.onTtsDone = {
                    ttsState       = TtsState.IDLE
                    currentTtsText = ""

                    if (allowAutoAdvance) {
                        // Từ Grid → fetch bài mới rồi hỏi user
                        fetchNextAndShowDialog()
                    } else {
                        // Từ NewsListScreen → chỉ báo xong
                        CarToast.makeText(
                            carContext, "✅ Đã đọc xong bài báo", CarToast.LENGTH_SHORT
                        ).show()
                        invalidate()
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                // User nhấn Back → dừng TTS, fire callback refresh grid
                chatScreen.onTtsDone = null
                articleId?.let { onArticleConsumed?.invoke(it, articleCategory) }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                chatScreen.onTtsDone = null
                if (ttsState != TtsState.IDLE) chatScreen.stopSpeak()
                if (!isAutoAdvancing) scope.cancel()
            }
        })

        loadArticle()
    }

    // ── Load ──────────────────────────────────────────────────────────────

    private fun loadArticle() {
        if (articleId == null) {
            articleContent = fallbackContent
            loadState      = LoadState.LOADED
            invalidate()
            return
        }
        scope.launch {
            try {
                val article = EntryPointAccessors
                    .fromApplication(carContext.applicationContext, ChatEntryPoint::class.java)
                    .chatRepository()
                    .getArticleById(articleId)

                if (article != null) {
                    articleTitle       = article.title.ifBlank { fallbackTitle }
                    articleDescription = article.description?.takeIf { it.isNotBlank() }
                    articleContent     = cleanContent(article.content ?: fallbackContent)
                    articleDate        = article.publishedDate
                    articleCategory    = article.category
                    articleAuthor      = article.author?.takeIf { it.isNotBlank() }  // ✅ Lấy author từ DB

                    // ✅ Nếu author null, extract từ cuối content
                    if (articleAuthor == null) {
                        articleAuthor = extractAuthorFromContent(articleContent)
                    }
                } else {
                    articleContent = fallbackContent
                }
            } catch (e: Exception) {
                android.util.Log.e("NEWS_DETAIL", "loadArticle: ${e.message}")
                articleContent = fallbackContent
            }
            loadState = LoadState.LOADED
            markArticleRead()
            invalidate()
        }
    }
    private fun extractAuthorFromContent(content: String): String? {
        // Pattern 1: Tác giả sau dấu ngoặc kép đóng
        // Ví dụ: "...hoàn thành nhiệm vụ". Hồng Duy(theo Sky Sports)
        val pattern1 = Regex("""[”"\']\s*\.?\s*([A-ZÀ-Ỹ][a-zà-ỹ]+(?:\s+[A-ZÀ-Ỹ][a-zà-ỹ]+)*\s*(?:\([^)]+\))?)\s*$""")
        pattern1.find(content)?.let { match ->
            val author = match.groupValues[1].trim()
            if (author.length in 3..60 && !author.contains("http")) {
                // Xóa phần author khỏi content
                articleContent = content.substring(0, match.range.first).trim()
                return author
            }
        }

        // Pattern 2: Tác giả sau dấu chấm cuối cùng (nếu không có ngoặc kép)
        // Ví dụ: "...hoàn thành nhiệm vụ. Hồng Duy"
        val pattern2 = Regex("""[.!?]\s+([A-ZÀ-Ỹ][a-zà-ỹ]+(?:\s+[A-ZÀ-Ỹ][a-zà-ỹ]+)*\s*(?:\([^)]+\))?)\s*$""")
        pattern2.find(content)?.let { match ->
            val author = match.groupValues[1].trim()
            if (author.length in 3..60 && !author.contains("http")) {
                // Xóa phần author khỏi content
                articleContent = content.substring(0, match.range.first + 1).trim()
                return author
            }
        }

        // Pattern 3: Tên riêng viết hoa ở cuối chuỗi (fallback)
        val pattern3 = Regex("""([A-ZÀ-Ỹ][a-zà-ỹ]+(?:\s+[A-ZÀ-Ỹ][a-zà-ỹ]+){1,3})\s*$""")
        pattern3.find(content)?.let { match ->
            val author = match.groupValues[1].trim()
            // Chỉ lấy nếu trước đó là space hoặc dấu câu
            val beforeAuthor = content.substring(0, match.range.first).trimEnd()
            if (beforeAuthor.endsWith(".") || beforeAuthor.endsWith("\"") ||
                beforeAuthor.endsWith("'") || beforeAuthor.endsWith("”")) {
                if (author.length in 3..50) {
                    articleContent = beforeAuthor
                    return author
                }
            }
        }

        return null
    }

    private fun markArticleRead() {
        val id = articleId ?: return
        scope.launch {
            try {
                EntryPointAccessors
                    .fromApplication(carContext.applicationContext, ChatEntryPoint::class.java)
                    .readHistoryRepository()
                    .markRead(articleId = id, title = articleTitle, category = articleCategory)
            } catch (e: Exception) {
                android.util.Log.w("NEWS_DETAIL", "markArticleRead: ${e.message}")
            }
        }
    }

    // ── Next article: fetch → dialog ──────────────────────────────────────

    /**
     * Gọi sau khi TTS xong (chỉ khi allowAutoAdvance = true).
     * Fetch bài tiếp theo trước, rồi hiện dialog kèm title bài đó.
     * Người dùng nhìn thấy title trước khi quyết định có nghe hay không.
     */
    private fun fetchNextAndShowDialog() {
        val currentId = articleId ?: return
        isAutoAdvancing = true

        scope.launch {
            try {
                val ep = EntryPointAccessors
                    .fromApplication(carContext.applicationContext, ChatEntryPoint::class.java)

                val excludeIds = (seenArticleIds + ep.readHistoryRepository().getAllReadIds()).distinct()

                val next = ep.chatRepository().getNextArticle(
                    currentId = currentId,
                    category  = articleCategory,
                    seenIds   = excludeIds
                )

                if (next != null && next.id != null) {  // ✅ Check next.id != null
                    val nextId = next.id  // ✅ Lưu lại để dùng

                    // Hiện dialog với title bài mới
                    showNextArticleDialog(
                        nextTitle    = next.title,
                        nextCategory = next.category,
                        onConfirm    = {
                            seenArticleIds.add(nextId)  // ✅ Dùng nextId đã check null
                            val nextContent = buildString {
                                if (!next.description.isNullOrBlank()) append(next.description).append("\n\n")
                                if (!next.content.isNullOrBlank()) append(next.content)
                            }.ifBlank { next.title }

                            screenManager.push(
                                NewsDetailScreen(
                                    carContext        = carContext,
                                    chatScreen        = chatScreen,
                                    articleId         = nextId,  // ✅ Dùng nextId
                                    fallbackTitle     = next.title,
                                    fallbackContent   = nextContent,
                                    allowAutoAdvance  = true,
                                    onArticleConsumed = onArticleConsumed,
                                    inheritedSeenIds  = seenArticleIds.toList()
                                )
                            )
                        }
                    )
                } else {
                    CarToast.makeText(
                        carContext, "✅ Đã xem hết bài báo liên quan", CarToast.LENGTH_LONG
                    ).show()
                    invalidate()
                }
            } catch (e: Exception) {
                android.util.Log.e("NEWS_DETAIL", "fetchNextAndShowDialog: ${e.message}")
                invalidate()
            } finally {
                isAutoAdvancing = false
            }
        }
    }

    /**
     * Dialog hỏi user có muốn đọc bài tiếp theo không.
     * Hiển thị rõ title + category của bài sắp đọc.
     *
     *   ┌─────────────────────────────────────────┐
     *   │  🎧 Bài tiếp theo                        │
     *   │                                          │
     *   │  Thể thao:                               │
     *   │  "Ronaldo lập hat-trick, Real thắng..."  │
     *   │                                          │
     *   │  Bạn có muốn nghe bài này không?         │
     *   │                                          │
     *   │  [▶ Đọc bài này]      [✕ Thôi]          │
     *   └─────────────────────────────────────────┘
     */
    private fun showNextArticleDialog(
        nextTitle    : String,
        nextCategory : String?,
        onConfirm    : () -> Unit
    ) {
        val categoryLine = if (!nextCategory.isNullOrBlank()) "$nextCategory:\n" else ""
        val body = "${categoryLine}\"${nextTitle.take(120)}\"\n\nBạn có muốn nghe bài này không?"

        screenManager.push(object : Screen(carContext) {
            override fun onGetTemplate(): Template =
                MessageTemplate.Builder(body)
                    .setTitle("🎧 Bài tiếp theo")
                    .setHeaderAction(Action.BACK)
                    .addAction(
                        Action.Builder()
                            .setTitle("▶ Đọc bài này")
                            .setOnClickListener {
                                screenManager.pop()   // đóng dialog
                                onConfirm()
                            }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("✕ Thôi")
                            .setOnClickListener { screenManager.pop() }
                            .build()
                    )
                    .build()
        })
    }

    // ── Content helpers ───────────────────────────────────────────────────

    private fun cleanContent(raw: String): String = raw
        .replace(Regex("\\[HÌNH ẢNH:[^\\]]*\\]"), "")
        .replace(Regex("\\[VIDEO:[^\\]]*\\]"), "")
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .lines().map { it.trim() }.filter { it.isNotBlank() }
        .joinToString("\n\n")

    // ✅ Cập nhật buildFullText để hiển thị author
    private fun buildFullText(): String = buildString {
        append(articleTitle.uppercase()).append("\n\n")

        // Meta info: date • category • author
        val metaParts = mutableListOf<String>()
        articleDate?.takeIf { it.isNotBlank() }?.let { metaParts.add(it) }
        articleCategory?.takeIf { it.isNotBlank() }?.let { metaParts.add(it) }
        articleAuthor?.takeIf { it.isNotBlank() }?.let { metaParts.add("✍️ $it") }  // ✅ Hiển thị author với icon

        if (metaParts.isNotEmpty()) {
            append(metaParts.joinToString(" • ")).append("\n")
        }
        append("─".repeat(30)).append("\n\n")

        if (!articleDescription.isNullOrBlank()) {
            append(articleDescription).append("\n\n")
            append("─".repeat(30)).append("\n\n")
        }
        append(articleContent)
    }

    // ✅ Cập nhật buildTtsText để đọc author
    private fun buildTtsText(): String = buildString {
        append(articleTitle).append(". ")
        if (!articleDescription.isNullOrBlank()) append(articleDescription).append(". ")
        // ✅ Đọc tên tác giả nếu có
        if (!articleAuthor.isNullOrBlank()) {
            append("Bài viết của ").append(articleAuthor).append(". ")
        }
        append(articleContent)
    }

    // ── TTS actions ───────────────────────────────────────────────────────

    private fun onPlay() {
        if (currentTtsText.isBlank()) currentTtsText = buildTtsText()
        chatScreen.resumeSpeak(currentTtsText)
        ttsState = TtsState.SPEAKING

        CarToast.makeText(carContext, "Đang đọc bài báo...", CarToast.LENGTH_SHORT).show()
        invalidate()
    }

    private fun onPause() {
        chatScreen.pauseSpeak()
        ttsState = TtsState.PAUSED
        CarToast.makeText(carContext, "Đã tạm dừng", CarToast.LENGTH_SHORT).show()
        invalidate()
    }

    private fun onStop() {
        chatScreen.stopSpeak()
        ttsState       = TtsState.IDLE
        currentTtsText = ""
        CarToast.makeText(carContext, "Đã dừng đọc", CarToast.LENGTH_SHORT).show()
        invalidate()
    }

    // ── Template ──────────────────────────────────────────────────────────

    override fun onGetTemplate(): Template = when (loadState) {

        LoadState.LOADING ->
            MessageTemplate.Builder("Đang tải bài báo...\n\n$fallbackTitle")
                .setTitle("Đang tải")
                .setHeaderAction(Action.BACK)
                .build()

        LoadState.LOADED -> {
            val icon = { resId: Int ->
                CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()
            }

            val actionStrip = when (ttsState) {
                TtsState.IDLE -> ActionStrip.Builder()
                    .addAction(Action.Builder().setIcon(icon(android.R.drawable.ic_media_play))
                        .setOnClickListener { onPlay() }.build())
                    .build()

                TtsState.SPEAKING -> ActionStrip.Builder()
                    .addAction(Action.Builder().setIcon(icon(android.R.drawable.ic_media_pause))
                        .setOnClickListener { onPause() }.build())
                    .addAction(Action.Builder().setIcon(icon(android.R.drawable.ic_media_rew))
                        .setOnClickListener { onStop() }.build())
                    .build()

                TtsState.PAUSED -> ActionStrip.Builder()
                    .addAction(Action.Builder().setIcon(icon(android.R.drawable.ic_media_play))
                        .setOnClickListener { onPlay() }.build())
                    .addAction(Action.Builder().setIcon(icon(android.R.drawable.ic_media_rew))
                        .setOnClickListener { onStop() }.build())
                    .build()
            }

            // ✅ Sử dụng biến global từ AppState
            if (AppState.isDriving) {
                // 🚗 Xe đang chạy → MessageTemplate (giới hạn nội dung)
                val drivingMessage = buildString {
                    if (!articleDescription.isNullOrBlank()) {
                        append(articleDescription)
                    } else {
                        append(articleContent.take(200))
                        if (articleContent.length > 200) append("...")
                    }

                    if (!articleAuthor.isNullOrBlank()) {
                        append("\n\n✍️ ").append(articleAuthor)
                    }
                }

                MessageTemplate.Builder(drivingMessage)
                    .setTitle(when (ttsState) {
                        TtsState.IDLE     -> articleTitle.take(40)
                        TtsState.SPEAKING -> "🔊 ${articleTitle.take(35)}"
                        TtsState.PAUSED   -> "⏸ ${articleTitle.take(35)}"
                    })
                    .setHeaderAction(Action.BACK)
                    .setActionStrip(actionStrip)
                    .build()

            } else {
                // 🅿️ Xe đỗ → LongMessageTemplate (hiển thị toàn bộ nội dung)
                LongMessageTemplate.Builder(buildFullText())
                    .setTitle(when (ttsState) {
                        TtsState.IDLE     -> articleTitle.take(35)
                        TtsState.SPEAKING -> "🔊 ${articleTitle.take(30)}"
                        TtsState.PAUSED   -> "⏸ ${articleTitle.take(30)}"
                    })
                    .setHeaderAction(Action.BACK)
                    .setActionStrip(actionStrip)
                    .build()
            }
        }
    }
}