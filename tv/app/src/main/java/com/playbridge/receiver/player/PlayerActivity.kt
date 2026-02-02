package com.playbridge.receiver.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.playbridge.receiver.server.ServerService

private const val TAG = "PlayerActivity"

/**
 * Minimal ExoPlayer-based video player activity.
 * Receives play commands from the phone via the ServerService.
 */
class PlayerActivity : ComponentActivity() {
    
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ServerService.ACTION_CONTROL -> {
                    val command = intent.getStringExtra(ServerService.EXTRA_COMMAND)
                    handleControlCommand(command)
                }
                ServerService.ACTION_PLAY -> {
                    val url = intent.getStringExtra(ServerService.EXTRA_URL)
                    val title = intent.getStringExtra(ServerService.EXTRA_TITLE)
                    if (url != null) {
                        playVideo(url, title)
                    }
                }
            }
        }
    }
    
    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "=== PlayerActivity CREATED ===")
        Log.i(TAG, "Intent action: ${intent?.action}")
        Log.i(TAG, "Has URL extra: ${intent?.hasExtra(ServerService.EXTRA_URL)}")
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Keep screen on during playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Get URL and title from intent
        val url = intent?.getStringExtra(ServerService.EXTRA_URL)
        val title = intent?.getStringExtra(ServerService.EXTRA_TITLE)
        
        Log.i(TAG, "URL from intent: $url")
        Log.i(TAG, "Title from intent: $title")
        
        // Create PlayerView programmatically
        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 3000
            controllerAutoShow = true
        }
        setContentView(playerView)
        
        // Initialize ExoPlayer
        initializePlayer()
        
        // Register broadcast receiver for control commands
        val filter = IntentFilter().apply {
            addAction(ServerService.ACTION_CONTROL)
            addAction(ServerService.ACTION_PLAY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }
        
        // Handle initial intent
        handleIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        val url = intent?.getStringExtra(ServerService.EXTRA_URL)
        val title = intent?.getStringExtra(ServerService.EXTRA_TITLE)
        
        if (url != null) {
            Log.i(TAG, "Playing video: $url (title: $title)")
            playVideo(url, title)
        }
    }
    
    private fun initializePlayer() {
        // Configure load control for better live streaming performance
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15000,  // Min buffer: 15 seconds
                30000,  // Max buffer: 30 seconds  
                2500,   // Buffer for playback: 2.5 seconds
                5000    // Buffer for playback after rebuffer: 5 seconds
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.playWhenReady = true
                
                // Add listener for playback events
                exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            androidx.media3.common.Player.STATE_BUFFERING -> {
                                Log.d(TAG, "Buffering...")
                            }
                            androidx.media3.common.Player.STATE_READY -> {
                                Log.i(TAG, "Playback ready")
                            }
                            androidx.media3.common.Player.STATE_ENDED -> {
                                Log.i(TAG, "Playback ended")
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e(TAG, "Playback error: ${error.message}", error)
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this@PlayerActivity,
                                "Playback error: ${error.message}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d(TAG, "Is playing: $isPlaying")
                    }
                })
            }
    }
    
    private fun playVideo(url: String, title: String?) {
        Log.i(TAG, "=== PLAYING VIDEO ===")
        Log.i(TAG, "URL: $url")
        Log.i(TAG, "Title: $title")
        
        player?.let { exoPlayer ->
            // Detect if this is a live stream
            val isLiveStream = url.contains("/live/", ignoreCase = true) || 
                             url.contains("playlist.m3u8", ignoreCase = true) ||
                             url.contains("/mono.ts.m3u8", ignoreCase = true)
            
            Log.i(TAG, "Detected stream type: ${if (isLiveStream) "LIVE" else "VOD"}")
            
            val mediaItem = MediaItem.Builder()
                .setUri(url)
                .apply {
                    title?.let { setMediaId(it) }
                    if (isLiveStream) {
                        // Configure for live streaming
                        setLiveConfiguration(
                            MediaItem.LiveConfiguration.Builder()
                                .setTargetOffsetMs(3000)  // 3 seconds from live edge
                                .setMinPlaybackSpeed(0.95f)
                                .setMaxPlaybackSpeed(1.05f)
                                .build()
                        )
                    }
                }
                .build()
            
            Log.i(TAG, "Setting media item...")
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
            
            Log.i(TAG, "Started playback: $url")
        } ?: Log.e(TAG, "Player is null!")
    }
    
    private fun handleControlCommand(command: String?) {
        Log.i(TAG, "Control command: $command")
        
        when (command) {
            "pause" -> player?.pause()
            "play" -> player?.play()
            "stop" -> {
                player?.stop()
                finish()
            }
            "toggle" -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
            }
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Handle TV remote D-pad controls
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                player?.stop()
                finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                player?.seekBack()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                player?.seekForward()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    override fun onStart() {
        super.onStart()
        if (player == null) {
            initializePlayer()
        }
    }
    
    override fun onStop() {
        super.onStop()
        releasePlayer()
    }
    
    override fun onDestroy() {
        unregisterReceiver(controlReceiver)
        releasePlayer()
        super.onDestroy()
    }
    
    private fun releasePlayer() {
        player?.release()
        player = null
    }
}
