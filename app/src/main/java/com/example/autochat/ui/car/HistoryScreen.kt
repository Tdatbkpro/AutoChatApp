package com.example.autochat.ui.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.autochat.AppState
import com.example.autochat.di.ChatEntryPoint
import com.example.autochat.domain.model.ChatSession
import com.example.autochat.domain.repository.ChatRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryScreen(
    carContext: CarContext,
    private val chatScreen: MyChatScreen
) : Screen(carContext) {

    private val chatRepository: ChatRepository by lazy {
        EntryPointAccessors.fromApplication(
            carContext.applicationContext,
            ChatEntryPoint::class.java
        ).chatRepository()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sessions = listOf<ChatSession>()
    private var page = 0
    private val pageSize = 5
    private var showDeleteConfirm = false
    private var sessionToDelete: ChatSession? = null

    init {
        loadSessions()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                scope.cancel()
            }
        })
    }
    private fun loadSessions() {
        scope.launch {
            try {
                chatRepository.getSessionsFlow().collect { list ->
                    sessions = list.sortedByDescending { it.updatedAt }
                    val totalPages = maxOf(1, (sessions.size + pageSize - 1) / pageSize)
                    if (page >= totalPages) page = maxOf(0, totalPages - 1)
                    invalidate()
                }
            } catch (e: Exception) {
                android.util.Log.e("HISTORY", "loadSessions error: ${e.message}")
            }
        }
    }
    private fun formatTime(timestamp: Long): String {
        return try {
            val date = Date(timestamp)
            val now = Date()
            val sdf = if (date.year == now.year && date.month == now.month && date.date == now.date) {
                SimpleDateFormat("HH:mm", Locale("vi"))
            } else {
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("vi"))
            }
            sdf.format(date)
        } catch (e: Exception) {
            "--:--"
        }
    }

    override fun onGetTemplate(): Template {
        return when {
            showDeleteConfirm && sessionToDelete != null -> buildDeleteConfirmTemplate()
            else -> buildSessionListTemplate()
        }
    }

    private fun buildSessionListTemplate(): Template {
        val itemListBuilder = ItemList.Builder()

        val totalPages = maxOf(1, (sessions.size + pageSize - 1) / pageSize)
        val start = page * pageSize
        val end = minOf(start + pageSize, sessions.size)
        val pageSessions = if (sessions.isEmpty()) emptyList()
        else sessions.subList(start, end)

        if (sessions.isEmpty()) {
            itemListBuilder.setNoItemsMessage(" Chưa có lịch sử chat nào")
        } else {
            // Header thông tin
            if (page == 0) {
                itemListBuilder.addItem(
                    Row.Builder()
                        .setTitle("Tổng quan")
                        .addText("Gồm ${sessions.size} đoạn chat")
                        .build()
                )
            }

            // Danh sách sessions
            pageSessions.forEach { session ->
                val timeStr = formatTime(session.updatedAt)

                itemListBuilder.addItem(
                    Row.Builder()
                        .setTitle("💬 ${session.title.take(35)}")
                        .addText(" $timeStr")
                        .setBrowsable(true)
                        .setOnClickListener {
                            screenManager.push(
                                HistoryDetailScreen(carContext, session, chatScreen)
                            )
                        }
                        .build()
                )
            }

            //  THÊM LẠI ĐIỀU HƯỚNG TRANG
            if (page > 0 || end < sessions.size) {
                val navRowBuilder = Row.Builder()

                when {
                    page > 0 && end < sessions.size -> {
                        navRowBuilder.setTitle(" Điều hướng")
                        navRowBuilder.addText("◀ Trang trước  |  Trang sau ▶")
                        navRowBuilder.setBrowsable(true)
                        navRowBuilder.setOnClickListener {
                            showPageNavigationDialog(totalPages)
                        }
                    }
                    page > 0 -> {
                        navRowBuilder.setTitle("◀ Trang trước")
                        navRowBuilder.setBrowsable(true)
                        navRowBuilder.setOnClickListener {
                            page--
                            invalidate()
                        }
                    }
                    end < sessions.size -> {
                        navRowBuilder.setTitle("Trang sau ▶")
                        navRowBuilder.setBrowsable(true)
                        navRowBuilder.setOnClickListener {
                            page++
                            invalidate()
                        }
                    }
                }

                itemListBuilder.addItem(navRowBuilder.build())
            }
        }

        // Footer thông tin trang
        if (totalPages > 1) {
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle(" Trang ${page + 1}/$totalPages")
                    .addText("${sessions.size} đoạn chat")
                    .build()
            )
        }

        // Action Strip - chỉ 1 action có title
        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(
                                carContext, android.R.drawable.ic_menu_add
                            )
                        ).build()
                    )
                    .setOnClickListener {
                        AppState.currentSession = null
                        chatScreen.clearMessages()
                        screenManager.popToRoot()
                        CarToast.makeText(carContext, "✨ Bắt đầu chat mới", CarToast.LENGTH_SHORT).show()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(
                                carContext, android.R.drawable.ic_menu_rotate
                            )
                        ).build()
                    )
                    .setOnClickListener {
                        page = 0
                        loadSessions()
                        CarToast.makeText(carContext, "🔄 Đã làm mới lịch sử", CarToast.LENGTH_SHORT).show()
                    }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setTitle(" Lịch sử trò chuyện")
            .setHeaderAction(Action.BACK)
            .setSingleList(itemListBuilder.build())
            .setActionStrip(actionStrip)
            .build()
    }

    private fun showPageNavigationDialog(totalPages: Int) {
        CarToast.makeText(
            carContext,
            "Trang ${page + 1}/$totalPages - Dùng nút ◀ ▶ để chuyển",
            CarToast.LENGTH_SHORT
        ).show()
    }

    private fun buildDeleteConfirmTemplate(): Template {
        val session = sessionToDelete ?: return buildSessionListTemplate()

        val confirmMsg = buildString {
            append(" Xóa đoạn chat?\n\n")
            append("${session.title}\n\n")
            append("Hành động này sẽ xóa tất cả tin nhắn và không thể khôi phục!")
        }

        return MessageTemplate.Builder(confirmMsg)
            .setTitle("🗑Xóa đoạn chat")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle(" Hủy")
                    .setOnClickListener {
                        showDeleteConfirm = false
                        sessionToDelete = null
                        invalidate()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(" Xóa")
                    .setOnClickListener {
                        deleteSession(session)
                    }
                    .build()
            )
            .build()
    }

    private fun deleteSession(session: ChatSession) {
        scope.launch {
            try {
                chatRepository.deleteSession(session.id)

                CarToast.makeText(carContext, " Đã xóa đoạn chat", CarToast.LENGTH_SHORT).show()

                showDeleteConfirm = false
                sessionToDelete = null

                val newTotalPages = maxOf(1, (sessions.size + pageSize - 1) / pageSize)
                if (page >= newTotalPages) page = maxOf(0, newTotalPages - 1)

            } catch (e: Exception) {
                android.util.Log.e("HISTORY", "deleteSession error: ${e.message}")
                CarToast.makeText(carContext, " Lỗi xóa. Thử lại!", CarToast.LENGTH_SHORT).show()
                showDeleteConfirm = false
                sessionToDelete = null
                invalidate()
            }
        }
    }
}