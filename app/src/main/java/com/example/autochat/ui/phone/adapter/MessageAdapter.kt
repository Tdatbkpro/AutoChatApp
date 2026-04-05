package com.example.autochat.ui.phone.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.R
import com.example.autochat.domain.model.Message  // ✅ Import domain model
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(private var messages: List<Message>) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount() = messages.size

    // ✅ Thêm method để cập nhật danh sách
    fun updateMessages(newMessages: List<Message>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val timeText: TextView = itemView.findViewById(R.id.timeText)
        private val userIndicator: TextView = itemView.findViewById(R.id.userIndicator)

        fun bind(message: Message) {
            messageText.text = message.content

            // ✅ Hiển thị theo giờ Việt Nam
            val format = SimpleDateFormat("HH:mm", Locale("vi"))
            format.timeZone = java.util.TimeZone.getTimeZone("GMT+7")
            timeText.text = format.format(Date(message.timestamp))

            when (message.sender) {
                "user" -> {
                    userIndicator.text = "👤 Bạn"
                    userIndicator.setBackgroundColor(0xFFE3F2FD.toInt())
                }
                "bot" -> {
                    userIndicator.text = "🤖 Bot"
                    userIndicator.setBackgroundColor(0xFFF1F8E9.toInt())
                }
            }
        }
    }
}