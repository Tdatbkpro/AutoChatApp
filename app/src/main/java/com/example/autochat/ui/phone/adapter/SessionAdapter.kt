package com.example.autochat.ui.phone.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.databinding.ItemSessionBinding
import com.example.autochat.domain.model.ChatSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionAdapter(
    private val onSessionClick: (ChatSession) -> Unit,
    private val onDeleteClick: (ChatSession) -> Unit
) : ListAdapter<ChatSession, SessionAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemSessionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(
        private val binding: ItemSessionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(session: ChatSession) {
            binding.tvTitle.text = session.title
            binding.tvTime.text = SimpleDateFormat(
                "dd/MM/yyyy HH:mm", Locale.getDefault()
            ).format(Date(session.updatedAt))

            binding.root.setOnClickListener { onSessionClick(session) }
            binding.btnDelete.setOnClickListener { onDeleteClick(session) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatSession>() {
        override fun areItemsTheSame(a: ChatSession, b: ChatSession) = a.id == b.id
        override fun areContentsTheSame(a: ChatSession, b: ChatSession) = a == b
    }
}