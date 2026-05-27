package com.playbridge.sender.cast

import com.playbridge.sender.downloads.DownloadManagerSingleton
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import com.playbridge.sender.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service that exports a completed HLS download from SimpleCache to
 * the public Downloads folder. Runs as foreground to keep the process alive
 * during the (potentially multi-second) MediaStore copy in Phase 2.
 */
@UnstableApi
class HlsExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: run {
            Log.e(TAG, "No URL in intent, stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "HLS Video"

        Log.d(TAG, "Export service started for: $title")
        val progressNotif = buildProgressNotification(title)
        // Must pass foregroundServiceType on API 29+ to match the manifest declaration.
        // Without this the call is a no-op on API 29-33 and throws on API 34+,
        // meaning the service is NOT actually foreground and Android kills it immediately.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, progressNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, progressNotif)
        }
        Log.d(TAG, "startForeground called — process is now foreground-protected")

        scope.launch {
            try {
                val cache = DownloadManagerSingleton.getDownloadCache(applicationContext)
                Log.d(TAG, "Starting HlsExporter for: $title")
                val path = HlsExporter.export(
                    context = applicationContext,
                    hlsUrl = url,
                    title = title,
                    cache = cache
                )
                if (path != null) {
                    Log.d(TAG, "Export SUCCESS: $path")
                    HlsExportRegistry.markDone(url, path)
                    showResultNotification(applicationContext, title, success = true)
                } else {
                    Log.e(TAG, "Export FAILED (returned null)")
                    HlsExportRegistry.markFailed(url)
                    showResultNotification(applicationContext, title, success = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Export CRASHED: ${e.message}", e)
                HlsExportRegistry.markFailed(url)
                showResultNotification(applicationContext, title, success = false)
            } finally {
                Log.d(TAG, "Export service stopping")
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildProgressNotification(title: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Saving to Downloads…")
            .setContentText(title)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun showResultNotification(context: Context, title: String, success: Boolean) {
        ensureChannel()
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (success) "Saved to Downloads" else "Export failed")
            .setContentText(title)
            .setAutoCancel(true)
            .build()
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(title.hashCode(), notif)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Export", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        const val TAG = "HlsExport"
        const val CHANNEL_ID = "export_channel"
        const val NOTIF_ID = 2
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"

        fun start(context: Context, url: String, title: String) {
            val intent = Intent(context, HlsExportService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
