package com.example.autochat.ui.phone.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.databinding.ItemUserMessageQuickBinding
import com.example.autochat.domain.model.Message

class UserMessagesAdapter(
    private var messages: List<Message>,
    private val onMessageClick: (Int) -> Unit
) : RecyclerView.Adapter<UserMessagesAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemUserMessageQuickBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemUserMessageQuickBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]
        holder.binding.apply {
            tvMessageIndex.text = "${position + 1}"
            tvMessagePreview.text = msg.content
            tvMessageTime.text = ChatMessageAdapter.formatTime(msg.timestamp)
            root.setOnClickListener { onMessageClick(position) }
        }
    }
    fun updateMessages(newMessages: List<Message>) {
        messages = newMessages
        notifyDataSetChanged()
    }
    override fun getItemCount() = messages.size
}