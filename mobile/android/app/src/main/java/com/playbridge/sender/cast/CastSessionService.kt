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
 * survive screen-off and app backgrounding.
 *
 * Lifecycle is driven entirely by [CastSessionManager.hasActiveSession]; the notification
 * mirrors [CastSessionManager.sessionInfo].
 *
 * External playback uses this FGS only while the phone is serving the media path.
 * High-performance Wi-Fi and partial CPU wake locks are held only while
 * [CastSessionManager.needsCastWakeLock] is true (phone data path or native player
 * context). Direct and remote-proxy external playback do not keep this service alive.
 */
class CastSessionService : Service(), KoinComponent {

    private val manager: CastSessionManager by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var currentlyPlaying = false
    private var locksHeld = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "playbridge:cast").apply {
            setReferenceCounted(false)
            // Not acquired here — only while needsCastWakeLock (phone path / native player).
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "playbridge:cast").apply {
            setReferenceCounted(false)
        }
        scope.launch {
            combine(
                manager.sessionInfo,
                manager.isActivelyPlaying,
                manager.needsCastWakeLock,
            ) { info, playing, needsWake ->
                Triple(info, playing, needsWake)
            }.collect { (info, playing, needsWake) ->
                if (!foregroundStarted) return@collect
                currentlyPlaying = playing
                applyResourceLocks(needsWake)
                startForegroundWithType(info, playing)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CAST -> {
                manager.endCastSession()
                if (!manager.hasActiveSession.value) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val playing = manager.isActivelyPlaying.value
                currentlyPlaying = playing
                startForegroundWithType(manager.sessionInfo.value, playing)
                applyResourceLocks(manager.needsCastWakeLock.value)
                return START_STICKY
            }
            ACTION_DISCONNECT -> {
                manager.disconnectSession()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_NOTIFICATION_DISMISSED -> {
                if (manager.hasActiveSession.value) {
                    Log.i(TAG, "Cast notification dismissed while session active — re-showing")
                    val playing = manager.isActivelyPlaying.value
                    currentlyPlaying = playing
                    startForegroundWithType(manager.sessionInfo.value, playing)
                    foregroundStarted = true
                    applyResourceLocks(manager.needsCastWakeLock.value)
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
        applyResourceLocks(manager.needsCastWakeLock.value)
        return START_STICKY
    }

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

    /**
     * Acquire or release both high-performance Wi-Fi and partial CPU wake locks together.
     * Direct / remote Via-proxy playback releases them; Via phone or native player reacquires.
     */
    private fun applyResourceLocks(needed: Boolean) {
        if (needed == locksHeld) return
        if (needed) {
            acquireWifi()
            acquireWake()
            locksHeld = true
            Log.d(TAG, "Cast resource locks acquired (phone path / native player)")
        } else {
            releaseWake()
            releaseWifi()
            locksHeld = false
            Log.d(TAG, "Cast resource locks released (Direct or idle session)")
        }
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        wakeLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
    }

    private fun acquireWifi() {
        wifiLock?.let { if (!it.isHeld) runCatching { it.acquire() } }
    }

    private fun releaseWifi() {
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        }
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        wifiLock = null
        wakeLock = null
        locksHeld = false
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
