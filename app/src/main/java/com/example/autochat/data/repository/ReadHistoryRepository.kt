package com.example.autochat.data.repository

import com.example.autochat.data.local.dao.ReadHistoryDao
import com.example.autochat.data.local.entity.ReadHistoryEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReadHistoryRepository @Inject constructor(
    private val dao: ReadHistoryDao
) {
    /**
     * Đánh dấu bài đã đọc và tự prune về 100 bài nếu vượt giới hạn.
     */
    suspend fun markRead(
        articleId: Int,
        title: String,
        category: String?
    ) {
        dao.insert(
            ReadHistoryEntity(
                articleId = articleId,
                title = title,
                category = category,
                readAt = System.currentTimeMillis()
            )
        )
        dao.pruneToLimit(100)
    }

    /** Trả về list ID đã đọc, mới nhất trước — dùng để exclude khi fetch next. */
    suspend fun getAllReadIds(): List<Int> = dao.getAllReadIds()
}