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
import kotlinx.coroutines.flow.combine
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
    private var currentlyPlaying = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Keep WiFi responsive for WS pings / proxy traffic the whole time we're linked.
        // It's cheap relative to the CPU wake lock, which we only hold while actually
        // playing/proxying (see acquireWake/releaseWake). Released in onDestroy.
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION") // FULL_LOW_LATENCY is only effective while the screen is on
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "playbridge:cast").apply {
            setReferenceCounted(false)
            acquire()
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // Created but NOT acquired here — only held while actively playing/proxying.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "playbridge:cast").apply {
            setReferenceCounted(false)
        }
        // Drive the notification, FGS type, and wake lock from session info + play/idle state.
        scope.launch {
            combine(manager.sessionInfo, manager.isActivelyPlaying) { info, playing -> info to playing }
                .collect { (info, playing) ->
                    if (!foregroundStarted) return@collect
                    if (playing != currentlyPlaying) {
                        currentlyPlaying = playing
                        if (playing) acquireWake() else releaseWake()
                        // Changing the FGS type requires another startForeground() call.
                        startForegroundWithType(info, playing)
                    } else {
                        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        mgr.notify(NOTIF_ID, buildNotification(info, playing))
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
        val playing = manager.isActivelyPlaying.value
        currentlyPlaying = playing
        startForegroundWithType(manager.sessionInfo.value, playing)
        foregroundStarted = true
        if (playing) acquireWake()
        return START_STICKY
    }

    /**
     * (Re)enter the foreground with the FGS type that matches the current state:
     * mediaPlayback while playing/proxying, connectedDevice while idle-but-linked. The
     * connectedDevice type keeps us off the "media-playback FGS with nothing playing"
     * Play Store policy edge.
     */
    private fun startForegroundWithType(info: CastSessionManager.SessionInfo, playing: Boolean) {
        val notif = buildNotification(info, playing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (playing) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        wakeLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
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

    private fun buildNotification(info: CastSessionManager.SessionInfo, playing: Boolean): Notification {
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
        val title = if (playing) "Casting to ${info.deviceName}" else "Connected to ${info.deviceName}"
        val text = if (playing) (info.title ?: "Playing") else "Ready to cast"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "cast_session_channel"
        private const val NOTIF_ID = 4712
        private const val ACTION_STOP = "com.playbridge.sender.cast.action.STOP_SESSION"

        fun start(context: Context) {
            val intent = Intent(context, CastSessionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastSessionService::class.java))
        }
    }
}
