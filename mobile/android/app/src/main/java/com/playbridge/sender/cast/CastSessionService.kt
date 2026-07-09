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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.playbridge.sender.R
import com.playbridge.sender.util.ProcessUtil
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
 * mirrors [CastSessionManager.sessionInfo] and exposes a state-dependent action:
 *  - **Casting** → **Stop** ([CastSessionManager.endCastSession]) — ends playback; link may remain
 *  - **Connected** → **Disconnect** ([CastSessionManager.disconnectSession]) — drops the link
 *
 * Notification persistence (Android 13+): FGS notifications are user-dismissible by default;
 * [setOngoing] alone is insufficient on many Android 14+ OEMs. We always update via
 * [startForeground] (never a bare [NotificationManager.notify]), set ongoing + no-clear
 * flags, and re-promote on [ACTION_NOTIFICATION_DISMISSED] while the session is still live.
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
        // Always go through startForegroundWithType — bare notify() can demote the notif from
        // the FGS association on some OEMs and make it swipe-dismissible.
        scope.launch {
            combine(manager.sessionInfo, manager.isActivelyPlaying) { info, playing -> info to playing }
                .collect { (info, playing) ->
                    if (!foregroundStarted) return@collect
                    if (playing != currentlyPlaying) {
                        currentlyPlaying = playing
                        if (playing) acquireWake() else releaseWake()
                    }
                    startForegroundWithType(info, playing)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CAST -> {
                // End playback only. If the native link stays up, hasActiveSession remains
                // true and the notif morphs to "Connected" + Disconnect — do not stopSelf.
                manager.endCastSession()
                if (!manager.hasActiveSession.value) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val playing = manager.isActivelyPlaying.value
                currentlyPlaying = playing
                startForegroundWithType(manager.sessionInfo.value, playing)
                if (!playing) releaseWake()
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                manager.disconnectSession()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NOTIFICATION_DISMISSED -> {
                // Android 13+ lets users swipe FGS notifications. If the session is still
                // live, re-enter foreground so the notif returns; do not end the cast/link.
                if (manager.hasActiveSession.value) {
                    Log.i(TAG, "Cast notification dismissed while session active — re-showing")
                    val playing = manager.isActivelyPlaying.value
                    currentlyPlaying = playing
                    startForegroundWithType(manager.sessionInfo.value, playing)
                    foregroundStarted = true
                    if (playing) acquireWake()
                } else {
                    stopSelf()
                }
                return START_STICKY
            }
        }
        val playing = manager.isActivelyPlaying.value
        currentlyPlaying = playing
        startForegroundWithType(manager.sessionInfo.value, playing)
        foregroundStarted = true
        if (playing) acquireWake()
        return START_STICKY
    }

    /**
     * (Re)enter the foreground as a **connectedDevice** session.
     *
     * We previously used [ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK] whenever the
     * TV reported context "player". That is wrong: the phone is a cast remote / WS client,
     * not a media player — there is no MediaSession. On Android 14+ the system stops
     * mediaPlayback FGSes without a session after a short time, which made the Casting
     * notification vanish a few seconds after cold-start reconnect (B7).
     *
     * [playing] still drives notification copy and the CPU wake lock (series queue top-up).
     */
    private fun startForegroundWithType(info: CastSessionManager.SessionInfo, playing: Boolean) {
        val notif = buildNotification(info, playing)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        Log.d(
            TAG,
            "startForeground type=connectedDevice playing=$playing device=${info.deviceName} title=${info.title}",
        )
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
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
        // Drop the notification explicitly so a killed FGS doesn't leave a swipe-orphaned row.
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        }
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        wifiLock = null
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(info: CastSessionManager.SessionInfo, playing: Boolean): Notification {
        val stopCastPi = PendingIntent.getService(
            this, 1,
            Intent(this, CastSessionService::class.java).setAction(ACTION_STOP_CAST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectPi = PendingIntent.getService(
            this, 3,
            Intent(this, CastSessionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismissPi = PendingIntent.getService(
            this, 2,
            Intent(this, CastSessionService::class.java).setAction(ACTION_NOTIFICATION_DISMISSED),
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
        // Casting → Stop (end playback, keep link). Connected → Disconnect (drop the link).
        val actionLabel = if (playing) "Stop" else "Disconnect"
        val actionPi = if (playing) stopCastPi else disconnectPi
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPi)
            .addAction(0, actionLabel, actionPi)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(dismissPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val notification = builder.build()
        // Belt-and-braces: some OEMs honor these flags more reliably than setOngoing alone.
        notification.flags = notification.flags or
            Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun ensureChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while casting or connected to a TV"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val TAG = "CastSessionService"
        private const val CHANNEL_ID = "cast_session_channel"
        private const val NOTIF_ID = 4712
        private const val ACTION_STOP_CAST = "com.playbridge.sender.cast.action.STOP_CAST"
        private const val ACTION_DISCONNECT = "com.playbridge.sender.cast.action.DISCONNECT"
        private const val ACTION_NOTIFICATION_DISMISSED =
            "com.playbridge.sender.cast.action.NOTIFICATION_DISMISSED"

        fun start(context: Context) {
            if (!ProcessUtil.isMainProcess(context)) {
                Log.w(TAG, "start ignored in non-main process ${ProcessUtil.processName()}")
                return
            }
            val intent = Intent(context, CastSessionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            if (!ProcessUtil.isMainProcess(context)) {
                Log.w(TAG, "stop ignored in non-main process ${ProcessUtil.processName()}")
                return
            }
            context.stopService(Intent(context, CastSessionService::class.java))
        }

        /**
         * Stop the service and remove the ongoing notification immediately. Use on full
         * app exit — [stop] alone can leave the shade row briefly (or stuck) if the
         * process outlives a racing [startForeground] from [CastSessionManager].
         *
         * Main-process only: Gecko child processes must never cancel the cast notif
         * (package-wide NotificationManager.cancel + stopService hit the main FGS).
         */
        fun stopAndCancelNotification(context: Context) {
            if (!ProcessUtil.isMainProcess(context)) {
                Log.w(
                    TAG,
                    "stopAndCancelNotification ignored in non-main process ${ProcessUtil.processName()}",
                )
                return
            }
            Log.i(TAG, "stopAndCancelNotification")
            stop(context)
            runCatching {
                val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                mgr.cancel(NOTIF_ID)
            }
        }
    }
}

