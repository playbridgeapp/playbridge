package com.playbridge.sender.data.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["url"], unique = true)]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val title: String?,
    val timestamp: Long = System.currentTimeMillis()
)
