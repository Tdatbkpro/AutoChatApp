package com.example.autochat.ui.phone.fragment

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
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
import com.example.autochat.ui.phone.MainActivity
import com.example.autochat.ui.phone.adapter.ChatMessageAdapter
import com.example.autochat.websocket.WebSocketManager
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.example.autochat.llm.LlmEngine
import com.example.autochat.llm.ModelManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.animation.doOnEnd
import androidx.lifecycle.repeatOnLifecycle
import com.example.autochat.databinding.DialogUserMessagesBinding
import com.example.autochat.ui.phone.adapter.UserMessagesAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import androidx.core.graphics.toColorInt
import com.example.autochat.ui.phone.BranchManager

enum class SendMode { ONLINE, OFFLINE }
@AndroidEntryPoint
class ChatFragment : Fragment() {
    private var dX = 0f
    private var dY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var isDragging = false
    @Inject
    lateinit var llmEngine: LlmEngine

    @Inject
    lateinit var modelManager: ModelManager
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
    private var currentBranchId: String? = null
    private val localMessages = mutableListOf<Message>()
    private var sendMode = SendMode.ONLINE
    @Inject
    lateinit var webSocketManager: WebSocketManager
    private var isGenerating: Boolean = false
    private var currentGenerationJob: Job? = null
    private val ENDPOINTS = listOf("news", "ask")
    private var endpointIndex = 0          // index trong ENDPOINTS
    private var userScrolledUp = false
    private val chatRepository: ChatRepository by lazy {
        EntryPointAccessors.fromApplication(
            requireContext().applicationContext,
            ChatEntryPoint::class.java
        ).chatRepository()
    }
    private var endpointAnimating = false
    private var endpointCollapsed = false
    private var endpointOriginalWidth = 0
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
    private fun initAdapter() {
        adapter = ChatMessageAdapter(
            onNewsItemClick = { articleId, title, description ->
                openArticleDetail(articleId, title, description)
            },
            onRetry = { message ->
                retryMessage(message)
            },
            onEdit = { message, newContent ->
                editMessageAndBranch(message, newContent)
            },
            onSwitchBranch = { pivotId, delta ->
                switchBranch(pivotId, delta)
            },
        )
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ── Khởi tạo adapter với callback mở chi tiết bài báo ──

//        adapter.setOnUserMessageLongPress { position ->
//            showUserMessagesButton()
//        }
        initAdapter()
        AppState.currentSessionId?.let { BranchManager.init(it) }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ChatFragment.adapter
        }
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                updateEmptyState()
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                updateEmptyState()
            }
            override fun onChanged() {
                updateEmptyState()
            }
        })

