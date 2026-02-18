package com.playbridge.sender.data.history

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [HistoryEntity::class, BookmarkEntity::class], version = 2)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
}
