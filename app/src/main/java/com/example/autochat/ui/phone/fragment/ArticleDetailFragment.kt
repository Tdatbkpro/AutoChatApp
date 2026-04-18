package com.example.autochat.ui.phone.fragment

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope

import com.example.autochat.R
import com.example.autochat.databinding.FragmentArticleDetailBinding
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.model.Article
import com.example.autochat.domain.repository.ChatRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Hiển thị chi tiết bài báo trên điện thoại.
 * Có đầy đủ ảnh (khác với Android Auto không có ảnh).
 *
 * Args:
 *   articleId   : Int  — ID bài báo (−1 nếu không có)
 *   title       : String — tiêu đề fallback
 *   description : String — mô tả fallback
 *
 * Layout: fragment_article_detail.xml
 *   - toolbar          : Toolbar với nút back + share
 *   - ivThumbnail      : ImageView ảnh thumbnail (GONE nếu không có)
 *   - tvTitle          : tiêu đề bài báo
 *   - tvMeta           : "dd/MM/yyyy • Danh mục"
 *   - tvDescription    : mô tả / sapo
 *   - tvContent        : nội dung bài báo (đã clean)
 *   - progressBar      : loading
 *   - tvError          : thông báo lỗi
 *   - fabTts           : FloatingActionButton đọc bài
 *   - scrollView       : ScrollView bọc content
 */
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
    private var fullText: String = ""   // để TTS đọc

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        tts = TextToSpeech(requireContext(), this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("vi", "VN")
            ttsReady = true
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

        val articleId = arguments?.getInt("articleId", -1) ?: -1
        val fallbackTitle = arguments?.getString("title") ?: ""
        val fallbackDesc = arguments?.getString("description") ?: ""

        // Hiện fallback ngay lập tức trong khi load
        binding.tvTitle.text = fallbackTitle
        binding.tvDescription.text = fallbackDesc
        fullText = "$fallbackTitle. $fallbackDesc"

        if (articleId != -1) {
            loadArticle(articleId, fallbackTitle, fallbackDesc)
        } else {
            // Không có ID, chỉ dùng fallback
            binding.progressBar.visibility = View.GONE
            binding.scrollView.visibility = View.VISIBLE
        }

        binding.fabTts.setOnClickListener {
            toggleTts()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.apply {
            setNavigationOnClickListener {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
            title = "Chi tiết bài báo"
        }
    }

    private fun loadArticle(articleId: Int, fallbackTitle: String, fallbackDesc: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.scrollView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val article = chatRepository.getArticleById(articleId)

                if (article != null) {
                    renderArticle(article)
                } else {
                    // Fallback
                    binding.tvTitle.text = fallbackTitle
                    binding.tvDescription.text = fallbackDesc
                    binding.tvContent.text = fallbackDesc
                    fullText = "$fallbackTitle. $fallbackDesc"
                    binding.progressBar.visibility = View.GONE
                    binding.scrollView.visibility = View.VISIBLE
                }

            } catch (e: Exception) {
                android.util.Log.e("ARTICLE_DETAIL", "loadArticle error: ${e.message}")
                // Graceful fallback thay vì hiện error
                binding.tvTitle.text = fallbackTitle
                binding.tvDescription.text = fallbackDesc
                binding.progressBar.visibility = View.GONE
                binding.scrollView.visibility = View.VISIBLE
            }
        }
    }

    private fun renderArticle(article: Article) {
        binding.tvTitle.text = article.title
        binding.toolbar.title = article.title.take(40)

        // Meta: ngày + danh mục
        val meta = listOfNotNull(article.publishedDate, article.category)
            .joinToString(" • ")
        binding.tvMeta.text = meta
        binding.tvMeta.visibility = if (meta.isNotBlank()) View.VISIBLE else View.GONE

        // Description / sapo
        if (!article.description.isNullOrBlank()) {
            binding.tvDescription.text = article.description
            binding.tvDescription.visibility = View.VISIBLE
        } else {
            binding.tvDescription.visibility = View.GONE
        }

        // Content đã clean (bỏ [HÌNH ẢNH:...], [VIDEO:...], markdown, URL)
        val cleanContent = cleanContent(article.content ?: "")
        binding.tvContent.text = cleanContent

        // TTS sẽ đọc: title + description + content
        fullText = buildString {
            append(article.title)
            append(". ")
            if (!article.description.isNullOrBlank()) {
                append(article.description)
                append(". ")
            }
            append(cleanContent.take(3000))
        }

        // Link gốc
        articleUrl = article.url
        if (!article.url.isNullOrBlank()) {
            binding.tvSourceLink.visibility = View.VISIBLE
            binding.tvSourceLink.text = "🔗 Đọc bài gốc"
            binding.tvSourceLink.setOnClickListener {
                openUrl(article.url)
            }
        }

        binding.progressBar.visibility = View.GONE
        binding.scrollView.visibility = View.VISIBLE
    }

    /**
     * Xóa các placeholder media và markdown để hiển thị text sạch.
     * (Ảnh sẽ được hiển thị riêng trong layout nếu cần trong tương lai)
     */
    private fun cleanContent(content: String): String {
        return content
            .replace(Regex("\\[HÌNH ẢNH:[^\\]]*\\]"), "")
            .replace(Regex("\\[VIDEO:[^\\]]*\\]"), "")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), "")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    private fun toggleTts() {
        if (tts?.isSpeaking == true) {
            tts?.stop()
            binding.fabTts.setImageResource(android.R.drawable.ic_media_play)
            Toast.makeText(requireContext(), "Đã dừng đọc", Toast.LENGTH_SHORT).show()
        } else {
            if (!ttsReady) {
                Toast.makeText(requireContext(), "TTS chưa sẵn sàng", Toast.LENGTH_SHORT).show()
                return
            }
            tts?.speak(fullText, TextToSpeech.QUEUE_FLUSH, null, "article_tts")
            binding.fabTts.setImageResource(android.R.drawable.ic_media_pause)
            Toast.makeText(requireContext(), "Đang đọc bài báo...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            )
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Không mở được link", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_article, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share -> {
                shareArticle()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun shareArticle() {
        val url = articleUrl ?: return
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, url)
        }
        startActivity(android.content.Intent.createChooser(shareIntent, "Chia sẻ bài báo"))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts?.stop()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }
}