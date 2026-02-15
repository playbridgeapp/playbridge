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
import kotlinx.coroutines.Dispatchers
import com.playbridge.receiver.ui.theme.PlayBridgeTVTheme
import com.playbridge.receiver.player.TrackSelectionDialog
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

private const val TAG = "PlayerActivity"

/**
 * Minimal ExoPlayer-based video player activity.
 * Receives play commands from the phone via the ServerService.
 *
 * Delegates to:
 * - [ContentSniffer] for SSL bypass and content type detection
 * - [PlayerControlsManager] for custom controls overlay
 * - [ProgressManager] for progress save/restore and thumbnails
 * - [InputHandler] for D-pad, phone remote, and control commands
 */
class PlayerActivity : ComponentActivity() {
    
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var audioDiscontinuityRetryCount = 0

    // Managers
    private val contentSniffer = ContentSniffer()
    private lateinit var controlsManager: PlayerControlsManager
    private lateinit var progressManager: ProgressManager
    private lateinit var inputHandler: InputHandler
    
    @OptIn(UnstableApi::class)
    private lateinit var subtitleManager: SubtitleManager
    private var subtitleUrls: List<String> = emptyList()

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ServerService.ACTION_CONTROL -> {
                    val command = intent.getStringExtra(ServerService.EXTRA_COMMAND)
                    inputHandler.handleControlCommand(command)
                }
                ServerService.ACTION_REMOTE -> {
                    val key = intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY)
                    inputHandler.handleRemoteCommand(key)
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
                    