//        updateEmptyState()
        setupInputActions()
        setupRealtimeEvents()
        setupScrollToBottomButton()
        setupSwipeToOpenDrawer()
        setupModelStatus()
        setupEndpointButton()
        setupDraggableUserMessagesButton()
        // ✅ Khởi tạo trạng thái pill
        endpointCollapsed = false
        endpointAnimating = false
        binding.tvEndpointLabel.visibility = View.VISIBLE
        binding.tvEndpointLabel.alpha = 1f
        binding.tvEndpointOut.visibility = View.INVISIBLE

        binding.btnEndpoint.post {
            endpointOriginalWidth = binding.btnEndpoint.width
            // Đảm bảo width là wrap_content ban đầu
            val params = binding.btnEndpoint.layoutParams
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT
            binding.btnEndpoint.layoutParams = params
        }

        binding.btnNewChat.setOnClickListener {
            loadMessagesJob?.cancel()
            observeJob?.cancel()
            AppState.currentSessionId?.let { webSocketManager.leaveSession(it) }
            AppState.currentSessionId = null
            currentBranchId = null  // ← THÊM: reset branch
            BranchManager.init("")  // ← THÊM: reset BranchManagertr
            AppState.currentSession = null
            adapter.submitList(emptyList())
            updateEmptyState()
            binding.tvSessionTitle.text = "Chat mới"
            binding.etMessage.setText("")
            binding.tvTyping.visibility = View.GONE
        }

        binding.btnMenu.setOnClickListener {
            (requireActivity() as? com.example.autochat.ui.phone.MainActivity)?.openDrawer()
        }

        if (AppState.currentUserId.isNotEmpty() && !webSocketManager.isConnected()) {
            webSocketManager.connect(AppState.currentUserId)
        }

        AppState.currentSession?.let { session ->
            binding.tvSessionTitle.text = session.title
            loadSession(session.id)
        }
    }
    private var startX = 0f
    private var startY = 0f

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeToOpenDrawer() {
        val drawerLayout = (requireActivity() as MainActivity).binding.drawerLayout

        // Set cho toàn bộ fragment
        binding.root.setOnTouchListener { view, event ->
            handleSwipeGesture(event, drawerLayout)
        }

        // Set riêng cho EditText (phần 2)
        binding.etMessage.setOnTouchListener { view, event ->
            handleSwipeGesture(event, drawerLayout)
        }

        // Set cho RecyclerView (phần 1)
        binding.recyclerView.setOnTouchListener { view, event ->
            handleSwipeGesture(event, drawerLayout)
        }

        // Set cho TextInputLayout (wrapper của EditText)
        binding.tilMessage.setOnTouchListener { view, event ->
            handleSwipeGesture(event, drawerLayout)
        }
    }

    private fun handleSwipeGesture(event: MotionEvent, drawerLayout: DrawerLayout): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - startX
                val deltaY = abs(event.rawY - startY)

                // Chỉ mở drawer khi:
                // 1. Vuốt sang phải đủ 80px
                // 2. VÀ deltaX > deltaY * 2 (vuốt ngang rõ ràng)
                if (deltaX > 80 && deltaX > deltaY * 2) {
                    drawerLayout.openDrawer(GravityCompat.START)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }
    private fun setupModelStatus() {
        binding.btnModelStatus.setOnClickListener {
            showModelBottomSheet()
        }

        // ✅ Thêm click cho toggle mode
        binding.btnToggleMode.setOnClickListener {
            toggleSendMode()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) {
                    updateModelStatus()
                    updateToggleButton()
                    kotlinx.coroutines.delay(2000)
                }
            }
        }
    }
    private fun retryMessage(message: Message) {
        editMessageAndBranch(message, message.content)
    }

    /** Edit message → cắt tại điểm đó, tạo branch mới, hiển thị optimistic UI */
    private fun editMessageAndBranch(message: Message, newContent: String) {
        val sessionId = AppState.currentSessionId ?: return
        val actualPivotId = message.parentMessageId ?: message.id
        val now     = System.currentTimeMillis()
        val optUser = Message(
            id        = "opt_edit_$now",
            sessionId = sessionId,
            content   = newContent,
            sender    = "user",
            timestamp = now,
            extraData = null,
        )
        val optBot = Message(
            id        = "streaming_$now",
            sessionId = sessionId,
            content   = "",
            sender    = "bot",
            timestamp = now + 1,
            extraData = null,
        )

        // Cắt list: giữ messages TRƯỚC điểm edit, thêm optimistic pair
        val currentList = adapter.currentList.toMutableList()
        val pivotIdx    = currentList.indexOfFirst { it.id == message.id }
        val contextMsgs = if (pivotIdx > 0)
            currentList.subList(0, pivotIdx).toMutableList()
        else
            mutableListOf()
        contextMsgs.add(optUser)
        contextMsgs.add(optBot)
        adapter.submitList(contextMsgs.toList())
        scrollToBottom()

        isGenerating = true
        isStreaming   = true
        binding.btnSend.apply {
            setImageResource(R.drawable.ic_pause)
            isEnabled = true
            alpha     = 1f
        }

        streamingJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = chatRepository.editMessage(
                    sessionId  = sessionId,
                    messageId  = actualPivotId,
                    newContent = newContent,
                    endpoint   = AppState.currentEndpoint,
                )
                val branches = chatRepository.getBranchesAtMessage(sessionId, actualPivotId)

                // Cập nhật BranchManager
                BranchManager.onBranchCreated(
                    pivotMessageId = actualPivotId,
                    newBranchInfo  = BranchManager.BranchInfo(
                        branchId  = result.newBranchId,
                        index     = result.branchInfo.index,
                        total     = result.branchInfo.total,
                        createdAt = result.branchInfo.createdAt.toString(),
                    ),
                    allBranches = branches.map {  // ✅ dùng list thật từ server
                        BranchManager.BranchInfo(
                            branchId  = it.branchId,
                            index     = it.index,
                            total     = it.total,
                            createdAt = it.createdAt.toString(),
                        )
                    }
                )
                currentBranchId = result.newBranchId

                // Thay opt bubbles bằng kết quả thực
                val cur       = adapter.currentList.toMutableList()
                val editIdx   = cur.indexOfFirst { it.id == "opt_edit_$now" }
                val streamIdx = cur.indexOfFirst { it.id == "streaming_$now" }
                if (editIdx   != -1) cur[editIdx]   = result.userMessage
                if (streamIdx != -1) cur[streamIdx] = result.botMessage
                adapter.submitList(cur.toList())
                scrollToBottom()

            } catch (e: Exception) {
                // Rollback optimistic UI
                adapter.submitList(adapter.currentList.filter {
                    !it.id.startsWith("opt_edit_") && !it.id.startsWith("streaming_")
                })
                if (isAdded) {
                    Toast.makeText(requireContext(), "Lỗi edit: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isGenerating = false
                isStreaming   = false
                resetSendButton()
            }
        }
    }
    private fun switchBranch(pivotMessageId: String, delta: Int) {
        val sessionId   = AppState.currentSessionId ?: return
        val newBranchId = BranchManager.switchBranch(pivotMessageId, delta) ?: return
        currentBranchId = newBranchId

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val messages = chatRepository.getBranchMessages(
                    sessionId = sessionId,
                    branchId  = newBranchId,
                ).sortedBy { it.timestamp }

                adapter.submitList(messages)

                // Tìm pivot trong list — có thể là msg.id hoặc msg.parentMessageId
                val pivotPos = messages.indexOfFirst { msg ->
                    msg.id == pivotMessageId || msg.parentMessageId == pivotMessageId
                }
                if (pivotPos != -1) {
                    binding.recyclerView.scrollToPosition(pivotPos)
                }
                launch {
                    chatRepository.syncBranchToRag(sessionId, newBranchId)
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Lỗi đổi nhánh: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun setupEndpointButton() {
        // Sync với AppState khi fragment khởi tạo
        endpointIndex = ENDPOINTS.indexOf(AppState.currentEndpoint).coerceAtLeast(0)
        renderEndpointLabel(animated = false)

        var swipeStartY = 0f
        val SWIPE_THRESHOLD = 40f      // px cần vuốt để trigger
        var swipeConsumed = false

        binding.btnEndpoint.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartY = event.rawY
                    swipeConsumed = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - swipeStartY
                    if (!swipeConsumed && deltaY > SWIPE_THRESHOLD) {
                        swipeConsumed = true
                        cycleEndpoint(direction = +1)   // vuốt xuống → endpoint tiếp
                        true
                    } else if (!swipeConsumed && deltaY < -SWIPE_THRESHOLD) {
                        swipeConsumed = true
                        cycleEndpoint(direction = -1)   // vuốt lên → endpoint trước
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> {
                    if (!swipeConsumed) {
                        // tap đơn → cycle tiếp theo
                        v.performClick()
                    }
                    false
                }
                else -> false
            }
        }

        binding.btnEndpoint.setOnClickListener {
            cycleEndpoint(direction = +1)
        }
    }

// ── cycleEndpoint — đổi endpoint + chạy animation ────────────────────────────

    private fun cycleEndpoint(direction: Int) {
        val oldIndex = endpointIndex
        endpointIndex = (endpointIndex + direction + ENDPOINTS.size) % ENDPOINTS.size
        if (oldIndex == endpointIndex) return

        AppState.currentEndpoint = ENDPOINTS[endpointIndex]

        // ✅ Nếu đang collapse -> chỉ update icon, không animation
        if (endpointCollapsed) {
            binding.tvEndpointOut.text = when (ENDPOINTS[endpointIndex]) {
                "news" -> "📰"
                "ask"  -> "🤖"
                else   -> "💬"
            }
        } else {
            renderEndpointLabel(animated = true, direction = direction)
        }

        binding.btnEndpoint.performHapticFeedback(
            android.view.HapticFeedbackConstants.VIRTUAL_KEY
        )
    }

// ── renderEndpointLabel — animation slide + fade ─────────────────────────────

    private fun renderEndpointLabel(animated: Boolean, direction: Int = 1) {
        val label = endpointLabel(ENDPOINTS[endpointIndex])
        val accentColor = endpointAccent(ENDPOINTS[endpointIndex])

        if (!animated) {
            binding.tvEndpointLabel.text = label
            binding.tvEndpointLabel.setTextColor(accentColor)
            applyEndpointBorder(accentColor)
            return
        }

        val inView  = binding.tvEndpointLabel
        val outView = binding.tvEndpointOut

        // Chuẩn bị outView (clone trạng thái hiện tại)
        outView.text = inView.text
        outView.setTextColor(inView.currentTextColor)
        outView.translationY = 0f
        outView.alpha = 1f
        outView.visibility = android.view.View.VISIBLE

        // Chuẩn bị inView (giá trị mới, bắt đầu từ ngoài)
        val slideDistance = if (direction > 0) 36f else -36f
        inView.text = label
        inView.setTextColor(accentColor)
        inView.translationY = slideDistance
        inView.alpha = 0f

        // Animate out
        outView.animate()
            .translationY(-slideDistance)
            .alpha(0f)
            .setDuration(180)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
            .withEndAction { outView.visibility = android.view.View.INVISIBLE }
            .start()

        // Animate in
        inView.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220)
            .setStartDelay(40)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
            .start()

        // Pulse scale trên container
        binding.btnEndpoint.animate()
            .scaleX(1.08f).scaleY(1.08f)
            .setDuration(100)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                binding.btnEndpoint.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(150)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }.start()

        // Đổi màu border pill theo endpoint
        applyEndpointBorder(accentColor)
    }

