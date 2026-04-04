package com.playbridge.sender.data.library

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun getAll(): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    suspend fun getAllSync(): List<WatchlistEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE tmdbId = :tmdbId)")
    fun isWatchlisted(tmdbId: Int): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WatchlistEntity)

    @Delete
    suspend fun delete(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE tmdbId = :tmdbId")
    suspend fun deleteById(tmdbId: Int)
}
