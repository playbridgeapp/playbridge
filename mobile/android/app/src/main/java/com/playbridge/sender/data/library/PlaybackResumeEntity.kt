package com.playbridge.sender.data.library

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Cross-session resume position, keyed by *content* (not URL — debrid/addon URLs
 * expire between sessions). Written by PlaybackProgressTracker (throttled), deleted
 * once the item crosses the watched threshold so resume never offers finished content.
 *
 * Keys: `"tmdb:<id>"` for movies, `"tmdb:<id>:<season>:<episode>"` for episodes.
 */
@Entity(tableName = "playback_resume")
data class PlaybackResumeEntity(
    @PrimaryKey val contentKey: String,
    val tmdbId: Int,
    val mediaType: String,          // "movie" | "tv"
    val season: Int? = null,
    val episode: Int? = null,
    val title: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface PlaybackResumeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PlaybackResumeEntity)

    @Query("SELECT * FROM playback_resume WHERE contentKey = :key")
    suspend fun getByKey(key: String): PlaybackResumeEntity?

    /** All resume points for one show/movie, newest first (drives the detail screen). */
    @Query("SELECT * FROM playback_resume WHERE tmdbId = :tmdbId ORDER BY updatedAt DESC")
    fun observeForTmdb(tmdbId: Int): Flow<List<PlaybackResumeEntity>>

    /** Most recent resume points overall (drives Continue Watching annotations). */
    @Query("SELECT * FROM playback_resume ORDER BY updatedAt DESC LIMIT :limit")
    fun observeLatest(limit: Int): Flow<List<PlaybackResumeEntity>>

    @Query("DELETE FROM playback_resume WHERE contentKey = :key")
    suspend fun deleteByKey(key: String)

    /** Drop resume points implied-watched by a skip-ahead (everything ≤ the new pointer). */
    @Query(
        "DELETE FROM playback_resume WHERE tmdbId = :tmdbId AND mediaType = 'tv' AND " +
            "(season < :season OR (season = :season AND episode <= :episode))",
    )
    suspend fun deleteEpisodesUpTo(tmdbId: Int, season: Int, episode: Int)

    @Query("DELETE FROM playback_resume WHERE updatedAt < :olderThan")
    suspend fun prune(olderThan: Long)
}
