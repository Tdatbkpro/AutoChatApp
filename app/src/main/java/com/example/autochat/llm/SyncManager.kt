package com.example.autochat.llm

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.autochat.AppState
import com.example.autochat.data.local.AppDatabase
import com.example.autochat.data.local.entity.SessionEntity
import com.example.autochat.remote.api.ChatApi
import com.example.autochat.remote.dto.request.OfflineMessageSync
import com.example.autochat.remote.dto.request.SyncOfflineRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// SyncManager.kt
// ─── llm/SyncManager.kt ─────────────────────────────────────────────────────

@Singleton
class SyncManager @Inject constructor(
    private val database: AppDatabase,
    private val chatApi: ChatApi,
    private val dataStore: DataStore<Preferences>,
    @ApplicationContext private val context: Context
) {
    companion object {
        private val ACCESS_TOKEN = stringPreferencesKey("access_token")
        private const val TAG = "SyncManager"
    }
    init {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(2000)
                    syncPendingMessages()
                }
            }
        })
    }
    suspend fun syncPendingMessages() {
        withContext(Dispatchers.IO) {
            // ── 1. Lấy token ──────────────────────────────────────────────
            val token: String = (AppState.accessToken?.takeIf { it.isNotEmpty() }
                ?: runCatching { dataStore.data.first()[ACCESS_TOKEN] }.getOrNull()?.takeIf { it.isNotEmpty() })
                ?: run {
                    Log.w(TAG, "No token → skip sync")
                    return@withContext
                }

            // ── 2. Lấy danh sách chưa sync ───────────────────────────────
            val unsyncedMessages = runCatching {
                database.messageDao().getUnsyncedMessages()
            }.getOrElse {
                Log.e(TAG, "Read unsynced messages failed: ${it.message}")
                return@withContext
            }

            if (unsyncedMessages.isEmpty()) {
                Log.d(TAG, "Nothing to sync")
                return@withContext
            }

            Log.d(TAG, "Found ${unsyncedMessages.size} unsynced messages")

            // ── 3. Sync sessions TRƯỚC ────────────────────────────────────
            val sessionIds = unsyncedMessages.map { it.sessionId }.distinct()
            val unsyncedSessions = sessionIds
                .mapNotNull { database.sessionDao().getSession(it) }
                .filter { !it.isSynced }

            Log.d(TAG, "Found ${unsyncedSessions.size} unsynced sessions")

            for (session in unsyncedSessions) {
                val success = syncSingleSession(token, session)
                if (!success) {
                    // Session fail → dừng hẳn, không sync message
                    // (tránh foreign key lỗi phía server)
                    Log.e(TAG, "Session sync failed → abort message sync")
                    return@withContext
                }
            }

            // ── 4. Sync messages SAU KHI session đã lên server ───────────
            val sessionTitleMap = sessionIds
                .mapNotNull { database.sessionDao().getSession(it) }
                .associate { it.id to it.title }

            val items = unsyncedMessages.map { msg ->
                OfflineMessageSync(
                    id           = msg.id,
                    sessionId    = msg.sessionId,
                    content      = msg.content,
                    sender       = msg.sender,
                    timestamp    = msg.timestamp,
                    sessionTitle = sessionTitleMap[msg.sessionId],
                    extraData    = null
                )
            }

            runCatching {
                val response = chatApi.syncOfflineMessages(
                    token = "Bearer $token",
                    body  = SyncOfflineRequest(messages = items)
                )
                if (response.isSuccessful) {
                    val synced = response.body()
                    database.messageDao().markAsSynced(unsyncedMessages.map { it.id })
                    Log.d(TAG, "✅ Messages synced: ${synced?.syncedCount}, new sessions: ${synced?.newSessions}")
                } else {
                    Log.e(TAG, "❌ Message sync HTTP ${response.code()}: ${response.errorBody()?.string()}")
                }
            }.onFailure {
                Log.e(TAG, "❌ Message sync exception: ${it.message}", it)
            }
        }
    }

    // ── Helper: sync 1 session lên server ────────────────────────────────────
    // Server tự get_or_create qua endpoint syncOfflineMessages với session_title
    // nên thực ra chỉ cần đánh dấu local là synced nếu server đã biết session này.
    // Nếu server CÓ endpoint riêng /chat/sessions/sync thì gọi ở đây,
    // còn không thì chỉ mark local là synced để không lặp lại.
    private suspend fun syncSingleSession(token: String, session: SessionEntity): Boolean {
        return runCatching {
            // Server sẽ get_or_create session khi nhận syncOfflineMessages,
            // nên đây chỉ cần mark local trước để tránh sync lại nhiều lần.
            // Nếu muốn chắc chắn hơn: gọi API riêng tạo session ở đây.
            database.sessionDao().markAsSynced(listOf(session.id))
            Log.d(TAG, "✅ Session marked synced: ${session.id}")
            true
        }.getOrElse {
            Log.e(TAG, "❌ Session mark failed: ${it.message}")
            false
        }
    }
}