// ── applyEndpointBorder — đổi stroke pill theo màu endpoint ──────────────────

    private fun applyEndpointBorder(color: Int) {
        val bg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(android.graphics.Color.parseColor("#1A1A2E"))
            setStroke(
                (1.5f * resources.displayMetrics.density).toInt(),
                color
            )
        }
        binding.btnEndpoint.background = bg

        // Glow tắt dần
        binding.btnEndpoint.elevation = 6f
        binding.btnEndpoint.animate()
            .setStartDelay(200)
            .withEndAction {
                binding.btnEndpoint.elevation = 2f
            }
            .setDuration(400)
            .start()
    }

// ── endpointLabel / endpointAccent — mapping hiển thị ────────────────────────

    private fun endpointLabel(endpoint: String): String = when (endpoint) {
        "news" -> "📰 NEWS"
        "ask"  -> "🤖 ASK"
        "chat" -> "💬 CHAT"
        else   -> endpoint.uppercase()
    }

    private fun endpointAccent(endpoint: String): Int = when (endpoint) {
        "news" -> "#31B1BD".toColorInt()   // teal
        "ask"  -> "#A78BFA".toColorInt()   // violet
        "chat" -> "#4A90E2".toColorInt()   // blue
        else   -> "#AAAACC".toColorInt()
    }

    private fun toggleSendMode() {
        sendMode = if (sendMode == SendMode.ONLINE) SendMode.OFFLINE else SendMode.ONLINE
        updateToggleButton()

        val modeText = if (sendMode == SendMode.OFFLINE) "Offline (Model)" else "Online (Server)"
        Toast.makeText(requireContext(), "Chuyển sang: $modeText", Toast.LENGTH_SHORT).show()
    }

    private fun updateToggleButton() {
        val hasModel = llmEngine.isLoaded()

        // Chỉ hiện toggle khi vừa có mạng vừa có model
        if (AppState.isConnectServer && hasModel) {
            binding.btnToggleMode.visibility = View.VISIBLE

            if (sendMode == SendMode.OFFLINE) {
                    binding.btnToggleMode.setImageResource(R.drawable.ic_offline)
                binding.btnToggleMode.imageTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                binding.btnToggleMode.setImageResource(R.drawable.ic_online)
                binding.btnToggleMode.imageTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4A90E2"))
            }
        } else {
            binding.btnToggleMode.visibility = View.GONE

            // Tự động chọn mode phù hợp
            sendMode = if (hasModel) SendMode.OFFLINE else SendMode.ONLINE
        }
    }


    private fun showModelBottomSheet() {
        val bottomSheet = ModelManagerFragment()
        bottomSheet.show(parentFragmentManager, "ModelBottomSheet")
    }
    private fun resetSendButton() {
        binding.btnSend.apply {
            setImageResource(R.drawable.ic_send)
            val hasText = binding.etMessage.text?.isNotEmpty() == true
            isEnabled = hasText
            alpha = if (hasText) 1f else 0.4f
        }
    }
    // Trong ChatFragment.setupModelStatus()
    private fun updateModelStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val isOnline = AppState.isConnectServer
            val loadedModelId = llmEngine.getCurrentModelId()

            val loadedModelName = if (loadedModelId != null) {
                modelManager.getAllModels().find { it.id == loadedModelId }?.name ?: loadedModelId
            } else null

            binding.tvModelStatus.text = when {
                isOnline && loadedModelId != null -> loadedModelName
                isOnline -> "Online"
                loadedModelId != null -> loadedModelName
                else -> "Chọn model"
            }

            val iconTint = when {
                isOnline && loadedModelId != null -> "#4CAF50"
                isOnline -> "#4A90E2"
                loadedModelId != null -> "#4CAF50"
                else -> "#FF6B6B"
            }
            binding.ivModelIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                iconTint.toColorInt()
            )
        }
    }

