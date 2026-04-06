package com.playbridge.sender.browser

import android.app.Notification
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.playbridge.sender.R

@UnstableApi
class MediaDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description
) {

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

            // Mark state before starting the service so the UI shows the spinner immediately
            HlsExportRegistry.markExporting(url)
            Log.d(TAG, "Starting HlsExportService for: $title")

            // Delegate to a dedicated foreground service so the process stays alive
            // for the full export + MediaStore copy even after DownloadService stops.
            HlsExportService.start(applicationContext, url, title)
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

    companion object {
        const val CHANNEL_ID = "download_channel"
        const val FOREGROUND_NOTIFICATION_ID = 1
        const val JOB_ID = 1
        const val TAG = "HlsExport"
    }
}
