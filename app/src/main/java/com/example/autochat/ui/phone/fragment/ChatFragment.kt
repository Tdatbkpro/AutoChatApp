package com.example.autochat.ui.phone.fragment

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.autochat.AppState
import com.example.autochat.R
import com.example.autochat.databinding.FragmentChatBinding
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.repository.ChatRepository
import com.example.autochat.ui.phone.adapter.ChatMessageAdapter
import com.example.autochat.websocket.WebSocketManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    // Adapter nhận callback khi nhấn vào bài báo trong news list
    private lateinit var adapter: ChatMessageAdapter

    private var loadMessagesJob: Job? = null
    private var observeJob: Job? = null
    private var realtimeJob: Job? = null
    private var typingJob: Job? = null

    @Inject
    lateinit var webSocketManager: WebSocketManager

    private val chatRepository: ChatRepository by lazy {
        EntryPointAccessors.fromApplication(
            requireContext().applicationContext,
            ChatEntryPoint::class.java
        ).chatRepository()
    }

    companion object {
        private const val REQUEST_SPEECH = 101
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Khởi tạo adapter với callback mở chi tiết bài báo ──
        adapter = ChatMessageAdapter { articleId, title, description ->
            openArticleDetail(articleId, title, description)
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ChatFragment.adapter
        }

        setupInputActions()
        setupRealtimeEvents()

        binding.btnNewChat.setOnClickListener {
            loadMessagesJob?.cancel()
            observeJob?.cancel()
            AppState.currentSessionId?.let { webSocketManager.leaveSession(it) }
            AppState.currentSessionId = null
            AppState.currentSession = null
            adapter.submitList(emptyList())
            binding.tvSessionTitle.text = "Chat mới"
            binding.etMessage.setText("")
            binding.tvTyping.visibility = View.GONE
        }

        binding.btnMenu.setOnClickListener {
            (requireActivity() as? com.example.autochat.ui.phone.MainActivity)?.openDrawer()
        }

        if (AppState.currentUserId.isNotEmpty()) {
            webSocketManager.connect(AppState.currentUserId)
        }

        AppState.currentSession?.let { session ->
            binding.tvSessionTitle.text = session.title
            loadSession(session.id)
        }
    }

    /**
     * Mở ArticleDetailFragment khi nhấn vào bài báo trong danh sách.
     * Truyền articleId để fragment tự fetch chi tiết từ repository.
     */
    private fun openArticleDetail(articleId: Int?, title: String, description: String) {
        val bundle = bundleOf(
            "articleId" to (articleId ?: -1),
            "title" to title,
            "description" to description
        )
        try {
            findNavController().navigate(R.id.action_chat_to_articleDetail, bundle)
        } catch (e: Exception) {
            // Fallback nếu chưa có action trong nav_graph
            parentFragmentManager.beginTransaction()
                .replace(R.id.navHostFragment, ArticleDetailFragment().apply { arguments = bundle })
                .addToBackStack("article_detail")
                .commit()
        }
    }

    private fun setupInputActions() {
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnMic.setOnClickListener { startVoiceInput() }

        binding.etMessage.addTextChangedListener(object : TextWatcher {
            private var isTyping = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val newTyping = !s.isNullOrEmpty()
                if (newTyping != isTyping) {
                    isTyping = newTyping
                    webSocketManager.sendTyping(isTyping)
                    if (isTyping) {
                        typingJob?.cancel()
                        typingJob = lifecycleScope.launch {
                            delay(2000)
                            if (binding.etMessage.text.isNullOrEmpty()) {
                                webSocketManager.sendTyping(false)
                                isTyping = false
                            }
                        }
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupRealtimeEvents() {
        realtimeJob = lifecycleScope.launch {
            chatRepository.realtimeEvents.collect { event ->
                when (event) {
                    is ChatRepository.RealtimeEvent.NewMessage -> {
                        binding.recyclerView.post {
                            binding.recyclerView.scrollToPosition(adapter.itemCount - 1)
                        }
                    }
                    is ChatRepository.RealtimeEvent.SessionDeleted -> {
                        if (event.sessionId == AppState.currentSessionId) {
                            Toast.makeText(requireContext(), "Đoạn chat đã bị xóa", Toast.LENGTH_SHORT).show()
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        }
                    }
                    is ChatRepository.RealtimeEvent.MessagesDeleted -> {
                        if (event.sessionId == AppState.currentSessionId) {
                            Toast.makeText(requireContext(), "Tin nhắn đã bị xóa", Toast.LENGTH_SHORT).show()
                        }
                    }
                    is ChatRepository.RealtimeEvent.Typing -> {
                        if (event.userId != AppState.currentUserId) {
                            binding.tvTyping.visibility = if (event.isTyping) View.VISIBLE else View.GONE
                            if (event.isTyping) binding.tvTyping.text = "Đang nhập..."
                        }
                    }
                    is ChatRepository.RealtimeEvent.Connected -> {
                        binding.tvConnectionStatus.visibility = View.GONE
                    }
                    is ChatRepository.RealtimeEvent.Disconnected -> {
                        binding.tvConnectionStatus.visibility = View.VISIBLE
                        binding.tvConnectionStatus.text = "Đang kết nối lại..."
                    }
                    is ChatRepository.RealtimeEvent.Error -> {
                        Toast.makeText(requireContext(), "Lỗi: ${event.error}", Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return
        binding.etMessage.setText("")
        webSocketManager.sendTyping(false)

        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                val result = chatRepository.sendMessage(AppState.currentSessionId, text,false, null)

                if (AppState.currentSessionId != result.sessionId) {
                    AppState.currentSessionId = result.sessionId
                    binding.tvSessionTitle.text = result.sessionTitle
                    AppState.currentSession = com.example.autochat.domain.model.ChatSession(
                        id = result.sessionId,
                        userId = AppState.currentUserId,
                        title = result.sessionTitle,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                }

                loadMessages()
                binding.progressBar.visibility = View.GONE
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadMessages() {
        if (AppState.currentSessionId == null) {
            adapter.submitList(emptyList())
            return
        }

        loadMessagesJob?.cancel()
        observeJob?.cancel()

        loadMessagesJob = lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                observeJob = lifecycleScope.launch {
                    chatRepository.getMessagesFlow(AppState.currentSessionId!!).collect { msgList ->
                        adapter.submitList(msgList)
                        if (msgList.isNotEmpty()) {
                            binding.recyclerView.post {
                                binding.recyclerView.scrollToPosition(msgList.size - 1)
                            }
                        }
                        binding.progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun loadSession(sessionId: String) {
        loadMessagesJob?.cancel()
        observeJob?.cancel()

        AppState.currentSessionId?.let { oldId ->
            if (oldId != sessionId) webSocketManager.leaveSession(oldId)
        }

        adapter.submitList(emptyList())
        webSocketManager.joinSession(sessionId)
        loadMessages()
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tin nhắn của bạn...")
        }
        try {
            startActivityForResult(intent, REQUEST_SPEECH)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Không hỗ trợ voice input", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SPEECH && resultCode == android.app.Activity.RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.getOrNull(0) ?: return
            if (text.isNotEmpty()) {
                binding.etMessage.setText(text)
                sendMessage()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        AppState.currentSessionId?.let { webSocketManager.leaveSession(it) }
        loadMessagesJob?.cancel()
        observeJob?.cancel()
        realtimeJob?.cancel()
        typingJob?.cancel()
        _binding = null
    }
}