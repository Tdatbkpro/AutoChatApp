package com.example.autochat.ui.phone.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.R
import com.example.autochat.databinding.ItemDrawerSessionBinding
import com.example.autochat.domain.model.ChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DrawerSessionAdapter(
    private val onSessionClick: (ChatSession) -> Unit,
    private val onDeleteClick: (ChatSession) -> Unit
) : ListAdapter<ChatSession, DrawerSessionAdapter.ViewHolder>(DiffCallback()) {

    private var selectedId: String? = null
    private var searchQuery: String = ""

    fun setSelected(id: String?) {
        selectedId = id
        notifyDataSetChanged()
    }

    fun setSearchQuery(query: String) {
        searchQuery = query
        // Filter sẽ được xử lý bên ngoài
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            ItemDrawerSessionBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemDrawerSessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(session: ChatSession) {
            // Highlight từ khóa tìm kiếm
            val title = if (searchQuery.isNotEmpty() && session.title.contains(searchQuery, ignoreCase = true)) {
                highlightText(session.title, searchQuery)
            } else {
                session.title
            }
            binding.tvSessionTitle.text = title

            // Hiển thị thời gian
            binding.tvSessionTime.text = formatTime(session.updatedAt)
            binding.tvSessionTime.visibility = View.VISIBLE

            // Highlight session đang active
            val isSelected = session.id == selectedId
            binding.root.setBackgroundResource(
                if (isSelected) R.drawable.bg_session_selected
                else android.R.color.transparent
            )
            binding.tvSessionTitle.setTextColor(
                if (isSelected) 0xFFFFFFFF.toInt() else 0xFFCCCCDD.toInt()
            )
            binding.tvSessionTime.setTextColor(
                if (isSelected) 0xFF8888AA.toInt() else 0xFF666677.toInt()
            )

            // Hiện nút delete khi selected hoặc hover
            binding.btnDeleteSession.visibility =
                if (isSelected) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onSessionClick(session) }
            binding.btnDeleteSession.setOnClickListener { onDeleteClick(session) }

            // Hiện delete khi long press
            binding.root.setOnLongClickListener {
                binding.btnDeleteSession.visibility = View.VISIBLE
                true
            }
        }

        private fun highlightText(text: String, query: String): android.text.SpannableString {
            val spannable = android.text.SpannableString(text)
            val startIndex = text.indexOf(query, ignoreCase = true)
            if (startIndex != -1) {
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(0xFF4A90E2.toInt()),
                    startIndex,
                    startIndex + query.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return spannable
        }

        private fun formatTime(timestamp: Long): String {
            return try {
                val date = Date(timestamp)
                val now = Date()
                val sdf = if (date.year == now.year && date.month == now.month && date.date == now.date) {
                    SimpleDateFormat("HH:mm", Locale("vi"))
                } else {
                    SimpleDateFormat("dd/MM/yyyy", Locale("vi"))
                }
                sdf.format(date)
            } catch (e: Exception) {
                "--:--"
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatSession>() {
        override fun areItemsTheSame(a: ChatSession, b: ChatSession) = a.id == b.id
        override fun areContentsTheSame(a: ChatSession, b: ChatSession) = a == b
    }
}