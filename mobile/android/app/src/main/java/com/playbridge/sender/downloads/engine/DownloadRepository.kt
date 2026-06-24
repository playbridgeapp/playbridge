package com.playbridge.sender.downloads.engine

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.playbridge.sender.data.downloads.DownloadDao
import com.playbridge.sender.data.downloads.DownloadEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Phase-1 public entry point for the new download engine. The UI (Phase 2) and any
 * debug trigger talk to this; it owns enqueue + lifecycle control and exposes durable
 * state from Room. The actual work runs in [DownloadWorker] under WorkManager.
 *
 * Pause/cancel are signalled to a possibly-running worker through [DownloadController]
 * marker files; resume just re-enqueues the unique work, and the strategy continues
 * from whatever is already on disk.
 */
class DownloadRepository(
    private val context: Context,
    private val dao: DownloadDao,
) {

    fun observe(): Flow<List<DownloadEntity>> = dao.observeAll()

    suspend fun enqueue(request: DownloadRequest) {
        dao.upsert(request.toEntity(DownloadStatus.QUEUED))
        scheduleWork(request.id)
    }

    /** Ask a running download to stop and keep its partial progress. */
    fun pause(id: String) {
        DownloadController(DownloadPaths.dirFor(context, id)).requestPause()
    }

    /** Re-run the work; the strategy resumes from existing segments/bytes on disk. */
    suspend fun resume(id: String) {
        DownloadController(DownloadPaths.dirFor(context, id)).start() // clears pause flag
        dao.updateStatus(id, DownloadStatus.QUEUED.name)
        scheduleWork(id)
    }

    /** Stop, drop the work, and (optionally) wipe partial files + the Room row. */
    suspend fun cancel(id: String, removeFiles: Boolean = true) {
        DownloadController(DownloadPaths.dirFor(context, id)).requestCancel()
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        if (removeFiles) {
            DownloadPaths.dirFor(context, id).deleteRecursively()
            dao.delete(id)
        }
    }

    private fun scheduleWork(id: String) {
        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        // KEEP: if a worker for this id is already running, don't double-schedule.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, work)
    }

    private fun workName(id: String) = "download:$id"

    companion object {
        const val TAG = "playbridge-download"
    }
}

/** Resolves per-download temp directories. One dir per id under app external files. */
object DownloadPaths {
    fun root(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")

    fun dirFor(context: Context, id: String): File =
        File(root(context), id).apply { if (!exists()) mkdirs() }
}

// --- Entity <-> request mapping (headers carried as JSON, isolated per id) ---

fun DownloadRequest.toEntity(status: DownloadStatus): DownloadEntity = DownloadEntity(
    id = id,
    url = url,
    title = title,
    kind = kind.name,
    status = status.name,
    mimeType = mimeType,
    headersJson = if (headers.isEmpty()) null else JSONObject(headers as Map<*, *>).toString(),
)

fun DownloadEntity.toRequest(): DownloadRequest = DownloadRequest(
    id = id,
    url = url,
    title = title,
    kind = runCatching { DownloadKind.valueOf(kind) }.getOrDefault(DownloadKind.FILE),
    mimeType = mimeType,
    headers = decodeHeaders(headersJson),
)

private fun decodeHeaders(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val obj = JSONObject(json)
        buildMap { obj.keys().forEach { k -> put(k, obj.getString(k)) } }
    }.getOrDefault(emptyMap())
}
