package com.playbridge.sender.data.history

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


object DatabaseProvider {

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `command_history` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`commandType` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, " +
                "`title` TEXT, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`payloadJson` TEXT)"
            )
        }
    }

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE watchlist ADD COLUMN status TEXT NOT NULL DEFAULT 'plan_to_watch'")
            db.execSQL("ALTER TABLE watchlist ADD COLUMN userRating INTEGER")
            db.execSQL("ALTER TABLE watchlist ADD COLUMN seasonProgress INTEGER")
            db.execSQL("ALTER TABLE watchlist ADD COLUMN episodeProgress INTEGER")
            db.execSQL("ALTER TABLE watchlist ADD COLUMN notes TEXT")
            db.execSQL("ALTER TABLE watchlist ADD COLUMN startedAt INTEGER")
            db.execSQL("ALTER TABLE watchlist ADD COLUMN completedAt INTEGER")
        }
    }

    @Volatile
    private var INSTANCE: HistoryDatabase? = null

    fun getDatabase(context: Context): HistoryDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                HistoryDatabase::class.java,
                "history_database"
            )
            .addMigrations(MIGRATION_4_5, MIGRATION_6_7)
            .fallbackToDestructiveMigration()
            .build()
            INSTANCE = instance
            instance
        }
    }
}
