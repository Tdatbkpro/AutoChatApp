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
import androidx.recyclerview.widget.RecyclerView
import com.example.autochat.AppState
import com.example.autochat.R
import com.example.autochat.databinding.FragmentChatBinding
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.domain.model.Message
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
    private var streamingJob: Job? = null
    private var isStreaming: Boolean = false
    private val localMessages = mutableListOf<Message>()
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
        setupScrollToBottomButton()
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

        // Trong onViewCreated, sửa lại:
        if (AppState.currentUserId.isNotEmpty() && !webSocketManager.isConnected()) {
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
                        val msg = event.message
                        when {
                            // Bot processing (content rỗng) -> Hiện Lottie
                            msg.content.isEmpty() && msg.sender == "bot" -> {
                                val uniqueStreamingMsg = msg.copy(
                                    id = "streaming_${System.currentTimeMillis()}"
                                )
                                val cur = adapter.currentList.toMutableList()
                                cur.removeAll { it.id.startsWith("streaming_") }
                                cur.add(uniqueStreamingMsg)
                                adapter.submitList(cur.toList())
                                Log.d("ChatFragment", "✅ Added streaming")
                            }

                            // Bot trả lời thật -> Thay thế streaming bằng message thật
                            msg.sender == "bot" && msg.content.isNotEmpty() -> {
                                val cur = adapter.currentList.toMutableList()

                                // Tìm vị trí của streaming placeholder
                                val streamingIndex = cur.indexOfFirst { it.id.startsWith("streaming_") }
                                if (streamingIndex != -1) {
                                    // Thay thế streaming bằng message thật
                                    cur[streamingIndex] = msg
                                } else {
                                    // Nếu không có streaming thì thêm vào cuối
                                    cur.add(msg)
                                }

                                adapter.submitList(cur.toList())
                                scrollToBottom()
                                Log.d("ChatFragment", "📝 Bot message added, list size: ${cur.size}")
                            }

                            // User message - Thêm vào list (cả máy gửi và máy nhận)
                            msg.sender == "user" -> {
                                // KHÔNG gọi loadMessages() nữa, chỉ thêm message mới
                                val cur = adapter.currentList.toMutableList()

                                // Xóa optimistic user message có cùng content
                                cur.removeAll {
                                    it.id.startsWith("opt_user_") &&
                                            it.content == msg.content
                                }

                                // Chỉ thêm nếu chưa có
                                if (cur.none { it.id == msg.id }) {
                                    cur.add(msg)
                                    adapter.submitList(cur.toList())
                                    scrollToBottom()
                                }

                                Log.d("ChatFragment", "👤 User message added to list")
                            }
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
                        binding.connectionStatusBar.visibility = View.GONE
                    }
                    is ChatRepository.RealtimeEvent.Disconnected -> {
                        binding.connectionStatusBar.visibility = View.VISIBLE
                        binding.connectionStatusBar.text = "Đang kết nối lại..."
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

        // Cancel stream cũ nếu có
        streamingJob?.cancel()
        isStreaming = true  // ← set NGAY ĐÂY, trước khi collect
        AppState.streamingSessionId = AppState.currentSessionId  // ← lưu session đang stream
        AppState.streamingContent = ""
        Log.d("ChatFragment", "sendMessage sessionId=${AppState.currentSessionId}")
        binding.etMessage.setText("")
        webSocketManager.sendTyping(false)

        val now = System.currentTimeMillis()

        // ── 1. Hiện user bubble + streaming placeholder ngay ──────────────
        val optUser = Message(
            id        = "opt_user_$now",
            sessionId = AppState.currentSessionId ?: "new",
            content   = text,
            sender    = "user",
            timestamp = now,
            extraData = null
        )
        val optBot = Message(
            id        = "streaming_$now",
            sessionId = AppState.currentSessionId ?: "new",
            content   = "",   // rỗng → Lottie chạy
            sender    = "bot",
            timestamp = now + 1,
            extraData = null
        )

        val seedList = adapter.currentList.toMutableList().apply {
            removeAll { it.id.startsWith("streaming_") }
            add(optUser)
            add(optBot)
        }
        adapter.submitList(seedList.toList())
        scrollToBottom()

        // ── 2. Collect stream ─────────────────────────────────────────────
        streamingJob = lifecycleScope.launch {
            chatRepository.streamMessage(AppState.currentSessionId, text)
                .collect { chunk ->
                    when (chunk) {

                        is ChatRepository.StreamChunk.Token -> {
                            val cur = adapter.currentList.toMutableList()
                            val idx = cur.indexOfFirst { it.id.startsWith("streaming_") }
                            if (idx != -1) {
                                cur[idx] = cur[idx].copy(content = cur[idx].content + chunk.text)
                                adapter.submitList(cur.toList())
                                AppState.streamingContent = cur[idx].content  // ← lưu content hiện tại
                                scrollToBottom()
                            }
                        }

                        is ChatRepository.StreamChunk.Done -> {
                            // Stream văn bản xong — giữ nguyên chờ Meta
                        }

                        is ChatRepository.StreamChunk.Meta -> {
                            isStreaming = false
                            AppState.streamingSessionId = null  // ← clear khi xong
                            AppState.streamingContent = ""
                            if (AppState.currentSessionId != chunk.sessionId) {
                                AppState.currentSessionId = chunk.sessionId
                                binding.tvSessionTitle.text = chunk.sessionTitle
                                AppState.currentSession = ChatSession(
                                    id = chunk.sessionId,
                                    userId = AppState.currentUserId,
                                    title = chunk.sessionTitle,
                                    createdAt = now,
                                    updatedAt = now
                                )
                                webSocketManager.joinSession(chunk.sessionId)
                            }

                            // Xóa optimistic khỏi local - SỬA LẠI CHO ĐÚNG
                            localMessages.removeAll {
                                it.id.startsWith("streaming_") || it.id.startsWith("opt_user_")
                            }

                            // KHÔNG gọi loadMessages() ở đây
                            // WebSocket broadcast sẽ trigger setupRealtimeEvents → loadMessages()
                            // Nếu sau 3s WebSocket chưa về thì mới fallback load
//                            lifecycleScope.launch {
//                                delay(3000)
//                                if (!isStreaming) loadMessages()
//                            }
                        }

                        is ChatRepository.StreamChunk.Error -> {
                            // Rollback toàn bộ optimistic messages
                            AppState.streamingSessionId = null  // ← clear khi xong
                            AppState.streamingContent = ""
                            adapter.submitList(
                                adapter.currentList.filter {
                                    !it.id.startsWith("streaming_") && !it.id.startsWith("opt_user_")  // ← SỬA Ở ĐÂY
                                }
                            )
                            if (isAdded) {
                                Toast.makeText(
                                    requireContext(),
                                    "Lỗi: ${chunk.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
        }
    }
    private fun scrollToBottom() {
        binding.recyclerView.post {
            val count = adapter.itemCount
            if (count > 0) binding.recyclerView.scrollToPosition(count - 1)
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
                // Hiện skeleton loading, ẩn progressBar
                binding.skeletonLoading.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                binding.recyclerView.visibility = View.GONE

                observeJob = lifecycleScope.launch {
                    chatRepository.getMessagesFlow(AppState.currentSessionId!!).collect { msgList ->
                        // Ẩn skeleton, hiện recyclerView
                        binding.skeletonLoading.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE

                        if (!isStreaming) {
                            adapter.submitList(msgList)
                            if (msgList.isNotEmpty()) {
                                binding.recyclerView.post {
                                    binding.recyclerView.scrollToPosition(msgList.size - 1)
                                }
                            }
                        }
                        binding.progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                binding.skeletonLoading.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
                binding.progressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun loadSession(sessionId: String) {
        Log.d("ChatFragment", "loadSession: $sessionId, current: ${AppState.currentSessionId}, streamingSession: ${AppState.streamingSessionId}")

        loadMessagesJob?.cancel()
        observeJob?.cancel()
        loadMessagesJob = null
        observeJob = null

        AppState.currentSessionId?.let { oldId ->
            if (oldId != sessionId) {
                webSocketManager.leaveSession(oldId)
            }
        }

        AppState.currentSessionId = sessionId
        AppState.currentSession = AppState.currentSession?.takeIf { it.id == sessionId }

        webSocketManager.joinSession(sessionId)

        // Chỉ check streamingSessionId, không cần isStreaming
        if (AppState.streamingSessionId == sessionId) {
            Log.d("ChatFragment", "🔄 Restoring streaming for session $sessionId")
            isStreaming = true

            loadMessagesJob = lifecycleScope.launch {
                try {
                    // Hiện skeleton trước khi load
                    binding.skeletonLoading.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE

                    chatRepository.getMessagesFlow(sessionId).collect { msgList ->
                        // Ẩn skeleton khi có data
                        binding.skeletonLoading.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE

                        if (AppState.currentSessionId == sessionId) {
                            val cur = msgList.toMutableList().apply {
                                removeAll { it.id.startsWith("streaming_") }
                                add(Message(
                                    id = "streaming_${System.currentTimeMillis()}",
                                    sessionId = sessionId,
                                    content = AppState.streamingContent,
                                    sender = "bot",
                                    timestamp = System.currentTimeMillis(),
                                    extraData = null
                                ))
                            }
                            adapter.submitList(cur)
                            scrollToBottom()
                        }
                    }
                } catch (e: Exception) {
                    binding.skeletonLoading.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                    if (AppState.currentSessionId == sessionId) {
                        Toast.makeText(requireContext(), "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Log.d("ChatFragment", "📝 Normal load for session $sessionId")
            isStreaming = false
            adapter.submitList(emptyList())
            loadMessages()
        }
    }
    private fun setupScrollToBottomButton() {
        binding.btnScrollToBottom.setOnClickListener {
            scrollToBottom()
            binding.btnScrollToBottom.visibility = View.GONE
        }

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                val totalItems = adapter.itemCount

                // Hiện nút khi item cuối cùng không còn visible
                binding.btnScrollToBottom.visibility =
                    if (totalItems > 0 && lastVisibleItem < totalItems - 1) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }
        })
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
        // XÓA dòng leaveSession ở đây
        loadMessagesJob?.cancel()
        observeJob?.cancel()
        realtimeJob?.cancel()
        typingJob?.cancel()
        streamingJob?.cancel()
        _binding = null
    }
}