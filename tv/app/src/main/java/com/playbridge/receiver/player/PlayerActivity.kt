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
import com.playbridge.receiver.data.HistoryStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import com.playbridge.receiver.ui.theme.PlayBridgeTVTheme
import com.playbridge.receiver.player.TrackSelectionDialog
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

private const val TAG = "PlayerActivity"

/**
 * Minimal ExoPlayer-based video player activity.
 * Receives play commands from the phone via the ServerService.
 */
class PlayerActivity : ComponentActivity() {
    
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var audioDiscontinuityRetryCount = 0

    private lateinit var historyStore: HistoryStore
    private var currentUrl: String? = null
    private var currentTitle: String? = null
    private var currentContentType: String? = null
    private var currentHeaders: Map<String, String>? = null
    
    // UI Components
    private lateinit var controlsRoot: android.view.View
    private lateinit var controlsPanel: android.view.View
    private lateinit var seekBar: android.widget.SeekBar
    private lateinit var playPauseButton: android.widget.ImageButton
    private lateinit var tracksButton: android.widget.ImageButton
    private lateinit var timeText: android.widget.TextView
    
    private val hideControlsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideUI() }
    
    // Scrubbing State
    private var isScrubbing = false
    private var scrubPosition: Long = 0
    private val commitSeekHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val commitSeekRunnable = Runnable { commitSeek() }
    
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            if (!isScrubbing) {
                updateProgress()
            }
            if (controlsRoot.visibility == android.view.View.VISIBLE) {
                hideControlsHandler.postDelayed(this, 1000)
            }
        }
    }


    
    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ServerService.ACTION_CONTROL -> {
                    val command = intent.getStringExtra(ServerService.EXTRA_COMMAND)
                    handleControlCommand(command)
                }
                ServerService.ACTION_REMOTE -> {
                    val key = intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY)
                    handleRemoteCommand(key)
                }
                ServerService.ACTION_PLAY -> {
                    val url = intent.getStringExtra(ServerService.EXTRA_URL)
                    val title = intent.getStringExtra(ServerService.EXTRA_TITLE)
                    val contentType = intent.getStringExtra(ServerService.EXTRA_CONTENT_TYPE)
                    val headers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getSerializableExtra(ServerService.EXTRA_HEADERS, HashMap::class.java) as? Map<String, String>
                    } else {
                        @Suppress("DEPRECATION") 
                        intent.getSerializableExtra(ServerService.EXTRA_HEADERS) as? Map<String, String>
                    }
                    
                    
                    if (url != null) {
                        playVideo(url, title, contentType, headers)
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

        // Configure immersive mode to hide system bars
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        
        // Get URL and title from intent
        val url = intent?.getStringExtra(ServerService.EXTRA_URL)
        val title = intent?.getStringExtra(ServerService.EXTRA_TITLE)
        val contentType = intent?.getStringExtra(ServerService.EXTRA_CONTENT_TYPE)
        
        Log.i(TAG, "URL from intent: $url")
        Log.i(TAG, "Title from intent: $title")
        
        // Initialize View Bindings
        setContentView(com.playbridge.receiver.R.layout.activity_player)
        
        historyStore = HistoryStore(applicationContext)
        
        playerView = findViewById(com.playbridge.receiver.R.id.player_view)
        controlsRoot = findViewById(com.playbridge.receiver.R.id.controls_root)
        controlsPanel = findViewById(com.playbridge.receiver.R.id.controls_panel)
        seekBar = findViewById(com.playbridge.receiver.R.id.player_seekbar)
        playPauseButton = findViewById(com.playbridge.receiver.R.id.btn_play_pause)
        tracksButton = findViewById(com.playbridge.receiver.R.id.btn_tracks)
        timeText = findViewById(com.playbridge.receiver.R.id.tv_duration)
        
        // Setup UI handlers
        setupControls()
        
        // Initialize ExoPlayer
        initializePlayer()
        
        // Register broadcast receiver for control commands
        val filter = IntentFilter().apply {
            addAction(ServerService.ACTION_CONTROL)
            addAction(ServerService.ACTION_REMOTE)
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
        val contentType = intent?.getStringExtra(ServerService.EXTRA_CONTENT_TYPE)
        val headers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getSerializableExtra(ServerService.EXTRA_HEADERS, HashMap::class.java) as? Map<String, String>
        } else {
            @Suppress("DEPRECATION")
            intent?.getSerializableExtra(ServerService.EXTRA_HEADERS) as? Map<String, String>
        }
        
        if (url != null) {
            Log.i(TAG, "Playing video: $url (title: $title, type: $contentType)")
            playVideo(url, title, contentType, headers)
        }
    }
    
    private fun initializePlayer() {
        // Release any existing player to prevent leaks/double playback
        releasePlayer()

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
                exoPlayer.addListener(createPlayerListener())
            }
    }

    private fun getUnsafeOkHttpClient(): okhttp3.OkHttpClient {
        try {
            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })

            // Install the all-trusting trust manager
            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory = sslContext.socketFactory

            return okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true } // Trust all hostnames
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    private fun playVideo(url: String, title: String?, contentType: String? = null, intentHeaders: Map<String, String>? = null) {
        Log.i(TAG, "========== PLAY COMMAND RECEIVED ==========")
        Log.i(TAG, "Target URL: $url")
        Log.i(TAG, "Target Title: $title")
        Log.i(TAG, "Raw Headers from Intent: $intentHeaders")
        Log.i(TAG, "Content Type: $contentType")
        Log.i(TAG, "===========================================")

        releasePlayer()
        
        lifecycleScope.launch(Dispatchers.Main) {
            var finalContentType = contentType
            
            // Pre-flight Sniffing: Always check content signature to be safe
            Log.d(TAG, "Attempting pre-flight sniff...")
            val sniffedType = sniffContent(url, intentHeaders)
            if (sniffedType != null) {
                Log.i(TAG, "Pre-flight sniff detected: $sniffedType")
                finalContentType = sniffedType
                android.widget.Toast.makeText(this@PlayerActivity, "Detected: $finalContentType", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                Log.d(TAG, "Pre-flight sniff returned null")
            }
            
            startPlayback(url, title, finalContentType, intentHeaders)
        }
    }

    private suspend fun sniffContent(url: String, headers: Map<String, String>?): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Use our unsafe client to handle potential SSL issues on local network/custom severs
                val client = getUnsafeOkHttpClient()
                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    // Fetch only the first 50 bytes to check the header signature
                    .header("Range", "bytes=0-50")
                
                headers?.forEach { (k, v) -> 
                     // Don't overwrite Range if it was somehow passed (unlikely)
                     if (!k.equals("Range", ignoreCase = true)) {
                         requestBuilder.header(k, v)
                     }
                }
                
                val request = requestBuilder.build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    
                    val source = response.body?.source() ?: return@use null
                    
                    // Peek or read the first few bytes
                    // #EXTM3U is 7 bytes
                    val headerBytes = try {
                        source.readByteString(7)
                    } catch (e: Exception) {
                        return@use null
                    }
                    
                    if (headerBytes.utf8().startsWith("#EXTM3U")) {
                        return@use androidx.media3.common.MimeTypes.APPLICATION_M3U8
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sniffing failed: ${e.message}")
            }
            return@withContext null
        }
    }

    private fun startPlayback(url: String, title: String?, contentType: String?, intentHeaders: Map<String, String>?) {
        Log.i(TAG, "Starting playback with Final Content Type: $contentType")

        currentUrl = url
        currentTitle = title
        currentContentType = contentType
        currentHeaders = intentHeaders

        // 1. Prepare Headers (Merge Intent headers with defaults)
        val requestProperties = HashMap<String, String>()
        intentHeaders?.forEach { (key, value) -> requestProperties[key] = value }

        // Fallback for Referer if missing
        if (!requestProperties.containsKey("Referer")) {
            try {
                val uri = android.net.Uri.parse(url)
                val scheme = uri.scheme ?: "https"
                val host = uri.host
                if (host != null) {
                    val referer = "$scheme://$host/"
                    requestProperties["Referer"] = referer
                    Log.i(TAG, "Added fallback Referer: $referer")
                    
                    if (!requestProperties.containsKey("Origin")) {
                        requestProperties["Origin"] = "$scheme://$host"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing URL for Referer fallback", e)
            }
        }
        
        // Set a consistent User-Agent if not provided
        val userAgent = intentHeaders?.get("User-Agent") 
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        requestProperties["User-Agent"] = userAgent
        
        Log.i(TAG, "Final Request Headers: $requestProperties")

        // 2. Create the OkHttp Data Source Factory
        // This uses the "Unsafe" client we created
        val okHttpClient = getUnsafeOkHttpClient()
        
        // Configure default cache control to force network for live streams
        val cacheControl = okhttp3.CacheControl.Builder().noCache().noStore().build()

        val okHttpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(requestProperties)
            .setCacheControl(cacheControl)

        // Wrap it in a DefaultDataSource to handle file://, asset://, etc. automatically if needed
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, okHttpDataSourceFactory)

        // 3. Configure Load Control
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
            
        // Configure Track Selector to allow exceeding capabilities (for 4K on emulator/weak devices)
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters()
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
            )
        }

        // Determine if content is HLS
        val isHls = (contentType == "application/vnd.apple.mpegurl") || 
                    (contentType == "application/x-mpegurl") ||
                    (contentType == androidx.media3.common.MimeTypes.APPLICATION_M3U8) ||
                    (contentType.isNullOrEmpty() && (url.contains(".m3u8") || url.contains(".jpg")))

        // 4. Build Player
        val mediaSourceFactory = if (isHls) {
            Log.i(TAG, "Using HlsMediaSource.Factory")
            androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(CustomLoadErrorHandlingPolicy())
                .setLoadErrorHandlingPolicy(CustomLoadErrorHandlingPolicy())
        } else {
            Log.i(TAG, "Using DefaultMediaSourceFactory")
             androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(CustomLoadErrorHandlingPolicy())
        }

        // Release any existing player to prevent leaks/double playback
        releasePlayer()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.playWhenReady = true
                exoPlayer.addListener(createPlayerListener())
            }

        // 5. Create Media Item
        val builder = MediaItem.Builder()
            .setUri(url)
            .apply { title?.let { setMediaId(it) } }
            
        // specific logic for mime type
        if (!contentType.isNullOrEmpty()) {
             builder.setMimeType(contentType)
        } else if (isHls) {
             builder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }
        // Otherwise leave null, let ExoPlayer infer from extension
        
        val mediaItem = builder.build()

        player?.setMediaItem(mediaItem)
        player?.prepare()
        
        // Check for history and resume
        lifecycleScope.launch {
            try {
                val history = historyStore.history.first()
                val item = history.find { it.url == url }
                if (item != null && item.position > 5000 && item.position < (item.duration - 5000)) {
                    Log.i(TAG, "Resuming from history: ${item.position}ms")
                    player?.seekTo(item.position)
                    android.widget.Toast.makeText(this@PlayerActivity, "Resuming playback", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore history", e)
            }
        }
        
        player?.play()
        
        // Reset retry count on new video
        audioDiscontinuityRetryCount = 0
    }

    private fun createPlayerListener() = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                androidx.media3.common.Player.STATE_BUFFERING -> {
                    Log.d(TAG, "Buffering...")
                }
                androidx.media3.common.Player.STATE_READY -> {
                    Log.i(TAG, "Playback ready")
                    // Reset retry count on successful playback
                    audioDiscontinuityRetryCount = 0
                }
                androidx.media3.common.Player.STATE_ENDED -> {
                    Log.i(TAG, "Playback ended")
                }
            }
        }
        
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val isAudioDiscontinuity = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED || 
                                       error.cause is androidx.media3.exoplayer.audio.AudioSink.UnexpectedDiscontinuityException
            
            if (isAudioDiscontinuity) {
                if (audioDiscontinuityRetryCount < 3) {
                    audioDiscontinuityRetryCount++
                    Log.w(TAG, "Audio discontinuity detected. Attempting recovery (attempt $audioDiscontinuityRetryCount)...")
                    val currentPos = player?.currentPosition ?: 0L
                    player?.seekTo(currentPos)
                    player?.prepare()
                    player?.play()
                    return
                } else {
                    Log.e(TAG, "Audio discontinuity persisted after $audioDiscontinuityRetryCount attempts. Giving up.")
                }
            }

            Log.e(TAG, "ExoPlayer Error: ${error.message}", error)
            runOnUiThread {
                android.widget.Toast.makeText(this@PlayerActivity, "Error: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        
        override fun onIsPlayingChanged(isPlaying: Boolean) {
             Log.d(TAG, "Is playing: $isPlaying")
        }
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
            "seek_back" -> {
                player?.let {
                    val newPos = maxOf(0L, it.currentPosition - 10_000L)
                    it.seekTo(newPos)
                    showSeekUI()
                }
            }
            "seek_forward" -> {
                player?.let {
                    val dur = it.duration
                    val newPos = if (dur > 0) minOf(dur, it.currentPosition + 10_000L) else it.currentPosition + 10_000L
                    it.seekTo(newPos)
                    showSeekUI()
                }
            }
        }
    }
    
    private fun handleRemoteCommand(key: String?) {
        Log.i(TAG, "Remote command: $key")
        
        // Simulate key events based on remote command
        val keyCode = when (key) {
            "dpad_up" -> KeyEvent.KEYCODE_DPAD_UP
            "dpad_down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "dpad_left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "dpad_right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "dpad_center" -> KeyEvent.KEYCODE_DPAD_CENTER
            "back" -> KeyEvent.KEYCODE_BACK
            else -> null
        }
        
        if (keyCode != null) {
            // Dispatch the key event to this activity
            val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            
            runOnUiThread {
                dispatchKeyEvent(downEvent)
                dispatchKeyEvent(upEvent)
            }
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If controls are visible and we are focused on a navigable element,
        // let the system handle navigation (DPAD directions and CENTER/ENTER)
        if (controlsRoot.visibility == android.view.View.VISIBLE && currentFocus != null) {
            val focusedId = currentFocus?.id
            val isNavigable = focusedId == com.playbridge.receiver.R.id.btn_play_pause || 
                              focusedId == com.playbridge.receiver.R.id.btn_tracks ||
                              focusedId == com.playbridge.receiver.R.id.player_seekbar
            
            if (isNavigable) {
                // Let system handle navigation between buttons and click events
                if (focusedId == com.playbridge.receiver.R.id.player_seekbar && 
                    (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    // Fallthrough to custom scrubbing logic below
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                           keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                           keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    return super.onKeyDown(keyCode, event)
                }
            }
        }

        // Handle TV remote D-pad controls
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                togglePlayPause()
                showControlsUI()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause()
                showControlsUI()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play()
                showControlsUI()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause()
                showControlsUI()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                player?.stop()
                finish()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val repeatCount = event?.repeatCount ?: 0
                val multiplier = if (repeatCount > 10) 5 else 1
                handleScrubbing(-10000L * multiplier) // Accelerate after 10 repeats
                showSeekUI() // Only seekbar
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val repeatCount = event?.repeatCount ?: 0
                val multiplier = if (repeatCount > 10) 5 else 1
                handleScrubbing(10000L * multiplier) // Accelerate after 10 repeats
                showSeekUI() // Only seekbar
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                 // Show full controls on up/down if not already navigating
                 if (!isScrubbing) showControlsUI()
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
    
    private var cachedBitmap: android.graphics.Bitmap? = null

    override fun onPause() {
        super.onPause()
        // Capture bitmap in onPause when the view is definitely still visible
        cachedBitmap = captureBitmap()
    }
    
    override fun onStop() {
        // Use the bitmap captured in onPause
        val bitmap = cachedBitmap
        super.onStop()
        saveProgress(bitmap)
        releasePlayer()
        cachedBitmap = null
    }
    
    private fun saveProgress(thumbnailBitmap: android.graphics.Bitmap? = null) {
        val player = this.player ?: return
        val url = currentUrl
        Log.d(TAG, "Attempting to save progress for $url")
        if (url != null && player.duration > 0 && player.currentPosition > 0) {
            val position = player.currentPosition
            val duration = player.duration
            val title = currentTitle
            val contentType = currentContentType
            val headers = currentHeaders
            
            lifecycleScope.launch {
                withContext(NonCancellable) {
                    var thumbnailPath: String? = null
                    
                    if (thumbnailBitmap != null) {
                        thumbnailPath = saveBitmapToStorage(thumbnailBitmap)
                        Log.d(TAG, "Saved thumbnail to: $thumbnailPath")
                    }

                    Log.d(TAG, "Saving progress: $position / $duration")

                    withContext(Dispatchers.IO) {
                        try {
                            historyStore.saveProgress(url, title, position, duration, contentType, headers, thumbnailPath)
                            Log.d(TAG, "Progress saved successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save progress", e)
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "Not saving: URL=$url, Duration=${player.duration}, Pos=${player.currentPosition}")
        }
    }
    
    private fun captureBitmap(): android.graphics.Bitmap? {
        try {
            val textureView = playerView.videoSurfaceView as? android.view.TextureView
            if (textureView != null) {
                Log.d(TAG, "Capturing bitmap from TextureView")
                return textureView.bitmap
            }
        } catch (e: Exception) {
           Log.e(TAG, "Failed to capture screenshot", e)
        }
        return null
    }

    private suspend fun saveBitmapToStorage(bitmap: android.graphics.Bitmap): String? {
        return withContext(Dispatchers.IO) {
            try {
                val thumbnailsDir = java.io.File(filesDir, "thumbnails")
                if (!thumbnailsDir.exists()) thumbnailsDir.mkdirs()
                
                // Use URL hash as filename
                val filename = "${currentUrl?.hashCode() ?: System.currentTimeMillis()}.jpg"
                val file = java.io.File(thumbnailsDir, filename)
                
                java.io.FileOutputStream(file).use { out ->
                    // Compress nicely
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                }
                
                Log.d(TAG, "Saved bitmap size: ${file.length()} bytes")
                file.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save bitmap", e)
                null
            }
        }
    }
    
    override fun onDestroy() {
        unregisterReceiver(controlReceiver)
        releasePlayer()
        super.onDestroy()
    }
    
    private fun releasePlayer() {
        try {
            player?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing player: ${e.message}", e)
        }
        player = null
    }

    private fun setupControls() {
        playPauseButton.setOnClickListener {
            togglePlayPause()
            showControlsUI() // Keep UI visible on interaction
        }
        
        tracksButton.setOnClickListener {
            showTrackSelectionDialog()
        }
        
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: 0
                    val newPosition = (duration * progress) / 1000
                    timeText.text = formatTime(newPosition, duration)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                hideControlsHandler.removeCallbacks(hideControlsRunnable)
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val duration = player?.duration ?: 0
                val newPosition = (duration * seekBar!!.progress) / 1000
                player?.seekTo(newPosition)
                showSeekUI() // Show just seekbar after seeking
            }
        })
        
        // Handle D-pad events on SeekBar to use our custom scrubbing logic (acceleration + delay)
        // instead of the default SeekBar behavior which is too slow/simple for TV.
        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val repeatCount = event.repeatCount
                        val multiplier = if (repeatCount > 10) 5 else 1
                        handleScrubbing(-10000L * multiplier)
                        showSeekUI()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val repeatCount = event.repeatCount
                        val multiplier = if (repeatCount > 10) 5 else 1
                        handleScrubbing(10000L * multiplier)
                        showSeekUI()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }
    
    private fun formatTime(currentMs: Long, durationMs: Long): String {
        val currentSeconds = currentMs / 1000
        val durationSeconds = durationMs / 1000
        
        val currentStr = String.format("%02d:%02d", currentSeconds / 60, currentSeconds % 60)
        val durationStr = String.format("%02d:%02d", durationSeconds / 60, durationSeconds % 60)
        
        return "$currentStr / $durationStr"
    }
    
    private fun updateProgress() {
        player?.let { p ->
            val duration = p.duration
            val position = p.currentPosition
            
            if (duration > 0) {
                val progress = (1000 * position / duration).toInt()
                seekBar.progress = progress
                timeText.text = formatTime(position, duration)
            }
        }
    }
    
    private fun showControlsUI() {
        controlsRoot.visibility = android.view.View.VISIBLE
        controlsPanel.visibility = android.view.View.VISIBLE
        seekBar.visibility = android.view.View.VISIBLE
        
        updatePlayPauseIcon()
        if (!isScrubbing) updateProgress()
        startUpdateProgress()
        
        // Request focus on Play/Pause button if nothing is focused to enable D-pad navigation
        if (currentFocus == null || currentFocus == playerView) {
             playPauseButton.requestFocus()
        }
        
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 3000)
    }
    
    private fun showSeekUI() {
        controlsRoot.visibility = android.view.View.VISIBLE
        controlsPanel.visibility = android.view.View.GONE // Hide buttons, show only seekbar
        seekBar.visibility = android.view.View.VISIBLE
        
        if (!isScrubbing) updateProgress()
        startUpdateProgress()
        
        hideControlsHandler.removeCallbacks(hideControlsRunnable)
        hideControlsHandler.postDelayed(hideControlsRunnable, 3000)
    }
    
    private fun hideUI() {
        controlsRoot.visibility = android.view.View.GONE
        hideControlsHandler.removeCallbacks(updateProgressRunnable)
    }
    
    private fun startUpdateProgress() {
        hideControlsHandler.removeCallbacks(updateProgressRunnable)
        hideControlsHandler.post(updateProgressRunnable)
    }
    
    private fun togglePlayPause() {
        if (isScrubbing) {
            commitSeek()
            return
        }
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
            updatePlayPauseIcon()
        }
    }
    
    private fun updatePlayPauseIcon() {
        val isPlaying = player?.isPlaying == true
        val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        playPauseButton.setImageResource(iconRes)
    }
    
    private fun handleScrubbing(deltaMs: Long) {
        if (!isScrubbing) {
            isScrubbing = true
            scrubPosition = player?.currentPosition ?: 0
            // Pause playback while scrubbing if desired, or just let it play. 
            // Usually simpler to just update UI target.
        }
        
        val duration = player?.duration ?: 0
        if (duration > 0) {
            scrubPosition = (scrubPosition + deltaMs).coerceIn(0, duration)
            
            // Update UI immediately
            val progress = (1000 * scrubPosition / duration).toInt()
            seekBar.progress = progress
            timeText.text = formatTime(scrubPosition, duration)
            
            // Schedule commit
            commitSeekHandler.removeCallbacks(commitSeekRunnable)
            commitSeekHandler.postDelayed(commitSeekRunnable, 1000) // Wait 1s after last key press to commit
            
            // Reset hide controls timer
            hideControlsHandler.removeCallbacks(hideControlsRunnable)
            hideControlsHandler.postDelayed(hideControlsRunnable, 4000)
        }
    }
    
    private fun commitSeek() {
        if (isScrubbing) {
            Log.i(TAG, "Committing seek to: $scrubPosition")
            player?.seekTo(scrubPosition)
            isScrubbing = false
            commitSeekHandler.removeCallbacks(commitSeekRunnable)
            
            // Force immediate progress update after seek
            updateProgress()
        }
    }

    /**
     * Custom error handling policy to retry on ParserException.
     * Use with caution: infinite retries can cause loops if the stream is truly broken.
     */
    private fun showTrackSelectionDialog() {
        val player = this.player ?: return
        val currentTracks = player.currentTracks
        
        // Pause playback while selecting tracks
        val wasPlaying = player.isPlaying
        if (wasPlaying) player.pause()
        
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val composeView = androidx.compose.ui.platform.ComposeView(this)
        
        // Required for Compose to work inside a Dialog backed by a View
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        
        composeView.setContent {
            PlayBridgeTVTheme {
                TrackSelectionDialog(
                    tracks = currentTracks,
                    trackSelectionParameters = player.trackSelectionParameters,
                    onDismiss = {
                        dialog.dismiss()
                        if (wasPlaying) player.play()
                        showControlsUI()
                    },
                    onTrackSelected = { trackType, format ->
                        applyTrackSelection(trackType, format)
                        android.widget.Toast.makeText(this, "Track selected", android.widget.Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        if (wasPlaying) player.play()
                        showControlsUI()
                    }
                )
            }
        }
        
        dialog.setContentView(composeView)
        dialog.show()
    }
    
    private fun applyTrackSelection(trackType: Int, format: androidx.media3.common.Format?) {
        val player = this.player ?: return
        
        val parametersBuilder = player.trackSelectionParameters.buildUpon()
        
        if (format == null) {
            // Auto / Default / Off
            when (trackType) {
                 androidx.media3.common.C.TRACK_TYPE_VIDEO -> {
                     parametersBuilder
                         .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                         .setMaxVideoSizeSd() // reset to default constraints if any
                         .setMaxVideoBitrate(Int.MAX_VALUE)
                 }
                 androidx.media3.common.C.TRACK_TYPE_AUDIO -> {
                     parametersBuilder
                         .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                         .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
                 }
                 androidx.media3.common.C.TRACK_TYPE_TEXT -> {
                     parametersBuilder
                         .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                         .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true) // Off
                 }
            }
        } else {
            // Specific track selection using TrackSelectionOverride
            // We need to find the specific TrackGroup that contains this format
            val tracks = player.currentTracks
            val groups = tracks.groups.filter { it.type == trackType }
            
            var foundGroup: androidx.media3.common.TrackGroup? = null
            var trackIndex = -1
            
            for (group in groups) {
                for (i in 0 until group.length) {
                    if (group.getTrackFormat(i) == format) {
                        foundGroup = group.mediaTrackGroup
                        trackIndex = i
                        break
                    }
                }
                if (foundGroup != null) break
            }
            
            if (foundGroup != null) {
                parametersBuilder.setOverrideForType(
                    androidx.media3.common.TrackSelectionOverride(foundGroup, trackIndex)
                )
                 parametersBuilder.setTrackTypeDisabled(trackType, false)
            }
        }
        
        Log.i(TAG, "Applied track selection parameters for type $trackType")
        
        player.trackSelectionParameters = parametersBuilder.build()
        
        // Force a hard reset of the player to ensure decoders are flushed and surface is reset.
        // This fixes issues on some TV devices where switching tracks causes artifacts 
        // (looping old video) or scaling issues (new video in top-left corner).
        val currentPos = player.currentPosition
        val currentWindowIndex = player.currentMediaItemIndex
        val playWhenReady = player.playWhenReady
        
        // Stop the player (releases decoders)
        player.stop()
        
        // Re-prepare at the same position
        // Note: We don't need to setMediaItem again as stop(false) retains the playlist.
        // However, some devices might need a full reload if the surface state is corrupted.
        // For now, try simple re-prepare. If that fails, we might need setMediaItem again.
        player.seekTo(currentWindowIndex, currentPos)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    private class CustomLoadErrorHandlingPolicy : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val exception = loadErrorInfo.exception
            
            // Log the error for debugging
            Log.w(TAG, "Load error occurred: ${exception.message}, count: ${loadErrorInfo.errorCount}")
            
            // Retry ParserException which is usually fatal, but can happen if server returns 
            // valid HTTP 200 but invalid content (like an HTML error page) temporarily.
            if (exception is androidx.media3.common.ParserException) {
                // Retry up to 5 times with 1 second delay
                if (loadErrorInfo.errorCount < 5) {
                    Log.w(TAG, "Retrying ParserException (attempt ${loadErrorInfo.errorCount + 1})")
                    return 1000L 
                }
            }
            
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }
}
