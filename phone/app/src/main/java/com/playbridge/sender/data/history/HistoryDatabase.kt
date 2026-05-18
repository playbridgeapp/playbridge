package com.playbridge.sender.data.history

import androidx.room.Database
import androidx.room.RoomDatabase
import com.playbridge.sender.data.library.AddonDao
import com.playbridge.sender.data.library.InstalledAddonEntity
import com.playbridge.sender.data.library.WatchlistDao
import com.playbridge.sender.data.library.WatchlistEntity

@Database(
    entities = [HistoryEntity::class, BookmarkEntity::class, TabEntity::class, InstalledAddonEntity::class, CommandHistoryEntity::class, WatchlistEntity::class, SearchHistoryEntity::class],
    version = 15
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun tabDao(): TabDao
    abstract fun addonDao(): AddonDao
    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun searchHistoryDao(): SearchHistoryDao
}
