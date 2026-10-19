package com.playbridge.sender.cast
import com.playbridge.sender.browser.*

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import com.playbridge.sender.R

/**
 * Foreground service that manages a MediaSession and displays a media notification
 * for background playback — web media (per-tab [MediaSessionObserver]s) and the
 * in-app player ([com.playbridge.sender.player.PlayerActivity]'s background mode).
 *
 * Lifecycle model (mirrors the Media3 pattern used by ArchiveTune):
 *  - **Owners**: each media source registers via [activate]/[deactivate] with a stable
 *    id (tab id / "inapp-player"). The service only stops when the last owner leaves,
 *    so one tab ending can't kill another tab's session. Metadata stays with each
 *    owner across dismiss/Stop/idle teardown and is cleared only on deactivation.
 *  - **Foreground only while playing**: on pause the service demotes itself
 *    (stopForeground(DETACH)) so the notification becomes swipe-dismissible — the old
 *    behaviour kept an ongoing FGS notification forever when Gecko never reported
 *    media deactivation (ended/abandoned videos), which is exactly the "stuck
 *    notification" bug.
 *  - **Dismiss = cleanup**: a paused notification carries a deleteIntent that stops
 *    the service (playback state is untouched; playing again re-creates it).
 *  - **Idle stop**: a session paused for [PAUSED_IDLE_STOP_MS] is torn down.
 *  - **Task removed**: swiping the app away stops media and the service.
 */
class MediaPlaybackService : Service() {

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** True while startForeground is in effect (playing). */
    private var isForegroundActive = false

    /** startForegroundService contract: the first notification must promote. */
    private var startedForegroundOnce = false

    private var idleStopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastPlayEventTime = 0L

