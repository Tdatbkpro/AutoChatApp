package com.example.autochat.ui.phone

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val historyViewModel: HistoryViewModel by viewModels()
    private lateinit var drawerAdapter: DrawerSessionAdapter
    private val searchQuery = MutableStateFlow("")
    private var allSessions = listOf<ChatSession>()

    // ✅ Khởi tạo với giá trị mặc định, không dùng lateinit
    private var tvSessionsTitle: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Khởi tạo các view trước
        initViews()

        setupToolbar()
        setupDrawer()
        setupUserInfo()
        setupSearch()
    }

    private fun initViews() {
        // ✅ Khởi tạo các view từ NavigationView
        tvSessionsTitle = findViewById(R.id.tvSessionsTitle)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "AutoChat"
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu)
        }

        binding.toolbar.setNavigationOnClickListener {
            openDrawer()
        }
    }

    private fun setupDrawer() {
        drawerAdapter = DrawerSessionAdapter(
            onSessionClick = { session ->
                AppState.currentSession = session
                drawerAdapter.setSelected(session.id)
                navigateToChat(session.id)
                closeDrawer()
            },
            onDeleteClick = { session ->
                historyViewModel.deleteSession(session.id)
                if (AppState.currentSession?.id == session.id) {
                    AppState.currentSession = null
                    drawerAdapter.setSelected(null)
                }
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
            AppState.currentSession = null
            drawerAdapter.setSelected(null)
            navigateToChat(null)
            closeDrawer()
        }

        binding.btnSettings.setOnClickListener {
            closeDrawer()
            val navHost = supportFragmentManager
                .findFragmentById(R.id.navHostFragment) as NavHostFragment
            navHost.navController.navigate(R.id.profileFragment)
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
        // ✅ Kiểm tra null an toàn
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

        // ✅ Nếu đã ở chat fragment, pop để tạo mới
        if (navController.currentDestination?.id == R.id.chatFragment) {
            navController.popBackStack()
        }

        navController.navigate(R.id.chatFragment)

        // ✅ Load session sau khi fragment tạo xong
        binding.root.postDelayed({
            val chatFragment = navHost.childFragmentManager.findFragmentById(R.id.chatFragment) as? ChatFragment
            sessionId?.let {
                android.util.Log.d("MainActivity", "Load session: $it")
                chatFragment?.loadSession(it)
            }
        }, 300)
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