package com.example.autochat.ui.phone.adapter

import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.R
import com.example.autochat.databinding.ItemDrawerSessionBinding
import com.example.autochat.databinding.LayoutPopupSessionBinding
import com.example.autochat.domain.model.ChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DrawerSessionAdapter(
    private val onSessionClick: (ChatSession) -> Unit,
    private val onDeleteClick: (ChatSession) -> Unit,
    private val onPinClick: (ChatSession) -> Unit,
    private val onRenameClick: (ChatSession) -> Unit
) : ListAdapter<ChatSession, DrawerSessionAdapter.ViewHolder>(DiffCallback()) {

    private var selectedId: String? = null
    private var searchQuery: String = ""

    fun setSelected(id: String?) {
        selectedId = id
        notifyDataSetChanged()
    }

    fun setSearchQuery(query: String) {
        searchQuery = query
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
            val context = binding.root.context

            // Title với highlight search
            val title = if (searchQuery.isNotEmpty() &&
                session.title.contains(searchQuery, ignoreCase = true)) {
                highlightText(session.title, searchQuery)
            } else {
                session.title
            }
            binding.tvSessionTitle.text = title

            // Thời gian
            binding.tvSessionTime.text = formatTime(session.updatedAt)
            binding.tvSessionTime.visibility = View.VISIBLE

            // Pin badge
            binding.pinBadge.visibility = if (session.isPinned) View.VISIBLE else View.GONE

            // Selected state
            val isSelected = session.id == selectedId
            if (isSelected) {
                binding.root.setBackgroundResource(R.drawable.bg_session_selected)
                binding.tvSessionTitle.setTextColor(0xFFFFFFFF.toInt())
                binding.tvSessionTime.setTextColor(0xFF8888AA.toInt())
                binding.btnDeleteSession.visibility = View.VISIBLE
            } else {
                binding.root.setBackgroundResource(android.R.color.transparent)
                binding.tvSessionTitle.setTextColor(0xFFE0E0F0.toInt())
                binding.tvSessionTime.setTextColor(0xFF6B6B8A.toInt())
                binding.btnDeleteSession.visibility = View.GONE
            }

            // Click
            binding.root.setOnClickListener { onSessionClick(session) }
            binding.btnDeleteSession.setOnClickListener { onDeleteClick(session) }

            // Long press -> Popup menu
            binding.root.setOnLongClickListener { view ->
                showPopupMenu(view, session)
                true
            }
        }

        private fun showPopupMenu(anchor: View, session: ChatSession) {
            val context = anchor.context
            val popupBinding = LayoutPopupSessionBinding.inflate(LayoutInflater.from(context))

            val popupWindow = PopupWindow(
                popupBinding.root,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )
            popupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            popupWindow.elevation = 16f

            // Set pin action text
            popupBinding.tvPinAction.text = if (session.isPinned) "Bỏ ghim" else "Ghim"

            // Actions
            popupBinding.actionPin.setOnClickListener {
                onPinClick(session)
                popupWindow.dismiss()
            }
            popupBinding.actionRename.setOnClickListener {
                onRenameClick(session)
                popupWindow.dismiss()
            }
            popupBinding.actionDelete.setOnClickListener {
                onDeleteClick(session)
                popupWindow.dismiss()
            }

            // Tính toán vị trí hiển thị
            popupBinding.root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val popupHeight = popupBinding.root.measuredHeight
            val popupWidth = popupBinding.root.measuredWidth

            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            val anchorX = location[0]
            val anchorY = location[1]
            val anchorHeight = anchor.height

            val screenHeight = context.resources.displayMetrics.heightPixels
            val screenWidth = context.resources.displayMetrics.widthPixels

            // Mặc định hiện dưới ngón tay
            var yOffset = anchorY + anchorHeight / 2
            var xOffset = anchorX + anchor.width / 2 - popupWidth / 2

            // Nếu gần mép dưới -> đẩy lên trên
            if (anchorY + anchorHeight / 2 + popupHeight > screenHeight - 100) {
                yOffset = anchorY - popupHeight
            }

            // Nếu gần mép trên -> đẩy xuống dưới
            if (anchorY - popupHeight < 100) {
                yOffset = anchorY + anchorHeight / 2
            }

            // Căn chỉnh X không vượt quá màn hình
            if (xOffset < 16) xOffset = 16
            if (xOffset + popupWidth > screenWidth - 16) xOffset = screenWidth - popupWidth - 16

            popupWindow.showAtLocation(anchor, Gravity.NO_GRAVITY, xOffset, yOffset)
        }

        private fun highlightText(text: String, query: String): android.text.SpannableString {
            val spannable = android.text.SpannableString(text)
            val startIndex = text.indexOf(query, ignoreCase = true)
            if (startIndex != -1) {
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(0xFF6C63FF.toInt()),
                    startIndex,
                    startIndex + query.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
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
                val diff = now.time - date.time

                when {
                    diff < 60_000 -> "Vừa xong"
                    diff < 3600_000 -> "${diff / 60_000} phút trước"
                    diff < 86400_000 -> {
                        val sdf = SimpleDateFormat("HH:mm", Locale("vi"))
                        sdf.format(date)
                    }
                    diff < 604800_000 -> {
                        val days = diff / 86400_000
                        "$days ngày trước"
                    }
                    else -> {
                        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale("vi"))
                        sdf.format(date)
                    }
                }
            } catch (e: Exception) {
                "--:--"
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatSession>() {
        override fun areItemsTheSame(a: ChatSession, b: ChatSession) = a.id == b.id
        override fun areContentsTheSame(a: ChatSession, b: ChatSession) =
            a == b && a.isPinned == b.isPinned
    }
}