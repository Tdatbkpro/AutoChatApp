package com.example.autochat.ui.phone

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.autochat.AppState
import com.example.autochat.R
import com.example.autochat.databinding.ActivityMainBinding
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.ui.phone.adapter.DrawerSessionAdapter
import com.example.autochat.ui.phone.fragment.ChatFragment
import com.example.autochat.ui.phone.viewmodel.HistoryViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

     lateinit var binding: ActivityMainBinding
    private val historyViewModel: HistoryViewModel by viewModels()
    private lateinit var drawerAdapter: DrawerSessionAdapter
    private val searchQuery = MutableStateFlow("")
    private var allSessions = listOf<ChatSession>()
    private var tvSessionsTitle: TextView? = null
    private var currentSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Cho phép vuốt ở bất cứ đâu
        setupFullScreenSwipe()

        initViews()
        setupDrawer()
        setupUserInfo()
        setupSearch()
        observeCurrentSession()
    }

    private var startX = 0f
    private var startY = 0f
    private val SWIPE_THRESHOLD = 150

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFullScreenSwipe() {
        // Bắt sự kiện touch trên toàn bộ màn hình
        binding.root.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = event.x - startX
                    val deltaY = Math.abs(event.y - startY)

                    // Vuốt ngang (deltaX > threshold) và không phải vuốt dọc
                    if (deltaX > SWIPE_THRESHOLD && deltaX > deltaY) {
                        binding.drawerLayout.openDrawer(GravityCompat.START)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun initViews() {
        tvSessionsTitle = findViewById(R.id.tvSessionsTitle)
    }

    private fun setupDrawer() {
        drawerAdapter = DrawerSessionAdapter(
            onSessionClick = { session ->
                selectSession(session)
                closeDrawer()
            },
            onDeleteClick = { session ->
                showDeleteConfirmDialog(session)
            },
            onPinClick = { session ->
                togglePinSession(session)
            },
            onRenameClick = { session ->
                showRenameDialog(session)
            }
        )

        binding.rvSessions.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = drawerAdapter
        }

        // Observe sessions với tìm kiếm
        lifecycleScope.launch {
            combine(
                historyViewModel.sessions,
                searchQuery
            ) { sessions, query ->
                allSessions = sessions
                if (query.isBlank()) {
                    sessions
                } else {
                    sessions.filter { it.title.contains(query, ignoreCase = true) }
                }
            }.collect { filteredSessions ->
                drawerAdapter.submitList(filteredSessions)
                updateSearchResultCount(filteredSessions.size)
            }
        }

        binding.btnNewChatDrawer.setOnClickListener {
            createNewChat()
            closeDrawer()
        }

        binding.btnSettings.setOnClickListener {
            closeDrawer()
            navigateToProfile()
        }
    }

    private fun observeCurrentSession() {
        lifecycleScope.launch {
            historyViewModel.sessions.collect { sessions ->
                // Update current session từ AppState
                val session = AppState.currentSession
                if (session != null && sessions.any { it.id == session.id }) {
                    currentSessionId = session.id
                    drawerAdapter.setSelected(session.id)
                } else if (session != null && sessions.none { it.id == session.id }) {
                    // Session đã bị xóa
                    handleSessionDeleted()
                }
            }
        }
    }

    private fun selectSession(session: ChatSession) {
        AppState.currentSession = session
        currentSessionId = session.id
        drawerAdapter.setSelected(session.id)
        navigateToChat(session.id)
    }

    private fun createNewChat() {
        AppState.currentSession = null
        currentSessionId = null
        drawerAdapter.setSelected(null)
        navigateToChat(null)
    }

    private fun togglePinSession(session: ChatSession) {
        historyViewModel.togglePinSession(session.id)
    }

    private fun showRenameDialog(session: ChatSession) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_rename_session, null)
        val tilName = dialogView.findViewById<TextInputLayout>(R.id.tilSessionName)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.etSessionName)

        etName.setText(session.title)
        etName.setSelection(session.title.length)

        MaterialAlertDialogBuilder(this)
            .setTitle("Đổi tên hội thoại")
            .setView(dialogView)
            .setPositiveButton("Lưu") { dialog, _ ->
                val newName = etName.text.toString().trim()
                if (newName.isNotEmpty() && newName != session.title) {
                    historyViewModel.renameSession(session.id, newName)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Hủy") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showDeleteConfirmDialog(session: ChatSession) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Xóa hội thoại")
            .setMessage("Bạn có chắc muốn xóa hội thoại \"${session.title}\"?\nHành động này không thể hoàn tác.")
            .setPositiveButton("Xóa") { dialog, _ ->
                performDeleteSession(session)
                dialog.dismiss()
            }
            .setNegativeButton("Hủy") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun performDeleteSession(session: ChatSession) {
        val isCurrentSession = currentSessionId == session.id

        // Xóa session từ ViewModel
        historyViewModel.deleteSession(session.id)

        if (isCurrentSession) {
            // Đánh dấu current session là null
            AppState.currentSession = null
            currentSessionId = null
            drawerAdapter.setSelected(null)

            // Kiểm tra còn session nào không
            val remainingSessions = allSessions.filter { it.id != session.id }

            if (remainingSessions.isNotEmpty()) {
                // Load session đầu tiên còn lại
                val firstSession = remainingSessions.first()
                selectSession(firstSession)
            } else {
                // Không còn session -> tạo session mới
                createNewChat()
                // Hoặc nếu muốn thoát app:
                // finishAffinity()
            }
        }

        // Close drawer
        closeDrawer()
    }

    private fun handleSessionDeleted() {
        // Session hiện tại đã bị xóa từ bên ngoài (hoặc bởi device khác)
        AppState.currentSession = null
        currentSessionId = null
        drawerAdapter.setSelected(null)

        val remainingSessions = allSessions.filter { it.id != currentSessionId }
        if (remainingSessions.isNotEmpty()) {
            selectSession(remainingSessions.first())
        } else {
            createNewChat()
        }
    }

    private fun setupSearch() {
        val etSearch = findViewById<EditText>(R.id.etSearchSession)
        val btnClear = findViewById<ImageButton>(R.id.btnClearSearch)

        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery.value = s.toString()
                btnClear?.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                drawerAdapter.setSearchQuery(s.toString())
            }
        })

        btnClear?.setOnClickListener {
            etSearch?.text?.clear()
            searchQuery.value = ""
            btnClear.visibility = View.GONE
        }
    }

    private fun updateSearchResultCount(count: Int) {
        tvSessionsTitle?.text = when {
            searchQuery.value.isBlank() -> "📜 Lịch sử chat"
            count > 0 -> "🔍 Tìm thấy $count kết quả"
            else -> "🔍 Không tìm thấy kết quả"
        }
    }

    private fun setupUserInfo() {
        binding.tvUsername.text = AppState.username.ifEmpty { "Khách" }
        binding.tvAvatar.text = AppState.username
            .firstOrNull()?.uppercaseChar()?.toString() ?: "K"
    }

    private fun navigateToChat(sessionId: String?) {
        val navHost = supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment
        val navController = navHost.navController

        // Nếu đã ở chat fragment, pop để tạo mới
        if (navController.currentDestination?.id == R.id.chatFragment) {
            navController.popBackStack()
        }

        navController.navigate(R.id.chatFragment)

        // Load session sau khi fragment tạo xong
        binding.root.postDelayed({
            val chatFragment = navHost.childFragmentManager
                .findFragmentById(R.id.chatFragment) as? ChatFragment
            if (sessionId != null) {
                chatFragment?.loadSession(sessionId)
            }
        }, 300)
    }

    private fun navigateToProfile() {
        val navHost = supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment
        navHost.navController.navigate(R.id.profileFragment)
    }

    fun openDrawer() {
        if (!binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    fun closeDrawer() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            closeDrawer()
        } else {
            super.onBackPressed()
        }
    }
}