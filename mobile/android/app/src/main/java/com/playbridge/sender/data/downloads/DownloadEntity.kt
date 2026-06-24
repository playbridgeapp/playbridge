package com.playbridge.sender.data.downloads

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Durable state for one download (Phase-1 engine). Replaces the in-memory
 * `HlsExportRegistry` and the dual-cursor polling in the legacy `DownloadsScreen`:
 * the worker writes progress here, the UI observes [DownloadDao.observeAll]. Because
 * it's persisted, "Open in player" survives a cold start.
 *
 * `status` / `kind` are the enum names from the engine package (stored as TEXT).
 * `headersJson` holds the per-download request headers (cookie/UA/referer) so they're
 * isolated per id rather than shared in a global host map.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val kind: String,
    val status: String,
    val mimeType: String? = null,
    val headersJson: String? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val filePath: String? = null,
    val errorReason: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadEntity)

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: String): DownloadEntity?

    @Query("UPDATE downloads SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE downloads SET bytesDownloaded = :bytes, totalBytes = :total, " +
            "status = :status, updatedAt = :now WHERE id = :id",
    )
    suspend fun updateProgress(
        id: String,
        bytes: Long,
        total: Long,
        status: String,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE downloads SET status = :status, filePath = :path, updatedAt = :now WHERE id = :id")
    suspend fun markDone(
        id: String,
        path: String,
        status: String = "DONE",
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE downloads SET status = 'FAILED', errorReason = :reason, updatedAt = :now WHERE id = :id")
    suspend fun markFailed(id: String, reason: String?, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)
}