                    val subtitles = intent.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)
                    
                    if (url != null) {
                        playVideo(url, title, contentType, headers, subtitles)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "=== PlayerActivity CREATED ===")
        Log.i(TAG, "Intent action: ${intent?.action}")
        Log.i(TAG, "Has URL extra: ${intent?.hasExtra(ServerService.EXTRA_URL)}")
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        
        // Initialize View Bindings
        setContentView(com.playbridge.receiver.R.layout.activity_player)
        
        val historyStore = HistoryStore(applicationContext)
        
        playerView = findViewById(com.playbridge.receiver.R.id.player_view)
        val controlsRoot = findViewById<android.view.View>(com.playbridge.receiver.R.id.controls_root)
        val controlsPanel = findViewById<android.view.View>(com.playbridge.receiver.R.id.controls_panel)
        val seekBar = findViewById<android.widget.SeekBar>(com.playbridge.receiver.R.id.player_seekbar)
        val playPauseButton = findViewById<android.widget.ImageButton>(com.playbridge.receiver.R.id.btn_play_pause)
        val tracksButton = findViewById<android.widget.ImageButton>(com.playbridge.receiver.R.id.btn_tracks)
        val elapsedText = findViewById<android.widget.TextView>(com.playbridge.receiver.R.id.tv_elapsed)
        val remainingText = findViewById<android.widget.TextView>(com.playbridge.receiver.R.id.tv_remaining)
        val titleText = findViewById<android.widget.TextView>(com.playbridge.receiver.R.id.title_text)
        val centerPlayButton = findViewById<android.widget.ImageButton>(com.playbridge.receiver.R.id.btn_center_play)
        val skipBackButton = findViewById<android.widget.ImageButton>(com.playbridge.receiver.R.id.btn_skip_back)
        val skipForwardButton = findViewById<android.widget.ImageButton>(com.playbridge.receiver.R.id.btn_skip_forward)
        val bufferingSpinner = findViewById<android.widget.ProgressBar>(com.playbridge.receiver.R.id.buffering_spinner)
        
        // Initialize SubtitleManager
        val subtitleTextView = findViewById<android.widget.TextView>(com.playbridge.receiver.R.id.subtitle_view)
        subtitleManager = SubtitleManager(subtitleTextView, lifecycleScope)
        subtitleManager.setPlayer { player?.currentPosition ?: 0L }
        
        // Initialize managers
        controlsManager = PlayerControlsManager(
            controlsRoot = controlsRoot,
            controlsPanel = controlsPanel,
            seekBar = seekBar,
            playPauseButton = playPauseButton,
            tracksButton = tracksButton,
            elapsedText = elapsedText,
            remainingText = remainingText,
            titleText = titleText,
            centerPlayButton = centerPlayButton,
            skipBackButton = skipBackButton,
            skipForwardButton = skipForwardButton,
            bufferingSpinner = bufferingSpinner,
            playerProvider = { player },
            onShowTrackSelection = { showTrackSelectionDialog() }
        )
        controlsManager.setupControls()

        progressManager = ProgressManager(
            context = this,
            historyStore = historyStore,
            playerView = playerView,
            lifecycleScope = lifecycleScope,
            playerProvider = { player }
        )

        inputHandler = InputHandler(
            activity = this,
            playerProvider = { player },
            controls = controlsManager
        )
        
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
        
        val subtitles = intent?.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)
        
        if (url != null) {
            Log.i(TAG, "Playing video: $url (title: $title, type: $contentType, subs: $subtitles)")
            playVideo(url, title, contentType, headers, subtitles)
        }
    }
    
    private fun initializePlayer() {
        releasePlayer()

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 30000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.playWhenReady = true
                exoPlayer.addListener(createPlayerListener())
            }
    }

    private fun playVideo(url: String, title: String?, contentType: String? = null, intentHeaders: Map<String, String>? = null, subtitles: ArrayList<String>? = null) {
        Log.i(TAG, "========== PLAY COMMAND RECEIVED ==========")
        Log.i(TAG, "Target URL: $url")
        Log.i(TAG, "Target Title: $title")
        Log.i(TAG, "Raw Headers from Intent: $intentHeaders")
        Log.i(TAG, "Content Type: $contentType")
        Log.i(TAG, "===========================================")

        releasePlayer()
        
        lifecycleScope.launch(Dispatchers.Main) {
            var finalContentType = contentType
            
            Log.d(TAG, "Attempting pre-flight sniff...")
            val sniffedType = contentSniffer.sniffContent(url, intentHeaders)
            if (sniffedType != null) {
                Log.i(TAG, "Pre-flight sniff detected: $sniffedType")
                finalContentType = sniffedType
                android.widget.Toast.makeText(this@PlayerActivity, "Detected: $finalContentType", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                Log.d(TAG, "Pre-flight sniff returned null")
            }
            
            startPlayback(url, title, finalContentType, intentHeaders, subtitles)
        }
    }

    private fun startPlayback(url: String, title: String?, contentType: String?, intentHeaders: Map<String, String>?, subtitles: ArrayList<String>?) {
        Log.i(TAG, "Starting playback with Final Content Type: $contentType")

        progressManager.setCurrentMedia(url, title, contentType, intentHeaders)
        controlsManager.setTitle(title)

        // 1. Prepare Headers
        val requestProperties = HashMap<String, String>()
        intentHeaders?.forEach { (key, value) -> requestProperties[key] = value }

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
        
        val userAgent = intentHeaders?.get("User-Agent") 
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        requestProperties["User-Agent"] = userAgent
        
        Log.i(TAG, "Final Request Headers: $requestProperties")

        // 2. Create OkHttp Data Source Factory
        val okHttpClient = contentSniffer.getUnsafeOkHttpClient()
        val cacheControl = okhttp3.CacheControl.Builder().noCache().noStore().build()

        val okHttpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(requestProperties)
            .setCacheControl(cacheControl)

        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, okHttpDataSourceFactory)

        // 3. Configure Load Control
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 50000, 2500, 5000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
            
        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters()
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
            )
        }

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
        } else {
            Log.i(TAG, "Using DefaultMediaSourceFactory")
             androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(CustomLoadErrorHandlingPolicy())
        }

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
            
        this.subtitleUrls = subtitles ?: emptyList()
        if (subtitleUrls.isNotEmpty()) {
             Log.i(TAG, "Subtitles available for manual selection: ${subtitleUrls.size}")
        }
            
        if (isHls) {
             builder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        } else if (!contentType.isNullOrEmpty()) {
             builder.setMimeType(contentType)
        }
        
        val mediaItem = builder.build()
        val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

        player?.setMediaSource(mediaSource)
        player?.prepare()
        
        // Restore progress from history
        progressManager.restoreProgress(url)
        
        player?.play()
        
        audioDiscontinuityRetryCount = 0
    }

    private fun createPlayerListener() = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                androidx.media3.common.Player.STATE_BUFFERING -> {
                    Log.d(TAG, "Buffering...")
                    controlsManager.showBuffering()
                }
                androidx.media3.common.Player.STATE_READY -> {
                    Log.i(TAG, "Playback ready")
                    controlsManager.hideBuffering()
                    audioDiscontinuityRetryCount = 0
                }
                androidx.media3.common.Player.STATE_ENDED -> {
                    Log.i(TAG, "Playback ended")
                    controlsManager.hideBuffering()
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
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val handled = inputHandler.handleKeyDown(keyCode, event, findViewById(com.playbridge.receiver.R.id.controls_root), currentFocus)
        return if (handled) true else super.onKeyDown(keyCode, event)
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
        cachedBitmap = progressManager.captureBitmap()
    }
    
    override fun onStop() {
        val bitmap = cachedBitmap
        super.onStop()
        progressManager.saveProgress(bitmap)
        releasePlayer()
        cachedBitmap = null
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

    private var currentSubtitleUrl: String? = null

    private fun showTrackSelectionDialog() {
        val player = this.player ?: return
        val currentTracks = player.currentTracks
        
        val wasPlaying = player.isPlaying
        if (wasPlaying) player.pause()
        
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val composeView = androidx.compose.ui.platform.ComposeView(this)
        
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        
        composeView.setContent {
            PlayBridgeTVTheme {
                TrackSelectionDialog(
                    tracks = currentTracks,
                    trackSelectionParameters = player.trackSelectionParameters,
                    subtitleUrls = subtitleUrls,
                    currentSubtitleUrl = currentSubtitleUrl,
                    onDismiss = {
                        dialog.dismiss()
                        if (wasPlaying) player.play()
                        controlsManager.showControlsUI()
                    },
                    onTrackSelected = { trackType, format ->
                        if (trackType == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                            currentSubtitleUrl = null
                            subtitleManager.disable()
                        }
                        
                        applyTrackSelection(trackType, format)
                        android.widget.Toast.makeText(this, "Track selected", android.widget.Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        if (wasPlaying) player.play()
                        controlsManager.showControlsUI()
                    },
                    onExternalSubtitleSelected = { url ->
                        currentSubtitleUrl = url
                        
                        if (url != null) {
                            subtitleManager.loadSubtitle(url)
                            android.widget.Toast.makeText(this, "Subtitle loaded", android.widget.Toast.LENGTH_SHORT).show()
                            applyTrackSelection(androidx.media3.common.C.TRACK_TYPE_TEXT, null) 
                        } else {
                            subtitleManager.disable()
                            applyTrackSelection(androidx.media3.common.C.TRACK_TYPE_TEXT, null)
                        }
                        
                        dialog.dismiss()
                        if (wasPlaying) player.play()
                        controlsManager.showControlsUI()
                    },
                    onPreviewRequest = { url ->
                         subtitleManager.getPreview(url)
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
            when (trackType) {
                 androidx.media3.common.C.TRACK_TYPE_VIDEO -> {
                     parametersBuilder
                         .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
                         .setMaxVideoSizeSd()
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
                         .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                 }
            }
        } else {
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
        
        // Force hard reset to flush decoders and reset surface
        val currentPos = player.currentPosition
        val currentWindowIndex = player.currentMediaItemIndex
        val playWhenReady = player.playWhenReady
        
        player.stop()
        player.seekTo(currentWindowIndex, currentPos)
        player.prepare()
        player.playWhenReady = playWhenReady
    }

    private class CustomLoadErrorHandlingPolicy : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val exception = loadErrorInfo.exception
            
            Log.w(TAG, "Load error occurred: ${exception.message}, count: ${loadErrorInfo.errorCount}")
            
            if (exception is androidx.media3.common.ParserException) {
                if (loadErrorInfo.errorCount < 5) {
                    Log.w(TAG, "Retrying ParserException (attempt ${loadErrorInfo.errorCount + 1})")
                    return 1000L 
                }
            }
            
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }
}
