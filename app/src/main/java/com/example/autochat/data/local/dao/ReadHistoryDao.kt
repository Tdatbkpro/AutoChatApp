package com.example.autochat.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.autochat.data.local.entity.ReadHistoryEntity

@Dao
interface ReadHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ReadHistoryEntity)

    @Query("SELECT articleId FROM read_history ORDER BY readAt DESC")
    suspend fun getAllReadIds(): List<Int>

    @Query("SELECT * FROM read_history ORDER BY readAt DESC")
    suspend fun getAll(): List<ReadHistoryEntity>

    @Query("SELECT COUNT(*) FROM read_history")
    suspend fun count(): Int

    /**
     * Xóa các bài cũ nhất để giữ tối đa [limit] bài.
     * Gọi sau mỗi insert.
     */
    @Query("""
            DELETE FROM read_history
            WHERE rowId NOT IN (
                SELECT rowId FROM read_history
                ORDER BY readAt DESC
                LIMIT :limit
            )
        """)
    suspend fun pruneToLimit(limit: Int = 100)
}