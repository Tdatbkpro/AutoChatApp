package com.example.autochat.ui.phone.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.databinding.ItemMessageUserBinding
import com.example.autochat.databinding.ItemMessageBotBinding
import com.example.autochat.domain.model.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatMessageAdapter : ListAdapter<Message, RecyclerView.ViewHolder>(DiffCallback()) {

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).sender == "user") VIEW_TYPE_USER else VIEW_TYPE_BOT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> {
                val binding = ItemMessageUserBinding.inflate(inflater, parent, false)
                UserViewHolder(binding)
            }
            else -> {
                val binding = ItemMessageBotBinding.inflate(inflater, parent, false)
                BotViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is UserViewHolder -> holder.bind(message)
            is BotViewHolder -> holder.bind(message)
        }
    }

    class UserViewHolder(private val binding: ItemMessageUserBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.tvContent.text = message.content
            binding.tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
            android.util.Log.d("ChatAdapter", "User message: ${message.content}")
        }
    }

    class BotViewHolder(private val binding: ItemMessageBotBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: Message) {
            binding.tvContent.text = message.content
            binding.tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
            android.util.Log.d("ChatAdapter", "Bot message: ${message.content}")
        }
    }

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_BOT = 1
    }

    class DiffCallback : DiffUtil.ItemCallback<Message>() {
        override fun areItemsTheSame(oldItem: Message, newItem: Message) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Message, newItem: Message) = oldItem == newItem
    }
}