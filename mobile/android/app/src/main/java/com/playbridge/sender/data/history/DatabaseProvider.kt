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

    private val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tabs ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Content-keyed cross-session resume positions (PROGRESS_TRACKING_PLAN.md P1.5).
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `playback_resume` (" +
                    "`contentKey` TEXT NOT NULL, " +
                    "`tmdbId` INTEGER NOT NULL, " +
                    "`mediaType` TEXT NOT NULL, " +
                    "`season` INTEGER, " +
                    "`episode` INTEGER, " +
                    "`title` TEXT, " +
                    "`positionMs` INTEGER NOT NULL, " +
                    "`durationMs` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`contentKey`))"
            )
        }
    }

    private val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // IPTV playlists + their cached channels (IPTV_PLAN.md §2).
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `iptv_playlists` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "`sourceType` TEXT NOT NULL, " +
                    "`addedAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`channelCount` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `iptv_channels` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`playlistId` INTEGER NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`logo` TEXT, " +
                    "`groupTitle` TEXT, " +
                    "`tvgId` TEXT, " +
                    "`orderIndex` INTEGER NOT NULL, " +
                    "`headersJson` TEXT, " +
                    "`probeStatus` TEXT NOT NULL, " +
                    "`probeLatencyMs` INTEGER, " +
                    "`probedAt` INTEGER, " +
                    "FOREIGN KEY(`playlistId`) REFERENCES `iptv_playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_iptv_channels_playlistId` ON `iptv_channels` (`playlistId`)")
        }
    }

    private val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // User-curated collections + their ordered items (COLLECTIONS_PLAN.md §2).
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `collections` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`addedAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`itemCount` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `collection_items` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`collectionId` INTEGER NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, " +
                    "`mimeType` TEXT, " +
                    "`headersJson` TEXT, " +
                    "`logo` TEXT, " +
                    "`sourceTag` TEXT, " +
                    "`orderIndex` INTEGER NOT NULL, " +
                    "`addedAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_items_collectionId` ON `collection_items` (`collectionId`)")
        }
    }

    private val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Phase-1 download engine state (DOWNLOAD_REWRITE_PLAN.md §3.4).
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `downloads` (" +
                    "`id` TEXT NOT NULL, " +
                    "`url` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`kind` TEXT NOT NULL, " +
                    "`status` TEXT NOT NULL, " +
                    "`mimeType` TEXT, " +
                    "`headersJson` TEXT, " +
                    "`bytesDownloaded` INTEGER NOT NULL, " +
                    "`totalBytes` INTEGER NOT NULL, " +
                    "`filePath` TEXT, " +
                    "`errorReason` TEXT, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`id`))"
            )
        }
    }

    private val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Nuvio scraper-plugin support (NUVIO_PLAN Phase 1). The repo itself is an
            // installed_addons row (resource "nuvio"); per-scraper metadata lives here.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `nuvio_scrapers` (" +
                    "`repoUrl` TEXT NOT NULL, " +
                    "`scraperId` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`description` TEXT NOT NULL, " +
                    "`version` TEXT NOT NULL, " +
                    "`filename` TEXT NOT NULL, " +
                    "`supportedTypes` TEXT NOT NULL, " +
                    "`contentLanguage` TEXT NOT NULL, " +
                    "`logo` TEXT NOT NULL, " +
                    "`isEnabled` INTEGER NOT NULL, " +
                    "`installedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`repoUrl`, `scraperId`))"
            )
        }
    }

    private val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Per-scraper settings (NUVIO_PLAN Phase 4).
            db.execSQL("ALTER TABLE nuvio_scrapers ADD COLUMN hasSettings INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE nuvio_scrapers ADD COLUMN settingsJson TEXT NOT NULL DEFAULT '{}'")
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
            .addMigrations(MIGRATION_4_5, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
            .fallbackToDestructiveMigration()
            .build()
            INSTANCE = instance
            instance
        }
    }
}
