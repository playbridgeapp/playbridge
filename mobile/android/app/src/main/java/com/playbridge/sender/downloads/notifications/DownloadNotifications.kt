package com.playbridge.sender.downloads.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.playbridge.sender.R
import com.playbridge.sender.browser.BrowserActivity
import com.playbridge.sender.downloads.engine.DownloadWorker

/** Rendering only; repository/worker remain the owners of durable download state. */
internal class DownloadNotifications(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val reconcileSummary = Runnable { updateSummary() }
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun foregroundInfo(id: String, title: String, progress: DownloadNotificationProgress): ForegroundInfo {
        val notification = builder(id, downloadNotificationTitle(title), progress.text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, progress.percent ?: 0, progress.percent == null)
            .addAction(action(id, DownloadNotificationAction.PAUSE))
            .addAction(action(id, DownloadNotificationAction.CANCEL))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(downloadNotificationId(id), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(downloadNotificationId(id), notification)
        }
    }

    @SuppressLint("MissingPermission") // App-wide recovery owns prompting; notify is silent when denied.
    fun postResult(id: String, title: String, mode: DownloadNotificationMode, reason: String? = null) = synchronized(lock) {
        val safeTitle = downloadNotificationTitle(title)
        val notification = when (mode) {
            DownloadNotificationMode.PAUSED -> builder(id, safeTitle, "Paused")
                .addAction(action(id, DownloadNotificationAction.RESUME))
                .addAction(action(id, DownloadNotificationAction.CANCEL))
            DownloadNotificationMode.DONE -> builder(id, "Download complete", safeTitle)
                .setContentIntent(contentIntent(id, openFile = true))
            DownloadNotificationMode.FAILED -> builder(id, "Download failed", reason ?: "Could not finish the download")
                .setSubText(safeTitle)
                .addAction(action(id, DownloadNotificationAction.RETRY))
            else -> return@synchronized
        }.setAutoCancel(true).setOngoing(false).build()
        manager.notify(downloadNotificationId(id, result = true), notification)
        refreshSummary()
    }

    fun clear(id: String) = synchronized(lock) {
        manager.cancel(downloadNotificationId(id))
        manager.cancel(downloadNotificationId(id, result = true))
        refreshSummary(excludingId = id)
    }

    fun clearResult(id: String) = synchronized(lock) {
        manager.cancel(downloadNotificationId(id, result = true))
        refreshSummary()
    }

    fun refreshSummary(excludingId: String? = null) {
        updateSummary(excludingId)
        // Framework notification/service posts are queued: reconcile after they reach
        // activeNotifications too, including quick results with no later progress tick.
        main.removeCallbacks(reconcileSummary)
        main.postDelayed(reconcileSummary, 250L)
        main.postDelayed(reconcileSummary, 1_000L)
    }

    @SuppressLint("MissingPermission")
    private fun updateSummary(excludingId: String? = null) = synchronized(lock) {
        val active = manager.activeNotifications
        val ids = active.filter { it.notification.group == GROUP && it.id != DOWNLOAD_GROUP_SUMMARY_ID }
            .mapNotNull { it.notification.extras.getString(EXTRA_DOWNLOAD_ID) }
        val count = downloadNotificationGroupCount(ids, excludingId)
        if (count < 2) {
            manager.cancel(DOWNLOAD_GROUP_SUMMARY_ID)
        } else {
            val title = "$count downloads"
            if (active.any { it.id == DOWNLOAD_GROUP_SUMMARY_ID &&
                    it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString() == title }) return@synchronized
            ensureChannel()
            manager.notify(DOWNLOAD_GROUP_SUMMARY_ID, NotificationCompat.Builder(context, DownloadWorker.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentIntent(contentIntent("summary"))
                .setOnlyAlertOnce(true)
                .setGroup(GROUP).setGroupSummary(true)
                .build())
        }
    }

    private fun builder(id: String, title: String, text: String): NotificationCompat.Builder {
        ensureChannel()
        return NotificationCompat.Builder(context, DownloadWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(contentIntent(id))
            .setOnlyAlertOnce(true)
            .setGroup(GROUP)
            .addExtras(Bundle().apply { putString(EXTRA_DOWNLOAD_ID, id) })
            .setDeleteIntent(actionIntent(id, DownloadNotificationAction.DISMISS))
    }

    private fun contentIntent(id: String, openFile: Boolean = false): PendingIntent {
        val action = if (openFile) "open-file" else "open-downloads"
        val intent = Intent(context, BrowserActivity::class.java).apply {
            this.action = BrowserActivity.ACTION_OPEN_DOWNLOADS
            data = identityUri(id, action)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (id != "summary") putExtra(EXTRA_DOWNLOAD_ID, id)
            putExtra(BrowserActivity.EXTRA_OPEN_DOWNLOAD_FILE, openFile)
        }
        return PendingIntent.getActivity(context, downloadActionRequestCode(id, action), intent, FLAGS)
    }

    private fun action(id: String, action: DownloadNotificationAction) = NotificationCompat.Action.Builder(
        when (action) {
            DownloadNotificationAction.PAUSE -> android.R.drawable.ic_media_pause
            DownloadNotificationAction.RESUME, DownloadNotificationAction.RETRY -> android.R.drawable.ic_media_play
            else -> android.R.drawable.ic_menu_close_clear_cancel
        }, action.label, actionIntent(id, action),
    ).build()

    private fun actionIntent(id: String, action: DownloadNotificationAction): PendingIntent {
        val intent = Intent(context, DownloadNotificationReceiver::class.java).apply {
            this.action = action.name
            data = identityUri(id, action.name)
            putExtra(EXTRA_DOWNLOAD_ID, id)
        }
        return PendingIntent.getBroadcast(context, downloadActionRequestCode(id, action.name), intent, FLAGS)
    }

    private fun ensureChannel() {
        manager.createNotificationChannel(NotificationChannel(
            DownloadWorker.CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW,
        ))
    }

    private fun identityUri(id: String, action: String): Uri = Uri.Builder()
        .scheme("playbridge-download").authority("notification").appendPath(id).appendPath(action).build()

    companion object {
        const val EXTRA_DOWNLOAD_ID = "notification_download_id"
        private const val GROUP = "playbridge.downloads"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private val lock = Any()
    }
}

internal enum class DownloadNotificationAction(val label: String) {
    PAUSE("Pause"), CANCEL("Cancel"), RESUME("Resume"), RETRY("Retry"), DISMISS("Dismiss"),
}
