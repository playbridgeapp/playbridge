package com.playbridge.sender.downloads.engine

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.playbridge.sender.data.downloads.DownloadDao
import com.playbridge.sender.data.downloads.DownloadEntity
import com.playbridge.sender.downloads.notifications.DownloadNotifications
import com.playbridge.sender.notifications.NotificationPermissionRecovery
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
        NotificationPermissionRecovery.onNotificationNeeded()
        dao.upsert(request.toEntity(DownloadStatus.QUEUED))
        scheduleWork(request.id)
    }

    /** Ask a running download to stop and keep its partial progress. */
    fun pause(id: String) {
        DownloadController(DownloadPaths.dirFor(context, id)).requestPause()
    }

    /** Re-run the work; the strategy resumes from existing segments/bytes on disk. */
    suspend fun resume(id: String) {
        val status = dao.getById(id)?.status ?: return
        if (status != DownloadStatus.PAUSED.name && status != DownloadStatus.FAILED.name) return
        NotificationPermissionRecovery.onNotificationNeeded()
        DownloadNotifications(context).clearResult(id)
        DownloadController(DownloadPaths.dirFor(context, id)).start() // clears pause flag
        dao.updateStatus(id, DownloadStatus.QUEUED.name)
        scheduleWork(id, if (status == DownloadStatus.PAUSED.name) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE)
    }

    /** Stop, drop the work, and (optionally) wipe partial files + the Room row. */
    suspend fun cancel(id: String, removeFiles: Boolean = true) {
        DownloadController(DownloadPaths.dirFor(context, id)).requestCancel()
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        if (removeFiles) {
            DownloadPaths.dirFor(context, id).deleteRecursively()
            dao.delete(id)
        }
        DownloadNotifications(context).clear(id)
    }

    /** Drop a finished download from the list; the saved file is left alone. */
    suspend fun removeFromList(id: String) {
        DownloadPaths.dirFor(context, id).deleteRecursively()
        dao.delete(id)
        DownloadNotifications(context).clear(id)
    }

    /** Undo for [removeFromList]: put the row back exactly as it was. */
    suspend fun restore(entity: DownloadEntity) {
        dao.upsert(entity)
    }

    suspend fun rename(id: String, title: String) {
        dao.updateTitle(id, title)
    }

    private fun scheduleWork(id: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ID to id))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        // Initial enqueue uses KEEP. Resume queues behind a paused worker still unwinding
        // so an immediate notification tap cannot be silently dropped by KEEP. Failed
        // work is replaced: a still-unwinding failed parent must not fail its retry too.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(id), policy, work)
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
    sourcePageUrl = sourcePageUrl,
)

fun DownloadEntity.toRequest(): DownloadRequest = DownloadRequest(
    id = id,
    url = url,
    title = title,
    kind = runCatching { DownloadKind.valueOf(kind) }.getOrDefault(DownloadKind.FILE),
    mimeType = mimeType,
    headers = decodeHeaders(headersJson),
    sourcePageUrl = sourcePageUrl,
)

private fun decodeHeaders(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching {
        val obj = JSONObject(json)
        buildMap { obj.keys().forEach { k -> put(k, obj.getString(k)) } }
    }.getOrDefault(emptyMap())
}
