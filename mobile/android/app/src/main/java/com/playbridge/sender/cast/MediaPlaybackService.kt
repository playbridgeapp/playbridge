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
import android.util.Log
import kotlinx.coroutines.*
import com.playbridge.sender.R

/**
 * Foreground service that manages a MediaSession and displays a media notification.
 * This keeps the app alive during background playback and provides controls.
 */
class MediaPlaybackService : Service() {

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
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
                    MediaControlChannel.tryEmit(MediaControlChannel.Action.PAUSE)
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

        // Show initial notification immediately to avoid "Service.startForeground() not called" error
        createNotificationChannel()
        setupMediaSession()
        showNotification() 

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlayBridge:MediaPlayback")
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
                    MediaControlChannel.tryEmit(MediaControlChannel.Action.PLAY)
                }

                override fun onPause() {
                    Log.d(TAG, "MediaSession: onPause")
                    MediaControlChannel.tryEmit(MediaControlChannel.Action.PAUSE)
                }

                override fun onStop() {
                    Log.d(TAG, "MediaSession: onStop")
                    MediaControlChannel.tryEmit(MediaControlChannel.Action.STOP)
                    stopSelf()
                }
            })
            isActive = true
        }
    }


    /**
     * Update the notification and media session metadata.
     */
    fun updateMetadata(title: String?, artist: String?, artwork: Bitmap? = null) {
        val metadataBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title ?: "Web Video")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist ?: "PlayBridge Browser")
        
        if (artwork != null) {
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
        }
        
        mediaSession?.setMetadata(metadataBuilder.build())
        showNotification()
    }

    /**
     * Update the playback state (playing vs paused).
     */
    @SuppressLint("WakelockTimeout")
    fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val playbackState = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or 
                PlaybackState.ACTION_PAUSE or 
                PlaybackState.ACTION_STOP
            )
            .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        
        mediaSession?.setPlaybackState(playbackState)
        showNotification()
        
        if (isPlaying) {
            lastPlayEventTime = System.currentTimeMillis()
            requestAudioFocus()
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
                Log.d(TAG, "WakeLock acquired")
            }
        } else {
            abandonAudioFocus()
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released (paused)")
            }
        }
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

    private fun showNotification() {
        val metadata = mediaSession?.controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Web Video"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Browser"
        val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val isPlaying = mediaSession?.controller?.playbackState?.state == PlaybackState.STATE_PLAYING

        val intent = Intent(this, BrowserActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

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

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createPlaybackPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_PLAY -> {
                Log.d(TAG, "ACTION_PLAY received")
                MediaControlChannel.tryEmit(MediaControlChannel.Action.PLAY)
            }
            ACTION_PAUSE -> {
                Log.d(TAG, "ACTION_PAUSE received")
                MediaControlChannel.tryEmit(MediaControlChannel.Action.PAUSE)
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received (user initiated)")
                MediaControlChannel.tryEmit(MediaControlChannel.Action.STOP)
                stopSelf()
            }
            ACTION_DESTROY_SERVICE -> {
                Log.d(TAG, "ACTION_DESTROY_SERVICE received (programmatic)")
                stopSelf()
            }
            ACTION_UPDATE_METADATA -> {
                val title = intent.getStringExtra(EXTRA_TITLE)
                val artist = intent.getStringExtra(EXTRA_ARTIST)
                updateMetadata(title, artist)
            }
            ACTION_UPDATE_STATE -> {
                val isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
                updatePlaybackState(isPlaying)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        abandonAudioFocus()
        scope.cancel()
        mediaSession?.isActive = false
        mediaSession?.release()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
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

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_IS_PLAYING = "extra_is_playing"

        @Volatile
        var instance: MediaPlaybackService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_DESTROY_SERVICE
            }
            context.startService(intent)
        }

        fun sendMetadataUpdate(context: Context, title: String?, artist: String?) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_UPDATE_METADATA
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_ARTIST, artist)
            }
            context.startForegroundService(intent)
        }

        fun sendStateUpdate(context: Context, isPlaying: Boolean) {
            val intent = Intent(context, MediaPlaybackService::class.java).apply {
                action = ACTION_UPDATE_STATE
                putExtra(EXTRA_IS_PLAYING, isPlaying)
            }
            context.startForegroundService(intent)
        }
    }

}
