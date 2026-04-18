package com.example.autochat.ui.phone.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.R
import com.example.autochat.databinding.ItemMessageBotBinding
import com.example.autochat.databinding.ItemMessageBotNewsBinding
import com.example.autochat.databinding.ItemMessageUserBinding
import com.example.autochat.domain.model.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatMessageAdapter(
    private val onNewsItemClick: (articleId: Int?, title: String, description: String) -> Unit =
        { _, _, _ -> }
) : ListAdapter<Message, RecyclerView.ViewHolder>(DiffCallback()) {

    // ── Data class ────────────────────────────────────────────────────────

    data class NewsItemData(
        val number: Int,
        val title: String,
        val description: String,
        val articleId: Int?,
        val url: String?
    )

    // ── Companion object (duy nhất) ───────────────────────────────────────

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_BOT = 1
        private const val VIEW_TYPE_BOT_NEWS = 2

        fun isNewsList(msg: Message): Boolean {
            val extra = msg.extraData ?: return false
            if ((extra["type"] as? String) != "news_list") return false
            val items = extra["news_items"] as? List<*> ?: return false
            return items.isNotEmpty()
        }

        @Suppress("UNCHECKED_CAST")
        fun extractNewsItems(msg: Message): List<NewsItemData> {
            val extra = msg.extraData ?: return emptyList()
            val rawItems = extra["news_items"] as? List<*> ?: return emptyList()
            return rawItems.mapIndexedNotNull { index, raw ->
                val item = raw as? Map<*, *> ?: return@mapIndexedNotNull null
                NewsItemData(
                    number = (item["number"] as? Number)?.toInt() ?: (index + 1),
                    title = item["title"] as? String ?: "Không có tiêu đề",
                    description = item["description"] as? String ?: "",
                    articleId = (item["article_id"] as? Number)?.toInt(),
                    url = item["url"] as? String
                )
            }
        }

        fun formatTime(timestamp: Long): String = try {
            SimpleDateFormat("HH:mm", Locale("vi")).format(Date(timestamp))
        } catch (e: Exception) { "--:--" }
    }

    // ── Adapter overrides ─────────────────────────────────────────────────

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        return when {
            msg.sender == "user" -> VIEW_TYPE_USER
            msg.sender == "bot" && isNewsList(msg) -> VIEW_TYPE_BOT_NEWS
            else -> VIEW_TYPE_BOT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> UserViewHolder(
                ItemMessageUserBinding.inflate(inflater, parent, false)
            )
            VIEW_TYPE_BOT_NEWS -> BotNewsViewHolder(
                ItemMessageBotNewsBinding.inflate(inflater, parent, false),
                onNewsItemClick
            )
            else -> BotViewHolder(
                ItemMessageBotBinding.inflate(inflater, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is UserViewHolder -> holder.bind(getItem(position))
            is BotViewHolder -> holder.bind(getItem(position))
            is BotNewsViewHolder -> holder.bind(getItem(position))
        }
    }

    // ── ViewHolders ───────────────────────────────────────────────────────

    class UserViewHolder(
        private val binding: ItemMessageUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(msg: Message) {
            binding.tvContent.text = msg.content
            binding.tvTime.text = formatTime(msg.timestamp)
        }
    }

    class BotViewHolder(
        private val binding: ItemMessageBotBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(msg: Message) {
            binding.tvContent.text = msg.content
            binding.tvTime.text = formatTime(msg.timestamp)
        }
    }

    class BotNewsViewHolder(
        private val binding: ItemMessageBotNewsBinding,
        private val onNewsItemClick: (articleId: Int?, title: String, description: String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var isExpanded = false

        fun bind(msg: Message) {
            val newsItems = extractNewsItems(msg)

            binding.tvContent.text = msg.content
            binding.tvTime.text = formatTime(msg.timestamp)
            binding.tvNewsCount.text = "📰 ${newsItems.size} bài liên quan • nhấn để xem"

            // Reset khi RecyclerView recycle view
            isExpanded = false
            binding.newsListContainer.visibility = View.GONE
            binding.btnToggleNews.text = "Xem danh sách ▼"

            binding.btnToggleNews.setOnClickListener { toggle(newsItems) }
            binding.tvNewsCount.setOnClickListener { toggle(newsItems) }
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

            items.forEachIndexed { index, item ->
                val rowView = inflater.inflate(
                    R.layout.item_news_row,
                    binding.newsListContainer,
                    false
                )

                rowView.findViewById<android.widget.TextView>(R.id.tvNewsTitle)?.text =
                    "${item.number}. ${item.title}"

                rowView.findViewById<android.widget.TextView>(R.id.tvNewsDesc)?.text =
                    if (item.description.length > 100)
                        item.description.take(100) + "..."
                    else item.description

                rowView.setOnClickListener {
                    onNewsItemClick(item.articleId, item.title, item.description)
                }

                binding.newsListContainer.addView(rowView)

                // Divider giữa các item (trừ item cuối)
                if (index < items.lastIndex) {
                    val divider = View(binding.root.context).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).apply { setMargins(16, 4, 16, 4) }
                        setBackgroundColor(0x1AFFFFFF)
                    }
                    binding.newsListContainer.addView(divider)
                }
            }
        }
    }

    // ── DiffCallback ──────────────────────────────────────────────────────

    class DiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(a: Message, b: Message) = a.id == b.id
        override fun areContentsTheSame(a: Message, b: Message) = a == b
    }
}