package com.playbridge.player.player

import com.playbridge.shared.player.PlaybackEngine
import com.playbridge.shared.player.PlaybackState
import com.playbridge.shared.player.ExoPlayerEngine
import com.playbridge.shared.player.VideoFilter
import com.playbridge.shared.player.VideoFilterAndroid
import com.playbridge.shared.player.VideoFilterManager
import com.playbridge.shared.player.M3uParser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.playbridge.player.logging.FileLogger
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.playbridge.player.server.ServerService
import com.playbridge.player.data.HistoryStore
import com.playbridge.player.util.getStringMapExtra
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.media3.common.Tracks
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.playbridge.player.ui.player.PlayerControlsOverlay
import com.playbridge.player.ui.player.PlayerControlsViewModel
import com.playbridge.player.ui.player.PlayerControlsState
import com.playbridge.player.ui.player.SettingsTab
import com.playbridge.player.ui.player.ActiveOverlay
import com.playbridge.player.ui.player.UnifiedTrack

private const val TAG = "ExoPlayerActivity"

/**
 * Minimal ExoPlayer-based video player activity.
 * Receives play commands from the phone via the ServerService.
 *
 * Delegates to:
 * - [ContentSniffer] for SSL bypass and content type detection
 * - [UnifiedControlsManager] for custom controls overlay
 * - [ProgressManager] for progress save/restore and thumbnails
 * - [InputHandler] for D-pad, phone remote, and control commands
 */
class ExoPlayerActivity : PlayerActivity() {

    private var engine: ExoPlayerEngine? = null
    private lateinit var viewModel: com.playbridge.shared.player.PlayerViewModel
    private lateinit var resumeStore: com.playbridge.player.data.HistoryResumeStore
    private var pendingResumePosition: Long = -1L

    private lateinit var playerView: PlayerView

    override fun play() { engine?.play() }
    override fun pause() { engine?.pause() }
    override fun isPlaying(): Boolean = engine?.getExoPlayer()?.isPlaying == true
    override fun getMediaDuration(): Long = engine?.getExoPlayer()?.duration ?: 0L
    override fun getCurrentPosition(): Long = engine?.getExoPlayer()?.currentPosition ?: 0L
    override fun seekTo(position: Long) {
        val player = engine?.getExoPlayer()
        if (player != null && player.playbackState != Player.STATE_IDLE) {
            player.seekTo(position)
        } else {
            FileLogger.d(TAG, "Player not ready, stashing seek position: ${position}ms")
            pendingResumePosition = position
        }
    }
    override fun getVideoSurfaceView(): android.view.SurfaceView? = playerView.videoSurfaceView as? android.view.SurfaceView

    override fun stopPlayback() {
        FileLogger.i(TAG, "stopPlayback() — clearing surface for transition")
        releasePlayer()
        runOnUiThread {
            controlsViewModel.hideControls()
        }
    }

    override fun getPlayerProgressManager(): ProgressManager? = if (::progressManager.isInitialized) progressManager else null

    private var audioDiscontinuityRetryCount = 0
    private var videoDecoderRetryCount = 0
    private var malformedContentRetryCount = 0
    private var stuckBufferRetryCount = 0
    private var networkErrorRetryCount = 0
    private val stuckBufferHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Loop state
    private var isLooping = false

    // VM wiring (Step 5a proxy layer)
    private var vmUiJob: kotlinx.coroutines.Job? = null

    private var composeView: androidx.compose.ui.platform.ComposeView? = null

    // Managers
    private val contentSniffer = ContentSniffer()
    private val controlsViewModel = PlayerControlsViewModel()
    private lateinit var videoFilterManager: VideoFilterManager
    private lateinit var engineAdapter: PlayerEngineAdapter
    private lateinit var progressManager: ProgressManager
    private lateinit var inputHandler: InputHandler

    @OptIn(UnstableApi::class)
    private var subtitleUrls: List<String> = emptyList()

