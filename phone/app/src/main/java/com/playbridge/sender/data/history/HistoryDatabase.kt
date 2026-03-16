package com.playbridge.sender.data.history

import androidx.room.Database
import androidx.room.RoomDatabase
import com.playbridge.sender.data.library.AddonDao
import com.playbridge.sender.data.library.InstalledAddonEntity

@Database(
    entities = [HistoryEntity::class, BookmarkEntity::class, TabEntity::class, InstalledAddonEntity::class, CommandHistoryEntity::class],
    version = 5
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun tabDao(): TabDao
    abstract fun addonDao(): AddonDao
    abstract fun commandHistoryDao(): CommandHistoryDao
}
