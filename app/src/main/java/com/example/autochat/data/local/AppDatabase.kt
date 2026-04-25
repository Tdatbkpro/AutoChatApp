package com.example.autochat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.autochat.data.local.dao.MessageDao
import com.example.autochat.data.local.dao.ReadHistoryDao
import com.example.autochat.data.local.dao.SessionDao
import com.example.autochat.data.local.entity.MessageEntity
import com.example.autochat.data.local.entity.ReadHistoryEntity
import com.example.autochat.data.local.entity.SessionEntity

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ReadHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun readHistoryDao(): ReadHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "autochat.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}