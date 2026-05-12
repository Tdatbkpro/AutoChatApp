package com.example.autochat.ui.phone.adapter

import android.R.string.selectAll
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.R
import com.example.autochat.databinding.ItemMessageBotBinding
import com.example.autochat.databinding.ItemMessageBotNewsBinding
import com.example.autochat.databinding.ItemMessageUserBinding   // ✅ giữ nguyên tên
import com.example.autochat.domain.model.Message
import com.example.autochat.domain.repository.HasCodeExecutor
import com.example.autochat.ui.phone.BranchManager
import com.example.autochat.ui.phone.MarkdownRenderer
import com.example.autochat.ui.phone.MessageActionsPopup
import com.example.autochat.ui.phone.adapter.com.example.autochat.tts.TtsTextCleaner
import com.example.autochat.ui.phone.fragment.TtsSettingsFragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.noties.markwon.Markwon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import io.noties.prism4j.GrammarLocator
import io.noties.markwon.syntax.Prism4jThemeDarkula

class ChatMessageAdapter(
    private val onNewsItemClick: (articleId: Int?, title: String, description: String) -> Unit =
        { _, _, _ -> },
    // ✅ Thêm 3 callback mới — default no-op, không break caller cũ
    private val onRetry:        (Message) -> Unit                  = {},
    private val onEdit:         (Message, String) -> Unit          = { _, _ -> },
    private val onSwitchBranch: (pivotId: String, delta: Int) -> Unit = { _, _ -> },
    private val onTts: (msgId: String, content: String, onLoadingEnd: () -> Unit) -> Unit = { _, _, _ -> } ,
            private val onStopTts: () -> Unit = {},                    // ← thêm
    private val onOpenTtsSettings: () -> Unit = {},            // ← thêm
) : ListAdapter<Message, RecyclerView.ViewHolder>(DiffCallback()) {

    data class NewsItemData(
        val number: Int,
        val title: String,
        val description: String,
        val articleId: Int?,
        val url: String?,
        val category: String? = null,
        val thumbnail: String? = null,
        val mediaType: String? = null
    )

    companion object {
        private const val VIEW_TYPE_USER     = 0
        private const val VIEW_TYPE_BOT      = 1
        private const val VIEW_TYPE_BOT_NEWS = 2
        const val STREAMING_PREFIX = "streaming_"

        fun isStreamingPlaceholder(msg: Message) = msg.id.startsWith(STREAMING_PREFIX)

        fun isNewsList(msg: Message): Boolean {
            val extra = msg.extraData ?: return false
            if ((extra["type"] as? String) != "news_list") return false
            val items = extra["news_items"] as? List<*> ?: return false
            return items.isNotEmpty()
        }

        @Suppress("UNCHECKED_CAST")
        fun extractNewsItems(msg: Message): List<NewsItemData> {
            val extra    = msg.extraData ?: return emptyList()
            val rawItems = extra["news_items"] as? List<*> ?: return emptyList()
            return rawItems.mapIndexedNotNull { index, raw ->
                val item       = raw as? Map<*, *> ?: return@mapIndexedNotNull null
                val mediaItems = item["media_items"] as? List<*>
                val firstMedia = mediaItems?.firstOrNull() as? Map<*, *>
                NewsItemData(
                    number      = (item["number"] as? Number)?.toInt() ?: (index + 1),
                    title       = item["title"] as? String ?: "Không có tiêu đề",
                    description = item["description"] as? String ?: "",
                    articleId   = (item["article_id"] as? Number)?.toInt(),
                    url         = item["url"] as? String,
                    category    = item["category"] as? String,
                    thumbnail   = firstMedia?.get("url") as? String ?: item["thumbnail"] as? String,
                    mediaType   = firstMedia?.get("type") as? String
                )
            }
        }

        fun formatTime(timestamp: Long): String = try {
            val now = Date()
            val date = Date(timestamp)
            val calNow = java.util.Calendar.getInstance().apply { time = now }
            val calMsg = java.util.Calendar.getInstance().apply { time = date }

            val sameDay  = calNow.get(java.util.Calendar.YEAR) == calMsg.get(java.util.Calendar.YEAR) &&
                    calNow.get(java.util.Calendar.DAY_OF_YEAR) == calMsg.get(java.util.Calendar.DAY_OF_YEAR)
            val sameYear = calNow.get(java.util.Calendar.YEAR) == calMsg.get(java.util.Calendar.YEAR)

            val fmt = when {
                sameDay  -> "HH:mm"
                sameYear -> "dd/MM HH:mm"
                else     -> "dd/MM/yyyy HH:mm"
            }
            SimpleDateFormat(fmt, Locale("vi")).format(date)
        } catch (e: Exception) { "--:--" }
    }

    private var onUserMessageLongPress: ((Int) -> Unit)? = null
    private var markwon: Markwon? = null
    private var renderer: MarkdownRenderer? = null
    // Thêm vào class ChatMessageAdapter (ngoài các ViewHolder)
    private val speakingIds: MutableSet<String> = mutableSetOf()
    private var currentPlayingId: String? = null
    private fun getOrCreateMarkwon(context: Context): Markwon {
        return markwon ?: run {
            ru.noties.jlatexmath.JLatexMathAndroid.init(context.applicationContext)
            val prism4j = Prism4j(object : GrammarLocator {
                override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
                    return when (language) {
                        "clike"                 -> io.noties.prism4j.languages.Prism_clike.create(prism4j)
                        "c"                     -> io.noties.prism4j.languages.Prism_c.create(prism4j)
                        "cpp"                   -> io.noties.prism4j.languages.Prism_cpp.create(prism4j)
                        "java"                  -> io.noties.prism4j.languages.Prism_java.create(prism4j)
                        "kotlin"                -> io.noties.prism4j.languages.Prism_kotlin.create(prism4j)
                        "python"                -> io.noties.prism4j.languages.Prism_python.create(prism4j)
                        "javascript", "js"      -> io.noties.prism4j.languages.Prism_javascript.create(prism4j)
                        "json"                  -> io.noties.prism4j.languages.Prism_json.create(prism4j)
                        "markup", "html", "xml" -> io.noties.prism4j.languages.Prism_markup.create(prism4j)
                        "css"                   -> io.noties.prism4j.languages.Prism_css.create(prism4j)
                        "sql"                   -> io.noties.prism4j.languages.Prism_sql.create(prism4j)
                        "swift"                 -> io.noties.prism4j.languages.Prism_swift.create(prism4j)
                        "go"                    -> io.noties.prism4j.languages.Prism_go.create(prism4j)
                        "yaml"                  -> io.noties.prism4j.languages.Prism_yaml.create(prism4j)
                        "csharp"                  -> io.noties.prism4j.languages.Prism_csharp.create(prism4j)
                        else                    -> null
                    }
                }
                override fun languages() = setOf(
                    "clike", "c", "cpp", "java", "kotlin", "python",
                    "javascript", "js", "json", "markup", "html", "xml",
                    "css", "sql", "swift", "go", "yaml"
                )
            })
            val textSize = context.resources.displayMetrics.density * 24
            Markwon.builder(context)
                .usePlugin(io.noties.markwon.inlineparser.MarkwonInlineParserPlugin.create())
                .usePlugin(
                    io.noties.markwon.ext.latex.JLatexMathPlugin.create(textSize) { builder ->
                        builder.inlinesEnabled(true)
                        builder.blocksEnabled(true)
                        builder.blocksLegacy(true)
                        builder.theme()
                            .inlinePadding(io.noties.markwon.ext.latex.JLatexMathTheme.Padding.symmetric(4, 2))
                            .blockPadding(io.noties.markwon.ext.latex.JLatexMathTheme.Padding.symmetric(8, 4))
                            .blockTextColor(0xFFE0E0F0.toInt())
                            .inlineTextColor(0xFFE0E0F0.toInt())
                            .blockFitCanvas(true)
                    }
                )
                .usePlugin(SyntaxHighlightPlugin.create(prism4j, Prism4jThemeDarkula.create()))
                .usePlugin(io.noties.markwon.image.ImagesPlugin.create())
                .build()
                .also { markwon = it }
        }
    }

    private fun getOrCreateRenderer(context: Context): MarkdownRenderer {
        return renderer ?: MarkdownRenderer(
            markwon      = getOrCreateMarkwon(context),
            codeExecutor = (context.applicationContext as? HasCodeExecutor)?.codeExecutor
        ).also { renderer = it }
    }

    fun setOnUserMessageLongPress(callback: (Int) -> Unit) {
        onUserMessageLongPress = callback
    }

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        return when {
            msg.sender == "user"                       -> VIEW_TYPE_USER
            msg.sender == "bot" && isNewsList(msg)     -> VIEW_TYPE_BOT_NEWS
            else                                       -> VIEW_TYPE_BOT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
        VIEW_TYPE_BOT_NEWS -> BotNewsViewHolder(
            binding         = ItemMessageBotNewsBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            renderer        = getOrCreateRenderer(parent.context),
            onNewsItemClick = onNewsItemClick,
            onRetry         = onRetry,
            onTts           = onTts,
            onStopTts       = onStopTts,
            onOpenTtsSettings = onOpenTtsSettings,
            speakingIds     = speakingIds,
            playingIdProvider = { currentPlayingId },
        )
        VIEW_TYPE_USER -> UserViewHolder(
            binding        = ItemMessageUserBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onLongPress    = onUserMessageLongPress,
            onEdit         = onEdit,
            onSwitchBranch = onSwitchBranch,
        )
        else -> BotViewHolder(
            binding           = ItemMessageBotBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            renderer          = getOrCreateRenderer(parent.context),
            onRetry           = onRetry,
            onTts             = onTts,
            onStopTts         = onStopTts,
            onOpenTtsSettings = onOpenTtsSettings,
            speakingIds       = speakingIds,
            playingIdProvider = { currentPlayingId },
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is UserViewHolder    -> holder.bind(getItem(position), position)
            is BotViewHolder     -> {
                // ✅ Lấy user message trước bot message
                val userMsg = if (position > 0) getItem(position - 1) else null
                holder.bind(getItem(position), userMsg)
            }
            is BotNewsViewHolder -> {
                val userMsg = if (position > 0) getItem(position - 1) else null
                holder.bind(getItem(position), userMsg)   // ✅ truyền userMsg
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: List<Any>
    ) {
        if (payloads.isNotEmpty() && holder is BotViewHolder) {
            val newContent = payloads.last() as? String ?: ""
            holder.updateContent(newContent)
            return
        }
        onBindViewHolder(holder, position)
    }

    // ── UserViewHolder ────────────────────────────────────────────────────────

    class UserViewHolder(
        private val binding: ItemMessageUserBinding,
        private val onLongPress: ((Int) -> Unit)? = null,
        private val onEdit:  ((Message, String) -> Unit)? = null,
        private val onSwitchBranch: ((pivotId: String, delta: Int) -> Unit)? = null,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var lastTouchX = 0f
        private var lastTouchY = 0f

        @SuppressLint("ClickableViewAccessibility")
        fun bind(msg: Message, position: Int) {
            binding.tvContent.text = msg.content
            binding.tvTime.text    = ChatMessageAdapter.formatTime(msg.timestamp)
            binding.tvContent.setTextIsSelectable(true)

            // ── Ghi tọa độ ngón tay ──────────────────────────────────────────────
            binding.root.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                }
                false
            }

            // ── Long press ───────────────────────────────────────────────────────
            binding.root.setOnLongClickListener {
                if (msg.id.startsWith("streaming_") || msg.id.startsWith("opt_")) {
                    return@setOnLongClickListener true
                }
                binding.root.performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS
                )
                if (onEdit != null) {
                    MessageActionsPopup.show(
                        context    = binding.root.context,
                        anchorView = binding.root,
                        touchX     = lastTouchX,
                        touchY     = lastTouchY,
                        message    = msg,
                        listener   = object : MessageActionsPopup.Listener {
                            override fun onSelectText(message: Message) {
                                // ✅ Hiện bottom sheet chọn văn bản
                                showSelectTextSheet(binding.root.context, message.content)
                            }
                            override fun onEdit(message: Message, newContent: String) {
                                onEdit.invoke(message, newContent)
                            }
                        }
                    )
                } else {
                    onLongPress?.invoke(position)
                }
                true
            }


            val pivotKey = when {
                BranchManager.hasBranches(msg.id)                            -> msg.id
                msg.parentMessageId != null &&
                        BranchManager.hasBranches(msg.parentMessageId)           -> msg.parentMessageId
                else                                                          -> null
            }

            val navLayout = binding.root.findViewById<View>(R.id.layoutBranchNav)

            if (pivotKey != null && navLayout != null) {
                val branches = BranchManager.getBranchesAt(pivotKey)
                val curIdx   = BranchManager.getCurrentIndexAt(pivotKey)

                navLayout.visibility = View.VISIBLE

                val tvIndex = navLayout.findViewById<TextView>(R.id.tvBranchIndex)
                val btnPrev = navLayout.findViewById<TextView>(R.id.btnPrevBranch)
                val btnNext = navLayout.findViewById<TextView>(R.id.btnNextBranch)

                tvIndex?.text  = "${curIdx + 1} / ${branches.size}"
                btnPrev?.alpha = if (curIdx > 0) 1f else 0.3f
                btnNext?.alpha = if (curIdx < branches.size - 1) 1f else 0.3f

                btnPrev?.setOnClickListener {
                    if (curIdx > 0) onSwitchBranch?.invoke(pivotKey, -1)
                }
                btnNext?.setOnClickListener {
                    if (curIdx < branches.size - 1) onSwitchBranch?.invoke(pivotKey, +1)
                }
            } else {
                navLayout?.visibility = View.GONE
            }
        }

        private fun showSelectTextSheet(context: Context, content: String) {
            val dialog = BottomSheetDialog(context, R.style.BottomSheetGitHubTheme)
            val sheetView = LayoutInflater.from(context)
                .inflate(R.layout.bottom_sheet_response, null)

            // Dùng lại layout bottom_sheet_response
            // Nhưng thay expandedContentContainer bằng TextView selectable
            val container = sheetView.findViewById<LinearLayout>(R.id.expandedContentContainer)
            val tv = TextView(context).apply {
                text = content
                setTextIsSelectable(true)  // ✅ cho phép chọn văn bản
                textSize = 15f
                setTextColor(0xFFE6EDF3.toInt())
                setPadding(0, 8, 0, 8)
            }
            container.addView(tv)

            // Copy all
            val tvCopyAllLabel = sheetView.findViewById<TextView>(R.id.tvCopyAllLabel)
            sheetView.findViewById<LinearLayout>(R.id.btnCopyAll).setOnClickListener {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("message", content))
                tvCopyAllLabel.text = "Copied!"
                tvCopyAllLabel.setTextColor(0xFF3FB950.toInt())
                it.postDelayed({
                    tvCopyAllLabel.text = "Copy all"
                    tvCopyAllLabel.setTextColor(0xFF8B949E.toInt())
                }, 2000)
            }

            dialog.setContentView(sheetView)
            dialog.behavior.apply {
                state         = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
            dialog.show()
        }
    }

    // ── BotViewHolder (giữ nguyên) ────────────────────────────────────────────

    class BotViewHolder(
        private val binding: ItemMessageBotBinding,
        private val renderer: MarkdownRenderer,
        private val onRetry: ((Message) -> Unit)? = null,
        private val onTts: (msgId: String, content: String, onLoadingEnd: () -> Unit) -> Unit = { _, _, _ -> },
        private val onStopTts: () -> Unit = {},
        private val onOpenTtsSettings: () -> Unit = {},
        private val speakingIds: MutableSet<String>,
        private val playingIdProvider: () -> String?,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var isSpeaking = false
        private var currentClean: String = ""
        private var boundMessageId: String = ""


        fun updateContent(content: String) {
            if (content.isEmpty()) return
            boundMessageId = ""
            currentClean   = content.replace(
                Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), ""
            )
            binding.lottieTyping.cancelAnimation()
            binding.lottieTyping.visibility    = View.GONE
            binding.contentContainer.visibility = View.VISIBLE
            binding.divider.visibility          = View.VISIBLE
            binding.actionButtons.visibility    = View.VISIBLE
            isSpeaking = false
            binding.btnStop.visibility  = View.GONE
            binding.btnSpeak.visibility = View.VISIBLE
            renderer.renderStreaming(binding.contentContainer, currentClean)
        }

        fun bind(msg: Message, userMessage: Message? = null) {
            val msgKey = "${msg.id}_${msg.content.length}"
            if (boundMessageId == msgKey) return
            boundMessageId = msgKey

            binding.contentContainer.removeAllViews()
            binding.tvTime.text = formatTime(msg.timestamp)
            binding.btnRetry.setOnClickListener {
                if (userMessage != null) {
                    onRetry?.invoke(userMessage)  // ✅ truyền user message
                }
            }
            val isLoading  = speakingIds.contains(msg.id)
            val isPlaying  = playingIdProvider() == msg.id
            binding.btnRetry.visibility = if (
                msg.content.isEmpty() || userMessage == null || userMessage.sender != "user"
            ) View.GONE else View.VISIBLE
            if (msg.content.isEmpty()) {
                binding.lottieTyping.visibility    = View.VISIBLE
                binding.lottieTyping.playAnimation()
                binding.contentContainer.visibility = View.GONE
                binding.divider.visibility          = View.GONE
                binding.actionButtons.visibility    = View.GONE
                val isLoading = speakingIds.contains(msg.id)
                binding.ivSpeakIcon.visibility    = if (isLoading || isPlaying) View.GONE else View.VISIBLE
                binding.pbSpeakLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
                binding.btnStop.visibility        = if (isPlaying) View.VISIBLE else View.GONE
                binding.btnSpeak.isClickable      = !isLoading && !isPlaying
            } else {
                currentClean = msg.content.replace(
                    Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), ""
                )
                binding.lottieTyping.cancelAnimation()
                binding.lottieTyping.visibility    = View.GONE
                binding.contentContainer.visibility = View.VISIBLE
                binding.divider.visibility          = View.VISIBLE
                binding.actionButtons.visibility    = View.VISIBLE
                binding.btnStop.visibility          = View.GONE
                val isLoading = speakingIds.contains(msg.id)
                Log.d("SPEAKING", "bind msgId=${msg.id}, isLoading=$isLoading, speakingIds=$speakingIds")
                binding.ivSpeakIcon.visibility    = if (isLoading || isPlaying) View.GONE else View.VISIBLE
                binding.pbSpeakLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
                binding.btnStop.visibility        = if (isPlaying) View.VISIBLE else View.GONE
                binding.btnSpeak.isClickable      = !isLoading && !isPlaying

                renderer.render(binding.contentContainer, currentClean)

                binding.btnCopy.setOnClickListener {
                    val cb = itemView.context
                        .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cb.setPrimaryClip(
                        android.content.ClipData.newPlainText("message", msg.content)
                    )
                    Toast.makeText(itemView.context, "✅ Đã sao chép", Toast.LENGTH_SHORT).show()
                }

                binding.btnSpeak.setOnClickListener {
                    if (speakingIds.contains(msg.id) || playingIdProvider() == msg.id) return@setOnClickListener
                    speakingIds.add(msg.id)
                    binding.ivSpeakIcon.visibility    = View.GONE
                    binding.pbSpeakLoading.visibility = View.VISIBLE
                    binding.btnSpeak.isClickable      = false
                    onTts(msg.id, TtsTextCleaner.clean(currentClean)) {
                        speakingIds.remove(msg.id)
                        binding.ivSpeakIcon.visibility    = View.GONE  // btnStop sẽ hiện thay
                        binding.pbSpeakLoading.visibility = View.GONE
                    }
                }
                binding.btnSpeak.setOnLongClickListener {
                    onOpenTtsSettings()
                    true
                }
                binding.btnStop.setOnClickListener {
                    onStopTts()
                }

                binding.btnExpand.setOnClickListener {
                    showFullResponseSheet(itemView.context, msg.content, currentClean)
                }
            }
        }

        private fun showFullResponseSheet(context: Context, rawContent: String, cleanContent: String) {
            val dialog = BottomSheetDialog(
                context, R.style.BottomSheetGitHubTheme
            )
            val sheetView = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_response, null)
            val container = sheetView.findViewById<LinearLayout>(R.id.expandedContentContainer)
            renderer.render(container, cleanContent)

            val tvCopyAllLabel = sheetView.findViewById<TextView>(R.id.tvCopyAllLabel)
            sheetView.findViewById<LinearLayout>(R.id.btnCopyAll).setOnClickListener {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("response", rawContent))
                tvCopyAllLabel.text = "Copied!"
                tvCopyAllLabel.setTextColor(0xFF3FB950.toInt())
                it.postDelayed({
                    tvCopyAllLabel.text = "Copy all"
                    tvCopyAllLabel.setTextColor(0xFF8B949E.toInt())
                }, 2000)
            }

            dialog.setContentView(sheetView)
            dialog.behavior.apply {
                state         = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
            dialog.show()
        }
    }
    fun setPlayingId(id: String?) {                            // ← thêm
        currentPlayingId = id
        notifyDataSetChanged()
    }
    // ── BotNewsViewHolder (giữ nguyên) ────────────────────────────────────────
    fun updateSpeakingIds(ids: Set<String>) {
        speakingIds.clear()
        speakingIds.addAll(ids)
        notifyDataSetChanged()
    }
    class BotNewsViewHolder(
        private val binding: ItemMessageBotNewsBinding,
        private val renderer: MarkdownRenderer,
        private val onNewsItemClick: (articleId: Int?, title: String, description: String) -> Unit,
        private val onRetry: (Message) -> Unit = {},
        private val onTts: (msgId: String, content: String, onLoadingEnd: () -> Unit) -> Unit = { _, _, _ -> },
        private val onStopTts: () -> Unit = {},
        private val onOpenTtsSettings: () -> Unit = {},
        private val playingIdProvider: () -> String?,
        private val speakingIds: MutableSet<String>,

    ) : RecyclerView.ViewHolder(binding.root) {

        private var isExpanded = false
        private var currentClean: String = ""
        fun bind(msg: Message, userMessage: Message?) {
            val newsItems = extractNewsItems(msg)
            binding.contentContainer.removeAllViews()

            currentClean = msg.content.replace(
                Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), ""
            ).trim()
            renderer.render(binding.contentContainer, currentClean)
            binding.tvTime.text = formatTime(msg.timestamp)
            binding.tvNewsCount.text = "📰 ${newsItems.size} bài liên quan • nhấn để xem"
            binding.divider.visibility       = View.VISIBLE
            binding.actionButtons.visibility = View.VISIBLE
            binding.btnStop.visibility       = View.GONE

            binding.btnRetry.visibility = if (userMessage?.sender == "user") View.VISIBLE else View.GONE
            binding.btnRetry.setOnClickListener { userMessage?.let { onRetry(it) } }

            binding.btnCopy.setOnClickListener {
                val cb = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("message", msg.content))
                Toast.makeText(itemView.context, "✅ Đã sao chép", Toast.LENGTH_SHORT).show()
            }

            // ── Render spinner state khi recycle ──────────────────────────────────
            val isLoading  = speakingIds.contains(msg.id)
            val isPlaying  = playingIdProvider() == msg.id

            binding.ivSpeakIcon.visibility    = if (isLoading || isPlaying) View.GONE else View.VISIBLE
            binding.pbSpeakLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.btnStop.visibility        = if (isPlaying) View.VISIBLE else View.GONE
            binding.btnSpeak.isClickable      = !isLoading && !isPlaying

            binding.btnSpeak.setOnClickListener {
                if (speakingIds.contains(msg.id) || playingIdProvider() == msg.id) return@setOnClickListener
                speakingIds.add(msg.id)
                binding.ivSpeakIcon.visibility    = View.GONE
                binding.pbSpeakLoading.visibility = View.VISIBLE
                binding.btnSpeak.isClickable      = false
                onTts(msg.id, TtsTextCleaner.clean(currentClean)) {
                    speakingIds.remove(msg.id)
                    binding.ivSpeakIcon.visibility    = View.GONE  // btnStop sẽ hiện thay
                    binding.pbSpeakLoading.visibility = View.GONE
                }
            }

            binding.btnStop.setOnClickListener {
                speakingIds.remove(msg.id)
                binding.btnStop.visibility        = View.GONE
                binding.btnSpeak.visibility       = View.VISIBLE
                binding.ivSpeakIcon.visibility    = View.VISIBLE
                binding.pbSpeakLoading.visibility = View.GONE
                binding.btnSpeak.isClickable      = true
            }

            binding.btnExpand.setOnClickListener {
                showFullResponseSheet(itemView.context, msg.content, currentClean, newsItems)
            }
            binding.btnSpeak.setOnLongClickListener {
                onOpenTtsSettings()
                true
            }

            binding.btnStop.setOnClickListener {
                onStopTts()
            }

            isExpanded = false
            binding.newsListContainer.visibility = View.GONE
            binding.btnToggleNews.text = "Xem danh sách ▼"
            binding.btnToggleNews.setOnClickListener { toggle(newsItems) }
            binding.tvNewsCount.setOnClickListener   { toggle(newsItems) }
        }
        private fun showFullResponseSheet(
            context: Context,
            rawContent: String,
            cleanContent: String,
            newsItems: List<NewsItemData>   // ✅ thêm param
        ) {
            val dialog = BottomSheetDialog(context, R.style.BottomSheetGitHubTheme)
            val sheetView = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_response, null)

            // Render markdown content
            val container = sheetView.findViewById<LinearLayout>(R.id.expandedContentContainer)
            renderer.render(container, cleanContent)

            // ✅ Thêm news list vào cuối expandedContentContainer
            if (newsItems.isNotEmpty()) {
                // Divider
                val divider = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).also { it.setMargins(0, 16, 0, 12) }
                    setBackgroundColor(0x20FFFFFF)
                }
                container.addView(divider)

                // Badge
                val badge = TextView(context).apply {
                    text = "📰 ${newsItems.size} bài liên quan"
                    textSize = 13f
                    setTextColor(0xFF90CAF9.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = 8 }
                }
                container.addView(badge)

                // Inflate từng item_news_row
                newsItems.forEach { item ->
                    val rowView = LayoutInflater.from(context)
                        .inflate(R.layout.item_news_row, container, false)

                    rowView.findViewById<TextView>(R.id.tvNewsTitle)?.text =
                        "${item.number}. ${item.title}"
                    rowView.findViewById<TextView>(R.id.tvNewsDesc)?.text =
                        item.description.take(120) + if (item.description.length > 120) "..." else ""

                    val tvCategory = rowView.findViewById<TextView>(R.id.tvNewsCategory)
                    if (!item.category.isNullOrBlank()) {
                        tvCategory?.text = item.category
                        tvCategory?.visibility = View.VISIBLE
                    } else {
                        tvCategory?.visibility = View.GONE
                    }

                    val ivThumb = rowView.findViewById<ImageView>(R.id.ivThumbnail)
                    val ivPlay  = rowView.findViewById<ImageView>(R.id.ivPlayIcon)
                    if (!item.thumbnail.isNullOrBlank()) {
                        ivThumb?.visibility = View.VISIBLE
                        com.bumptech.glide.Glide.with(context)
                            .load(item.thumbnail).centerCrop().into(ivThumb!!)
                        ivPlay?.visibility = if (item.mediaType == "video") View.VISIBLE else View.GONE
                    } else {
                        ivThumb?.visibility = View.GONE
                        ivPlay?.visibility  = View.GONE
                    }

                    rowView.setOnClickListener {
                        dialog.dismiss()   // ✅ đóng sheet rồi mới navigate
                        onNewsItemClick(item.articleId, item.title, item.description)
                    }
                    container.addView(rowView)
                }
            }

            // Copy all
            val tvCopyAllLabel = sheetView.findViewById<TextView>(R.id.tvCopyAllLabel)
            sheetView.findViewById<LinearLayout>(R.id.btnCopyAll).setOnClickListener {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("response", rawContent))
                tvCopyAllLabel.text = "Copied!"
                tvCopyAllLabel.setTextColor(0xFF3FB950.toInt())
                it.postDelayed({
                    tvCopyAllLabel.text = "Copy all"
                    tvCopyAllLabel.setTextColor(0xFF8B949E.toInt())
                }, 2000)
            }

            dialog.setContentView(sheetView)
            dialog.behavior.state         = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            dialog.behavior.skipCollapsed = true
            dialog.show()
        }
        private fun toggle(newsItems: List<NewsItemData>) {
            isExpanded = !isExpanded
            if (isExpanded) {
                binding.newsListContainer.visibility = View.VISIBLE
                binding.btnToggleNews.text = "Thu gọn ▲"
                populateNewsList(newsItems)
            } else {
                binding.newsListContainer.visibility = View.GONE
                binding.btnToggleNews.text = "Xem danh sách ▼"
            }
        }

        private fun populateNewsList(items: List<NewsItemData>) {
            binding.newsListContainer.removeAllViews()
            val inflater = LayoutInflater.from(binding.root.context)

            items.forEachIndexed { _, item ->
                val rowView = inflater.inflate(R.layout.item_news_row, binding.newsListContainer, false)

                rowView.findViewById<android.widget.TextView>(R.id.tvNewsTitle)?.text =
                    "${item.number}. ${item.title}"
                rowView.findViewById<android.widget.TextView>(R.id.tvNewsDesc)?.text =
                    item.description.take(120) + if (item.description.length > 120) "..." else ""

                val tvCategory = rowView.findViewById<android.widget.TextView>(R.id.tvNewsCategory)
                if (!item.category.isNullOrBlank()) {
                    tvCategory?.text = item.category
                    tvCategory?.visibility = View.VISIBLE
                } else {
                    tvCategory?.visibility = View.GONE
                }

                val ivThumb = rowView.findViewById<android.widget.ImageView>(R.id.ivThumbnail)
                val ivPlay  = rowView.findViewById<android.widget.ImageView>(R.id.ivPlayIcon)

                if (!item.thumbnail.isNullOrBlank()) {
                    ivThumb?.visibility = View.VISIBLE
                    com.bumptech.glide.Glide.with(binding.root.context)
                        .load(item.thumbnail).centerCrop().into(ivThumb!!)
                    ivPlay?.visibility = if (item.mediaType == "video") View.VISIBLE else View.GONE
                } else {
                    ivThumb?.visibility = View.GONE
                    ivPlay?.visibility  = View.GONE
                }

                rowView.setOnClickListener {
                    onNewsItemClick(item.articleId, item.title, item.description)
                }
                binding.newsListContainer.addView(rowView)
            }
        }
    }

    // ── DiffCallback (giữ nguyên) ─────────────────────────────────────────────

    class DiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(a: Message, b: Message) = a.id == b.id

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(a: Message, b: Message): Boolean {
            if (isStreamingPlaceholder(a) && isStreamingPlaceholder(b)) {
                return a.content == b.content
            }
            return a.id == b.id && a.content == b.content &&
                    a.sender == b.sender && a.extraData == b.extraData
        }

        override fun getChangePayload(oldItem: Message, newItem: Message): Any? {
            if (isStreamingPlaceholder(oldItem) && isStreamingPlaceholder(newItem)) {
                return newItem.content
            }
            return null
        }
    }
}