    private var audioFocusLossJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange: Int ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                audioFocusLossJob?.cancel()
                audioFocusLossJob = scope.launch {
                    delay(500)
                    val timeSincePlay = System.currentTimeMillis() - lastPlayEventTime
                    if (timeSincePlay < 2000) {
                        Log.d(TAG, "AudioFocus lost, but play event happened recently ($timeSincePlay ms ago). Ignoring.")
                        return@launch
                    }
                    Log.d(TAG, "AudioFocus lost, triggering pause")
                    dispatchControl(MediaControlChannel.Action.PAUSE)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusLossJob?.cancel()
                Log.d(TAG, "AudioFocus gained")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "MediaPlaybackService created")
        
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        if (notificationStart(!activeOwners.isEmpty()) == NotificationStart.FINISH_START_AND_STOP) {
            finishUnusedStart()
            return
        }
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlayBridge:MediaPlayback")
        setupMediaSession()
        // Restore metadata BEFORE the first notification, even if Gecko sends no new metadata.
        refreshOwnerState()
    }

    private fun stopNotificationService() {
        activeOwners.pauseCurrent(SystemClock.elapsedRealtime())
        if (instance === this) instance = null
        cancelIdleStop()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        isForegroundActive = false
        stopSelf()
    }

    /** Discharge an in-flight startForegroundService request after the last owner left. */
    private fun finishUnusedStart() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
        val promoted = promoteNotificationForeground(
            promote = { startForeground(NOTIFICATION_ID, notification) },
            onFailure = { Log.w(TAG, "Media foreground start failed (${it.javaClass.simpleName})") },
            stop = ::stopNotificationService,
        )
        if (promoted) stopNotificationService()
    }

    private fun refreshOwnerState() {
        val owner = activeOwners.current() ?: return
        applyMetadata(owner.metadata)
        if (owner.playing != null) {
            updatePlaybackState(owner.playing)
        } else {
            // An activated source that never reports playback state still expires.
            publishPlaybackState()
            if (showNotification()) scheduleIdleStop()
        }
    }

    private fun refreshOwnerMetadata() {
        applyMetadata(activeOwners.current()?.metadata)
        showNotification()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Media Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Controls for web media playback"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "PlayBridgeMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "MediaSession: onPlay")
                    dispatchControl(MediaControlChannel.Action.PLAY)
                }

                override fun onPause() {
                    Log.d(TAG, "MediaSession: onPause")
                    dispatchControl(MediaControlChannel.Action.PAUSE)
                }

                override fun onStop() {
                    Log.d(TAG, "MediaSession: onStop")
                    stopCurrentPlayback()
                }

                override fun onSeekTo(pos: Long) {
                    val owner = activeOwners.current() ?: return
                    val target = mediaNotificationSeekTarget(owner.position, owner.seekSupported, pos) ?: return
                    dispatchControl(MediaControlChannel.Action.SEEK_TO, positionMs = target)
                }
            })
            isActive = true
        }
    }


    /**
     * Update the notification and media session metadata.
     */
    private fun applyMetadata(metadata: MediaNotificationMetadata<Bitmap>?) {
        val metadataBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, metadata?.title ?: "Web Video")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, metadata?.artist ?: "Browser")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, activeOwners.current()?.position?.durationMs ?: -1L)
        metadata?.artwork?.let { metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        mediaSession?.setMetadata(metadataBuilder.build())
    }

    /**
     * Update the playback state (playing vs paused).
     */
    @SuppressLint("WakelockTimeout")
    fun updatePlaybackState(isPlaying: Boolean) {
        publishPlaybackState()
        if (!showNotification()) return

        if (isPlaying) {
            cancelIdleStop()
            lastPlayEventTime = System.currentTimeMillis()
            requestAudioFocus()
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
                Log.d(TAG, "WakeLock acquired")
            }
        } else {
            scheduleIdleStop()
            abandonAudioFocus()
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released (paused)")
            }
        }
    }

    /** Position ticks update the platform session only; no notification/focus/wakelock churn. */
    private fun publishPlaybackState() {
        val owner = activeOwners.current() ?: return
        val playing = owner.playing == true
        val now = SystemClock.elapsedRealtime()
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP
        if (mediaNotificationCanSeek(owner.position, owner.seekSupported)) actions = actions or PlaybackState.ACTION_SEEK_TO
        val playbackState = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                owner.position?.positionAt(now, playing) ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                if (playing) owner.position?.playbackRate ?: 1f else 0f,
                now,
            )
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }

    private fun dispatchControl(
        action: MediaControlChannel.Action,
        requestedOwnerId: String? = null,
        positionMs: Long? = null,
    ): Boolean {
        val command = notificationMediaControl(activeOwners.current()?.ownerId, requestedOwnerId, action, positionMs)
            ?: return false
        return MediaControlChannel.tryEmit(command)
    }

    private fun stopCurrentPlayback(requestedOwnerId: String? = null) {
        val ownerId = activeOwners.current()?.ownerId ?: return
        if (!dispatchControl(MediaControlChannel.Action.STOP, requestedOwnerId)) return
        activeOwners.updatePlayback(ownerId, false, SystemClock.elapsedRealtime())
        if (activeOwners.current()?.playing == true) refreshOwnerState() else stopNotificationService()
    }

    /** Whole-service termination preserves the old stop-all behavior, but every
     * command is explicitly addressed. Notification controls use only the selected owner. */
    private fun stopMediaForTermination() {
        for (ownerId in activeOwners.ownerIds()) {
            MediaControlChannel.tryEmit(MediaControlChannel.Command(ownerId, MediaControlChannel.Action.STOP))
            activeOwners.updatePlayback(ownerId, false, SystemClock.elapsedRealtime())
        }
        stopNotificationService()
    }

    /** Paused sessions don't live forever: tear down after [PAUSED_IDLE_STOP_MS]. */
    private fun scheduleIdleStop() {
        if (idleStopJob?.isActive == true) return
        idleStopJob = scope.launch {
            delay(PAUSED_IDLE_STOP_MS)
            if (mediaNotificationNeedsIdleStop(activeOwners.current()?.playing)) {
                Log.d(TAG, "Media idle for ${PAUSED_IDLE_STOP_MS / 60_000} min — stopping service")
                stopNotificationService()
            }
        }
    }

    private fun cancelIdleStop() {
        idleStopJob?.cancel()
        idleStopJob = null
    }

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()

        val result = audioManager.requestAudioFocus(audioFocusRequest!!)
        Log.d(TAG, "AudioFocus request result: $result")
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
            Log.d(TAG, "AudioFocus abandoned")
        }
    }

    private fun showNotification(): Boolean {
        if (activeOwners.isEmpty()) {
            finishUnusedStart()
            return false
        }
        val metadata = mediaSession?.controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Web Video"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Browser"
        val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val isPlaying = mediaSession?.controller?.playbackState?.state == PlaybackState.STATE_PLAYING

        // Tap target: the owning surface (e.g. the in-app player) when one registered
        // itself, else the browser. FLAG_UPDATE_CURRENT is required — the request code
        // is fixed, so without it the system would keep serving the first target.
        val ownerId = activeOwners.current()?.ownerId
        val intent = if (ownerId == OWNER_INAPP_PLAYER) contentIntentOverride?.let { Intent(it) } else null
        val contentIntent = intent ?: Intent(this, BrowserActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            action = BrowserActivity.ACTION_OPEN_MEDIA_TAB
            putExtra(BrowserActivity.EXTRA_MEDIA_TAB_ID, ownerId)
            data = Uri.Builder().scheme("playbridge-media-tab").authority("notification")
                .appendPath(ownerId.orEmpty()).build()
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        
        // We use standard Notification builder but set the MediaStyle
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use app icon
            .setLargeIcon(artwork)
            .setContentIntent(pendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.MediaStyle().setMediaSession(mediaSession?.sessionToken))
            // Paused → swipe-dismissible; dismissing cleans the service up.
            .setOngoing(isPlaying)
            .setDeleteIntent(createPlaybackPendingIntent(ACTION_NOTIFICATION_DISMISSED))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, playPauseIcon),
                    playPauseTitle,
                    createPlaybackPendingIntent(if (isPlaying) ACTION_PAUSE else ACTION_PLAY)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    createPlaybackPendingIntent(ACTION_STOP)
                ).build()
            )
            .build()

        // Foreground only while playing. On pause, DETACH keeps the notification visible
        // but swipe-dismissible — the always-startForeground behaviour was the "stuck
        // notification" bug: Gecko doesn't reliably deactivate ended media, leaving an
        // undismissible ongoing notification behind.
        if (isPlaying || !startedForegroundOnce) {
            if (!promoteNotificationForeground(
                    promote = { startForeground(NOTIFICATION_ID, notification) },
                    onFailure = { Log.w(TAG, "Media foreground start failed (${it.javaClass.simpleName})") },
                    stop = ::stopNotificationService,
                )
            ) return false
            isForegroundActive = true
            startedForegroundOnce = true
        } else {
            if (isForegroundActive) {
                stopForeground(STOP_FOREGROUND_DETACH)
                isForegroundActive = false
            }
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
        return true
    }

    private fun createPlaybackPendingIntent(action: String): PendingIntent {
        val ownerId = activeOwners.current()?.ownerId
        val intent = Intent(this, MediaPlaybackService::class.java).apply {
            this.action = action
            putExtra(EXTRA_OWNER_ID, ownerId)
            data = Uri.Builder().scheme("playbridge-media-control").authority("notification")
                .appendPath(ownerId.orEmpty()).appendPath(action).build()
        }
        return PendingIntent.getService(this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (notificationStart(!activeOwners.isEmpty()) == NotificationStart.FINISH_START_AND_STOP) {
            finishUnusedStart()
            return START_NOT_STICKY
        }
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_PLAY -> dispatchControl(MediaControlChannel.Action.PLAY, intent.getStringExtra(EXTRA_OWNER_ID))
            ACTION_PAUSE -> dispatchControl(MediaControlChannel.Action.PAUSE, intent.getStringExtra(EXTRA_OWNER_ID))
            ACTION_STOP -> {
                stopCurrentPlayback(intent.getStringExtra(EXTRA_OWNER_ID))
                return START_NOT_STICKY
            }
            ACTION_NOTIFICATION_DISMISSED -> {
                if (notificationMediaControl(activeOwners.current()?.ownerId,
                        intent.getStringExtra(EXTRA_OWNER_ID), MediaControlChannel.Action.PAUSE) == null
                ) return START_NOT_STICKY
                if (activeOwners.current()?.playing != true) stopNotificationService() else refreshOwnerState()
                return START_NOT_STICKY
            }
            ACTION_DESTROY_SERVICE -> {
                // Retain owners/metadata. Only a later PLAYING event restarts the service.
                stopNotificationService()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_METADATA -> {
                val ownerId = intent.getStringExtra(EXTRA_OWNER_ID) ?: activeOwners.current()?.ownerId
                if (ownerId != null) activeOwners.updateMetadata(
                    ownerId,
                    MediaNotificationMetadata(intent.getStringExtra(EXTRA_TITLE), intent.getStringExtra(EXTRA_ARTIST)),
                )
            }
            // State is cached before enqueuing a start. Read the latest owner snapshot,
            // not a stale PLAYING extra from an earlier owner/start request.
            ACTION_UPDATE_STATE -> Unit
        }
        refreshOwnerState()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) {
        handleForegroundTimeout(startId, fgsType = 0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleForegroundTimeout(startId, fgsType)
    }

    private fun handleForegroundTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "FGS timeout startId=$startId type=$fgsType — stopping")
        stopMediaForTermination()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped from recents: the UI is gone but the process (and Gecko) may keep
        // playing with no way to control it. Stop media and go away instead of leaving
        // an orphaned notification.
        Log.d(TAG, "Task removed — stopping media and service")
        stopMediaForTermination()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        cancelIdleStop()
        abandonAudioFocus()
        scope.cancel()
        mediaSession?.isActive = false
        mediaSession?.release()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        // A DETACHed (paused) notification survives stopSelf — remove it explicitly so
        // a dead service can't leave a zombie notification behind.
        runCatching {
            if (instance == null) getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
        Log.d(TAG, "MediaPlaybackService destroyed")
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "media_playback_channel"
        const val NOTIFICATION_ID = 101
        const val TAG = "MediaPlaybackService"

        const val ACTION_PLAY = "com.playbridge.sender.ACTION_PLAY"
        const val ACTION_PAUSE = "com.playbridge.sender.ACTION_PAUSE"
        const val ACTION_STOP = "com.playbridge.sender.ACTION_STOP"
        const val ACTION_DESTROY_SERVICE = "com.playbridge.sender.ACTION_DESTROY_SERVICE"
        const val ACTION_UPDATE_METADATA = "com.playbridge.sender.ACTION_UPDATE_METADATA"
        const val ACTION_UPDATE_STATE = "com.playbridge.sender.ACTION_UPDATE_STATE"
        const val ACTION_NOTIFICATION_DISMISSED = "com.playbridge.sender.ACTION_NOTIFICATION_DISMISSED"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        private const val EXTRA_OWNER_ID = "extra_owner_id"

        /** Owner id used by the in-app player's background-mode session. */
        const val OWNER_INAPP_PLAYER = "inapp-player"

        /** Paused sessions are torn down after this long (notification is dismissible anyway). */
        private const val PAUSED_IDLE_STOP_MS = 10L * 60 * 1000

        @Volatile
        var instance: MediaPlaybackService? = null
            private set

        /**
         * Media sources currently holding the service open (tab ids / OWNER_INAPP_PLAYER).
         * The service stops only when the LAST owner deactivates — previously any single
         * tab's deactivation stopped the shared service, and a tab closed while playing
         * (which never fires onMediaDeactivated) left it running forever.
         */
        private val activeOwners = MediaNotificationOwners<Bitmap>()

        /**
         * Where a notification tap should land. Defaults to the browser; the in-app
         * player registers itself while its background session is active. Companion
         * state so it survives service restarts within the owner's lifetime.
         */
        @Volatile
        private var contentIntentOverride: Intent? = null

        /** Set (or clear with null) the notification tap target. */
        fun setContentIntent(intent: Intent?) {
            contentIntentOverride = intent
        }

        /** Register [ownerId] as an active media source and ensure the service runs. */
        fun activate(context: Context, ownerId: String) {
            activeOwners.add(ownerId)
            if (instance == null) {
                runCatching {
                    context.startForegroundService(Intent(context, MediaPlaybackService::class.java))
                }.onFailure { Log.w(TAG, "Could not start media service (${it.javaClass.simpleName})") }
            }
        }

        /** Unregister [ownerId]; stops the service when no owners remain. */
        fun deactivate(ownerId: String) {
            if (!activeOwners.remove(ownerId)) return
            if (activeOwners.isEmpty()) {
                Log.d(TAG, "Last media owner gone — stopping service")
                instance?.stopNotificationService()
            } else {
                instance?.refreshOwnerState()
            }
        }

        fun sendMetadataUpdate(ownerId: String, title: String?, artist: String?, artwork: Bitmap? = null) {
            if (!activeOwners.updateMetadata(ownerId, MediaNotificationMetadata(title, artist, artwork))) return
            // Cache even while dismissed/stopped; metadata alone must not resurrect a notification.
            instance?.refreshOwnerMetadata()
        }

        fun sendPositionUpdate(
            ownerId: String,
            durationSeconds: Double,
            positionSeconds: Double,
            playbackRate: Double,
            seekSupported: Boolean? = null,
        ) {
            val before = activeOwners.current()
            val position = MediaNotificationPosition.fromSeconds(
                durationSeconds, positionSeconds, playbackRate, SystemClock.elapsedRealtime(),
            )
            if (!activeOwners.updatePosition(ownerId, position)) return
            if (seekSupported != null) activeOwners.updateSeekSupported(ownerId, seekSupported)
            val owner = activeOwners.current() ?: return
            if (owner.ownerId != ownerId) return
            instance?.let { service ->
                if (before?.position?.durationMs != owner.position?.durationMs) service.applyMetadata(owner.metadata)
                service.publishPlaybackState()
            }
        }

        fun sendSeekAvailability(ownerId: String, supported: Boolean) {
            if (!activeOwners.updateSeekSupported(ownerId, supported)) return
            if (activeOwners.current()?.ownerId == ownerId) instance?.publishPlaybackState()
        }

        fun sendStateUpdate(context: Context, ownerId: String, isPlaying: Boolean) {
            if (!activeOwners.updatePlayback(ownerId, isPlaying, SystemClock.elapsedRealtime())) return
            val svc = instance
            if (svc != null) {
                svc.refreshOwnerState()
                return
            }
            // Paused events never resurrect a dismissed notification. PLAYING restores
            // the owner's cached title/artist/artwork into the new MediaSession.
            if (!isPlaying) return
            runCatching {
                context.startForegroundService(
                    Intent(context, MediaPlaybackService::class.java).apply {
                        action = ACTION_UPDATE_STATE
                        putExtra(EXTRA_OWNER_ID, ownerId)
                        putExtra(EXTRA_IS_PLAYING, true)
                    }
                )
            }.onFailure { Log.w(TAG, "Could not resume media service (${it.javaClass.simpleName})") }
        }

    }

}
