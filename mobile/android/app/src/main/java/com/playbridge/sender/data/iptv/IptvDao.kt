package com.playbridge.sender.data.iptv

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface IptvPlaylistDao {
    @Query("SELECT * FROM iptv_playlists ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<IptvPlaylistEntity>>

    @Query("SELECT * FROM iptv_playlists WHERE id = :id")
    suspend fun getById(id: Long): IptvPlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: IptvPlaylistEntity): Long

    @Update
    suspend fun update(item: IptvPlaylistEntity)

    @Delete
    suspend fun delete(item: IptvPlaylistEntity)

    @Query("DELETE FROM iptv_playlists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE iptv_playlists SET updatedAt = :updatedAt, channelCount = :count WHERE id = :id")
    suspend fun markRefreshed(id: Long, updatedAt: Long, count: Int)
}

@Dao
interface IptvChannelDao {
    @Query("SELECT * FROM iptv_channels WHERE playlistId = :playlistId ORDER BY orderIndex ASC")
    fun observeForPlaylist(playlistId: Long): Flow<List<IptvChannelEntity>>

    @Query("SELECT * FROM iptv_channels WHERE playlistId = :playlistId ORDER BY orderIndex ASC")
    suspend fun getForPlaylist(playlistId: Long): List<IptvChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<IptvChannelEntity>)

    @Query("DELETE FROM iptv_channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query(
        "UPDATE iptv_channels SET probeStatus = :status, probeLatencyMs = :latencyMs, " +
            "probedAt = :probedAt WHERE id = :id",
    )
    suspend fun updateProbe(id: Long, status: String, latencyMs: Int?, probedAt: Long)

    /** Atomically replace the cached channels for a playlist. */
    @Transaction
    suspend fun replaceForPlaylist(playlistId: Long, items: List<IptvChannelEntity>) {
        deleteForPlaylist(playlistId)
        insertAll(items)
    }
}
