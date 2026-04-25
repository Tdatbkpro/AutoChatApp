package com.example.autochat.data.local.dao

import androidx.room.*
import com.example.autochat.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun updateTitle(sessionId: String, title: String, updatedAt: Long)

    @Query("UPDATE sessions SET isPinned = NOT isPinned WHERE id = :sessionId")
    suspend fun togglePin(sessionId: String)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE isSynced = 0")
    suspend fun getUnsyncedSessions(): List<SessionEntity>

    @Query("UPDATE sessions SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<String>)
}