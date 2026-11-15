package com.playbridge.sender.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CastHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CastHistoryEntity)

    @Query("SELECT * FROM cast_history WHERE id = :id")
    suspend fun get(id: String): CastHistoryEntity?

    @Query("SELECT * FROM cast_history ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<CastHistoryEntity>>

    @Query("SELECT * FROM cast_history ORDER BY startedAtMs DESC")
    suspend fun all(): List<CastHistoryEntity>

    @Query(
        "UPDATE cast_history SET positionMs = :positionMs, durationMs = :durationMs, " +
            "positionReported = :positionReported, finished = :finished, live = :live, " +
            "liveWatchedMs = :liveWatchedMs, failed = :failed, failureReason = :failureReason, " +
            "playlistIndex = :playlistIndex, playlistCount = :playlistCount WHERE id = :id",
    )
    suspend fun updateProgress(
        id: String,
        positionMs: Long,
        durationMs: Long,
        positionReported: Boolean,
        finished: Boolean,
        live: Boolean,
        liveWatchedMs: Long,
        failed: Boolean,
        failureReason: String?,
        playlistIndex: Int?,
        playlistCount: Int?,
    )

    @Query("UPDATE cast_history SET previewPath = :previewPath WHERE id = :id")
    suspend fun setPreview(id: String, previewPath: String?)

    @Query("UPDATE cast_history SET linkCheck = :result, linkCheckedAtMs = :at WHERE id = :id")
    suspend fun setLinkCheck(id: String, result: String?, at: Long?)

    @Query("UPDATE cast_history SET expiresAtMs = :expiresAtMs, linkCheck = NULL, linkCheckedAtMs = NULL WHERE id = :id")
    suspend fun resetLink(id: String, expiresAtMs: Long?)

    @Query("UPDATE cast_history SET previewPath = NULL")
    suspend fun clearPreviewPaths()

    @Query("DELETE FROM cast_history WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM cast_history")
    suspend fun clear()
}
