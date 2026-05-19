package com.playbridge.sender.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tabs")
data class TabEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val title: String?,
    val parentId: String?,
    val isSelected: Boolean,
    val lastAccessTime: Long = System.currentTimeMillis(),
    val sessionState: ByteArray? = null,
    val position: Int = 0
)
