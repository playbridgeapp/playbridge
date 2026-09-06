package com.playbridge.sender.downloads.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.playbridge.sender.R
import com.playbridge.sender.data.downloads.DownloadDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
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

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val entity = dao.getById(id) ?: return Result.failure()
        val request = entity.toRequest()

        val strategy = strategies.forKind(request.kind) ?: run {
            dao.markFailed(id, "No strategy registered for ${request.kind}")
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
            return Result.success()
        }
        controller.start()

        runCatching { setForeground(foregroundInfo(request.title)) }
            .onFailure { Log.w(TAG, "setForeground failed (continuing): ${it.message}") }

        dao.updateStatus(id, DownloadStatus.RUNNING.name)

        return coroutineScope {
            val latest = AtomicReference(Progress(entity.bytesDownloaded, entity.totalBytes))
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
                ticker.cancel()
                budget?.cancel()

                dao.updateStatus(id, DownloadStatus.MERGING.name) // published == "finalizing"
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
                dir.deleteRecursively() // temp segments no longer needed
                dao.markDone(id, publishedUri)
                Result.success()
            } catch (e: DownloadPausedException) {
                dao.updateStatus(id, DownloadStatus.PAUSED.name)
                Result.success() // paused is a clean stop, not a failure; resume re-enqueues
            } catch (e: CancellationException) {
                if (controller.isCancelRequested()) {
                    dir.deleteRecursively()
                    dao.delete(id)
                    Result.success()
                } else if (
                    Build.VERSION.SDK_INT >= 31 &&
                    stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT
                ) {
                    Log.w(TAG, "Download $id paused (foreground service timeout)")
                    pauseAfterForegroundTimeout(id, controller)
                    Result.success()
                } else {
                    throw e // genuine coroutine/system cancellation — preserve structured concurrency
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Download $id failed", e)
                dao.markFailed(id, e.message ?: e.javaClass.simpleName)
                Result.failure()
            } finally {
                ticker.cancel()
                budget?.cancel()
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
            Log.w(TAG, "Could not cancel timed-out work $downloadId: ${error.message}")
        }
        dao.updateStatus(downloadId, DownloadStatus.PAUSED.name)
    }

    // --- foreground notification ---

    private fun foregroundInfo(title: String): ForegroundInfo {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val KEY_ID = "download_id"
        const val CHANNEL_ID = "download_engine_channel"
        const val NOTIF_ID = 4411
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
