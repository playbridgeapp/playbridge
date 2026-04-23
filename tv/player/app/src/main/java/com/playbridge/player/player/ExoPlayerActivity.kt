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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import com.playbridge.player.player.TrackSelectionDialog
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
    override fun seekTo(position: Long) { engine?.getExoPlayer()?.seekTo(position) }
    override fun getVideoSurfaceView(): android.view.SurfaceView? = playerView.videoSurfaceView as? android.view.SurfaceView

    override fun stopPlayback() {
        FileLogger.i(TAG, "stopPlayback() — clearing surface for transition")
        releasePlayer()
        runOnUiThread {
            playerView.visibility = android.view.View.INVISIBLE
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

    // Pre-play (metadata resolution & pre-buffering)
    private var prePlayPayload by mutableStateOf<com.playbridge.shared.protocol.ContentPlayPayload?>(null)
    private var isPrePlayLaunching by mutableStateOf(false)
    private var prePlayCountdown by mutableIntStateOf(0)
    private var isPreBuffering = false
    private lateinit var composeView: androidx.compose.ui.platform.ComposeView
    private var resolutionJob: kotlinx.coroutines.Job? = null
    private var launchJob: kotlinx.coroutines.Job? = null

    // Managers
    private val contentSniffer = ContentSniffer()
    private lateinit var controlsManager: UnifiedControlsManager
    private lateinit var videoFilterManager: VideoFilterManager
    private lateinit var progressManager: ProgressManager
    private lateinit var inputHandler: InputHandler

    @OptIn(UnstableApi::class)
    private lateinit var subtitleManager: SubtitleManager
    private var subtitleUrls: List<String> = emptyList()

    // Playlist queue for auto-advancing through episodes
    private var playlistItems: MutableList<com.playbridge.shared.protocol.PlayPayload> = mutableListOf()
    private var playlistIndex: Int = 0

    private var activeDialog: android.app.Dialog? = null

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ServerService.ACTION_CONTROL -> {
                    val command = intent.getStringExtra(ServerService.EXTRA_COMMAND)
                    when (command) {
                        "loop_on"  -> setLooping(true)
                        "loop_off" -> setLooping(false)
                        else       -> inputHandler.handleControlCommand(command)
                    }
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

                    val detectedBy = intent.getStringExtra(ServerService.EXTRA_DETECTED_BY)
                    val subtitles = intent.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)

                    if (url != null) {
                        stopPlayback()
                        playVideo(url, title, contentType, detectedBy, headers, subtitles)
                    }
                }
                ServerService.ACTION_QUEUE_ADD -> {
                    ServerService.drainPendingQueueItems().forEach { payload ->
                        playlistItems.add(payload)
                        FileLogger.i(TAG, "Queue add: ${payload.title ?: payload.url} — playlist now has ${playlistItems.size} items")
                    }
                    controlsManager.setPlaylistVisible(true)
                    broadcastPlaylistStatus()
                }
                ServerService.ACTION_PLAYLIST_JUMP -> {
                    val index = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_JUMP_INDEX, -1)
                    if (index >= 0) {
                        FileLogger.i(TAG, "Playlist jump to index: $index")
                        playItemAtIndex(index) // broadcasts status internally after updating playlistIndex
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FileLogger.i(TAG, "=== ExoPlayerActivity CREATED ===")
        FileLogger.i(TAG, "Intent action: ${intent?.action}")
        FileLogger.i(TAG, "Has URL extra: ${intent?.hasExtra(ServerService.EXTRA_URL)}")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Screen will be kept on dynamically based on playback state

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }

        // Initialize View Bindings
        setContentView(com.playbridge.player.R.layout.activity_player)

        playerView = findViewById(com.playbridge.player.R.id.player_view)
        composeView = findViewById(com.playbridge.player.R.id.preplay_compose_view)
        composeView.setContent {
            val p = prePlayPayload
            if (p != null) {
                com.playbridge.player.preplay.PrePlayScreen(
                    payload = p,
                    isLaunching = isPrePlayLaunching,
                    launchCountdown = prePlayCountdown,
                    onStreamSelected = { stream ->
                        // Manually selecting a stream during pre-play
                        resolutionJob?.cancel()
                        playVideoAfterResolution(stream.url, p)
                    },
                    onBack = {
                        resolutionJob?.cancel()
                        prePlayPayload = null
                        composeView.visibility = android.view.View.GONE
                        ServerService.notifyContextIdle()
                        finish()
                    }
                )
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
        val controlsRoot = findViewById<android.view.View>(com.playbridge.player.R.id.controls_root)
        val controlsPanel = findViewById<android.view.View>(com.playbridge.player.R.id.controls_panel)
        val seekBar = findViewById<android.widget.SeekBar>(com.playbridge.player.R.id.player_seekbar)
        val playPauseButton = findViewById<android.widget.ImageButton>(com.playbridge.player.R.id.btn_play_pause)
        val tracksButton = findViewById<android.widget.ImageButton>(com.playbridge.player.R.id.btn_tracks)
        val playlistButton = findViewById<android.widget.ImageButton>(com.playbridge.player.R.id.btn_playlist)
        val streamsButton = findViewById<android.widget.ImageButton>(com.playbridge.player.R.id.btn_streams)
        val prevButton = findViewById<android.widget.ImageButton>(com.playbridge.player.R.id.btn_prev)
        val nextButton = findViewById<android.widget.ImageButton>(com.playbridge.player.R.id.btn_next)
        val filterButton = findViewById<android.widget.ImageButton>(com.playbridge.player.R.id.btn_filter)
        val streamInfoText = findViewById<android.widget.TextView>(com.playbridge.player.R.id.tv_stream_info)
        val seasonInfoText = findViewById<android.widget.TextView>(com.playbridge.player.R.id.tv_season_info)
        val elapsedText = findViewById<android.widget.TextView>(com.playbridge.player.R.id.tv_elapsed)
        val remainingText = findViewById<android.widget.TextView>(com.playbridge.player.R.id.tv_remaining)
        val titleText = findViewById<android.widget.TextView>(com.playbridge.player.R.id.title_text)
        val bufferingSpinner = findViewById<android.widget.ProgressBar>(com.playbridge.player.R.id.buffering_spinner)

        // Initialize SubtitleManager
        val subtitleTextView = findViewById<android.widget.TextView>(com.playbridge.player.R.id.subtitle_view)
        subtitleManager = SubtitleManager(subtitleTextView, lifecycleScope)
        subtitleManager.setPlayer { engine?.getExoPlayer()?.currentPosition ?: 0L }

        // Initialize VideoFilterManager
        videoFilterManager = VideoFilterManager()

        val loopButton = findViewById<android.widget.ImageButton>(com.playbridge.player.R.id.btn_loop)
        val switchPlayerButton = findViewById<android.widget.ImageButton>(com.playbridge.player.R.id.btn_switch_player)

        // Engine adapter for ExoPlayer
        val engineAdapter = object : PlayerEngineAdapter {
            override val isPlaying: Boolean get() = engine?.getExoPlayer()?.isPlaying == true
            override val currentPosition: Long get() = engine?.getExoPlayer()?.currentPosition ?: 0L
            override val duration: Long get() = engine?.getExoPlayer()?.duration ?: 0L
            override val bufferedPosition: Long get() = engine?.getExoPlayer()?.bufferedPosition ?: 0L
            override val streamInfo: String? get() = formatExoStreamInfo()
            override val frameRate: Float get() = engine?.getExoPlayer()?.videoFormat?.frameRate ?: 0f

            override fun play() { engine?.getExoPlayer()?.play() }
            override fun pause() { engine?.getExoPlayer()?.pause() }
            override fun seekTo(positionMs: Long) { engine?.getExoPlayer()?.seekTo(positionMs) }
        }

        // Initialize managers
        controlsManager = UnifiedControlsManager(
            controlsRoot = controlsRoot,
            controlsPanel = controlsPanel,
            seekBar = seekBar,
            playPauseButton = playPauseButton,
            tracksButton = tracksButton,
            playlistButton = playlistButton,
            streamsButton = streamsButton,
            prevButton = prevButton,
            nextButton = nextButton,
            filterButton = filterButton,
            loopButton = loopButton,
            switchPlayerButton = switchPlayerButton,
            streamInfoText = streamInfoText,
            seasonInfoText = seasonInfoText,
            elapsedText = elapsedText,
            remainingText = remainingText,
            titleText = titleText,
            bufferingSpinner = bufferingSpinner,
            engine = engineAdapter,
            engineType = "ExoPlayer",
            onShowTrackSelection = { showTrackSelectionDialog() },
            onShowPlaylist = { showPlaylistPicker() },
            onShowStreams = { showStreamSelectionDialog() },
            onShowFilter = { showVideoFilterDialog() },
            onSwitchPlayer = { showSwitchPlayerDialog("internal_exo") },
            onPrevious = { playPreviousInPlaylist() },
            onNext = { playNextInPlaylist() },
            onToggleLoop = { setLooping(!isLooping) }
        )

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
            controls = controlsManager,
            isExternalOverlayVisible = { prePlayPayload != null || activeDialog != null }
        )

        // Register broadcast receiver for control commands
        val filter = IntentFilter().apply {
            addAction(ServerService.ACTION_CONTROL)
            addAction(ServerService.ACTION_REMOTE)
            addAction(ServerService.ACTION_PLAY)
            addAction(ServerService.ACTION_QUEUE_ADD)
            addAction(ServerService.ACTION_PLAYLIST_JUMP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }

        // Handle initial intent
        handleIntent(intent)

        // Drain any queue items that arrived before our receiver was registered.
        // Must happen AFTER handleIntent because handleIntent replaces playlistItems.
        ServerService.drainPendingQueueItems().forEach { payload ->
            playlistItems.add(payload)
            FileLogger.i(TAG, "Queue add (startup drain): ${payload.title ?: payload.url}")
        }
        if (playlistItems.isNotEmpty()) {
            controlsManager.setPlaylistVisible(true)
            broadcastPlaylistStatus()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        FileLogger.i(TAG, "onNewIntent received")

        // Cancel any pending resolution or countdown from a previous intent
        resolutionJob?.cancel()
        launchJob?.cancel()
        isPrePlayLaunching = false
        isPreBuffering = false

        stopPlayback()
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        setupSeriesNavigator(intent)

        // Read playlist if present - needed early for button visibility logic
        val isPlaylist = intent?.getBooleanExtra(ServerService.EXTRA_IS_PLAYLIST, false) ?: false
        val inMemoryPlaylist = PlaylistStore.currentPlaylist
        if (isPlaylist && inMemoryPlaylist != null && inMemoryPlaylist.isNotEmpty()) {
            playlistItems = inMemoryPlaylist.toMutableList()
            playlistIndex = intent?.getIntExtra(ServerService.EXTRA_PLAYLIST_INDEX, 0) ?: 0
            FileLogger.i(TAG, "Playlist loaded: ${playlistItems.size} items, starting at index $playlistIndex")
        } else {
            playlistItems = mutableListOf()
        }

        // Update button visibility based on navigator and playlist
        val hasPlaylist = playlistItems.isNotEmpty()
        val hasEpisodeList = seriesNavigator?.episodeList?.isNotEmpty() == true
        val isSeries = seriesNavigator?.contentType == "series"

        // Show playlist button when a playlist is active OR series navigator has list mode
        controlsManager.setPlaylistVisible(hasPlaylist || hasEpisodeList)

        // Show streams button when series navigator is active
        controlsManager.setStreamsVisible(seriesNavigator != null)

        seriesNavigator?.let { nav ->
            if (nav.contentType == "series") {
                val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                controlsManager.setSeasonInfo(seasonInfo)
            } else {
                controlsManager.setSeasonInfo(null)
            }
        }

        // Ensure prev/next buttons are visible for ANY series (including optimistic mode)
        // or if a playlist is active. Movies with no playlist will have them hidden by setPlaylistVisible(false).
        if (isSeries || hasPlaylist) {
            controlsManager.setNavigationVisible(true)
        }

        val payloadJson = intent?.getStringExtra(ServerService.EXTRA_CONTENT_PAYLOAD)
        if (payloadJson != null) {
            try {
                val p = com.playbridge.shared.protocol.protocolJson.decodeFromString(
                    com.playbridge.shared.protocol.ContentPlayPayload.serializer(),
                    payloadJson
                )
                prePlayPayload = p
                composeView.visibility = android.view.View.VISIBLE
                resolveStreamsAndPreBuffer(p)
                return // resolveStreamsAndPreBuffer handles the rest
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to parse ContentPlayPayload", e)
            }
        }
        val startPos = intent?.getLongExtra("extra_start_position", -1L) ?: -1L
        if (startPos > 0L) {
            FileLogger.i(TAG, "Starting from explicit position: ${startPos}ms (from intent)")
            pendingResumePosition = startPos
        }

        // Standard direct URL path
        composeView.visibility = android.view.View.GONE
        prePlayPayload = null

        val url = intent?.getStringExtra(ServerService.EXTRA_URL)
        val title = intent?.getStringExtra(ServerService.EXTRA_TITLE)
        val contentType = intent?.getStringExtra(ServerService.EXTRA_CONTENT_TYPE)
        val detectedBy = intent?.getStringExtra(ServerService.EXTRA_DETECTED_BY)
        val headers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getSerializableExtra(ServerService.EXTRA_HEADERS, HashMap::class.java) as? Map<String, String>
        } else {
            @Suppress("DEPRECATION")
            intent?.getSerializableExtra(ServerService.EXTRA_HEADERS) as? Map<String, String>
        }

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
            val nav = seriesNavigator
            val baseTitle = if (nav != null && nav.seriesTitle != null) {
                nav.seriesTitle
            } else {
                title
            }

            val suffix = "(${playlistIndex + 1}/${playlistItems.size})"
            val displayTitle = if (playlistItems.isNotEmpty() && baseTitle?.contains(suffix) != true) {
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

        // 5a proxy: sync Activity-owned state into the VM so it stays consistent.
        if (playlistItems.isNotEmpty()) {
            viewModel.setPlaylist(playlistItems, playlistIndex)
        }
        seriesNavigator?.let { viewModel.setSeriesNavigator(it) }
    }

    private var playJob: kotlinx.coroutines.Job? = null
    /** Serialises Stremio series navigation. Cancelled before each new nav request. */
    private var navigationJob: kotlinx.coroutines.Job? = null

    private fun playVideo(url: String, title: String?, contentType: String? = null, detectedBy: String? = null, intentHeaders: Map<String, String>? = null, subtitles: ArrayList<String>? = null) {
        // Only rebuild the navigator on cold start / onNewIntent. During Stremio episode
        // switches the navigator already exists with its advanced currentIndex — rebuilding
        // from the intent would throw away that state and cause index drift.
        if (seriesNavigator == null) {
            setupSeriesNavigator(intent)
        }
        if (seriesNavigator == null) {
            controlsManager.setSeasonInfo(null)
        }
        controlsManager.setStreamsVisible(seriesNavigator != null)
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
                    playlistItems = parsedPlaylist.toMutableList()
                    playlistIndex = 0
                    controlsManager.setPlaylistVisible(true)
                    if (::viewModel.isInitialized) {
                        viewModel.setPlaylist(playlistItems, playlistIndex)
                    }

                    val firstItem = parsedPlaylist[0]
                    val displayTitle = if (firstItem.title != null) {
                        "${firstItem.title} (1/${parsedPlaylist.size})"
                    } else {
                        "Item 1/${parsedPlaylist.size}"
                    }
                    startPlayback(
                        firstItem.url,
                        displayTitle,
                        firstItem.contentType,
                        firstItem.detectedBy,
                        firstItem.headers,
                        firstItem.subtitles?.let { ArrayList(it) }
                    )
                    return@launch
                }
            }

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

        // Build playlist JSON for history persistence
        val plistJson = if (playlistItems.isNotEmpty()) {
            try {
                com.playbridge.shared.protocol.protocolJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(com.playbridge.shared.protocol.PlayPayload.serializer()),
                    playlistItems
                )
            } catch (e: Exception) { null }
        } else null

        applyPlaybackSpeed(currentPlaybackSpeed)
        applyVideoScalingMode(currentVideoScalingMode)

        progressManager.setCurrentMedia(url, title, contentType, intentHeaders, plistJson, playlistIndex)
        controlsManager.setTitle(title)

        val payload = com.playbridge.shared.protocol.PlayPayload(
            url = url,
            title = title,
            headers = intentHeaders,
            contentType = contentType,
            detectedBy = detectedBy,
            subtitles = subtitles,
            preferredAudioLanguage = preferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            defaultVideoQuality = defaultVideoQuality,
            maxBitrateCapMbps = maxBitrateCapMbps
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
                exoPlayer.playWhenReady = !isPreBuffering
            }

            // Handle manual subtitles
            this@ExoPlayerActivity.subtitleUrls = subtitles ?: emptyList()
            if (subtitleUrls.size == 1) {
                val subUrl = subtitleUrls[0]
                currentSubtitleUrl = subUrl
                subtitleManager.loadSubtitle(subUrl)
                engine?.setSubtitleTrack(null) // Disable internal
            } else {
                currentSubtitleUrl = null
                subtitleManager.disable()
            }
        }
        }

        private fun createPlayerListener() = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = engine?.getExoPlayer() ?: return
            when (playbackState) {
                androidx.media3.common.Player.STATE_BUFFERING -> {
                    FileLogger.d(TAG, "Buffering...")
                    if (player.playWhenReady == true) {
                        controlsManager.showBuffering()
                        val lastBuffered = engine?.getExoPlayer()?.bufferedPosition ?: 0L

                        scheduleStuckBufferCheck(lastBuffered)
                    }
                }
                androidx.media3.common.Player.STATE_READY -> {
                    FileLogger.i(TAG, "Playback ready")
                    controlsManager.hideBuffering()
                    stuckBufferHandler.removeCallbacksAndMessages(null)

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
                    controlsManager.hideBuffering()
                    playNextInPlaylist()
                }
                androidx.media3.common.Player.STATE_IDLE -> {
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            handlePlaybackError(error)
        }

        private fun handlePlaybackError(error: androidx.media3.common.PlaybackException) {
            val player = engine?.getExoPlayer() ?: return
            // Live stream fell behind the available DVR window — seek back to the live edge and resume.
            // Without this, the error falls through to the playlist-skip logic and drops the channel.
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                FileLogger.w(TAG, "Behind live window, seeking to live edge")
                player.seekToDefaultPosition()
                player.prepare()
                return
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

            // Unrecognized Input Format (e.g. WMV) — Transition to internal VLC player automatically
            val isUnrecognizedFormat = error.cause is androidx.media3.exoplayer.source.UnrecognizedInputFormatException ||
                                       (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
            if (isUnrecognizedFormat) {
                FileLogger.e(TAG, "Unrecognized format detected, automatically transitioning to VlcPlayerActivity")
                val currentUrl = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: ""
                val currentTitle = player.currentMediaItem?.mediaMetadata?.title?.toString()

                // Pause player just in case, though it's likely already stopped due to the error
                player.pause()

                runOnUiThread {
                    android.widget.Toast.makeText(this@ExoPlayerActivity, "Format not supported by ExoPlayer, trying VLC...", android.widget.Toast.LENGTH_SHORT).show()
                    val vlcIntent = Intent(this@ExoPlayerActivity, VlcPlayerActivity::class.java).apply {
                        putExtra(com.playbridge.player.server.ServerService.EXTRA_URL, currentUrl)
                        currentTitle?.let { putExtra(com.playbridge.player.server.ServerService.EXTRA_TITLE, it) }

                        // Pass along subtitle URLs
                        if (subtitleUrls.isNotEmpty()) {
                            putStringArrayListExtra(com.playbridge.player.server.ServerService.EXTRA_SUBTITLES, ArrayList(subtitleUrls))
                        }

                        val currentHeaders = if (playlistItems.isNotEmpty()) {
                            playlistItems.getOrNull(playlistIndex)?.headers ?: emptyMap()
                        } else {
                            val intentHeaders = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getSerializableExtra(com.playbridge.player.server.ServerService.EXTRA_HEADERS, java.util.HashMap::class.java) as? Map<String, String>
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                intent.getSerializableExtra(com.playbridge.player.server.ServerService.EXTRA_HEADERS) as? Map<String, String>
                            }
                            intentHeaders ?: emptyMap()
                        }
                        if (currentHeaders.isNotEmpty()) {
                            putExtra(com.playbridge.player.server.ServerService.EXTRA_HEADERS, HashMap(currentHeaders))
                        }

                        if (playlistItems.isNotEmpty()) {
                            putExtra(com.playbridge.player.server.ServerService.EXTRA_IS_PLAYLIST, true)
                            putExtra(com.playbridge.player.server.ServerService.EXTRA_PLAYLIST_INDEX, playlistIndex)
                        }
                    }
                    startActivity(vlcIntent)
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
            if (isNetworkOrHttpError && playlistItems.isEmpty() && networkErrorRetryCount < 3) {
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
            if (playlistItems.isNotEmpty() && isNetworkOrHttpError) {
                FileLogger.w(TAG, "Network/HTTP error detected. Skipping to next item in playlist.")
                runOnUiThread {
                    android.widget.Toast.makeText(this@ExoPlayerActivity, "Link failed, skipping to next channel...", android.widget.Toast.LENGTH_SHORT).show()
                }

                // Mark item as failed
                if (playlistIndex in playlistItems.indices) {
                    val failedItem = playlistItems[playlistIndex]
                    val title = failedItem.title ?: "Channel ${playlistIndex + 1}"
                    if (!title.startsWith("[FAILED]")) {
                        playlistItems[playlistIndex] = failedItem.copy(title = "[FAILED] $title")
                    }
                }

                // Add a small delay so the user sees the toast before it skips
                lifecycleScope.launch(Dispatchers.Main) {
                    kotlinx.coroutines.delay(1000)
                    playNextInPlaylist()
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
                controlsManager.hideBuffering()
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
        activeDialog?.dismiss()
        activeDialog = null
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
            is com.playbridge.shared.player.PlayerUiState.PrePlay ->
                FileLogger.d(TAG, "VM state: PrePlay resolving=${state.isResolving}")
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
     * Play the previous item in the playlist queue.
     */
    private fun playPreviousInPlaylist() {
        // Series navigation path (no playlist queue active)
        if (playlistItems.isEmpty()) {
            val nav = seriesNavigator
            if (nav != null && nav.hasPrev()) {
                navigationJob?.cancel()
                navigationJob = lifecycleScope.launch {
                    FileLogger.i(TAG, "SeriesNavigator: resolving previous episode")

                    stopPlayback()
                    controlsManager.showBuffering()

                    val stream = nav.resolvePrev()
                    if (stream != null) {
                        FileLogger.i(TAG, "Successfully resolved PREVIOUS: ${stream.name ?: stream.title}")
                        // Early-return guard: avoid flicker if a cancelled coroutine slips through
                        val currentUrl = engine?.getExoPlayer()?.currentMediaItem?.localConfiguration?.uri?.toString()
                        if (stream.url == currentUrl) {
                            FileLogger.i(TAG, "Resolved URL is same as current, skipping redundant play")
                            controlsManager.hideBuffering()
                            return@launch
                        }

                        val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                        controlsManager.setSeasonInfo(seasonInfo)

                        val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"
                        controlsManager.setTitle(mainTitle)

                        FileLogger.i(TAG, "Updating intent with PREVIOUS episode info: $seasonInfo, title: $mainTitle")
                        // Update intent
                        intent?.putExtra(ServerService.EXTRA_URL, stream.url)
                        intent?.putExtra(ServerService.EXTRA_TITLE, mainTitle)

                        val forwardedSubs = currentSubtitleUrl?.let { arrayListOf(it) }
                        playVideo(url = stream.url, title = mainTitle, subtitles = forwardedSubs)
                        videoFilterManager.reapplyFilter()
                        controlsManager.hideUI()
                    } else {
                        controlsManager.hideBuffering()
                        android.widget.Toast.makeText(
                            this@ExoPlayerActivity,
                            "Could not resolve previous episode",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } else {
                android.widget.Toast.makeText(this, "Already on first episode", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Playlist path
        if (playlistIndex <= 0) {
            android.widget.Toast.makeText(this, "Already on first episode", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            // Only capture screenshot and save progress if playback was actually ready/started
            val state = engine?.getExoPlayer()?.playbackState
            val hasPlayed = state == androidx.media3.common.Player.STATE_READY || state == androidx.media3.common.Player.STATE_ENDED || (engine?.getExoPlayer()?.currentPosition ?: 0) > 0L
            if (hasPlayed && engine?.getExoPlayer()?.playerError == null) {
                syncSelectionsToProgressManager()
                try {
                    val bitmap = progressManager.captureBitmapSuspend()
                    progressManager.saveProgress(bitmap)
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to capture/save progress before previous: ${e.message}")
                }
            }

            playlistIndex--
            if (::viewModel.isInitialized) {
                viewModel.setPlaylist(playlistItems, playlistIndex)
            }
            val prevItem = playlistItems[playlistIndex]
            val title = if (prevItem.title != null) {
                "${prevItem.title} (${playlistIndex + 1}/${playlistItems.size})"
            } else {
                "Item ${playlistIndex + 1}/${playlistItems.size}"
            }

            FileLogger.i(TAG, "Playing previous in playlist: $title")
            android.widget.Toast.makeText(this@ExoPlayerActivity, title, android.widget.Toast.LENGTH_SHORT).show()

            playVideo(
                url = prevItem.url,
                title = title,
                contentType = prevItem.contentType,
                detectedBy = prevItem.detectedBy,
                intentHeaders = prevItem.headers,
                subtitles = prevItem.subtitles?.let { ArrayList(it) }
            )
            videoFilterManager.reapplyFilter()
            controlsManager.hideUI()
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

    /**
     * Play the next item in the playlist queue, or finish if at the end.
     */
    private fun playNextInPlaylist() {
        navigationJob?.cancel()
        navigationJob = lifecycleScope.launch {
            // Save progress for the current episode before advancing,
            // but only if playback was actually ready (to avoid crashes on failed streams)
            val state = engine?.getExoPlayer()?.playbackState
            val hasPlayed = state == androidx.media3.common.Player.STATE_READY || state == androidx.media3.common.Player.STATE_ENDED || (engine?.getExoPlayer()?.currentPosition ?: 0) > 0L
            if (hasPlayed && engine?.getExoPlayer()?.playerError == null) {
                syncSelectionsToProgressManager()
                try {
                    val bitmap = progressManager.captureBitmapSuspend()
                    progressManager.saveProgress(bitmap)
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to capture/save progress before next: ${e.message}")
                }
            }

            if (playlistItems.isEmpty()) {
                // No playlist queue — try series navigator if available
                val nav = seriesNavigator
                if (nav != null && nav.hasNext()) {
                    FileLogger.i(TAG, "No playlist — trying SeriesNavigator next episode")

                    stopPlayback()
                    controlsManager.showBuffering()

                    val stream = nav.resolveNext()
                    if (stream != null) {
                        FileLogger.i(TAG, "Successfully resolved NEXT: ${stream.name ?: stream.title}")
                        // Early-return guard: a cancelled coroutine that slipped through
                        // the mutex might resolve the same URL we are already playing.
                        val currentUrl = engine?.getExoPlayer()?.currentMediaItem?.localConfiguration?.uri?.toString()
                        if (stream.url == currentUrl) {
                            FileLogger.i(TAG, "Resolved URL is same as current, skipping redundant play")
                            controlsManager.hideBuffering()
                            return@launch
                        }

                        val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                        controlsManager.setSeasonInfo(seasonInfo)

                        val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"
                        controlsManager.setTitle(mainTitle)

                        FileLogger.i(TAG, "Updating intent with NEXT episode info: $seasonInfo, title: $mainTitle")
                        // Update intent so that history saving and re-init works with the new stream
                        intent?.putExtra(ServerService.EXTRA_URL, stream.url)
                        intent?.putExtra(ServerService.EXTRA_TITLE, mainTitle)

                        // Forward the current external subtitle URL if the user picked one;
                        // playVideo will disable it if the new episode's subtitle list clears it.
                        val forwardedSubs = currentSubtitleUrl?.let { arrayListOf(it) }
                        playVideo(url = stream.url, title = mainTitle, subtitles = forwardedSubs)
                        videoFilterManager.reapplyFilter()
                        controlsManager.hideUI()
                    } else {
                        controlsManager.hideBuffering()
                        FileLogger.i(TAG, "SeriesNavigator returned null — series complete")
                        android.widget.Toast.makeText(
                            this@ExoPlayerActivity,
                            "No more episodes found",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                } else {                    FileLogger.i(TAG, "No playlist and no series nav — finishing")
                    finish()
                }
                return@launch
            }

            playlistIndex++
            if (::viewModel.isInitialized) {
                viewModel.setPlaylist(playlistItems, playlistIndex)
            }
            if (playlistIndex >= playlistItems.size) {
                FileLogger.i(TAG, "Playlist complete ($playlistIndex/${playlistItems.size}) — finishing")
                finish()
                return@launch
            }

            val nextItem = playlistItems[playlistIndex]
            val title = if (nextItem.title != null) {
                "${nextItem.title} (${playlistIndex + 1}/${playlistItems.size})"
            } else {
                "Item ${playlistIndex + 1}/${playlistItems.size}"
            }

            FileLogger.i(TAG, "Playing next in playlist: $title (${playlistIndex + 1}/${playlistItems.size})")
            android.widget.Toast.makeText(this@ExoPlayerActivity, "Next: $title", android.widget.Toast.LENGTH_SHORT).show()

            playVideo(
                url = nextItem.url,
                title = title,
                contentType = nextItem.contentType,
                detectedBy = nextItem.detectedBy,
                intentHeaders = nextItem.headers,
                subtitles = nextItem.subtitles?.let { ArrayList(it) }
            )
            videoFilterManager.reapplyFilter()
            controlsManager.hideUI()
            broadcastPlaylistStatus()
        }
    }

    /**
     * Jump to a specific item in the playlist by index.
     */
    private fun playItemAtIndex(index: Int) {
        if (index < 0 || index >= playlistItems.size) return

        lifecycleScope.launch {
            // Save progress for the current episode before jumping, if it was playing
            val state = engine?.getExoPlayer()?.playbackState
            val hasPlayed = state == androidx.media3.common.Player.STATE_READY || state == androidx.media3.common.Player.STATE_ENDED || (engine?.getExoPlayer()?.currentPosition ?: 0) > 0L
            if (hasPlayed && engine?.getExoPlayer()?.playerError == null) {
                syncSelectionsToProgressManager()
                try {
                    val bitmap = progressManager.captureBitmapSuspend()
                    progressManager.saveProgress(bitmap)
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to capture/save progress before jump: ${e.message}")
                }
            }

            playlistIndex = index
            if (::viewModel.isInitialized) {
                viewModel.setPlaylist(playlistItems, playlistIndex)
            }
            val item = playlistItems[index]
            val title = if (item.title != null) {
                "${item.title} (${index + 1}/${playlistItems.size})"
            } else {
                "Item ${index + 1}/${playlistItems.size}"
            }

            FileLogger.i(TAG, "Jumping to playlist item $index: $title")
            android.widget.Toast.makeText(this@ExoPlayerActivity, title, android.widget.Toast.LENGTH_SHORT).show()

            stopPlayback()
            playVideo(
                url = item.url,
                title = title,
                contentType = item.contentType,
                detectedBy = item.detectedBy,
                intentHeaders = item.headers,
                subtitles = item.subtitles?.let { ArrayList(it) }
            )
            videoFilterManager.reapplyFilter()
            controlsManager.hideUI()
            broadcastPlaylistStatus()
        }
    }

    /**
     * Broadcast current playlist state to the phone via WebSocket.
     */
    private fun broadcastPlaylistStatus() {
        if (playlistItems.isEmpty()) return

        try {
            val itemsArray = org.json.JSONArray()
            playlistItems.forEachIndexed { index, item ->
                itemsArray.put(org.json.JSONObject().apply {
                    put("index", index)
                    put("title", item.title ?: "Item ${index + 1}")
                })
            }
            val statusJson = org.json.JSONObject().apply {
                put("type", "playlist_status")
                put("items", itemsArray)
                put("currentIndex", playlistIndex)
                put("totalCount", playlistItems.size)
            }.toString()

            ServerService.broadcastPlaylistStatus(statusJson)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to broadcast playlist status: ${e.message}")
        }
    }

    /**
     * Enable or disable single-video loop mode.
     */
    private fun setLooping(enabled: Boolean) {
        isLooping = enabled
        engine?.getExoPlayer()?.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        controlsManager.updateLoopIcon(enabled)
        FileLogger.i(TAG, "Loop mode: $enabled")
        if (::viewModel.isInitialized) {
            viewModel.setLooping(enabled)
        }
    }

    /**
     * Show the playlist picker dialog.
     */
    /**
     * Show the stream selection dialog for Stremio sources.
     */
    @OptIn(ExperimentalTvMaterial3Api::class)
    private fun showStreamSelectionDialog() {
        val nav = seriesNavigator ?: return
        val currentUrl = engine?.getExoPlayer()?.currentMediaItem?.localConfiguration?.uri?.toString()

        val wasPlaying = engine?.getExoPlayer()?.isPlaying == true
        if (wasPlaying) engine?.getExoPlayer()?.pause()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            val scope = rememberCoroutineScope()
            var streams by remember { mutableStateOf<List<com.playbridge.shared.stremio.ScoredStremioStream>>(emptyList()) }
            var isLoading by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                streams = nav.resolveCurrentStreams()
                isLoading = false
            }

            PlayBridgeTVTheme {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = "Loading sources...", color = Color(0xFF00D9FF))
                    }
                } else {
                    StreamSelectionDialog(
                        streams = streams,
                        currentUrl = currentUrl,
                        preferredQuality = nav.qualityPreference,
                        preferredAddonName = nav.context.preferredAddonName,
                        preferredSourceTypeKeys = nav.preferredSourceTypes,
                        onStreamSelected = { stream ->
                            dialog.dismiss()
                            val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"

                            // Update intent
                            intent?.putExtra(ServerService.EXTRA_URL, stream.url)

                            playVideo(url = stream.url, title = mainTitle)
                            videoFilterManager.reapplyFilter()
                            controlsManager.hideUI()
                        },
                        onRefresh = {
                            com.playbridge.shared.stremio.StremioClient.clearCache(
                                contentId = nav.context.imdbId,
                                type = "series",
                                season = nav.currentSeason,
                                episode = nav.currentEpisode
                            )
                            isLoading = true
                            scope.launch {
                                streams = nav.resolveCurrentStreams()
                                isLoading = false
                            }
                        },
                        onDismiss = {
                            dialog.dismiss()
                        }
                    )
                }
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            activeDialog = null
            if (wasPlaying) engine?.getExoPlayer()?.play()
            controlsManager.showControlsUI()
        }
        dialog.show()
    }

    private fun showPlaylistPicker() {
        val displayItems: List<com.playbridge.shared.protocol.PlayPayload>
        val displayIndex: Int
        val isSeriesMode: Boolean

        if (playlistItems.isNotEmpty()) {
            displayItems = playlistItems
            displayIndex = playlistIndex
            isSeriesMode = false
        } else if (seriesNavigator?.episodeList?.isNotEmpty() == true) {
            val nav = seriesNavigator!!
            displayItems = nav.episodeList!!.map { ep ->
                val s = ep.season.toString().padStart(2, '0')
                val e = ep.episode.toString().padStart(2, '0')
                com.playbridge.shared.protocol.PlayPayload(
                    url = "", // Not needed for UI
                    title = "S${s}E${e} - ${ep.title ?: "Episode ${ep.episode}"}"
                )
            }
            displayIndex = nav.currentIndex ?: 0
            isSeriesMode = true
        } else {
            return
        }

        val wasPlaying = engine?.getExoPlayer()?.isPlaying == true
        if (wasPlaying) engine?.getExoPlayer()?.pause()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            com.playbridge.player.ui.theme.PlayBridgeTVTheme {
                PlaylistPickerDialog(
                    items = displayItems,
                    currentIndex = displayIndex,
                    onItemSelected = { index ->
                        dialog.dismiss()
                        if (isSeriesMode) {
                            playSeriesEpisodeAtIndex(index)
                        } else {
                            playItemAtIndex(index)
                        }
                    },
                    onDismiss = {
                        dialog.dismiss()
                    }
                )
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            activeDialog = null
            if (wasPlaying) engine?.getExoPlayer()?.play()
            controlsManager.showControlsUI()
        }
        dialog.show()
    }

    private fun playSeriesEpisodeAtIndex(index: Int) {
        val nav = seriesNavigator ?: return
        navigationJob?.cancel()
        navigationJob = lifecycleScope.launch {
            FileLogger.i(TAG, "SeriesNavigator: resolving episode at index $index")

            stopPlayback()
            controlsManager.showBuffering()
            val stream = nav.resolveAndAdvanceToIndex(index)
            if (stream != null) {
                FileLogger.i(TAG, "Successfully resolved JUMP episode: ${stream.name ?: stream.title}")
                // Early-return guard: a cancelled coroutine that slipped through the mutex
                // might resolve the same URL we are already playing — skip to avoid flicker.
                val currentUrl = engine?.getExoPlayer()?.currentMediaItem?.localConfiguration?.uri?.toString()
                if (stream.url == currentUrl) {
                    FileLogger.i(TAG, "Resolved URL is same as current, skipping redundant play")
                    controlsManager.hideBuffering()
                    return@launch
                }

                // Display season info on top left (e.g. "Season 1 (1x5)")
                val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                controlsManager.setSeasonInfo(seasonInfo)

                // Use the series title for the main title bar if available, else SxE
                val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"
                controlsManager.setTitle(mainTitle)

                FileLogger.i(TAG, "Updating intent with JUMP episode info: $seasonInfo, title: $mainTitle")
                // Update intent
                intent?.putExtra(ServerService.EXTRA_URL, stream.url)
                intent?.putExtra(ServerService.EXTRA_TITLE, mainTitle)

                val forwardedSubs = currentSubtitleUrl?.let { arrayListOf(it) }
                playVideo(url = stream.url, title = mainTitle, subtitles = forwardedSubs)
                videoFilterManager.reapplyFilter()
                controlsManager.hideUI()
            } else {
                controlsManager.hideBuffering()
                android.widget.Toast.makeText(this@ExoPlayerActivity, "Could not resolve episode", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var currentSubtitleUrl: String? = null
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

    private fun showVideoFilterDialog() {
        val p = engine?.getExoPlayer()
        val wasPlaying = p?.playWhenReady == true
        val surfaceView = playerView.videoSurfaceView as? android.view.SurfaceView

        if (!wasPlaying && p != null && surfaceView != null && surfaceView.width > 0 && surfaceView.height > 0) {
            lifecycleScope.launch {
                val origPos = p.currentPosition
                val origVol = p.volume

                // 1. Temporarily clear ExoPlayer GPU filter to grab a "clean" frame
                videoFilterManager.colorMatrixEffect.setMatrix(VideoFilterAndroid.matrixFor(VideoFilter.NONE))

                // 2. Mute and seek forward slightly to flush out the clean frame
                p.volume = 0f
                val targetPos = kotlin.math.min(p.duration, origPos + 200)
                p.seekTo(targetPos)

                // 3. Wait for renderer to output the clean frame
                kotlinx.coroutines.delay(150)

                // 4. Capture screenshot
                val bitmap = android.graphics.Bitmap.createBitmap(surfaceView.width, surfaceView.height, android.graphics.Bitmap.Config.ARGB_8888)
                val captureDeferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                android.view.PixelCopy.request(surfaceView, bitmap, { result ->
                    captureDeferred.complete(result == android.view.PixelCopy.SUCCESS)
                }, android.os.Handler(android.os.Looper.getMainLooper()))

                val success = captureDeferred.await()

                // 5. Restore GPU filter, position, and volume
                videoFilterManager.reapplyFilter()
                p.seekTo(origPos)
                p.volume = origVol

                showVideoFilterDialogInternal(wasPlaying, if(success) bitmap else null)
            }
        } else {
            showVideoFilterDialogInternal(wasPlaying, null)
        }
    }

    private fun showVideoFilterDialogInternal(wasPlaying: Boolean, previewBitmap: android.graphics.Bitmap?) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            com.playbridge.player.ui.theme.PlayBridgeTVTheme {
                VideoFilterDialog(
                    currentFilter = videoFilterManager.currentFilter,
                    customBrightness = videoFilterManager.customBrightness,
                    customContrast = videoFilterManager.customContrast,
                    customSaturation = videoFilterManager.customSaturation,
                    previewFrame = previewBitmap,
                    onFilterSelected = { filter ->
                        videoFilterManager.applyFilter(filter)
                    },
                    onCustomChanged = { brightness, contrast, saturation ->
                        videoFilterManager.applyCustom(brightness, contrast, saturation)
                    },
                    onDismiss = {
                        dialog.dismiss()
                    }
                )
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            activeDialog = null
            if (wasPlaying) engine?.getExoPlayer()?.play()
            controlsManager.hideUI()
        }

        dialog.show()
    }

    private fun showTrackSelectionDialog() {
        val player = engine?.getExoPlayer() ?: return

        val wasPlaying = player.isPlaying
        if (wasPlaying) player.pause()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            // Reactive state so the dialog updates in-place when tracks change
            var liveTracks by remember { mutableStateOf(player.currentTracks) }
            var liveParams by remember { mutableStateOf(player.trackSelectionParameters) }
            var liveSubtitleUrl by remember { mutableStateOf(currentSubtitleUrl) }

            var livePlaybackSpeed by remember { mutableStateOf(currentPlaybackSpeed) }
            var liveVideoScalingMode by remember { mutableStateOf(currentVideoScalingMode) }

            DisposableEffect(Unit) {
                val listener = object : androidx.media3.common.Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        liveTracks = tracks
                    }
                    override fun onTrackSelectionParametersChanged(parameters: androidx.media3.common.TrackSelectionParameters) {
                        liveParams = parameters
                    }
                    override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                        livePlaybackSpeed = playbackParameters.speed
                    }
                }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }

            PlayBridgeTVTheme {
                TrackSelectionDialog(
                    tracks = liveTracks,
                    trackSelectionParameters = liveParams,
                    subtitleUrls = subtitleUrls,
                    currentSubtitleUrl = liveSubtitleUrl,
                    currentPlaybackSpeed = livePlaybackSpeed,
                    currentVideoScalingMode = liveVideoScalingMode,
                    onDismiss = {
                        dialog.dismiss()
                    },
                    onTrackSelected = { trackType, format ->
                        if (trackType == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                            currentSubtitleUrl = null
                            liveSubtitleUrl = null
                            subtitleManager.disable()
                            if (format != null) {
                                preferredSubtitleLanguage = format.language
                                FileLogger.i(TAG, "Saved preferred subtitle language: ${format.language}")
                            }
                        }
                        if (trackType == androidx.media3.common.C.TRACK_TYPE_AUDIO && format != null) {
                            preferredAudioLanguage = format.language
                            FileLogger.i(TAG, "Saved preferred audio language: ${format.language}")
                        }

                        applyTrackSelection(trackType, format)
                    },
                    onExternalSubtitleSelected = { url ->
                        currentSubtitleUrl = url
                        liveSubtitleUrl = url

                        if (url != null) {
                            subtitleManager.loadSubtitle(url)
                            applyTrackSelection(androidx.media3.common.C.TRACK_TYPE_TEXT, null)
                        } else {
                            subtitleManager.disable()
                            applyTrackSelection(androidx.media3.common.C.TRACK_TYPE_TEXT, null)
                        }
                    },
                    onPreviewRequest = { url ->
                         subtitleManager.getPreview(url)
                    },
                    onPlaybackSpeedSelected = { speed ->
                        applyPlaybackSpeed(speed)
                        livePlaybackSpeed = speed
                    },
                    onVideoScalingSelected = { mode ->
                        applyVideoScalingMode(mode)
                        liveVideoScalingMode = mode
                    }
                )
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            activeDialog = null
            if (wasPlaying) player.play()
            controlsManager.showControlsUI()
        }
        dialog.show()
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

    private fun resolveStreamsAndPreBuffer(p: com.playbridge.shared.protocol.ContentPlayPayload) {
        resolutionJob?.cancel()
        isPrePlayLaunching = false
        prePlayCountdown = 0

        resolutionJob = lifecycleScope.launch {
            try {
                val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                val autoQuality = p.defaultVideoQuality ?: prefs.getString("auto_stream_quality", "") ?: ""
                val autoMaxMbps = p.maxBitrateCapMbps ?: prefs.getString("auto_stream_max_mbps", "")?.toDoubleOrNull()
                val preferredAddon = p.preferredAddonBaseUrl ?: prefs.getString("auto_stream_addon", "") ?: ""
                val preferredAddonName = p.preferredAddonName ?: prefs.getString("auto_stream_addon_name", "")
                val prefSourceTypesCsv = prefs.getString("auto_stream_source_types", "") ?: ""
                val sourceTypes: List<String>? = (p.preferredSourceTypes?.takeIf { it.isNotEmpty() }
                    ?: prefSourceTypesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() })

                FileLogger.i(TAG, "Resolving streams for pre-buffering: ${p.title}")

                val streams = com.playbridge.shared.stremio.StremioClient.resolveStreamsByContentId(
                    addonBaseUrls = p.addonBaseUrls,
                    addonNames = p.addonNames,
                    contentId = p.contentId,
                    contentType = p.contentType,
                    season = p.season,
                    episode = p.episode,
                    qualityPreference = autoQuality.takeIf { it.isNotEmpty() },
                    preferredAddonBaseUrl = preferredAddon.takeIf { it.isNotEmpty() },
                    preferredAddonName = preferredAddonName,
                    preferredSourceTypes = sourceTypes,
                    runtimeMinutes = p.episodeRuntimeMinutes,
                    maxBitrateMbps = autoMaxMbps
                )

                if (streams.isEmpty()) {
                    FileLogger.w(TAG, "No streams resolved for ${p.contentId}")
                    // UI will show error in PrePlayScreen
                    return@launch
                }

                // Auto-pick the best stream (sorted by score)
                val best = streams.firstOrNull()
                if (best != null && !p.forcePicker && autoQuality.isNotEmpty()) {
                    FileLogger.i(TAG, "Auto-picked stream for pre-buffering: ${best.name}")
                    playVideoAfterResolution(best.url, p)
                } else {
                    FileLogger.i(TAG, "Manual picker required, waiting for user selection")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Pre-play resolution failed", e)
            }
        }
    }

    private fun playVideoAfterResolution(url: String, p: com.playbridge.shared.protocol.ContentPlayPayload) {
        isPrePlayLaunching = true
        isPreBuffering = true

        // Start buffering in the background immediately
        FileLogger.i(TAG, "Starting background pre-buffer for: $url")
        val nav = seriesNavigator // setupSeriesNavigator called in handleIntent
        val baseTitle = if (nav != null && nav.seriesTitle != null) {
            nav.seriesTitle
        } else {
            p.title
        }

        val suffix = "(${playlistIndex + 1}/${playlistItems.size})"
        val displayTitle = if (playlistItems.isNotEmpty() && baseTitle?.contains(suffix) != true) {
            "$baseTitle $suffix"
        } else {
            baseTitle
        }

        // Initialize player and start buffering but KEEP composeView visible
        playVideo(url, displayTitle, p.contentType, "library", null, null)

        // Start countdown
        launchJob?.cancel()
        launchJob = lifecycleScope.launch {
            for (i in 5 downTo 1) {
                prePlayCountdown = i
                kotlinx.coroutines.delay(1000)
            }

            // Countdown finished, hide overlay and start playback
            FileLogger.i(TAG, "Pre-buffer countdown finished, revealing player")
            isPreBuffering = false
            prePlayPayload = null
            composeView.visibility = android.view.View.GONE
            engine?.getExoPlayer()?.play() // Ensure it starts playing
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
            }
