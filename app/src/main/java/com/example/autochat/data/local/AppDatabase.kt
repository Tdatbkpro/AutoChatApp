package com.example.autochat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.autochat.data.local.dao.CustomModelDao
import com.example.autochat.data.local.dao.MessageDao
import com.example.autochat.data.local.dao.ReadHistoryDao
import com.example.autochat.data.local.dao.SessionDao
import com.example.autochat.data.local.entity.CustomModelEntity
import com.example.autochat.data.local.entity.MessageEntity
import com.example.autochat.data.local.entity.ReadHistoryEntity
import com.example.autochat.data.local.entity.SessionEntity

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        ReadHistoryEntity::class,
        CustomModelEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun readHistoryDao(): ReadHistoryDao
    abstract fun customModelDao(): CustomModelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // ✅ Migration từ version 1 → 2: bỏ foreign key
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Tạo bảng messages mới không có foreign key
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS messages_new (
                        id TEXT PRIMARY KEY NOT NULL,
                        sessionId TEXT NOT NULL,
                        content TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        extraData TEXT,
                        isSynced INTEGER NOT NULL DEFAULT 0,
                        isOffline INTEGER NOT NULL DEFAULT 0
                    )
                """)
                // Copy data cũ sang bảng mới
                db.execSQL("INSERT OR IGNORE INTO messages_new SELECT id, sessionId, content, sender, timestamp, extraData, isSynced, isOffline FROM messages")
                // Xóa bảng cũ
                db.execSQL("DROP TABLE IF EXISTS messages")
                // Đổi tên bảng mới
                db.execSQL("ALTER TABLE messages_new RENAME TO messages")
                // Tạo index
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId ON messages(sessionId)")
            }
        }

        fun getInstance(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "autochat.db"
                )
                    .addMigrations(MIGRATION_1_2)  // ✅ Thêm migration
                    .fallbackToDestructiveMigration()  // ✅ Fallback nếu không tìm thấy migration phù hợp
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}