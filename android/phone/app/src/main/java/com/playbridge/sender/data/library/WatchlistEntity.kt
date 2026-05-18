package com.playbridge.sender.data.library

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val tmdbId: Int,
    val mediaType: String,          // "movie" or "tv"
    val title: String,
    val posterUrl: String?,
    val year: String,
    val rating: String,             // TMDB community rating
    val addedAt: Long = System.currentTimeMillis(),

    // Tracking fields (added in DB version 7)
    val status: String = WatchlistStatus.PLAN_TO_WATCH.value,
    val userRating: Int? = null,        // 1–10, null = unrated
    val seasonProgress: Int? = null,    // TV only — current season
    val episodeProgress: Int? = null,   // TV only — current episode within season
    val notes: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
)