    // Playlist queue / auto-advance — single source of truth, shared with MpvPlayerActivity.
    private val coordinator = PlaybackCoordinator(object : PlaybackCoordinator.Host {
        override fun loadItem(item: playbridge.PlayPayload, displayTitle: String?) {
            displayTitle?.takeIf { it.isNotBlank() }?.let {
                android.widget.Toast.makeText(this@ExoPlayerActivity, it, android.widget.Toast.LENGTH_SHORT).show()
            }
            playVideo(
                url = item.url,
                title = displayTitle,
                contentType = item.content_type,
                detectedBy = item.detected_by,
                intentHeaders = item.headers.takeIf { it.isNotEmpty() },
                subtitles = item.subtitles.takeIf { it.isNotEmpty() }?.let { ArrayList(it) },
            )
            videoFilterManager.reapplyFilter()
            controlsViewModel.hideControls()
        }

        override suspend fun saveProgressBeforeAdvance(captureThumbnail: Boolean) {
            val player = engine?.getExoPlayer()
            val state = player?.playbackState
            val hasPlayed = state == Player.STATE_READY || state == Player.STATE_ENDED || (player?.currentPosition ?: 0L) > 0L
            if (!hasPlayed || player?.playerError != null) return
            try {
                if (captureThumbnail) {
                    syncSelectionsToProgressManager()
                    val bitmap = progressManager.captureBitmapSuspend()
                    progressManager.saveProgress(bitmap)
                } else if (::progressManager.isInitialized) {
                    progressManager.saveProgress()
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to save progress before advance: ${e.message}")
            }
        }

        override fun onPlaylistChanged(items: List<playbridge.PlayPayload>, index: Int) {
            controlsViewModel.updatePlaylistData(items, index)
            broadcastPlaylistStatus()
        }

        override fun showMessage(message: String) {
            android.widget.Toast.makeText(this@ExoPlayerActivity, message, android.widget.Toast.LENGTH_SHORT).show()
        }

        override fun onPlaylistFinished() {
            FileLogger.i(TAG, "Playlist complete — finishing")
            finish()
        }
    })

    /** Live queue (incl. queue_add-appended episodes) for engine switches. */
    override fun playlistSnapshot(): Pair<List<playbridge.PlayPayload>, Int> =
        coordinator.playlist to coordinator.index
    private var lastVolume: Float = 1.0f
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ServerService.ACTION_CONTROL -> {
                    val command = intent.getStringExtra(ServerService.EXTRA_COMMAND)
                    when {
                        command == "loop_on"  -> setLooping(true)
                        command == "loop_off" -> setLooping(false)
                        command?.startsWith("audio_track:") == true ->
                            applyUnifiedTrackSelection("audio", command.removePrefix("audio_track:"))
                        command?.startsWith("sub_track:") == true ->
                            applyUnifiedTrackSelection("sub", command.removePrefix("sub_track:"))
                        command?.startsWith("scaling:") == true ->
                            applyExoScaling(command.removePrefix("scaling:"))
                        command?.startsWith("filter:") == true ->
                            applyExoFilter(command.removePrefix("filter:"))
                        command?.startsWith("filter_custom:") == true ->
                            applyExoCustomFilter(command.removePrefix("filter_custom:"))
                        command?.startsWith("switch_player:") == true ->
                            switchPlayer(command.removePrefix("switch_player:"))
                        else -> inputHandler.handleControlCommand(command)
                    }
                }
                ServerService.ACTION_REMOTE -> {
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        val key = intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY)
                        inputHandler.handleRemoteCommand(key)
                    }
                }
                ServerService.ACTION_PLAY -> {
                    val url = intent.getStringExtra(ServerService.EXTRA_URL)
                    val title = intent.getStringExtra(ServerService.EXTRA_TITLE)
                    val contentType = intent.getStringExtra(ServerService.EXTRA_CONTENT_TYPE)
                    val headers = intent.getStringMapExtra(ServerService.EXTRA_HEADERS)

                    val detectedBy = intent.getStringExtra(ServerService.EXTRA_DETECTED_BY)
                    val subtitles = intent.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)

                    if (url != null) {
                        stopPlayback()
                        playVideo(url, title, contentType, detectedBy, headers, subtitles)
                    }
                }
                ServerService.ACTION_QUEUE_ADD -> {
                    coordinator.queueAdd(ServerService.drainPendingQueueItems())
                    controlsViewModel.setPlaylistVisible(true)
                }
                ServerService.ACTION_PLAYLIST_JUMP -> {
                    val index = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_JUMP_INDEX, -1)
                    if (index >= 0) {
                        FileLogger.i(TAG, "Playlist jump to index: $index")
                        navigationJob?.cancel()
                        navigationJob = lifecycleScope.launch { coordinator.jumpTo(index) }
                    }
                }
                ServerService.ACTION_RESYNC -> {
                    broadcastNowPlayingResync(controlsViewModel)
                    broadcastPlaylistStatus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val state = controlsViewModel.controlsState.value
                if (state.activeOverlay != ActiveOverlay.NONE) {
                    controlsViewModel.hideOverlay()
                } else if (state.isVisible) {
                    controlsViewModel.hideControls()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        FileLogger.i(TAG, "=== ExoPlayerActivity CREATED ===")
        FileLogger.i(TAG, "Intent action: ${intent?.action}")
        FileLogger.i(TAG, "Has URL extra: ${intent?.hasExtra(ServerService.EXTRA_URL)}")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Screen will be kept on dynamically based on playback state

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())

        // Initialize View Bindings
        setContentView(com.playbridge.player.R.layout.activity_player)
        playerView = findViewById(com.playbridge.player.R.id.player_view)

        findViewById<androidx.compose.ui.platform.ComposeView>(com.playbridge.player.R.id.modern_controls_view).apply {
            setContent {
                PlayBridgeTVTheme {
                    val state by controlsViewModel.controlsState.collectAsState()
                    PlayerControlsOverlay(
                        state = state,
                        onTogglePlay = { controlsViewModel.togglePlayPause() },
                        onTrackSelection = { 
                            updateUnifiedTracks()
                            controlsViewModel.showSettings(SettingsTab.AUDIO) 
                        },
                        onPlaylist = { showPlaylistOverlay() },
                        onPrev = { navigationJob?.cancel(); navigationJob = lifecycleScope.launch { coordinator.previous() } },
                        onNext = { navigationJob?.cancel(); navigationJob = lifecycleScope.launch { coordinator.next() } },
                        onFilter = { showVideoFilterOverlay() },
                        onLoop = { setLooping(!isLooping) },
                        onSwitchPlayer = { controlsViewModel.showSwitchPlayer() },
                        onSeek = { controlsViewModel.handleScrubbing(it) },
                        onPrePlayStartNow = {
                            launchJob?.cancel()
                            controlsViewModel.setPrePlayLaunching(true)
                            controlsViewModel.setPrePlay(null)
                        },
                        onPrePlayBack = {
                            launchJob?.cancel()
                            controlsViewModel.setPrePlay(null)
                            ServerService.notifyContextIdle()
                            finish()
                        },
                        onSettingsTabSelected = { controlsViewModel.showSettings(it) },
                        onTrackSelected = { track ->
                            applyUnifiedTrackSelection(track.type, track.id)
                        },
                        onSpeedSelected = { speed ->
                            engine?.getExoPlayer()?.setPlaybackSpeed(speed)
                            controlsViewModel.setPlaybackSpeed(speed)
                        },
                        onScalingSelected = { mode ->
                             val resizeMode = when(mode) {
                                "Fit" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                "Fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                                "Zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                "Fixed Width" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                                "Fixed Height" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                                else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                             }
                             playerView.resizeMode = resizeMode
                             controlsViewModel.setVideoScaling(mode)
                        },
                        onPlaylistItemPicked = { index ->
                            controlsViewModel.hideOverlay()
                            navigationJob?.cancel()
                            navigationJob = lifecycleScope.launch { coordinator.jumpTo(index) }
                        },
                        onPlayerSwitched = { playerId ->
                            controlsViewModel.hideOverlay()
                            switchPlayer(playerId)
                        },
                        onToggleAudioBoost = { controlsViewModel.toggleAudioBoost() },
                        onAdjustSubtitleDelay = { controlsViewModel.adjustSubtitleDelay(it) }
                    )
                }
            }
        }

        val historyStore = HistoryStore(applicationContext)
        resumeStore = com.playbridge.player.data.HistoryResumeStore(historyStore)

        // Force default styling for ExoPlayer's SubtitleView (ignores embedded MKV/SSA styles)
        playerView.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setApplyEmbeddedFontSizes(false)
            setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.2f)
            setBottomPaddingFraction(0.12f)
            setStyle(
                androidx.media3.ui.CaptionStyleCompat(
                    android.graphics.Color.WHITE,
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                    android.graphics.Color.BLACK,
                    android.graphics.Typeface.DEFAULT_BOLD
                )
            )
        }
        val titleText = "" // temporary to avoid compile error if used later as String

        // Initialize VideoFilterManager
        videoFilterManager = VideoFilterManager()

        // Engine adapter for ExoPlayer
        engineAdapter = object : PlayerEngineAdapter {
            override val isPlaying: Boolean get() = engine?.getExoPlayer()?.isPlaying == true
            override val currentPosition: Long get() = engine?.getExoPlayer()?.currentPosition ?: 0L
            override val duration: Long get() = engine?.getExoPlayer()?.duration ?: 0L
            override val bufferedPosition: Long get() = engine?.getExoPlayer()?.bufferedPosition ?: 0L
            override val streamInfo: String? get() = formatExoStreamInfo()
            override val frameRate: Float get() = engine?.getExoPlayer()?.videoFormat?.frameRate ?: 0f
            override val hdrFormat: String? get() = getExoHdrFormat()

            override fun setLoudnessEnhancer(enabled: Boolean) {
                isLoudnessEnhancerEnabled = enabled
                if (enabled) {
                    try {
                        val session = engine?.getExoPlayer()?.audioSessionId ?: 0
                        if (session != 0) {
                            if (loudnessEnhancer == null || loudnessEnhancer!!.id != session) {
                                loudnessEnhancer?.release()
                                loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(session)
                            }
                            loudnessEnhancer?.setTargetGain(2000) // +20dB
                            loudnessEnhancer?.enabled = true
                            FileLogger.i(TAG, "Loudness Enhancer enabled (+20dB) for session $session")
                        }
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Failed to enable Loudness Enhancer", e)
                    }
                } else {
                    loudnessEnhancer?.enabled = false
                    FileLogger.i(TAG, "Loudness Enhancer disabled")
                }
            }

            override fun setSubtitleDelay(delayMs: Long) {
                // Now handled internally by controlsViewModel.subtitleManager
            }

            override fun setPlaybackSpeed(speed: Float) {
                engine?.getExoPlayer()?.setPlaybackSpeed(speed)
            }

            override fun play() { engine?.getExoPlayer()?.play() }
            override fun pause() { engine?.getExoPlayer()?.pause() }
            override fun seekTo(positionMs: Long) { engine?.getExoPlayer()?.seekTo(positionMs) }
        }

        // Initialize managers
        progressManager = ProgressManager(
            context = this,
            historyStore = historyStore,
            lifecycleScope = lifecycleScope,
            playerActivity = this
        )

        inputHandler = InputHandler(
            activity = this,
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            engine = engineAdapter,
            controls = controlsViewModel,
            isExternalOverlayVisible = { controlsViewModel.controlsState.value.prePlayMetadata != null || controlsViewModel.controlsState.value.activeOverlay != ActiveOverlay.NONE }
        )
        
        controlsViewModel.setEngine(engineAdapter, "exo")

        // Register broadcast receiver for control commands
        val filter = IntentFilter().apply {
            addAction(ServerService.ACTION_CONTROL)
            addAction(ServerService.ACTION_REMOTE)
            addAction(ServerService.ACTION_PLAY)
            addAction(ServerService.ACTION_QUEUE_ADD)
            addAction(ServerService.ACTION_PLAYLIST_JUMP)
            addAction(ServerService.ACTION_RESYNC)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }

        // Handle initial intent
        handleIntent(intent)

        // Drain any queue items that arrived before our receiver was registered.
        // Must happen AFTER handleIntent because handleIntent replaces the coordinator's queue.
        coordinator.queueAdd(ServerService.drainPendingQueueItems())
        if (coordinator.hasPlaylist) {
            controlsViewModel.setPlaylistVisible(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        FileLogger.i(TAG, "onNewIntent received")

        launchJob?.cancel()

        stopPlayback()
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        setupPlaybackExtras(intent)

        // Read playlist if present - needed early for button visibility logic
        val isPlaylist = intent?.getBooleanExtra(ServerService.EXTRA_IS_PLAYLIST, false) ?: false
        val inMemoryPlaylist = PlaylistStore.currentPlaylist
        if (isPlaylist && inMemoryPlaylist != null && inMemoryPlaylist.isNotEmpty()) {
            val startIndex = intent?.getIntExtra(ServerService.EXTRA_PLAYLIST_INDEX, 0) ?: 0
            coordinator.setPlaylist(inMemoryPlaylist, startIndex)
            FileLogger.i(TAG, "Playlist loaded: ${coordinator.playlist.size} items, starting at index ${coordinator.index}")
        } else {
            coordinator.setPlaylist(emptyList(), 0)
        }

        // Update button visibility based on playlist. A single video is modelled as an
        // empty queue, so only treat it as a "playlist" (prev/next nav + panel) once
        // there's more than one item.
        val hasPlaylist = coordinator.hasPlaylist

        // Show playlist button when a playlist is active
        controlsViewModel.setPlaylistVisible(hasPlaylist)

        // Sync the phone's episode list to this new content immediately — refreshes
        // it for a new playlist, or clears it when a single video replaces an
        // earlier one (startPlayback also re-broadcasts after the async sniff).
        broadcastPlaylistStatus()

        // Ensure prev/next buttons are visible if a playlist is active.
        if (hasPlaylist) {
            controlsViewModel.setNavigationVisible(true)
        }

        val startPos = intent?.getLongExtra(ServerService.EXTRA_START_POSITION, -1L) ?: -1L
        if (startPos > 0L) {
            FileLogger.i(TAG, "Starting from explicit position: ${startPos}ms (from intent)")
            pendingResumePosition = startPos
        }

        // Standard direct URL path
        // Handle pre-play metadata for pre-buffering via base class
        handlePrePlayMetadata(intent, controlsViewModel)

        val url = intent?.getStringExtra(ServerService.EXTRA_URL)
        val title = intent?.getStringExtra(ServerService.EXTRA_TITLE)
        val contentType = intent?.getStringExtra(ServerService.EXTRA_CONTENT_TYPE)
        val detectedBy = intent?.getStringExtra(ServerService.EXTRA_DETECTED_BY)
        val headers = intent?.getStringMapExtra(ServerService.EXTRA_HEADERS)

        val subtitles = intent?.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)
        // Restore saved selections from history or incoming intent preferences
        intent?.getStringExtra(ServerService.EXTRA_PREFERRED_AUDIO_LANG)?.let {
            preferredAudioLanguage = it
            FileLogger.i(TAG, "Restored preferred audio language: $it")
        }
        intent?.getStringExtra(ServerService.EXTRA_PREFERRED_SUBTITLE_LANG)?.let {
            preferredSubtitleLanguage = it
            FileLogger.i(TAG, "Restored preferred subtitle language: $it")
        }
        intent?.getStringExtra(ServerService.EXTRA_EXTERNAL_SUBTITLE_URL)?.let {
            currentSubtitleUrl = it
            FileLogger.i(TAG, "Restored external subtitle URL: $it")
        }
        intent?.getStringExtra(ServerService.EXTRA_VIDEO_FILTER)?.let { filterName ->
            try {
                val filter = VideoFilter.valueOf(filterName)
                val customValues = intent.getFloatArrayExtra(ServerService.EXTRA_CUSTOM_FILTER_VALUES)
                if (filter == VideoFilter.CUSTOM && customValues != null && customValues.size == 3) {
                    videoFilterManager.applyCustom(customValues[0], customValues[1], customValues[2])
                } else {
                    videoFilterManager.applyFilter(filter)
                }
                FileLogger.i(TAG, "Restored video filter: $filterName")
            } catch (e: IllegalArgumentException) {
                FileLogger.w(TAG, "Unknown video filter: $filterName")
            }
        }

        if (url != null) {
            val baseTitle = title

            val suffix = "(${coordinator.index + 1}/${coordinator.playlist.size})"
            val displayTitle = if (coordinator.hasPlaylist && baseTitle?.contains(suffix) != true) {
                "$baseTitle $suffix"
            } else {
                baseTitle
            }
            FileLogger.i(TAG, "Playing video: $url (title: $displayTitle, type: $contentType, subs: $subtitles, detectedBy: $detectedBy)")
            playVideo(url, displayTitle, contentType, detectedBy, headers, subtitles)
        }
    }

    private fun initializePlayer() {
        releasePlayer()

        engine = ExoPlayerEngine(this).also {
            playerView.player = it.getExoPlayer()
            // In Step 4, we keep some activity-local listeners/logic for now
            it.getExoPlayer()?.addListener(createPlayerListener())
        }
        viewModel = com.playbridge.shared.player.PlayerViewModel(
            engine = engine!!,
            resumeStore = resumeStore,
            scope = lifecycleScope,
        )
        vmUiJob?.cancel()
        vmUiJob = lifecycleScope.launch {
            viewModel.ui.collect { state -> handleVmUiState(state) }
        }

        // NOTE: do NOT feed the queue to the shared PlayerViewModel here. The PlaybackCoordinator
        // is the single source of truth for playlist advancement on the TV receiver; mirroring the
        // playlist into the VM would make its observeEngineState() auto-advance on end-of-stream and
        // race with the coordinator (double-load). The VM stays inert on TV (handleVmUiState logs only).
    }

    private var playJob: kotlinx.coroutines.Job? = null
    /** Serialises Stremio series navigation. Cancelled before each new nav request. */
    private var navigationJob: kotlinx.coroutines.Job? = null

    private fun playVideo(url: String, title: String?, contentType: String? = null, detectedBy: String? = null, intentHeaders: Map<String, String>? = null, subtitles: ArrayList<String>? = null) {
        
        FileLogger.i(TAG, "========== PLAY COMMAND RECEIVED ==========")
        FileLogger.i(TAG, "Target URL: $url")
        FileLogger.i(TAG, "Target Title: $title")
        FileLogger.i(TAG, "Raw Headers from Intent: $intentHeaders")
        FileLogger.i(TAG, "Content Type: $contentType")
        FileLogger.i(TAG, "===========================================")

        releasePlayer()

        playJob?.cancel()
        playJob = lifecycleScope.launch(Dispatchers.Main) {
            var finalContentType = contentType

            // Re-show playerView for the new video
            playerView.visibility = android.view.View.VISIBLE

            FileLogger.d(TAG, "Attempting pre-flight sniff...")
            val sniffedType = contentSniffer.sniffContent(url, intentHeaders)
            if (sniffedType != null) {
                FileLogger.i(TAG, "Pre-flight sniff detected: $sniffedType")
                finalContentType = sniffedType
            } else {
                FileLogger.d(TAG, "Pre-flight sniff returned null")
            }

            // Detect if this is an IPTV playlist and parse it instead of playing directly
            val urlWithoutQuery = url.substringBefore("?")
            val isM3u = finalContentType == androidx.media3.common.MimeTypes.APPLICATION_M3U8 || urlWithoutQuery.endsWith(".m3u") || urlWithoutQuery.endsWith(".m3u8")
            if (isM3u) {
                val parsedPlaylist = M3uParser.fetchAndParseM3u(url, intentHeaders)
                if (parsedPlaylist != null && parsedPlaylist.isNotEmpty()) {
                    FileLogger.i(TAG, "Successfully parsed IPTV M3U playlist with ${parsedPlaylist.size} items")
                    coordinator.setPlaylist(parsedPlaylist, 0)
                    controlsViewModel.setPlaylistVisible(true)

                    val firstItem = parsedPlaylist[0]
                    val displayTitle = if (firstItem.title != null) {
                        "${firstItem.title} (1/${parsedPlaylist.size})"
                    } else {
                        "Item 1/${parsedPlaylist.size}"
                    }
                    startPlayback(
                        firstItem.url,
                        displayTitle,
                        firstItem.content_type,
                        firstItem.detected_by,
                        firstItem.headers.takeIf { it.isNotEmpty() },
                        firstItem.subtitles.takeIf { it.isNotEmpty() }?.let { ArrayList(it) }
                    )
                    return@launch
                }
            }

            controlsViewModel.updateMetadata(
            title = title ?: "",
            subtitle = null,
            streamInfo = engineAdapter.streamInfo,
            hdrFormat = engineAdapter.hdrFormat
        )

        startPlayback(url, title, finalContentType, detectedBy, intentHeaders, subtitles)
        }
    }


    private fun startPlayback(url: String, title: String?, contentType: String?, detectedBy: String?, intentHeaders: Map<String, String>?, subtitles: ArrayList<String>?) {
        FileLogger.i(TAG, "startPlayback() called with url: $url")
        FileLogger.i(TAG, "Starting playback with Final Content Type: $contentType")

        if (engine == null) {
            FileLogger.i(TAG, "engine is null, calling initializePlayer()")
            initializePlayer()
        } else {
            FileLogger.i(TAG, "engine is NOT null")
        }

        // Re-sync the phone's now-playing surface for this new item: refresh the
        // episode list (or clear it for a single video) so it doesn't keep showing
        // the previous series.
        broadcastPlaylistStatus()

        // Build playlist JSON for history persistence
        val plistJson = if (!coordinator.isEmpty) {
            try {
                com.playbridge.shared.protocol.encodePlayPayloadListJson(coordinator.playlist)
            } catch (e: Exception) { null }
        } else null

        applyPlaybackSpeed(currentPlaybackSpeed)
        applyVideoScalingMode(currentVideoScalingMode)

        progressManager.setCurrentMedia(url, title, contentType, intentHeaders, plistJson, coordinator.index)
        controlsViewModel.setTitle(title ?: "")

        val payload = playbridge.PlayPayload(
            url = url,
            title = title,
            headers = intentHeaders ?: emptyMap(),
            content_type = contentType,
            detected_by = detectedBy,
            subtitles = subtitles ?: emptyList(),
            preferred_audio_language = preferredAudioLanguage,
            preferred_subtitle_language = preferredSubtitleLanguage,
            default_video_quality = defaultVideoQuality,
            max_bitrate_cap_mbps = maxBitrateCapMbps
        )

        lifecycleScope.launch {
            // Restore playback position from history if no explicit start position was provided
            if (pendingResumePosition <= 0L) {
                FileLogger.d(TAG, "No explicit start position, attempting to restore from history...")
                progressManager.restoreProgress(url)
            }

            engine?.load(payload)

            // Re-apply activity-specific player settings after engine creates the player
            engine?.getExoPlayer()?.let { exoPlayer ->
                playerView.player = exoPlayer
                exoPlayer.addListener(createPlayerListener())
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }

            // Handle manual subtitles (Nuvio-style Compose rendering)
            this@ExoPlayerActivity.subtitleUrls = subtitles ?: emptyList()
            this@ExoPlayerActivity.subtitleHeaders = intentHeaders
            if (subtitleUrls.isNotEmpty()) {
                val subUrl = subtitleUrls[0]
                currentSubtitleUrl = subUrl
                controlsViewModel.loadExternalSubtitle(subUrl, intentHeaders)
                engine?.setSubtitleTrack(null) // Disable internal
            } else {
                currentSubtitleUrl = null
                controlsViewModel.clearSubtitle()
            }

            startPlaybackWatchdog("exo")
        }
    }

        private fun createPlayerListener() = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = engine?.getExoPlayer() ?: return
            when (playbackState) {
                androidx.media3.common.Player.STATE_BUFFERING -> {
                    FileLogger.d(TAG, "Buffering...")
                    if (player.playWhenReady == true) {
                        controlsViewModel.setBuffering(true)
                        val lastBuffered = engine?.getExoPlayer()?.bufferedPosition ?: 0L

                        scheduleStuckBufferCheck(lastBuffered)
                    }
                }
                androidx.media3.common.Player.STATE_READY -> {
                    FileLogger.i(TAG, "Playback ready")
                    controlsViewModel.setBuffering(false)
                    stuckBufferHandler.removeCallbacksAndMessages(null)
                    cancelPlaybackWatchdog()
                    
                    // Trigger cinematic countdown only after we are connected and ready
                    if (controlsViewModel.controlsState.value.prePlayMetadata != null) {
                        engine?.getExoPlayer()?.playWhenReady = false // Keep paused while buffering
                        triggerPrePlayCountdown(controlsViewModel) {
                            FileLogger.i(TAG, "Countdown finished - starting playback")
                            engine?.getExoPlayer()?.playWhenReady = true
                            controlsViewModel.setPrePlay(null)
                        }
                    } else {
                        engine?.getExoPlayer()?.playWhenReady = true
                    }

                    // Apply Loudness Enhancer if enabled
                    if (isLoudnessEnhancerEnabled) {
                        try {
                            val session = player.audioSessionId
                            if (session != 0) {
                                if (loudnessEnhancer == null || loudnessEnhancer!!.id != session) {
                                    loudnessEnhancer?.release()
                                    loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(session)
                                }
                                loudnessEnhancer?.setTargetGain(2000)
                                loudnessEnhancer?.enabled = true
                            }
                        } catch (e: Exception) { FileLogger.e(TAG, "Loudness re-apply failed", e) }
                    }

                    // Detect and apply refresh rate matching
                    val fps = engine?.getExoPlayer()?.videoFormat?.frameRate ?: 0f
                    if (fps > 0f) {
                        updateRefreshRate(fps)
                    }
                    
                    if (pendingResumePosition > 0L) {
                        FileLogger.i(TAG, "Applying pending resume position: ${pendingResumePosition}ms")
                        player.seekTo(pendingResumePosition)
                        pendingResumePosition = -1L
                    }

                    stuckBufferRetryCount = 0
                    networkErrorRetryCount = 0
                    audioDiscontinuityRetryCount = 0
                    videoDecoderRetryCount = 0
                    malformedContentRetryCount = 0
                }
                androidx.media3.common.Player.STATE_ENDED -> {
                    FileLogger.i(TAG, "Playback ended")
                    controlsViewModel.setBuffering(false)
                    navigationJob?.cancel()
                    navigationJob = lifecycleScope.launch { coordinator.next() }
                }
                androidx.media3.common.Player.STATE_IDLE -> {
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            cancelPlaybackWatchdog()
            handlePlaybackError(error)
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            updateUnifiedTracks()
        }

        private fun handlePlaybackError(error: androidx.media3.common.PlaybackException) {
            val player = engine?.getExoPlayer() ?: return
            FileLogger.e(TAG, "ExoPlayer Error: ${error.message}", error)
            
            // Immediate failover for common fatal startup errors
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED) {
                FileLogger.w(TAG, "Fatal Decoder Error detected — immediate failover to MPV")
                switchPlayer("mpv")
                return
            }
            // Live stream fell behind the available DVR window — seek back to the live edge and resume.
            // Without this, the error falls through to the playlist-skip logic and drops the channel.
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                FileLogger.w(TAG, "Behind live window, seeking to live edge")
                player.seekToDefaultPosition()
                player.prepare()
                return
            }

            // Some streams reach the end without signalling EOS, so instead of STATE_ENDED the
            // player sits "playing but not ending" and Media3's StuckPlayerDetector raises
            // ERROR_CODE_TIMEOUT. When that happens at (or within a few seconds of) the end,
            // treat it as a clean end-of-stream and advance the queue — same as STATE_ENDED.
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_TIMEOUT) {
                val pos = player.currentPosition
                val dur = player.duration
                if (dur > 0 && pos >= dur - 5_000L) {
                    FileLogger.i(TAG, "Timeout at end of stream (${pos}/${dur}ms) — advancing as end-of-stream")
                    navigationJob?.cancel()
                    navigationJob = lifecycleScope.launch { coordinator.next() }
                    return
                }
            }

            val isAudioDiscontinuity = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                                       error.cause is androidx.media3.exoplayer.audio.AudioSink.UnexpectedDiscontinuityException

            if (isAudioDiscontinuity) {
                if (audioDiscontinuityRetryCount < 3) {
                    audioDiscontinuityRetryCount++
                    FileLogger.w(TAG, "Audio discontinuity detected. Attempting recovery (attempt $audioDiscontinuityRetryCount)...")
                    val currentPos = player.currentPosition
                    player.seekTo(currentPos)
                    player.prepare()
                    player.play()
                    return
                } else {
                    FileLogger.e(TAG, "Audio discontinuity persisted after $audioDiscontinuityRetryCount attempts. Giving up.")
                }
            }

            // Decoder init failure (e.g. unsupported codec like EAC3) — clear override, fall back
            val isDecoderInitFailure = error.cause is androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
            if (isDecoderInitFailure) {
                FileLogger.w(TAG, "Decoder init failed for selected track. Falling back to default.")
                runOnUiThread {
                    android.widget.Toast.makeText(this@ExoPlayerActivity, "Audio track not supported, reverting", android.widget.Toast.LENGTH_SHORT).show()
                }
                val currentPos = player.currentPosition
                val playWhenReady = player.playWhenReady
                val params = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
                    .build()
                player.trackSelectionParameters = params
                player.seekTo(currentPos)
                player.prepare()
                player.playWhenReady = playWhenReady
                return
            }

            // Video decoder crash (e.g. codec race on track switch) — retry once
            val isVideoDecoderCrash = error.cause is androidx.media3.exoplayer.video.MediaCodecVideoDecoderException
            if (isVideoDecoderCrash && videoDecoderRetryCount < 1) {
                videoDecoderRetryCount++
                FileLogger.w(TAG, "Video decoder crash detected. Attempting recovery (attempt $videoDecoderRetryCount)...")
                val pos = player.currentPosition
                val play = player.playWhenReady
                player.seekTo(pos)
                player.prepare()
                player.playWhenReady = play
                return
            }

            // Unrecognized Input Format (e.g. WMV) — Transition to the internal MPV player
            // automatically. MPV's software decode handles exotic containers ExoPlayer can't parse.
            val isUnrecognizedFormat = error.cause is androidx.media3.exoplayer.source.UnrecognizedInputFormatException ||
                                       (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
            if (isUnrecognizedFormat) {
                FileLogger.e(TAG, "Unrecognized format detected, automatically transitioning to MpvPlayerActivity")
                val currentUrl = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: ""
                val currentTitle = player.currentMediaItem?.mediaMetadata?.title?.toString()

                // Pause player just in case, though it's likely already stopped due to the error
                player.pause()

                runOnUiThread {
                    android.widget.Toast.makeText(this@ExoPlayerActivity, "Format not supported by ExoPlayer, trying MPV...", android.widget.Toast.LENGTH_SHORT).show()
                    val mpvIntent = Intent(this@ExoPlayerActivity, MpvPlayerActivity::class.java).apply {
                        putExtra(com.playbridge.player.server.ServerService.EXTRA_URL, currentUrl)
                        currentTitle?.let { putExtra(com.playbridge.player.server.ServerService.EXTRA_TITLE, it) }

                        // Pass along subtitle URLs
                        if (subtitleUrls.isNotEmpty()) {
                            putStringArrayListExtra(com.playbridge.player.server.ServerService.EXTRA_SUBTITLES, ArrayList(subtitleUrls))
                        }

                        val currentHeaders = if (!coordinator.isEmpty) {
                            coordinator.playlist.getOrNull(coordinator.index)?.headers ?: emptyMap()
                        } else {
                            intent.getStringMapExtra(com.playbridge.player.server.ServerService.EXTRA_HEADERS) ?: emptyMap()
                        }
                        if (currentHeaders.isNotEmpty()) {
                            putExtra(com.playbridge.player.server.ServerService.EXTRA_HEADERS, HashMap(currentHeaders))
                        }

                        if (!coordinator.isEmpty) {
                            // Refresh the store too — it misses queue_add-appended episodes.
                            PlaylistStore.currentPlaylist = coordinator.playlist
                            putExtra(com.playbridge.player.server.ServerService.EXTRA_IS_PLAYLIST, true)
                            putExtra(com.playbridge.player.server.ServerService.EXTRA_PLAYLIST_INDEX, coordinator.index)
                        }
                    }
                    startActivity(mpvIntent)
                    finish()
                }
                return
            }

            FileLogger.e(TAG, "ExoPlayer Error: ${error.message}", error)

            val isExtractorCrash = error.cause is androidx.media3.exoplayer.upstream.Loader.UnexpectedLoaderException &&
                error.cause?.cause is RuntimeException
            val isMalformedContent =
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                (error.cause as? androidx.media3.common.ParserException)?.contentIsMalformed == true ||
                isExtractorCrash
            if (isMalformedContent && malformedContentRetryCount < 10) {
                malformedContentRetryCount++
                val skipAheadMs = 1000L * malformedContentRetryCount
                val currentPos = player.currentPosition
                FileLogger.w(TAG, "Malformed content at ${currentPos}ms. Seeking forward ${skipAheadMs}ms (attempt $malformedContentRetryCount)...")
                val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                player.seekTo((currentPos + skipAheadMs).coerceAtMost(duration))
                player.prepare()
                player.play()
                return
            }

            // Classify as a transient network/HTTP error
            val isNetworkOrHttpError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                                       error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                                       error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                                       error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                                       error.cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException ||
                                       error.cause is java.net.UnknownHostException ||
                                       error.cause is androidx.media3.datasource.HttpDataSource.HttpDataSourceException ||
                                       error.cause?.javaClass?.simpleName == "PlaylistStuckException" ||
                                       error.message?.contains("timed out") == true

            // Single-stream (non-playlist) network error: re-prepare up to 3 times with a
            // short delay. Covers transient CDN drops and brief Debrid server hiccups.
            if (isNetworkOrHttpError && coordinator.isEmpty && networkErrorRetryCount < 3) {
                networkErrorRetryCount++
                val delayMs = 3000L * networkErrorRetryCount // 3s, 6s, 9s
                FileLogger.w(TAG, "Network error on single stream, retrying in ${delayMs}ms (attempt $networkErrorRetryCount/3)")

                lifecycleScope.launch(Dispatchers.Main) {
                    kotlinx.coroutines.delay(delayMs)
                    val p = engine?.getExoPlayer() ?: return@launch
                    val pos = p.currentPosition
                    p.stop()
                    p.prepare()
                    p.seekTo(pos)
                    p.play()

                }
                return
            }

            // Auto-skip logic for broken links in playlists (e.g., 403 Forbidden, 404 Not Found, Timeout)
            if (!coordinator.isEmpty && isNetworkOrHttpError) {
                FileLogger.w(TAG, "Network/HTTP error detected. Skipping to next item in playlist.")
                runOnUiThread {
                    android.widget.Toast.makeText(this@ExoPlayerActivity, "Link failed, skipping to next channel...", android.widget.Toast.LENGTH_SHORT).show()
                }

                // Mark item as failed in place (IPTV channel failover)
                coordinator.markCurrentFailed()

                // Add a small delay so the user sees the toast before it skips
                navigationJob?.cancel()
                navigationJob = lifecycleScope.launch(Dispatchers.Main) {
                    kotlinx.coroutines.delay(1000)
                    coordinator.next()
                }
                return
            }

            runOnUiThread {
                android.widget.Toast.makeText(this@ExoPlayerActivity, "Error: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            FileLogger.d(TAG, "Is playing: $isPlaying")
            if (isPlaying) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Hide spinner if we paused while buffering
                controlsViewModel.setBuffering(false)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (inputHandler.handleKeyEvent(event.keyCode, event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (engine?.getExoPlayer() == null) {
            initializePlayer()
        }
        // Stream now-playing status + available tracks to the phone (shared,
        // engine-agnostic — works the same for Exo/MPV).
        startNowPlayingBroadcasts(controlsViewModel)
    }

    private var cachedBitmap: android.graphics.Bitmap? = null

    override fun onPause() {
        super.onPause()
        syncSelectionsToProgressManager()
        // Capture thumbnail asynchronously and save progress
        lifecycleScope.launch {
            val bitmap = progressManager.captureBitmapSuspend()
            progressManager.saveProgress(bitmap)
        }
    }

    override fun onStop() {
        super.onStop()
        // Progress save with thumbnail is now handled by onPause asynchronously.
        // We ensure player releases cleanly.
        releasePlayer()
        if (!isFinishing && !isChangingConfigurations) {
            finish()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(controlReceiver)
        controlsViewModel.hideOverlay()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        stuckBufferHandler.removeCallbacksAndMessages(null)
        vmUiJob?.cancel()
        if (::viewModel.isInitialized) {
            viewModel.dispose()
        }
        try {
            engine?.getExoPlayer()?.clearVideoSurface()
            playerView.player = null
            engine?.release()
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error releasing player: ${e.message}", e)
        }
        engine = null
    }

    /**
     * React to [PlayerViewModel] UI state changes (Step 5a).
     *
     * The Activity keeps its own UI state for now; this collector runs in
     * parallel and handles VM-driven transitions (auto-advance, error
     * mapping, pre-play) so the VM becomes load-bearing gradually.
     */
    private fun handleVmUiState(state: com.playbridge.shared.player.PlayerUiState) {
        // Step 5a: Activity still owns all UI updates.  The VM collector runs in
        // parallel purely for logging and future load-bearing migration.
        when (state) {
            is com.playbridge.shared.player.PlayerUiState.Idle ->
                FileLogger.d(TAG, "VM state: Idle")
            is com.playbridge.shared.player.PlayerUiState.Loading ->
                FileLogger.d(TAG, "VM state: Loading ${state.payload.url}")
            is com.playbridge.shared.player.PlayerUiState.Playing ->
                FileLogger.d(TAG, "VM state: Playing")
            is com.playbridge.shared.player.PlayerUiState.Error ->
                FileLogger.d(TAG, "VM state: Error ${state.code}: ${state.message}")
            is com.playbridge.shared.player.PlayerUiState.Ended ->
                FileLogger.d(TAG, "VM state: Ended")
        }
    }

    /**
     * Checks every 5s whether bufferedPosition is advancing. If it hasn't moved at all
     * for two consecutive checks (10s with zero bytes received), the CDN connection is
     * dead and we seek forward 3s to request a new byte range. A 4K file that is simply
     * buffering slowly will always show some advancement and won't trigger this.
     */
    private fun scheduleStuckBufferCheck(lastBuffered: Long) {
        stuckBufferHandler.removeCallbacksAndMessages(null)
        stuckBufferHandler.postDelayed({
            val p = engine?.getExoPlayer() ?: return@postDelayed
            if (p.playbackState != androidx.media3.common.Player.STATE_BUFFERING) return@postDelayed
            val currentBuffered = p.bufferedPosition
            if (currentBuffered > lastBuffered) {
                // Bytes are arriving — reschedule with updated baseline, no seek
                scheduleStuckBufferCheck(currentBuffered)
            } else if (stuckBufferRetryCount < 5) {
                // Zero bytes received in the last 5s — connection is dead
                stuckBufferRetryCount++
                val skipAheadMs = 1000L * stuckBufferRetryCount
                val pos = p.currentPosition
                val duration = p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                val skipTo = (pos + skipAheadMs).coerceAtMost(duration)
                FileLogger.w(TAG, "No bytes received while buffering at ${pos}ms — seeking forward ${skipAheadMs}ms to ${skipTo}ms (attempt $stuckBufferRetryCount)")
                p.seekTo(skipTo)
            }
        }, 5_000L)
    }

    /** Broadcast current queue state to the phone (delegates to the shared base implementation). */
    private fun broadcastPlaylistStatus() {
        broadcastPlaylistStatus(coordinator.playlist, coordinator.index)
    }

    /**
     * Apply an audio/subtitle/video track selection by its [UnifiedTrack] id
     * ("off", "auto", or "groupIndex:trackIndex"). Shared by the TV's own track
     * dialog and phone-originated `audio_track:`/`sub_track:` commands.
     */
    private fun applyUnifiedTrackSelection(type: String, id: String) {
        val player = engine?.getExoPlayer() ?: return
        when (type) {
            "audio", "video", "sub" -> {
                // Choosing an embedded or "Off" subtitle clears any active external one.
                if (type == "sub") {
                    currentSubtitleUrl = null
                    controlsViewModel.clearSubtitle()
                }
                if (id == "auto" || id == "off") {
                    val trackType = when (type) {
                        "audio" -> androidx.media3.common.C.TRACK_TYPE_AUDIO
                        "video" -> androidx.media3.common.C.TRACK_TYPE_VIDEO
                        else -> androidx.media3.common.C.TRACK_TYPE_TEXT
                    }
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(trackType, id == "off")
                        .clearOverridesOfType(trackType)
                        .build()
                } else {
                    // Composite ID: "groupIndex:trackIndex"
                    val parts = id.split(":")
                    if (parts.size == 2) {
                        val groupIdx = parts[0].toIntOrNull() ?: return
                        val trackIdx = parts[1].toIntOrNull() ?: return
                        val group = player.currentTracks.groups.getOrNull(groupIdx) ?: return
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(group.type, false)
                            .setOverrideForType(
                                androidx.media3.common.TrackSelectionOverride(
                                    group.mediaTrackGroup,
                                    trackIdx
                                )
                            )
                            .build()
                    }
                }
            }
            "external_sub" -> {
                // id is the subtitle URL. Activate it in the overlay and turn off embedded text.
                currentSubtitleUrl = id
                controlsViewModel.loadExternalSubtitle(id, subtitleHeaders)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                    .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                    .build()
            }
        }
        updateUnifiedTracks()
    }

    /** Apply a video scaling mode (shared by the TV dialog and phone `scaling:` commands). */
    private fun applyExoScaling(mode: String) {
        playerView.resizeMode = when (mode) {
            "Fit" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            "Fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            "Zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "Fixed Width" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            "Fixed Height" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        controlsViewModel.setVideoScaling(mode)
    }

    /** Apply a preset video filter by enum name (from a phone `filter:` command). */
    private fun applyExoFilter(name: String) {
        val filter = try { VideoFilter.valueOf(name) } catch (e: Exception) { return }
        videoFilterManager.applyFilter(filter)
        controlsViewModel.setVideoFilterState(
            filter,
            videoFilterManager.customBrightness,
            videoFilterManager.customContrast,
            videoFilterManager.customSaturation
        )
    }

    /** Apply a custom video filter "brightness:contrast:saturation" (from a phone `filter_custom:` command). */
    private fun applyExoCustomFilter(args: String) {
        val parts = args.split(":")
        if (parts.size != 3) return
        val b = parts[0].toFloatOrNull() ?: return
        val c = parts[1].toFloatOrNull() ?: return
        val s = parts[2].toFloatOrNull() ?: return
        videoFilterManager.applyCustom(b, c, s)
        controlsViewModel.setVideoFilterState(VideoFilter.CUSTOM, b, c, s)
    }

    /**
     * Enable or disable single-video loop mode.
     */
    private fun setLooping(enabled: Boolean) {
        isLooping = enabled
        engine?.getExoPlayer()?.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        controlsViewModel.setLooping(enabled)
        FileLogger.i(TAG, "Loop mode: $enabled")
    }

    private fun showPlaylistOverlay() {
        val displayItems: List<playbridge.PlayPayload>
        val displayIndex: Int

        if (!coordinator.isEmpty) {
            displayItems = coordinator.playlist
            displayIndex = coordinator.index
        } else {
            return
        }

        val wasPlaying = engine?.getExoPlayer()?.isPlaying == true
        if (wasPlaying) engine?.getExoPlayer()?.pause()

        controlsViewModel.showPlaylist(displayItems, displayIndex)
    }



    private var currentSubtitleUrl: String? = null
    /** Request headers for the current media, reused when (re)loading an external subtitle. */
    private var subtitleHeaders: Map<String, String>? = null
    private var currentPlaybackSpeed: Float = 1.0f
    private var currentVideoScalingMode: Int = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

    // Preferred language for playlists — persists across episodes
    private var preferredAudioLanguage: String? = null
    private var preferredSubtitleLanguage: String? = null

    /**
     * Push current track/filter selections into ProgressManager so the next
     * saveProgress() call persists them to history.
     */
    private fun syncSelectionsToProgressManager() {
        val customValues = if (videoFilterManager.currentFilter == VideoFilter.CUSTOM) {
            listOf(videoFilterManager.customBrightness, videoFilterManager.customContrast, videoFilterManager.customSaturation)
        } else null

        progressManager.updateSelections(
            preferredAudioLanguage = preferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            externalSubtitleUrl = currentSubtitleUrl,
            videoFilter = videoFilterManager.currentFilter.name,
            customFilterValues = customValues,
            playbackSpeed = currentPlaybackSpeed,
            videoScalingMode = currentVideoScalingMode
        )
    }

    private fun applyPlaybackSpeed(speed: Float) {
        currentPlaybackSpeed = speed
        engine?.getExoPlayer()?.playbackParameters = androidx.media3.common.PlaybackParameters(speed)
    }

    private fun applyVideoScalingMode(mode: Int) {
        currentVideoScalingMode = mode
        playerView.resizeMode = mode
    }

    override fun showVideoFilterDialog() {
        showVideoFilterOverlay()
    }

    private fun showVideoFilterOverlay() {
        val wasPlaying = engine?.getExoPlayer()?.isPlaying == true
        if (wasPlaying) engine?.getExoPlayer()?.pause()

        val currentFilter = videoFilterManager.currentFilter
        val brightness = videoFilterManager.customBrightness
        val contrast = videoFilterManager.customContrast
        val saturation = videoFilterManager.customSaturation

        // Get a screenshot frame from the video for preview
        getVideoSurfaceView()?.let { surfaceView ->
            try {
                val bitmap = android.graphics.Bitmap.createBitmap(surfaceView.width, surfaceView.height, android.graphics.Bitmap.Config.ARGB_8888)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.view.PixelCopy.request(surfaceView, bitmap, { result ->
                        if (result == android.view.PixelCopy.SUCCESS) {
                            controlsViewModel.showVideoFilter(currentFilter, brightness, contrast, saturation, bitmap)
                        } else {
                            controlsViewModel.showVideoFilter(currentFilter, brightness, contrast, saturation, null)
                        }
                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                } else {
                    controlsViewModel.showVideoFilter(currentFilter, brightness, contrast, saturation, null)
                }
            } catch (e: Exception) {
                controlsViewModel.showVideoFilter(currentFilter, brightness, contrast, saturation, null)
            }
        } ?: run {
            controlsViewModel.showVideoFilter(currentFilter, brightness, contrast, saturation, null)
        }
    }

    private fun showTrackSelectionDialog() {
        updateUnifiedTracks()
        controlsViewModel.showSettings(SettingsTab.AUDIO)
    }


    private fun applyTrackSelection(trackType: Int, format: androidx.media3.common.Format?) {
        val player = engine?.getExoPlayer() ?: return

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

        FileLogger.i(TAG, "Applied track selection parameters for type $trackType")

        player.trackSelectionParameters = parametersBuilder.build()

        // Only do a hard reset for video track changes (codec reinit required).
        // Audio/subtitle tracks switch seamlessly via trackSelectionParameters.
        if (trackType == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
            val currentPos = player.currentPosition
            val currentWindowIndex = player.currentMediaItemIndex
            val playWhenReady = player.playWhenReady

            player.stop()
            player.seekTo(currentWindowIndex, currentPos)
            player.prepare()
            player.playWhenReady = playWhenReady
        }
    }

            private fun getExoHdrFormat(): String? {
        val format = engine?.getExoPlayer()?.videoFormat ?: return null
        val colorInfo = format.colorInfo ?: return null
        
        return when (colorInfo.colorTransfer) {
            androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "HDR10"
            androidx.media3.common.C.COLOR_TRANSFER_HLG -> "HLG"
            // Dolby Vision is often signaled via mime type or specific profiles in Media3,
            // but ST2084 is the common transfer. We check mime as well.
            else -> {
                if (format.sampleMimeType?.contains("dvhe") == true || format.sampleMimeType?.contains("dvh1") == true) {
                    "Dolby Vision"
                } else if (colorInfo.colorTransfer != androidx.media3.common.C.COLOR_TRANSFER_SDR && colorInfo.colorTransfer != -1) {
                    "HDR"
                } else {
                    null
                }
            }
        }
    }

    private fun formatExoStreamInfo(): String? {
        val player = engine?.getExoPlayer() ?: return null
        val parts = mutableListOf<String>()

        var videoFormat: androidx.media3.common.Format? = null
        var audioFormat: androidx.media3.common.Format? = null
        for (group in player.currentTracks.groups) {
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    val fmt = group.getTrackFormat(i)
                    when (group.type) {
                        androidx.media3.common.C.TRACK_TYPE_VIDEO -> if (videoFormat == null) videoFormat = fmt
                        androidx.media3.common.C.TRACK_TYPE_AUDIO -> if (audioFormat == null) audioFormat = fmt
                    }
                }
            }
        }

        if (videoFormat != null) {
            if (videoFormat.height != androidx.media3.common.Format.NO_VALUE) {
                parts.add("${videoFormat.height}p")
            }
            videoFormat.codecs?.let { codec ->
                val shortCodec = when {
                    codec.startsWith("avc") -> "H.264"
                    codec.startsWith("hvc") || codec.startsWith("hev") -> "H.265"
                    codec.startsWith("vp9") || codec.startsWith("vp09") -> "VP9"
                    codec.startsWith("av01") -> "AV1"
                    else -> codec.uppercase()
                }
                parts.add(shortCodec)
            }
            if (videoFormat.bitrate != androidx.media3.common.Format.NO_VALUE && videoFormat.bitrate > 0) {
                parts.add("%.1f Mbps".format(videoFormat.bitrate / 1_000_000f))
            }
        }

        if (audioFormat != null) {
            val audioParts = mutableListOf<String>()
            audioFormat.codecs?.let { codec ->
                val shortCodec = when {
                    codec.startsWith("mp4a") -> "AAC"
                    codec.startsWith("ac-3") || codec == "ac3" -> "AC3"
                    codec.startsWith("ec-3") || codec == "eac3" -> "EAC3"
                    codec.startsWith("dtsc") || codec.startsWith("dtsh") || codec.startsWith("dtse") -> "DTS"
                    codec.startsWith("opus") -> "Opus"
                    codec.startsWith("flac") -> "FLAC"
                    else -> codec.uppercase()
                }
                audioParts.add(shortCodec)
            }
            if (audioFormat.channelCount != androidx.media3.common.Format.NO_VALUE) {
                val chLabel = when (audioFormat.channelCount) {
                    1 -> "Mono"
                    2 -> "Stereo"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> "${audioFormat.channelCount}ch"
                }
                audioParts.add(chLabel)
            }
            audioFormat.language?.let { lang ->
                if (lang.isNotBlank() && lang != "und") {
                    audioParts.add(lang.uppercase())
                }
            }
            if (audioParts.isNotEmpty()) {
                parts.add("\uD83D\uDD0A " + audioParts.joinToString(" "))
            }
        }

        return if (parts.isNotEmpty()) parts.joinToString("  •  ") else null
    }

    private class CustomLoadErrorHandlingPolicy : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val exception = loadErrorInfo.exception

            FileLogger.w(TAG, "Load error occurred: ${exception.message}, count: ${loadErrorInfo.errorCount}")

            if (exception is androidx.media3.common.ParserException) {
                if (exception.contentIsMalformed) {
                    FileLogger.w(TAG, "Malformed content, escalating to player-level recovery")
                    return androidx.media3.common.C.TIME_UNSET
                }
                if (loadErrorInfo.errorCount < 5) {
                    FileLogger.w(TAG, "Retrying ParserException (attempt ${loadErrorInfo.errorCount + 1})")
                    return 1000L
                }
            }

            // Extractor crash (e.g. ArrayIndexOutOfBounds from EBML integer overflow) — same as
            // malformed content, escalate immediately rather than retrying the same corrupt bytes.
            if (exception is androidx.media3.exoplayer.upstream.Loader.UnexpectedLoaderException &&
                exception.cause is RuntimeException
            ) {
                FileLogger.w(TAG, "Extractor crash, escalating to player-level recovery")
                return androidx.media3.common.C.TIME_UNSET
            }

            if (exception is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                val code = exception.responseCode
                if (code == 429 || code == 502 || code == 503) {
                    if (loadErrorInfo.errorCount < 5) {
                        val delayMs = minOf(1000L shl loadErrorInfo.errorCount, 10_000L) // 1s, 2s, 4s, 8s, 10s
                        FileLogger.w(TAG, "HTTP $code from server, backing off ${delayMs}ms (attempt ${loadErrorInfo.errorCount + 1}/5)")
                        return delayMs
                    }
                }
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private fun updateUnifiedTracks() {
        val player = engine?.getExoPlayer() ?: return
        val tracks = player.currentTracks
        val params = player.trackSelectionParameters

        fun mapTracks(type: Int, typeStr: String): List<UnifiedTrack> {
            val list = mutableListOf<UnifiedTrack>()
            
            // Add "Auto/Off"
            val isDisabled = params.disabledTrackTypes.contains(type)
            val hasOverride = tracks.groups.any { it.type == type && params.overrides.containsKey(it.mediaTrackGroup) }
            
            if (type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                list.add(UnifiedTrack("off", "Off", isDisabled, typeStr))
            } else {
                list.add(UnifiedTrack("auto", "Auto / Default", !hasOverride && !isDisabled, typeStr))
            }

            tracks.groups.filter { it.type == type }.forEachIndexed { groupIdx, group ->
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = group.isTrackSelected(i)
                    val name = buildTrackName(format)
                    // Find actual index in tracks.groups
                    val actualGroupIdx = tracks.groups.indexOf(group)
                    list.add(UnifiedTrack("$actualGroupIdx:$i", name, isSelected, typeStr))
                }
            }
            return list
        }

        // Tracks are broadcast to the phone by the shared collector in
        // PlayerActivity, which observes these controlsViewModel updates.
        controlsViewModel.updateTracks(
            audio = mapTracks(androidx.media3.common.C.TRACK_TYPE_AUDIO, "audio"),
            subtitles = buildSubtitleTracks(tracks, params),
            video = mapTracks(androidx.media3.common.C.TRACK_TYPE_VIDEO, "video")
        )
        controlsViewModel.setPlaybackSpeed(player.playbackParameters.speed)
        
        val scalingMode = when(playerView.resizeMode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> "Fixed Width"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "Fixed Height"
            else -> "Fit"
        }
        controlsViewModel.setVideoScaling(scalingMode)
    }

    /**
     * Subtitle track list: "Off" + embedded (in-media) text tracks + externally side-loaded
     * subtitles ([subtitleUrls]). Exo renders externals via a custom overlay rather than as
     * player tracks, so they have to be added here explicitly (the separator in the UI groups
     * them under "External subtitles").
     */
    private fun buildSubtitleTracks(
        tracks: androidx.media3.common.Tracks,
        params: androidx.media3.common.TrackSelectionParameters
    ): List<UnifiedTrack> {
        val type = androidx.media3.common.C.TRACK_TYPE_TEXT
        val isDisabled = params.disabledTrackTypes.contains(type)

        val embedded = mutableListOf<UnifiedTrack>()
        tracks.groups.filter { it.type == type }.forEach { group ->
            val actualGroupIdx = tracks.groups.indexOf(group)
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                // An embedded track only counts as selected when no external sub is active.
                val selected = group.isTrackSelected(i) && currentSubtitleUrl == null
                embedded.add(UnifiedTrack("$actualGroupIdx:$i", buildTrackName(format), selected, "sub"))
            }
        }

        val external = subtitleUrls.map { url ->
            UnifiedTrack(url, externalSubtitleName(url), url == currentSubtitleUrl, "external_sub")
        }

        val offSelected = isDisabled && currentSubtitleUrl == null
        return listOf(UnifiedTrack("off", "Off", offSelected, "sub")) + embedded + external
    }

    private fun buildTrackName(format: androidx.media3.common.Format): String {
        val items = mutableListOf<String>()
        if (format.height != androidx.media3.common.Format.NO_VALUE) items.add("${format.height}p")
        format.label?.let { if (it.isNotEmpty()) items.add(it) }
        format.language?.let { if (it.isNotEmpty()) items.add(it.uppercase()) }
        if (format.bitrate != androidx.media3.common.Format.NO_VALUE) items.add("${format.bitrate / 1000} kbps")
        return if (items.isEmpty()) format.id ?: "Unknown" else items.joinToString(" • ")
    }
}
