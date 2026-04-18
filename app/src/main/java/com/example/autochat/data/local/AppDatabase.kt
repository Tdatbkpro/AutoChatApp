package com.example.autochat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.autochat.data.local.dao.ReadHistoryDao
import com.example.autochat.data.local.entity.ReadHistoryEntity

@Database(
    entities = [ReadHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun readHistoryDao(): ReadHistoryDao
}