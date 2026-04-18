package com.example.autochat.ui.phone.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.R
import com.example.autochat.databinding.FragmentNewsBottomSheetBinding
import com.example.autochat.ui.phone.adapter.ChatMessageAdapter.NewsItemData
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Bottom sheet hiển thị danh sách bài báo từ một bot message.
 *
 * Cách dùng từ ChatFragment / SessionDetailFragment:
 *   NewsBottomSheetFragment.newInstance(newsItems).show(childFragmentManager, "news")
 *
 * Layout: fragment_news_bottom_sheet.xml
 *   - tvSheetTitle   : "Danh sách tin tức (5 bài)"
 *   - recyclerNews   : RecyclerView danh sách
 */
class NewsBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentNewsBottomSheetBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val ARG_TITLES = "arg_titles"
        private const val ARG_DESCS = "arg_descs"
        private const val ARG_IDS = "arg_ids"
        private const val ARG_URLS = "arg_urls"
        private const val ARG_NUMBERS = "arg_numbers"

        fun newInstance(items: List<NewsItemData>): NewsBottomSheetFragment {
            return NewsBottomSheetFragment().apply {
                arguments = bundleOf(
                    ARG_NUMBERS to items.map { it.number }.toIntArray(),
                    ARG_TITLES to items.map { it.title }.toTypedArray(),
                    ARG_DESCS to items.map { it.description }.toTypedArray(),
                    ARG_IDS to items.map { it.articleId ?: -1 }.toIntArray(),
                    ARG_URLS to items.map { it.url ?: "" }.toTypedArray()
                )
            }
        }
    }

    private fun parseItems(): List<NewsItemData> {
        val numbers = arguments?.getIntArray(ARG_NUMBERS) ?: return emptyList()
        val titles = arguments?.getStringArray(ARG_TITLES) ?: return emptyList()
        val descs = arguments?.getStringArray(ARG_DESCS) ?: return emptyList()
        val ids = arguments?.getIntArray(ARG_IDS) ?: return emptyList()
        val urls = arguments?.getStringArray(ARG_URLS) ?: return emptyList()

        return titles.indices.map { i ->
            NewsItemData(
                number = numbers.getOrElse(i) { i + 1 },
                title = titles[i],
                description = descs.getOrElse(i) { "" },
                articleId = ids.getOrElse(i) { -1 }.takeIf { it != -1 },
                url = urls.getOrElse(i) { "" }.ifEmpty { null }
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNewsBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val items = parseItems()

        binding.tvSheetTitle.text = "Danh sách tin tức (${items.size} bài)"

        val adapter = NewsListAdapter(items) { item ->
            // Điều hướng đến ArticleDetailFragment
            dismiss()
            val bundle = bundleOf(
                "articleId" to (item.articleId ?: -1),
                "title" to item.title,
                "description" to item.description
            )
            // Nếu dùng Navigation Component:
            try {
                findNavController().navigate(R.id.action_to_articleDetail, bundle)
            } catch (e: Exception) {
                // Fallback: dùng fragment transaction trực tiếp
                parentFragmentManager.beginTransaction()
                    .replace(R.id.navHostFragment, ArticleDetailFragment().apply { arguments = bundle })
                    .addToBackStack(null)
                    .commit()
            }
        }

        binding.recyclerNews.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
            addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Inner adapter ─────────────────────────────────────────────────────

    private class NewsListAdapter(
        private val items: List<NewsItemData>,
        private val onClick: (NewsItemData) -> Unit
    ) : RecyclerView.Adapter<NewsListAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_news_row, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(item: NewsItemData) {
                itemView.findViewById<TextView>(R.id.tvNewsTitle)?.text =
                    "${item.number}. ${item.title}"
                itemView.findViewById<TextView>(R.id.tvNewsDesc)?.text =
                    if (item.description.length > 120)
                        item.description.take(120) + "..."
                    else item.description
                itemView.setOnClickListener { onClick(item) }
            }
        }
    }
}