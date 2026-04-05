package com.example.autochat.ui.phone.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.autochat.databinding.FragmentSessionDetailBinding
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.model.Message
import com.example.autochat.domain.repository.ChatRepository
import com.example.autochat.ui.phone.adapter.MessageAdapter
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

class SessionDetailFragment : Fragment() {

    private var _binding: FragmentSessionDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var messageAdapter: MessageAdapter
    private var messages = mutableListOf<Message>()
    private var sessionId: String? = null
    private var sessionTitle: String? = null

    private val chatRepository: ChatRepository by lazy {
        EntryPointAccessors.fromApplication(
            requireContext().applicationContext,
            ChatEntryPoint::class.java
        ).chatRepository()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            sessionId = it.getString("sessionId")
            sessionTitle = it.getString("sessionTitle")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSessionDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        loadMessages()

        binding.btnDeleteAll.setOnClickListener {
            deleteAllMessages()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.apply {
            title = sessionTitle ?: "Chi tiết chat"
            setNavigationOnClickListener {
                requireActivity().onBackPressed()
            }
        }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(messages)
        binding.recyclerViewMessages.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = messageAdapter
        }
    }

    private fun loadMessages() {
        if (sessionId == null) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE
            return
        }

        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                chatRepository.getMessagesFlow(sessionId!!).collect { messageList ->
                    binding.progressBar.visibility = View.GONE

                    if (messageList.isEmpty()) {
                        binding.recyclerViewMessages.visibility = View.GONE
                        binding.tvEmpty.visibility = View.VISIBLE
                    } else {
                        binding.recyclerViewMessages.visibility = View.VISIBLE
                        binding.tvEmpty.visibility = View.GONE

                        // Cập nhật messages và notify adapter
                        messages.clear()
                        messages.addAll(messageList)
                        messageAdapter.notifyDataSetChanged()

                        // Cuộn xuống tin nhắn cuối cùng
                        binding.recyclerViewMessages.scrollToPosition(messages.size - 1)
                    }
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "Lỗi tải tin nhắn: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteAllMessages() {
        if (sessionId == null) return

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Xóa tất cả tin nhắn")
            .setMessage("Bạn có chắc chắn muốn xóa tất cả tin nhắn trong đoạn chat này?")
            .setPositiveButton("Xóa") { _, _ ->
                lifecycleScope.launch {
                    try {
                        chatRepository.deleteMessages(sessionId!!)
                        Toast.makeText(requireContext(), "Đã xóa tất cả tin nhắn", Toast.LENGTH_SHORT).show()
                        requireActivity().onBackPressed()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}