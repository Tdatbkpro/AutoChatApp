package com.example.autochat.ui.phone.fragment

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.autochat.R
import com.example.autochat.databinding.FragmentArticleDetailBinding
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.model.Article
import com.example.autochat.domain.model.MediaItem
import com.example.autochat.domain.repository.ChatRepository
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.MediaItem as ExoMediaItem
import com.google.android.exoplayer2.ui.StyledPlayerView
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil

class ArticleDetailFragment : Fragment(), TextToSpeech.OnInitListener {

    private var _binding: FragmentArticleDetailBinding? = null
    private val binding get() = _binding!!

    private val chatRepository: ChatRepository by lazy {
        EntryPointAccessors.fromApplication(
            requireContext().applicationContext,
            ChatEntryPoint::class.java
        ).chatRepository()
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var articleUrl: String? = null
    private var fullText: String = ""
    private var articleId: Int = -1
    private var exoPlayer: ExoPlayer? = null
    private var fullscreenDialog: android.app.Dialog? = null
    private var fullscreenPlayer: ExoPlayer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(requireContext(), this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val langResult = tts?.setLanguage(Locale("vi", "VN"))
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.US
            }
            ttsReady = true

            // ✅ Debug listener
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    android.util.Log.d("TTS", "Started: $utteranceId")
                }
                override fun onDone(utteranceId: String?) {
                    android.util.Log.d("TTS", "Done: $utteranceId")
                    activity?.runOnUiThread {
                        binding.fabTts.setImageResource(R.drawable.ic_headphones)
                    }
                }
                override fun onError(utteranceId: String?) {
                    android.util.Log.e("TTS", "Error: $utteranceId")
                }
            })

            android.util.Log.d("TTS", "TTS initialized with language: ${tts?.language}")
        } else {
            android.util.Log.e("TTS", "Init failed: $status")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArticleDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupClickListeners()

        articleId = arguments?.getInt("articleId", -1) ?: -1
        val fallbackTitle = arguments?.getString("title") ?: ""
        val fallbackDesc = arguments?.getString("description") ?: ""

        binding.tvTitle.text = fallbackTitle
        fullText = "$fallbackTitle. $fallbackDesc"

        if (articleId != -1) {
            loadArticle(articleId, fallbackTitle, fallbackDesc)
        } else {
            showContent()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()  // ✅ Nút back
        }
        binding.toolbar.title = "Chi tiết"

        binding.btnShare.setOnClickListener { shareArticle() }
        binding.btnSettings.setOnClickListener {
            Toast.makeText(requireContext(), "Cài đặt sau", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupClickListeners() {
        binding.fabTts.setOnClickListener { toggleTts() }
        binding.btnRetry.setOnClickListener {
            loadArticle(articleId,
                arguments?.getString("title") ?: "",
                arguments?.getString("description") ?: "")
        }
    }

    private fun loadArticle(articleId: Int, fallbackTitle: String, fallbackDesc: String) {
        showLoading()
        lifecycleScope.launch {
            try {
                val article = chatRepository.getArticleById(articleId)
                if (article != null) {
                    renderArticle(article)
                } else {
                    showFallback(fallbackTitle, fallbackDesc)
                }
            } catch (e: Exception) {
                android.util.Log.e("ARTICLE_DETAIL", "loadArticle error", e)
                showFallback(fallbackTitle, fallbackDesc)
            }
        }
    }

    private fun renderArticle(article: Article) {
        binding.apply {
            tvTitle.text = article.title
            toolbar.title = article.title?.take(30)?.let {
                it + if ((article.title?.length ?: 0) > 30) "..." else ""
            } ?: "Chi tiết"

            // ✅ Category chip
            if (!article.category.isNullOrBlank()) {
                chipCategory.text = article.category.uppercase()
                chipCategory.visibility = View.VISIBLE
            } else {
                chipCategory.visibility = View.GONE
            }

            // ✅ Author
            if (!article.author.isNullOrBlank()) {
                tvAuthor.text = article.author
                tvAuthorMeta.text = article.publishedDate ?: ""
                authorBar.visibility = View.VISIBLE
            }

            tvDate.text = article.publishedDate ?: ""
            val readTime = maxOf(1, ceil((article.content?.length ?: 0) / 1000.0).toInt())
            tvReadTime.text = "${readTime} phút đọc"

            if (!article.description.isNullOrBlank()) {
                tvDescription.text = article.description
                tvDescription.visibility = View.VISIBLE
            } else {
                tvDescription.visibility = View.GONE
            }

            // ✅ Link button
            articleUrl = article.url
            if (!article.url.isNullOrBlank()) {
                btnSourceLink.visibility = View.VISIBLE
                btnSourceLink.setOnClickListener {
                    openUrl(article.url)
                }
            } else {
                btnSourceLink.visibility = View.GONE
            }

            renderMixedContent(article)

            fullText = buildString {
                append(article.title ?: "")
                append(". ")
                if (!article.description.isNullOrBlank()) {
                    append(article.description)
                    append(". ")
                }
                // Lấy text content đã clean (không có placeholder)
                val cleanContent = article.content
                    ?.replace(Regex("\\[HÌNH ẢNH:[^\\]]*\\]"), "")
                    ?.replace(Regex("\\[VIDEO:[^\\]]*\\]"), "")
                    ?.replace(Regex("<[^>]*>"), "")
                    ?: ""
                append(cleanContent.take(5000))
            }

            android.util.Log.d("TTS", "fullText length: ${fullText.length}")

            showContent()
        }
    }

    // ✅ Thêm hàm openUrl
    private fun openUrl(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            )
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Không thể mở link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderMixedContent(article: Article) {
        val container = binding.mixedContentContainer
        container.removeAllViews()

        val content = article.content ?: ""
        val mediaItems = article.mediaItems ?: emptyList()

        // Tạo map: vị trí [HÌNH ẢNH] -> MediaItem
        val mediaMap = mutableMapOf<Int, MediaItem>()
        var mediaIndex = 0

        // Tìm tất cả vị trí [HÌNH ẢNH] và [VIDEO]
        val mediaPlaceholders = Regex("\\[(HÌNH ẢNH|VIDEO):[^\\]]*\\]").findAll(content).toList()

        // Parse content và chèn media vào đúng vị trí
        var lastIndex = 0
        for (placeholder in mediaPlaceholders) {
            // Add text trước placeholder
            val textBefore = content.substring(lastIndex, placeholder.range.first).trim()
            if (textBefore.isNotBlank()) {
                container.addView(createTextView(textBefore))
            }

            // Add media tương ứng
            if (mediaIndex < mediaItems.size) {
                val media = mediaItems[mediaIndex]
                when (media.type) {
                    "image" -> container.addView(createImageView(media))
                    "video" -> container.addView(createVideoView(media))
                }
                mediaIndex++
            }

            lastIndex = placeholder.range.last + 1
        }

        // Add text còn lại sau placeholder cuối cùng
        val textAfter = content.substring(lastIndex).trim()
        if (textAfter.isNotBlank()) {
            container.addView(createTextView(textAfter))
        }
    }

    private fun createTextView(text: String): TextView {
        return TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
                bottomMargin = 12
            }
            this.text = cleanText(text)
            setTextColor(0xFFD0D0E8.toInt())
            textSize = 15f
            setLineSpacing(0f, 1.65f)
            setTextIsSelectable(true)  // ✅ Đã có rồi
            // Thêm custom selection action mode để có nút copy
            customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode?, menu: Menu?): Boolean {
                    menu?.add(0, android.R.id.copy, 0, "Copy")?.setIcon(android.R.drawable.ic_menu_edit)
                    return true
                }
                override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: Menu?): Boolean = false
                override fun onActionItemClicked(mode: android.view.ActionMode?, item: MenuItem?): Boolean {
                    if (item?.itemId == android.R.id.copy) {
                        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("text", this@apply.text)
                        clipboard.setPrimaryClip(clip)
                        mode?.finish()
                        Toast.makeText(requireContext(), "Đã copy", Toast.LENGTH_SHORT).show()
                        return true
                    }
                    return false
                }
                override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
            }
        }
    }

    private fun createImageView(media: MediaItem): View {
        val container = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
            orientation = LinearLayout.VERTICAL
        }

        val imageView = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(220)
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1A1A2E.toInt())
            isClickable = true
            isFocusable = true

            // ✅ Sửa: chỉ truyền url
            setOnClickListener {
                showFullscreenImage(media.url)
            }
        }

        Glide.with(this)
            .load(media.url)
            .placeholder(R.drawable.placeholder_image)
            .error(R.drawable.placeholder_image)
            .centerCrop()
            .into(imageView)

        container.addView(imageView)

        (media.caption ?: media.description)?.let { caption ->
            val captionView = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6 }
                text = cleanText(caption)
                textSize = 12f
                setTextColor(0xFF888899.toInt())
                setTypeface(typeface, Typeface.ITALIC)
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(16, 4, 16, 4)
            }
            container.addView(captionView)
        }

        return container
    }

    private fun createVideoView(media: MediaItem): View {
        val container = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
            orientation = LinearLayout.VERTICAL
        }

        // FrameLayout để overlay nút fullscreen
        val frameLayout = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(220)
            )
        }

        val playerView = PlayerView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = true
        }

        exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
            setMediaItem(ExoMediaItem.fromUri(Uri.parse(media.url ?: "")))
            prepare()
            playWhenReady = false
        }
        playerView.player = exoPlayer

        frameLayout.addView(playerView)

        // ✅ Nút fullscreen overlay
        val btnFullscreen = ImageView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(30),
                dpToPx(30),
                android.view.Gravity.BOTTOM or android.view.Gravity.END
            ).apply {
                bottomMargin = 100
            }
            setImageResource(R.drawable.ic_zoom)
            setColorFilter(0xFFFFFFFF.toInt())
            setBackgroundColor(0x80000000.toInt())
            setPadding(6, 6, 6, 6)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                openFullscreenVideo(media.url)
            }
        }
        frameLayout.addView(btnFullscreen)

        container.addView(frameLayout)

        (media.caption ?: media.description)?.let { caption ->
            val captionView = TextView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6 }
                text = cleanText(caption)
                textSize = 12f
                setTextColor(0xFF888899.toInt())
                setTypeface(typeface, Typeface.ITALIC)
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }
            container.addView(captionView)
        }

        return container
    }

    // ✅ Mở fullscreen video
    private fun openFullscreenVideo(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(url), "video/*")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: mở browser
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
        }
    }

    // ✅ Chỉ còn dialog cho ảnh
    private fun showFullscreenImage(url: String?) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_media_fullscreen, null)

        val ivFullscreen = dialogView.findViewById<PhotoView>(R.id.ivFullscreen)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnClose)

        Glide.with(this)
            .load(url)
            .into(ivFullscreen)

        fullscreenDialog = android.app.Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            setContentView(dialogView)
            setCancelable(true)
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            show()
        }

        btnClose.setOnClickListener { fullscreenDialog?.dismiss() }
        ivFullscreen.setOnClickListener { fullscreenDialog?.dismiss() }
    }



    private fun cleanText(text: String): String {
        return text
            .replace(Regex("<[^>]*>"), "")
            .replace(Regex("&[a-z]+;"), "")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .trim()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun showLoading() {
        binding.progressBar.isVisible = true
        binding.scrollView.isVisible = false
        binding.errorContainer.isVisible = false
    }

    private fun showContent() {
        binding.progressBar.isVisible = false
        binding.scrollView.isVisible = true
    }

    private fun showFallback(title: String, description: String) {
        binding.tvTitle.text = title
        binding.tvDescription.text = description
        binding.tvDescription.visibility = View.VISIBLE
        fullText = "$title. $description"
        showContent()
    }

    private fun toggleTts() {
        if (tts?.isSpeaking == true) {
            tts?.stop()
            binding.fabTts.setImageResource(R.drawable.ic_headphones)
            Toast.makeText(requireContext(), "Đã dừng đọc", Toast.LENGTH_SHORT).show()
        } else {
            if (!ttsReady) {
                Toast.makeText(requireContext(), "TTS đang khởi tạo, thử lại...", Toast.LENGTH_SHORT).show()
                return
            }

            if (fullText.isBlank()) {
                Toast.makeText(requireContext(), "Không có nội dung", Toast.LENGTH_SHORT).show()
                return
            }

            // ✅ Giới hạn text 4000 ký tự (giới hạn của TTS)
            val textToSpeak = if (fullText.length > 4000) fullText.take(4000) else fullText

            // ✅ Set speech rate chậm hơn
            tts?.setSpeechRate(0.9f)

            val utteranceId = "article_${System.currentTimeMillis()}"
            val result = tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

            android.util.Log.d("TTS", "speak result: $result, text length: ${textToSpeak.length}")

            if (result == TextToSpeech.SUCCESS) {
                binding.fabTts.setImageResource(R.drawable.ic_pause)
            } else {
                // ✅ Thử lại lần nữa
                ttsReady = false
                tts = TextToSpeech(requireContext(), this)
                Toast.makeText(requireContext(), "Đang thử lại...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareArticle() {
        articleUrl?.let {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, it)
            }
            startActivity(android.content.Intent.createChooser(intent, "Chia sẻ"))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts?.stop()
        exoPlayer?.release()
        exoPlayer = null
        fullscreenPlayer?.release()
        fullscreenPlayer = null
        fullscreenDialog?.dismiss()
        fullscreenDialog = null
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }
}