package com.playbridge.sender.cast

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.playbridge.sender.R
import com.playbridge.sender.cast.mirror.ScreenMirrorCoordinator
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

    private data class ForegroundState(
        val info: CastSessionManager.SessionInfo,
        val playing: Boolean,
        val needsWakeLock: Boolean,
        val mirroring: Boolean,
        val capturesDeviceAudio: Boolean,
    )

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
                manager.screenMirrorState,
            ) { info, playing, needsWake, mirror ->
                ForegroundState(info, playing, needsWake, mirror.isActive, mirror.deviceAudioRequested)
            }.collect { state ->
                if (!foregroundStarted) return@collect
                currentlyPlaying = state.playing
                applyResourceLocks(state.needsWakeLock)
                startForegroundWithType(
                    state.info,
                    state.playing,
                    state.mirroring,
                    state.capturesDeviceAudio,
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCREEN_MIRROR -> {
                val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_PERMISSION, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_PROJECTION_PERMISSION)
                }
                if (permission == null) return START_NOT_STICKY
                val quality = ScreenMirrorCoordinator.Quality.fromId(
                    intent.getStringExtra(EXTRA_SCREEN_MIRROR_QUALITY),
                )
                val audioRequested = intent.getBooleanExtra(EXTRA_SCREEN_MIRROR_DEVICE_AUDIO, false)
                val deviceAudio = audioRequested &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                val options = ScreenMirrorCoordinator.Options(quality, deviceAudio)
                // Android requires this foreground-service type before MediaProjection is acquired.
                foregroundStarted = true
                startForegroundWithType(
                    manager.sessionInfo.value,
                    playing = true,
                    mirroring = true,
                    capturesDeviceAudio = deviceAudio,
                )
                applyResourceLocks(true)
                if (!manager.startScreenMirror(permission, options)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            ACTION_STOP_CAST -> {
                manager.endCastSession()
                if (!manager.hasActiveSession.value) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val playing = manager.isActivelyPlaying.value
                currentlyPlaying = playing
                val mirror = manager.screenMirrorState.value
                startForegroundWithType(
                    manager.sessionInfo.value,
                    playing,
                    mirror.isActive,
                    mirror.deviceAudioRequested,
                )
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
                    val mirror = manager.screenMirrorState.value
                    startForegroundWithType(
                        manager.sessionInfo.value,
                        playing,
                        mirror.isActive,
                        mirror.deviceAudioRequested,
                    )
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
        val mirror = manager.screenMirrorState.value
        startForegroundWithType(
            manager.sessionInfo.value,
            playing,
            mirror.isActive,
            mirror.deviceAudioRequested,
        )
        foregroundStarted = true
        applyResourceLocks(manager.needsCastWakeLock.value)
        return START_STICKY
    }

    private fun startForegroundWithType(
        info: CastSessionManager.SessionInfo,
        playing: Boolean,
        mirroring: Boolean,
        capturesDeviceAudio: Boolean,
    ) {
        val notif = buildNotification(info, playing)
        val microphoneType = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mirroring && capturesDeviceAudio
        ) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (mirroring) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0) or
                microphoneType
        } else {
            0
        }
        Log.d(
            TAG,
            "startForeground mirroring=$mirroring deviceAudio=$capturesDeviceAudio " +
                "playing=$playing device=${info.deviceName} title=${info.title}",
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
        private const val ACTION_START_SCREEN_MIRROR =
            "com.playbridge.sender.cast.action.START_SCREEN_MIRROR"
        private const val EXTRA_PROJECTION_PERMISSION = "projection_permission"
        private const val EXTRA_SCREEN_MIRROR_QUALITY = "screen_mirror_quality"
        private const val EXTRA_SCREEN_MIRROR_DEVICE_AUDIO = "screen_mirror_device_audio"

        fun start(context: Context) {
            if (!ProcessUtil.isMainProcess(context)) {
                Log.w(TAG, "start ignored in non-main process ${ProcessUtil.processName()}")
                return
            }
            val intent = Intent(context, CastSessionService::class.java)
            context.startForegroundService(intent)
        }

        fun startScreenMirror(
            context: Context,
            projectionPermission: Intent,
            options: ScreenMirrorCoordinator.Options,
        ) {
            if (!ProcessUtil.isMainProcess(context)) return
            context.startForegroundService(
                Intent(context, CastSessionService::class.java)
                    .setAction(ACTION_START_SCREEN_MIRROR)
                    .putExtra(EXTRA_PROJECTION_PERMISSION, projectionPermission)
                    .putExtra(EXTRA_SCREEN_MIRROR_QUALITY, options.quality.id)
                    .putExtra(EXTRA_SCREEN_MIRROR_DEVICE_AUDIO, options.deviceAudio),
            )
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
