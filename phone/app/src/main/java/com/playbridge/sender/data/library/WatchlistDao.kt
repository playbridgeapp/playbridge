package com.playbridge.sender.data.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun getAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    suspend fun getAllSync(): List<WatchlistEntity>

    @Query("SELECT * FROM watchlist WHERE tmdbId = :tmdbId")
    fun getById(tmdbId: Int): Flow<WatchlistEntity?>

    /** Single-shot suspend read — use inside Mutex-locked blocks to avoid stale StateFlow reads. */
    @Query("SELECT * FROM watchlist WHERE tmdbId = :tmdbId LIMIT 1")
    suspend fun getByIdSync(tmdbId: Int): WatchlistEntity?

    @Query("SELECT * FROM watchlist WHERE status = :status ORDER BY addedAt DESC")
    fun getByStatus(status: String): Flow<List<WatchlistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE tmdbId = :tmdbId)")
    fun isWatchlisted(tmdbId: Int): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WatchlistEntity)

    @Delete
    suspend fun delete(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE tmdbId = :tmdbId")
    suspend fun deleteById(tmdbId: Int)

    @Query("""
        UPDATE watchlist
        SET status = :status, startedAt = :startedAt, completedAt = :completedAt
        WHERE tmdbId = :tmdbId
    """)
    suspend fun updateStatus(tmdbId: Int, status: String, startedAt: Long?, completedAt: Long?)

    @Query("UPDATE watchlist SET seasonProgress = :season, episodeProgress = :episode WHERE tmdbId = :tmdbId")
    suspend fun updateProgress(tmdbId: Int, season: Int?, episode: Int?)

    @Query("UPDATE watchlist SET userRating = :rating WHERE tmdbId = :tmdbId")
    suspend fun updateRating(tmdbId: Int, rating: Int?)

    @Query("UPDATE watchlist SET notes = :notes WHERE tmdbId = :tmdbId")
    suspend fun updateNotes(tmdbId: Int, notes: String?)
}
