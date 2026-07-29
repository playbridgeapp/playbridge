package com.playbridge.sender.cast.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.playbridge.sender.R
import com.playbridge.sender.util.ProcessUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

/**
 * Keeps the process alive while the phone hosts the browser receiver page
 * (LAN HTTP + WS on 8770–8779). Notification offers a Stop action.
 */
class BrowserReceiverHostService : Service(), KoinComponent {
    private val repository: BrowserReceiverRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    repository.stopHost()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        val port = intent?.getIntExtra(EXTRA_PORT, 0) ?: 0
        val notification = buildNotification(url, port)
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            // Still attempt to keep the host without a promoted notification.
            runCatching {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_ID, notification)
            }
        }
        // START_STICKY may recreate this service with a null intent after the
        // process was reclaimed. Recreate the native browser listener as well;
        // otherwise the notification returns but the advertised URL stays dead
        // until the user manually toggles hosting off and on.
        if (intent == null && !repository.state.value.running) {
            scope.launch {
                repository.startHost(preferredPort = DEFAULT_PORT)
                    .onFailure { error ->
                        Log.w(TAG, "sticky browser host restore failed: ${error.message}")
                        stopSelf(startId)
                    }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        }
        super.onDestroy()
    }

    private fun buildNotification(url: String?, port: Int): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, BrowserReceiverHostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            !url.isNullOrBlank() -> url
            port > 0 -> "Port $port"
            else -> "Waiting for browsers on your Wi‑Fi"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Browser receiver on")
            .setContentText(text)
            .setContentIntent(openApp)
            .addAction(0, "Stop host", stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Browser receiver",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while this phone hosts a TV browser receiver page"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val TAG = "BrowserReceiverHostSvc"
        private const val CHANNEL_ID = "browser_receiver_host"
        private const val NOTIF_ID = 4720
        private const val ACTION_STOP = "com.playbridge.sender.cast.browser.STOP_HOST"
        private const val EXTRA_URL = "url"
        private const val EXTRA_PORT = "port"
        private const val DEFAULT_PORT = 8770

        fun start(context: Context, primaryUrl: String?, port: Int) {
            if (!ProcessUtil.isMainProcess(context)) return
            val intent = Intent(context, BrowserReceiverHostService::class.java).apply {
                putExtra(EXTRA_URL, primaryUrl)
                putExtra(EXTRA_PORT, port)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            if (!ProcessUtil.isMainProcess(context)) return
            context.stopService(Intent(context, BrowserReceiverHostService::class.java))
        }
    }
}
