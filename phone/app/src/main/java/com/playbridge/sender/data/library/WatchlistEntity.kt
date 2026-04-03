package com.playbridge.sender.data.library

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val tmdbId: Int,
    val mediaType: String, // "movie" or "tv"
    val title: String,
    val posterUrl: String?,
    val year: String,
    val rating: String,
    val addedAt: Long = System.currentTimeMillis()
)
