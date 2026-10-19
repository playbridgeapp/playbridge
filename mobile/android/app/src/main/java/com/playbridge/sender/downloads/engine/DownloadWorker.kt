package com.playbridge.sender.downloads.engine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.playbridge.sender.data.downloads.DownloadDao
import com.playbridge.sender.downloads.notifications.DownloadNotificationProgress
import com.playbridge.sender.downloads.notifications.DownloadNotifications
import com.playbridge.sender.downloads.notifications.downloadNotificationMode
import com.playbridge.sender.downloads.notifications.downloadNotificationProgress
import com.playbridge.sender.downloads.notifications.downloadNotificationTitle
import com.playbridge.sender.downloads.notifications.shouldUpdateDownloadNotification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs one download to completion under WorkManager. Picks a [DownloadStrategy] by kind,
 * streams its progress into Room (throttled by a 1s ticker), publishes the finished file
 * to Downloads, and maps pause/cancel to durable state.
 *
 * Deps come from Koin via [KoinComponent] (no custom WorkerFactory needed). The strategy
 * does the actual fetching; this class only orchestrates lifecycle + persistence.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val dao: DownloadDao by inject()
    private val strategies: DownloadStrategies by inject()

    private val notifications by lazy { DownloadNotifications(applicationContext) }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val entity = dao.getById(id) ?: return Result.failure()
        val request = entity.toRequest()

        val strategy = strategies.forKind(request.kind) ?: run {
            dao.markFailed(id, "Unsupported download type")
            postResultIfPresent(id, request.title, DownloadStatus.FAILED, "Unsupported download type")
            return Result.failure()
        }

        val dir = DownloadPaths.dirFor(applicationContext, id)
        val controller = DownloadController(dir)
        // WorkManager re-enqueues a worker stopped by an FGS timeout. Do not let that
        // replacement execution clear the pause marker and silently resume the download.
        if (shouldKeepDownloadPaused(entity.status, controller.isPauseRequested())) {
            if (entity.status != DownloadStatus.PAUSED.name) {
                dao.updateStatus(id, DownloadStatus.PAUSED.name)
            }
            postResultIfPresent(id, request.title, DownloadStatus.PAUSED)
            return Result.success()
        }
        controller.start()
        notifications.clearResult(id)

        val initialProgress = downloadNotificationProgress(Progress(entity.bytesDownloaded, entity.totalBytes))
        updateForeground(id, request.title, initialProgress)

        dao.updateStatus(id, DownloadStatus.RUNNING.name)

        return coroutineScope {
            val latest = AtomicReference(Progress(entity.bytesDownloaded, entity.totalBytes))
            var lastNotification = initialProgress
            var lastNotificationAt = SystemClock.elapsedRealtime()
            var finalizingNotification: Job? = null
            val ticker = launch {
                var lastWrittenBytes = entity.bytesDownloaded
                var lastWrittenPercent = -1
                while (isActive) {
                    val p = latest.get()
                    // Perf: Room write re-emits observeAll and recomposes DownloadsScreen —
                    // the 1s delay below already caps writes at 1Hz, so write on any
                    // movement. (A 64KB gate froze slow connections for seconds.)
                    val total = p.totalBytes
                    val percent = if (total > 0) ((p.downloadedBytes * 100) / total).toInt() else -1
                    val moved = p.downloadedBytes - lastWrittenBytes
                    if (moved > 0 || percent != lastWrittenPercent) {
                        dao.updateProgress(id, p.downloadedBytes, p.totalBytes, DownloadStatus.RUNNING.name)
                        lastWrittenBytes = p.downloadedBytes
                        lastWrittenPercent = percent
                    }
                    val visible = downloadNotificationProgress(p)
                    val now = SystemClock.elapsedRealtime()
                    if (shouldUpdateDownloadNotification(lastNotification, visible, lastNotificationAt, now)) {
                        updateForeground(id, request.title, visible)
                        lastNotification = visible
                        lastNotificationAt = now
                    }
                    delay(PROGRESS_INTERVAL_MS)
                }
            }
            // Android 15+ kills dataSync FGS after 6h. Pause first so WorkManager can stop
            // in time and the user can resume; otherwise the process crashes with
            // ForegroundServiceDidNotStopInTimeException.
            val budget = if (DownloadForegroundLimits.appliesTo(Build.VERSION.SDK_INT)) {
                launch {
                    delay(DownloadForegroundLimits.DATA_SYNC_BUDGET_MS)
                    Log.w(TAG, "dataSync FGS budget reached — pausing download $id")
                    controller.requestPause()
                }
            } else {
                null
            }

            try {
                val file = strategy.download(request, dir, controller) { latest.set(it) }
                ticker.cancelAndJoin()
                budget?.cancel()
                dao.updateStatus(id, DownloadStatus.MERGING.name) // published == "finalizing"
                val finalizing = DownloadNotificationProgress("Finalizing…", null, finalizing = true)
                if (lastNotification != finalizing) {
                    finalizingNotification = launch {
                        // Phase changes share the 1 Hz limit; fast publishes need no extra post.
                        delay((PROGRESS_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastNotificationAt)).coerceAtLeast(0))
                        updateForeground(id, request.title, finalizing)
                    }
                }
                val displayName = buildDisplayName(request, file)
                // For HLS/MPD the source mime is m3u8/mpd, but we produced an MP4 — publish
                // with the container's real mime, not the request's.
                val mime = if (request.kind == DownloadKind.FILE) {
                    request.mimeType ?: defaultMime(request)
                } else {
                    defaultMime(request)
                }
                val publishedUri = withContext(Dispatchers.IO) {
                    MediaStorePublisher.publish(applicationContext, file, displayName, mime)
                }
                finalizingNotification?.cancelAndJoin()
                dir.deleteRecursively() // temp segments no longer needed
                dao.markDone(id, publishedUri)
                val notificationName = if (downloadNotificationTitle(request.title) == "Download") "Download" else displayName
                postResultIfPresent(id, notificationName, DownloadStatus.DONE)
                Result.success()
            } catch (e: DownloadPausedException) {
                ticker.cancelAndJoin()
                dao.updateStatus(id, DownloadStatus.PAUSED.name)
                postResultIfPresent(id, request.title, DownloadStatus.PAUSED)
                Result.success() // paused is a clean stop, not a failure; resume re-enqueues
            } catch (e: CancellationException) {
                if (controller.isCancelRequested()) {
                    dir.deleteRecursively()
                    dao.delete(id)
                    notifications.clear(id)
                    Result.success()
                } else if (
                    Build.VERSION.SDK_INT >= 31 &&
                    stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT
                ) {
                    Log.w(TAG, "Download $id paused (foreground service timeout)")
                    pauseAfterForegroundTimeout(id, controller)
                    withContext(NonCancellable) { postResultIfPresent(id, request.title, DownloadStatus.PAUSED) }
                    Result.success()
                } else {
                    notifications.clear(id)
                    throw e // genuine coroutine/system cancellation — preserve structured concurrency
                }
            } catch (e: Throwable) {
                ticker.cancelAndJoin()
                Log.e(TAG, "Download failed (${e.javaClass.simpleName})")
                // Exception messages may contain authenticated URLs or response headers.
                val reason = if (e is java.io.IOException) "Could not download or save the file" else "Could not finish the download"
                dao.markFailed(id, reason)
                postResultIfPresent(id, request.title, DownloadStatus.FAILED, reason)
                Result.failure()
            } finally {
                ticker.cancel()
                budget?.cancel()
                finalizingNotification?.cancel()
            }
        }
    }

    private suspend fun pauseAfterForegroundTimeout(
        downloadId: String,
        controller: DownloadController,
    ) = withContext(NonCancellable) {
        // WorkManager normally re-enqueues work stopped by an FGS timeout. Cancel this exact
        // request so PAUSED remains durable; resume() will create a fresh request explicitly.
        controller.requestPause()
        runCatching {
            WorkManager.getInstance(applicationContext).cancelWorkById(id).await()
        }.onFailure { error ->
            Log.w(TAG, "Could not cancel timed-out work $downloadId: ${error.javaClass.simpleName}")
        }
        dao.updateStatus(downloadId, DownloadStatus.PAUSED.name)
    }

    private suspend fun updateForeground(id: String, title: String, progress: DownloadNotificationProgress) {
        try {
            setForeground(notifications.foregroundInfo(id, title, progress))
            notifications.refreshSummary()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "setForeground failed (${error.javaClass.simpleName})")
        }
    }

    private suspend fun postResultIfPresent(id: String, title: String, status: DownloadStatus, reason: String? = null) {
        // Cancel removes the row; never bring back a cancelled download's notification.
        if (dao.getById(id)?.status == status.name) {
            try {
                notifications.postResult(id, title, downloadNotificationMode(status), reason)
            } catch (error: Exception) {
                Log.w(TAG, "Result notification failed (${error.javaClass.simpleName})")
            }
        }
    }

    companion object {
        const val KEY_ID = "download_id"
        const val CHANNEL_ID = "download_engine_channel"
        const val TAG = "DownloadWorker"
        const val PROGRESS_INTERVAL_MS = 1000L

        private fun sanitize(name: String): String =
            name.trim()
                .replace(Regex("""[\\/:*?"<>|]"""), "")
                .replace(Regex("""\s+"""), " ")
                .take(120)
                .trimEnd('.')
                .ifEmpty { "download" }

        private val MEDIA_EXT = Regex(
            """\.(mp4|mkv|m4v|webm|avi|mov|ts|m2ts|flv|wmv|mp3|m4a|aac|flac|wav|ogg|opus)$""",
            RegexOption.IGNORE_CASE,
        )

        /** Final on-disk name: a clean title + a real extension, with no double-extension. */
        private fun buildDisplayName(request: DownloadRequest, file: java.io.File): String {
            val base = sanitize(request.title)
            // If the title already ends in a media extension (e.g. "Movie.mp4"), keep it as-is.
            if (MEDIA_EXT.containsMatchIn(base)) return base
            return base + extensionFor(request, file)
        }

        private fun extensionFor(request: DownloadRequest, file: java.io.File): String {
            val fromFile = file.extension
            if (fromFile.isNotBlank() && fromFile.length <= 5) return ".$fromFile"
            return when (request.kind) {
                DownloadKind.HLS, DownloadKind.MPD -> if (request.audioOnly) ".mp3" else ".mp4"
                DownloadKind.FILE -> mimeExtension(request.mimeType)
            }
        }

        /** Derive an extension from a mime type, falling back to a sensible default per family. */
        private fun mimeExtension(mime: String?): String {
            if (mime.isNullOrBlank()) return ""
            val clean = mime.substringBefore(';').trim()
            MimeTypeMap.getSingleton().getExtensionFromMimeType(clean)
                ?.takeIf { it.isNotBlank() }
                ?.let { return ".$it" }
            return when {
                clean.startsWith("video/") -> ".mp4"
                clean.startsWith("audio/") -> ".mp3"
                else -> ""
            }
        }

        private fun defaultMime(request: DownloadRequest): String = when (request.kind) {
            DownloadKind.HLS, DownloadKind.MPD -> if (request.audioOnly) "audio/mpeg" else "video/mp4"
            DownloadKind.FILE -> "application/octet-stream"
        }
    }
}

/** DI holder so the worker can resolve a strategy by kind without knowing the impls. */
class DownloadStrategies(private val strategies: List<DownloadStrategy>) {
    fun forKind(kind: DownloadKind): DownloadStrategy? = strategies.firstOrNull { it.kind == kind }
}