//    private fun isNetworkAvailable(): Boolean {
//        val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
//        val network = cm.activeNetwork ?: return false
//        val caps = cm.getNetworkCapabilities(network) ?: return false
//        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
//    }
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
                if (isGenerating) stopGeneration() else sendMessage()
                true
            } else false
        }

        binding.btnSend.setOnClickListener {
            if (isGenerating) stopGeneration() else sendMessage()
        }

        binding.btnMic.setOnClickListener { startVoiceInput() }

        binding.etMessage.addTextChangedListener(object : TextWatcher {
            private var isTyping = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrEmpty()

                if (!isGenerating) {
                    binding.btnSend.apply {
                        isEnabled = hasText
                        alpha = if (hasText) 1f else 0.4f
                        setImageResource(R.drawable.ic_send)
                    }
                }

                // ✅ Sửa điều kiện: dùng endpointCollapsed thay vì endpointAnimating
                if (hasText) {
                    if (!endpointCollapsed && !endpointAnimating) {
                        animateEndpointPill(collapse = true)
                    }
                } else {
                    if (endpointCollapsed && !endpointAnimating) {  // ✅ Sửa endpointAnimating -> endpointCollapsed
                        animateEndpointPill(collapse = false)
                    }
                }

                val newTyping = hasText
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
    private fun animateEndpointPill(collapse: Boolean) {
        if (endpointAnimating) return
        if (collapse == endpointCollapsed) return

        endpointAnimating = true
        endpointCollapsed = collapse

        val endpointView = binding.btnEndpoint

        // Lưu width gốc lần đầu (khi expand)
        if (endpointOriginalWidth == 0) {
            endpointOriginalWidth = endpointView.width
        }

        val collapsedWidth = dpToPx(44)
        val expandedWidth  = endpointOriginalWidth

        val fromWidth = if (collapse) expandedWidth else collapsedWidth
        val toWidth   = if (collapse) collapsedWidth else expandedWidth

        // ── Animate text ──────────────────────────────────────────────
        if (collapse) {
            binding.tvEndpointOut.translationY = 0f
            binding.tvEndpointOut.alpha = 0f
            binding.tvEndpointOut.text = when (ENDPOINTS[endpointIndex]) {
                "news" -> "📰"
                "ask"  -> "🤖"
                else   -> "💬"
            }
            binding.tvEndpointOut.visibility = View.VISIBLE
            binding.tvEndpointLabel.animate().alpha(0f).setDuration(80)
                .withEndAction { binding.tvEndpointLabel.visibility = View.INVISIBLE }
                .start()
            binding.tvEndpointOut.animate().alpha(1f).setDuration(120).start()
        } else {
            binding.tvEndpointLabel.translationY = 0f
            binding.tvEndpointLabel.visibility = View.VISIBLE
            binding.tvEndpointLabel.alpha = 0f
            binding.tvEndpointLabel.animate().alpha(1f).setDuration(150).start()
            binding.tvEndpointOut.animate().alpha(0f).setDuration(80)
                .withEndAction { binding.tvEndpointOut.visibility = View.INVISIBLE }
                .start()
        }

        // ── Animate width thực của btnEndpoint ────────────────────────
        // LinearLayout sẽ tự co/giãn tilMessage (layout_weight="1")
        android.animation.ValueAnimator.ofInt(fromWidth, toWidth).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)

            addUpdateListener { anim ->
                val w = anim.animatedValue as Int
                // Đặt width cứng, bỏ weight tạm thời
                endpointView.layoutParams = (endpointView.layoutParams as LinearLayout.LayoutParams).also {
                    it.width = w
                }
            }

            doOnEnd {
                if (!collapse) {
                    // Trả về WRAP_CONTENT khi expand xong
                    endpointView.layoutParams = (endpointView.layoutParams as LinearLayout.LayoutParams).also {
                        it.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
                endpointAnimating = false
            }
            start()
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableUserMessagesButton() {
        binding.btnShowUserMessages.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    downRawX = event.rawX
                    downRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = abs(event.rawX - downRawX)
                    val deltaY = abs(event.rawY - downRawY)

                    if (deltaX > 20 || deltaY > 20) {
                        isDragging = true
                    }

                    if (isDragging) {
                        view.animate()
                            .x(event.rawX + dX)
                            .y(event.rawY + dY)
                            .setDuration(0)
                            .start()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // Snap vào cạnh gần nhất
                        val parentWidth = (view.parent as View).width
                        val viewWidth = view.width
                        val currentX = view.x

                        val targetX = if (currentX + viewWidth / 2 > parentWidth / 2) {
                            parentWidth - viewWidth - 16f.dpToPx()
                        } else {
                            16f.dpToPx()
                        }

                        view.animate()
                            .x(targetX)
                            .setDuration(200)
                            .setInterpolator(DecelerateInterpolator())
                            .start()

                        isDragging = false
                    } else {
                        // ✅ Không kéo -> xử lý như click
                        showUserMessagesDialog()
                    }
                    true
                }
                else -> false
            }
        }

        // ✅ Theo dõi scroll để hiện/ẩn button
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateUserMessagesButtonVisibility()
            }
        })

        // ✅ Kiểm tra khi data thay đổi
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                updateUserMessagesButtonVisibility()
            }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                updateUserMessagesButtonVisibility()
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                updateUserMessagesButtonVisibility()
            }
        })
    }

    // ✅ Cập nhật visibility của button
    private fun updateUserMessagesButtonVisibility() {
        binding.recyclerView.post {
            val userMessages = adapter.currentList.filter { it.sender == "user" && it.content.isNotEmpty() }
            val canScroll = binding.recyclerView.canScrollVertically(1) ||
                    binding.recyclerView.canScrollVertically(-1)

            if (userMessages.isNotEmpty() && canScroll) {
                if (binding.btnShowUserMessages.visibility != View.VISIBLE) {
                    binding.btnShowUserMessages.apply {
                        visibility = View.VISIBLE
                        alpha = 0f
                        scaleX = 0f
                        scaleY = 0f
                        animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(200)
                            .setInterpolator(OvershootInterpolator())
                            .start()
                    }
                }
            } else {
                if (binding.btnShowUserMessages.visibility == View.VISIBLE) {
                    binding.btnShowUserMessages.animate()
                        .alpha(0f)
                        .scaleX(0f)
                        .scaleY(0f)
                        .setDuration(150)
                        .withEndAction {
                            binding.btnShowUserMessages.visibility = View.GONE
                        }
                        .start()
                }
            }
        }
    }
    private fun Float.dpToPx(): Float {
        return this * resources.displayMetrics.density
    }

    private fun Int.dpToPx(): Float {
        return this * resources.displayMetrics.density
    }

    private fun showUserMessagesDialog() {
        val userMessages = adapter.currentList.filter { it.sender == "user" && it.content.isNotEmpty() }
        if (userMessages.isEmpty()) return

        val dialogBinding = DialogUserMessagesBinding.inflate(LayoutInflater.from(requireContext()))
        val dialog = android.app.Dialog(requireContext(), R.style.DialogTheme)
        dialog.setContentView(dialogBinding.root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)


        var allMessages = userMessages
        val messageAdapter = UserMessagesAdapter(allMessages) { position ->
            dialog.dismiss()
            scrollToUserMessage(allMessages[position])
        }

        dialogBinding.apply {
            tvMessageCount.text = "${userMessages.size} tin nhắn"
            recyclerViewUserMessages.layoutManager = LinearLayoutManager(requireContext())
            recyclerViewUserMessages.adapter = messageAdapter

            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString() ?: ""
                    allMessages = if (query.isBlank()) userMessages
                    else userMessages.filter { it.content.contains(query, true) }
                    messageAdapter.updateMessages(allMessages)
                    tvMessageCount.text = "${allMessages.size} tin nhắn"
                    ivClearSearch.visibility = if (query.isNotBlank()) View.VISIBLE else View.GONE
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            ivClearSearch.setOnClickListener { etSearch.text?.clear() }
            btnClose.setOnClickListener { dialog.dismiss() }
            btnScrollToBottom.setOnClickListener {
                scrollToBottom()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun scrollToUserMessage(targetMessage: Message) {
        val messages = adapter.currentList
        val index = messages.indexOfFirst { it.id == targetMessage.id }
        if (index != -1) {
            binding.recyclerView.scrollToPosition(index)

            // Highlight animation
            binding.recyclerView.postDelayed({
                val viewHolder = binding.recyclerView.findViewHolderForAdapterPosition(index)
                viewHolder?.itemView?.let { view ->
                    view.animate()
                        .scaleX(1.05f).scaleY(1.05f)
                        .setDuration(150)
                        .withEndAction {
                            view.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(150)
                                .start()
                        }
                        .start()
                }
            }, 300)

            Toast.makeText(requireContext(), "Đã tìm thấy tin nhắn", Toast.LENGTH_SHORT).show()
        }
    }
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
    private fun stopGeneration() {
        // ✅ Không cancel streamingJob ngay - để NonCancellable trong repo chạy xong
        llmEngine.stopGeneration() // dừng generate token
        isGenerating = false
        isStreaming = false
        resetSendButton()
        AppState.streamingSessionId = null
        AppState.streamingContent = ""

        val cur = adapter.currentList.toMutableList()
        val idx = cur.indexOfFirst { it.id.startsWith("streaming_") }
        if (idx != -1) {
            if (cur[idx].content.isNotEmpty()) {
                val stoppedMsg = cur[idx].copy(id = "stopped_${System.currentTimeMillis()}")
                cur[idx] = stoppedMsg
                adapter.submitList(cur.toList())
            } else {
                cur.removeAt(idx)
                adapter.submitList(cur.toList())
            }
        }

        // ✅ Cancel sau 2s để NonCancellable kịp lưu
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            streamingJob?.cancel()
            currentGenerationJob?.cancel()
        }
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
                                if (isStreaming) {
                                    Log.d("ChatFragment", "Ignoring bot_processing while streaming")
                                    return@collect
                                }
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
                            msg.content.isNotEmpty() && msg.sender == "bot" -> {
                                if (isStreaming) return@collect  // guard cũ

                                val cur = adapter.currentList.toMutableList()

                                // ✅ Thêm: đã có streaming bubble rồi thì bỏ qua hoàn toàn
                                if (cur.any { it.id.startsWith("streaming_") }) return@collect

                                val uniqueStreamingMsg = msg.copy(
                                    id = "streaming_${System.currentTimeMillis()}"
                                )
                                cur.removeAll { it.id.startsWith("streaming_") }
                                cur.add(uniqueStreamingMsg)
                                adapter.submitList(cur.toList())
                            }
                            // User message - Thêm vào list (cả máy gửi và máy nhận)
                            msg.sender == "user" -> {
                                val cur = adapter.currentList.toMutableList()
                                val optIdx = cur.indexOfFirst {
                                    it.id.startsWith("opt_user_") && it.content == msg.content
                                }

                                if (optIdx != -1) {
                                    // ✅ Replace tại chỗ — giữ nguyên vị trí trước streaming_bot
                                    cur[optIdx] = msg
                                    adapter.submitList(cur.toList())
                                    scrollToBottom()
                                } else if (cur.none { it.id == msg.id }) {
                                    // Không có opt (ví dụ tin nhắn từ thiết bị khác)
                                    val streamingIdx = cur.indexOfFirst { it.id.startsWith("streaming_") }
                                    if (streamingIdx != -1) {
                                        cur.add(streamingIdx, msg)  // chèn trước bot
                                    } else {
                                        cur.add(msg)
                                    }
                                    adapter.submitList(cur.toList())
                                    scrollToBottom()
                                }
                                // ✅ Không cần làm gì nếu msg.id đã tồn tại
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
                        AppState.isConnectServer = true
                        binding.tvConnectionStatus.visibility = View.GONE
                    }
                    is ChatRepository.RealtimeEvent.BranchCreated -> {
                        if (event.sessionId == AppState.currentSessionId) {
                            BranchManager.onBranchCreated(
                                pivotMessageId = event.pivotMessageId,
                                newBranchInfo  = BranchManager.BranchInfo(
                                    branchId  = event.branchId,
                                    index     = event.branchInfo.index,
                                    total     = event.branchInfo.total,
                                    createdAt = event.branchInfo.createdAt.toString(),
                                ),
                                allBranches = emptyList()
                            )
                        }
                    }
                    is ChatRepository.RealtimeEvent.Disconnected -> {
                        AppState.isConnectServer = false
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
    private fun updateEmptyState() {
        val hasMessages = adapter.currentList.isNotEmpty()
        binding.emptyState.visibility = if (hasMessages) View.GONE else View.VISIBLE
        binding.recyclerView.visibility = if (hasMessages) View.VISIBLE else View.GONE
    }
    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return

        streamingJob?.cancel()
        isStreaming = true
        AppState.streamingSessionId = AppState.currentSessionId
        AppState.streamingContent = ""
        binding.etMessage.setText("")
        webSocketManager.sendTyping(false)

        isGenerating = true
        binding.btnSend.apply {
            setImageResource(R.drawable.ic_pause)
            isEnabled = true
            alpha = 1f
        }

        val now = System.currentTimeMillis()
        val optUser = Message(
            id = "opt_user_$now",
            sessionId = AppState.currentSessionId ?: "new",
            content = text,
            sender = "user",
            timestamp = now,
            extraData = null
        )
        val optBot = Message(
            id = "streaming_$now",
            sessionId = AppState.currentSessionId ?: "new",
            content = "",
            sender = "bot",
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

        // ✅ Dùng sendMode để quyết định
        val isOnline = AppState.isConnectServer
        val hasModel = llmEngine.isLoaded()

        when {
            // Không có mạng + có model → bắt buộc offline
            !isOnline && hasModel -> sendOffline(text, now)

            // Có mạng + có model → theo lựa chọn của user
            isOnline && hasModel -> {
                if (sendMode == SendMode.OFFLINE) sendOffline(text, now)
                else sendOnline(text, now)
            }

            // Có mạng + không có model → bắt buộc online
            isOnline && !hasModel -> sendOnline(text, now)

            // Không có mạng + không có model → lỗi
            else -> {
                Toast.makeText(requireContext(), "Không có kết nối mạng và chưa tải model", Toast.LENGTH_SHORT).show()
                isGenerating = false
                resetSendButton()
            }
        }
    }

    private fun sendOffline(text: String, now: Long) {
        streamingJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = chatRepository.sendOfflineMessage(
                sessionId = AppState.currentSessionId,
                content = text,
                onToken = { token ->
                    val cur = adapter.currentList.toMutableList()
                    val idx = cur.indexOfFirst { it.id.startsWith("streaming_") }
                    if (idx != -1) {
                        cur[idx] = cur[idx].copy(content = cur[idx].content + token)
                        adapter.submitList(cur.toList())
                        AppState.streamingContent = cur[idx].content
                        if (!userScrolledUp) scrollToBottom()  // ✅
                    }
                }
            )

            // ✅ Luôn reset button dù success hay fail
            isGenerating = false
            isStreaming = false
            resetSendButton()
            AppState.streamingSessionId = null
            AppState.streamingContent = ""

            result.onSuccess { sendResult ->
                if (AppState.currentSessionId != sendResult.sessionId) {
                    AppState.currentSessionId = sendResult.sessionId
                    binding.tvSessionTitle.text = sendResult.sessionTitle
                    AppState.currentSession = ChatSession(
                        id = sendResult.sessionId,
                        userId = AppState.currentUserId,
                        title = sendResult.sessionTitle,
                        createdAt = now,
                        updatedAt = now
                    )
                }

                val cur = adapter.currentList.toMutableList()
                val idx = cur.indexOfFirst { it.id.startsWith("streaming_") }
                val optIdx = cur.indexOfFirst { it.id.startsWith("opt_user_") }

                // ✅ Chỉ đổi id bubble, giữ nguyên content đang hiển thị
                if (idx != -1) cur[idx] = cur[idx].copy(id = sendResult.botMessage.id)
                if (optIdx != -1) cur[optIdx] = sendResult.userMessage

                adapter.submitList(cur.toList())
                if (!userScrolledUp) scrollToBottom()
                updateModelStatus()
            }.onFailure { e ->
                adapter.submitList(
                    adapter.currentList.filter {
                        !it.id.startsWith("streaming_") && !it.id.startsWith("opt_user_")
                    }
                )
                if (isAdded) {
                    Toast.makeText(requireContext(), "Lỗi offline: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendOnline(text: String, now: Long) {
        streamingJob = viewLifecycleOwner.lifecycleScope.launch {
            chatRepository.streamMessage(AppState.currentSessionId, text, AppState.currentEndpoint, if (AppState.currentSessionId != null) currentBranchId else null    )
                .collect { chunk ->
                    when (chunk) {
                        is ChatRepository.StreamChunk.Token -> {
                            val cur = adapter.currentList.toMutableList()
                            val idx = cur.indexOfFirst { it.id.startsWith("streaming_") }
                            if (idx != -1) {
                                cur[idx] = cur[idx].copy(content = cur[idx].content + chunk.text)
                                adapter.submitList(cur.toList())
                                AppState.streamingContent = cur[idx].content
                                if (!userScrolledUp) scrollToBottom()  //
                            }
                        }
                        is ChatRepository.StreamChunk.Done -> {
                            if (chunk.botMessage != null) {
                                val cur = adapter.currentList.toMutableList()
                                val idx = cur.indexOfFirst { it.id.startsWith("streaming_") }
                                if (idx != -1) {
                                    cur[idx] = chunk.botMessage
                                    adapter.submitList(cur.toList())
                                    scrollToBottom()
                                }
                            }
                        }
                        is ChatRepository.StreamChunk.Meta -> {
                            isStreaming = false
                            isGenerating = false
                            resetSendButton()
                            AppState.streamingSessionId = null
                            AppState.streamingContent = ""

                            val isNewSession = AppState.currentSessionId != chunk.sessionId

                            if (isNewSession) {
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

                            localMessages.removeAll {
                                it.id.startsWith("streaming_") || it.id.startsWith("opt_user_")
                            }
                            updateModelStatus()
                        }
                        is ChatRepository.StreamChunk.Error -> {
                            isStreaming = false
                            AppState.streamingSessionId = null
                            AppState.streamingContent = ""
                            adapter.submitList(
                                adapter.currentList.filter {
                                    !it.id.startsWith("streaming_") && !it.id.startsWith("opt_user_")
                                }
                            )
                            if (isAdded) {
                                Toast.makeText(requireContext(), "Lỗi: ${chunk.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
        }
    }
    private fun scrollToBottom() {
        if (!isAdded || _binding == null) return
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
                    chatRepository.getMessagesFlow(AppState.currentSessionId!!, currentBranchId).collect { msgList ->
                        // Ẩn skeleton, hiện recyclerView
                        binding.skeletonLoading.visibility = View.GONE
                        binding.recyclerView.visibility = View.VISIBLE

                        if (!isStreaming) {
                            adapter.submitList(msgList)
                            binding.recyclerView.post {
                                adapter.notifyDataSetChanged()
                            }
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
    private fun rebuildBranchManagerFromMessages(messages: List<Message>) {
        // BranchManager đã được populate từ loadPivotsForSession trong repo
        // Chỉ cần notify adapter để re-bind navigator
        if (messages.isNotEmpty()) {
            adapter.notifyDataSetChanged()
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
        BranchManager.init(sessionId)
        currentBranchId = sessionId
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

                    chatRepository.getMessagesFlow(sessionId,currentBranchId).collect { msgList ->
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
            Log.d("ChatFragment", "Normal load for session $sessionId")
            isStreaming = false
            adapter.submitList(emptyList())
            loadMessagesJob = lifecycleScope.launch {
                // Lấy nhánh có message mới nhất
                val latestBranch = chatRepository.getLatestBranchId(sessionId)
                currentBranchId = latestBranch
                Log.d("ChatFragment", "Latest branch: $latestBranch")
                loadMessages()
            }
        }
    }
    private fun setupScrollToBottomButton() {
        binding.btnScrollToBottom.setOnClickListener {
            scrollToBottom()
            binding.btnScrollToBottom.visibility = View.GONE
            userScrolledUp = false
        }

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisibleItem = layoutManager.findLastVisibleItemPosition()
                val totalItems = adapter.itemCount

                // ✅ dy > 0 = vuốt lên (xem tin mới hơn), dy < 0 = vuốt xuống (xem tin cũ hơn)
                if (dy < 0) userScrolledUp = true
                if (lastVisibleItem >= totalItems - 2) userScrolledUp = false

                binding.btnScrollToBottom.visibility =
                    if (totalItems > 0 && lastVisibleItem < totalItems - 1)
                        View.VISIBLE else View.GONE
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