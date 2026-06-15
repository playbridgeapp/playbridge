package com.playbridge.sender.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.playbridge.sender.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Foreground service that keeps the process alive while a cast session is active, so the
 * WebSocket session (episode queue top-ups, remote control) and the DLNA local proxy
 * survive screen-off and app backgrounding. Replaces the DLNA-only DlnaProxyService and
 * extends the same guarantee to native (WebSocket) sessions.
 *
 * Lifecycle is driven entirely by [CastSessionManager.hasActiveSession]; the notification
 * mirrors [CastSessionManager.sessionInfo] and offers a Stop action that ends the session.
 */
class CastSessionService : Service(), KoinComponent {

    private val manager: CastSessionManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Keep WiFi responsive and the CPU available for WS pings / queue resolution while
        // the screen is off. Both are released in onDestroy, so they live exactly as long
        // as the session.
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") // FULL_LOW_LATENCY is only effective while the screen is on
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "playbridge:cast").apply {
            setReferenceCounted(false)
            acquire()
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "playbridge:cast").apply {
            setReferenceCounted(false)
            acquire()
        }
        // Live notification updates (device name + now-playing title).
        scope.launch {
            manager.sessionInfo.collect { info ->
                if (foregroundStarted) {
                    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    mgr.notify(NOTIF_ID, buildNotification(info))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            manager.endSession()
            stopSelf()
            return START_NOT_STICKY
        }
        val notif = buildNotification(manager.sessionInfo.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        foregroundStarted = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        wifiLock = null
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(info: CastSessionManager.SessionInfo): Notification {
        val stopIntent = Intent(this, CastSessionService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentPi = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Casting to ${info.deviceName}")
            .setContentText(info.title ?: "Session active")
            .setContentIntent(contentPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "cast_session_channel"
        private const val NOTIF_ID = 4712
        private const val ACTION_STOP = "com.playbridge.sender.cast.action.STOP_SESSION"

        fun start(context: Context) {
            val intent = Intent(context, CastSessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastSessionService::class.java))
        }
    }
}
