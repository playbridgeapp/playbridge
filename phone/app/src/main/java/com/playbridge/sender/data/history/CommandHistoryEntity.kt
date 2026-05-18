package com.playbridge.sender.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "command_history"
)
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val commandType: String, // e.g. "play" or "browser"
    val url: String,
    val title: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val payloadJson: String? // Raw JSON representation of the command for re-sending/inspection
)
