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

    private val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE installed_addons ADD COLUMN resources TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE installed_addons ADD COLUMN catalogsJson TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE installed_addons ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE installed_addons ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Comma-separated list of resource names the user has disabled per-addon,
            // e.g. "catalog,meta". Empty string = all features active.
            db.execSQL("ALTER TABLE installed_addons ADD COLUMN disabledFeatures TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `search_history` (`query` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`query`))")
        }
    }

    private val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE installed_addons ADD COLUMN resourceDetailsJson TEXT NOT NULL DEFAULT ''"
            )
        }
    }

    private val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tabs ADD COLUMN sessionState BLOB")
        }
    }

    private val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE installed_addons ADD COLUMN playEndpoint TEXT NOT NULL DEFAULT ''")
        }
    }

    private val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE installed_addons ADD COLUMN isConfigurable INTEGER NOT NULL DEFAULT 0")
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
            .addMigrations(MIGRATION_4_5, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
            .fallbackToDestructiveMigration()
            .build()
            INSTANCE = instance
            instance
        }
    }
}
