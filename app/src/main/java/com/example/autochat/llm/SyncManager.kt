package com.example.autochat.llm

import android.util.Log
import com.example.autochat.data.local.AppDatabase
import com.example.autochat.remote.api.ChatApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// SyncManager.kt
@Singleton
class SyncManager @Inject constructor(
    private val database: AppDatabase,
    private val chatApi: ChatApi
) {
    suspend fun syncPendingMessages() {
        withContext(Dispatchers.IO) {
            try {
                val unsyncedMessages = database.messageDao().getUnsyncedMessages()
                if (unsyncedMessages.isEmpty()) return@withContext

                // Convert sang format API
                val messages = unsyncedMessages.map { msg ->
                    mapOf(
                        "id" to msg.id,
                        "session_id" to msg.sessionId,
                        "content" to msg.content,
                        "sender" to msg.sender,
                        "timestamp" to msg.timestamp
                    )
                }

                // Gọi API sync
                val response = chatApi.syncOfflineMessages(messages)

                if (response.isSuccessful) {
                    // Đánh dấu đã sync
                    database.messageDao().markAsSynced(unsyncedMessages.map { it.id })
                }
            } catch (e: Exception) {
                Log.e("SyncManager", "Sync failed: ${e.message}")
            }
        }
    }
}