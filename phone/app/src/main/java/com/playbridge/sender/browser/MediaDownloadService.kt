package com.playbridge.sender.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.playbridge.sender.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@UnstableApi
class MediaDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description
) {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Listener attached to DownloadManager — this is the correct hook for completion events.
    // DownloadService itself does not expose onDownloadChanged as an overridable method.
    private val downloadListener = object : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?
        ) {
            Log.d(TAG, "onDownloadChanged: state=${download.state} url=${download.request.uri}")

            if (download.state != Download.STATE_COMPLETED) return

            val url = download.request.uri.toString()
            val title = String(download.request.data).ifEmpty { "HLS Video" }
            Log.d(TAG, "Download COMPLETED: title=$title url=$url")

            // Guard: skip if already exported or in progress
            val existing = HlsExportRegistry.get(url)
            if (existing?.state == HlsExportRegistry.ExportState.DONE ||
                existing?.state == HlsExportRegistry.ExportState.EXPORTING) {
                Log.d(TAG, "Skipping export — already ${existing.state}")
                return
            }

            HlsExportRegistry.markExporting(url)
            Log.d(TAG, "Starting export for: $title")

            // Capture context before service is destroyed
            val appContext = applicationContext
            serviceScope.launch {
                try {
                    val cache = DownloadManagerSingleton.getDownloadCache(appContext)
                    Log.d(TAG, "Cache retrieved, starting HlsExporter")
                    val path = HlsExporter.export(
                        context = appContext,
                        hlsUrl = url,
                        title = title,
                        cache = cache
                    )
                    if (path != null) {
                        Log.d(TAG, "Export SUCCESS: $path")
                        HlsExportRegistry.markDone(url, path)
                        showExportNotification(appContext, title, success = true)
                    } else {
                        Log.e(TAG, "Export FAILED (returned null) for: $url")
                        HlsExportRegistry.markFailed(url)
                        showExportNotification(appContext, title, success = false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Export CRASHED for: $url — ${e.message}", e)
                    HlsExportRegistry.markFailed(url)
                    showExportNotification(appContext, title, success = false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created — attaching download listener")
        DownloadManagerSingleton.getDownloadManager(this).addListener(downloadListener)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed — removing download listener")
        DownloadManagerSingleton.getDownloadManager(this).removeListener(downloadListener)
        super.onDestroy()
    }

    override fun getDownloadManager(): DownloadManager {
        return DownloadManagerSingleton.getDownloadManager(this)
    }

    override fun getScheduler(): Scheduler? {
        return null
    }

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        return DownloadNotificationHelper(this, CHANNEL_ID)
            .buildProgressNotification(
                this,
                R.drawable.ic_launcher_foreground,
                null,
                null,
                downloads,
                notMetRequirements
            )
    }

    private fun showExportNotification(context: Context, title: String, success: Boolean) {
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                EXPORT_CHANNEL_ID,
                "Export notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notifManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, EXPORT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (success) "Saved to Downloads" else "Export failed")
            .setContentText(title)
            .setAutoCancel(true)
            .build()

        notifManager.notify(title.hashCode(), notification)
        Log.d(TAG, "Notification posted: ${if (success) "success" else "failed"} for $title")
    }

    companion object {
        const val CHANNEL_ID = "download_channel"
        const val EXPORT_CHANNEL_ID = "export_channel"
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val JOB_ID = 1
        const val TAG = "HlsExport"
    }
}
