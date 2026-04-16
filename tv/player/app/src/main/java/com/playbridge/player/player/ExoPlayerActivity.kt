package com.playbridge.player.player

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * - [PlayerControlsManager] for custom controls overlay
 * - [ProgressManager] for progress save/restore and thumbnails
 * - [InputHandler] for D-pad, phone remote, and control commands
 */
class ExoPlayerActivity : PlayerActivity() {

    private var player: ExoPlayer? = null

    private lateinit var playerView: PlayerView

    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    override fun isPlaying(): Boolean = player?.isPlaying == true
    override fun getMediaDuration(): Long = player?.duration ?: 0L
    override fun getCurrentPosition(): Long = player?.currentPosition ?: 0L
    override fun seekTo(position: Long) { player?.seekTo(position) }
    override fun getVideoSurfaceView(): android.view.SurfaceView? = playerView.videoSurfaceView as? android.view.SurfaceView

    override fun stopPlayback() {
        FileLogger.i(TAG, "stopPlayback() — clearing surface for transition")
        releasePlayer()
        runOnUiThread {
            playerView.visibility = android.view.View.INVISIBLE
        }
    }

    private var audioDiscontinuityRetryCount = 0
    private var videoDecoderRetryCount = 0
    private var malformedContentRetryCount = 0
    private var stuckBufferRetryCount = 0
    private var networkErrorRetryCount = 0
    private val stuckBufferHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Loop state
    private var isLooping = false

    // Managers
    private val contentSniffer = ContentSniffer()
    private lateinit var controlsManager: PlayerControlsManager
    private lateinit var videoFilterManager: VideoFilterManager
    private lateinit var progressManager: ProgressManager
    private lateinit var inputHandler: InputHandler

    @OptIn(UnstableApi::class)
    private lateinit var subtitleManager: SubtitleManager
    private var subtitleUrls: List<String> = emptyList()

    // Playlist queue for auto-advancing through episodes
    private var playlistItems: MutableList<com.playbridge.protocol.PlayPayload> = mutableListOf()
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

        val historyStore = HistoryStore(applicationContext)

        playerView = findViewById(com.playbridge.player.R.id.player_view)

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
        subtitleManager.setPlayer { player?.currentPosition ?: 0L }

        // Initialize VideoFilterManager
        videoFilterManager = VideoFilterManager(playerView)

        val loopButton = findViewById<android.widget.ImageButton>(com.playbridge.player.R.id.btn_loop)

        // Initialize managers
        controlsManager = PlayerControlsManager(
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
            streamInfoText = streamInfoText,
            seasonInfoText = seasonInfoText,
            elapsedText = elapsedText,
            remainingText = remainingText,
            titleText = titleText,
            bufferingSpinner = bufferingSpinner,
            playerProvider = { player },
            onShowTrackSelection = { showTrackSelectionDialog() },
            onShowPlaylist = { showPlaylistPicker() },
            onShowStreams = { showStreamSelectionDialog() },
            onShowFilter = { showVideoFilterDialog() },
            onPrevious = { playPreviousInPlaylist() },
            onNext = { playNextInPlaylist() },
            onToggleLoop = { setLooping(!isLooping) }
        )
        controlsManager.setupControls()

        progressManager = ProgressManager(
            context = this,
            historyStore = historyStore,
            lifecycleScope = lifecycleScope,
            playerActivity = this
        )

        inputHandler = InputHandler(
            activity = this,
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            playerProvider = { player },
            controls = controlsManager
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
        stopPlayback()
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
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

        setupSeriesNavigator(intent)

        // Read playlist if present
        val isPlaylist = intent?.getBooleanExtra(ServerService.EXTRA_IS_PLAYLIST, false) ?: false
        val inMemoryPlaylist = PlaylistStore.currentPlaylist
        if (isPlaylist && inMemoryPlaylist != null && inMemoryPlaylist.isNotEmpty()) {
            playlistItems = inMemoryPlaylist.toMutableList()
            playlistIndex = intent?.getIntExtra(ServerService.EXTRA_PLAYLIST_INDEX, 0) ?: 0
            FileLogger.i(TAG, "Playlist loaded: ${playlistItems.size} items, starting at index $playlistIndex")
        } else {
            playlistItems = mutableListOf()
        }

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

        // Show playlist button when a playlist is active OR series navigator has list mode
        controlsManager.setPlaylistVisible(playlistItems.isNotEmpty() || (seriesNavigator?.episodeList?.isNotEmpty() ?: false))

        // Show streams button when series navigator is active
        controlsManager.setStreamsVisible(seriesNavigator != null)

        seriesNavigator?.let { nav ->
            val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
            controlsManager.setSeasonInfo(seasonInfo)
        }

        // Show prev/next buttons without the playlist button ONLY if series navigation is in optimistic mode (no list)
        if (seriesNavigator != null && seriesNavigator?.episodeList.isNullOrEmpty() && playlistItems.isEmpty()) {
            controlsManager.setNavigationVisible(true)
        }
    }

    private fun initializePlayer() {
        releasePlayer()

        val bufCfg = computeBufferConfig()
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, bufCfg.maxBufferMs, 1000, 2500)
            .setTargetBufferBytes(bufCfg.targetBytes)
            .setPrioritizeTimeOverSizeThresholds(bufCfg.prioritizeTime)
            .setBackBuffer(0, false)
            .build()

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildTextRenderers(
                context: android.content.Context,
                output: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                out.forEach {
                    if (it is androidx.media3.exoplayer.text.TextRenderer) {
                        it.experimentalSetLegacyDecodingEnabled(true)
                    }
                }
            }
        }

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                videoFilterManager.setPlayer(exoPlayer)

                exoPlayer.playWhenReady = true
                exoPlayer.addListener(createPlayerListener())
            }
    }

    private var playJob: kotlinx.coroutines.Job? = null

    private fun playVideo(url: String, title: String?, contentType: String? = null, detectedBy: String? = null, intentHeaders: Map<String, String>? = null, subtitles: ArrayList<String>? = null) {
        setupSeriesNavigator(intent)
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
                android.widget.Toast.makeText(this@ExoPlayerActivity, "Detected: $finalContentType", android.widget.Toast.LENGTH_SHORT).show()
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
        FileLogger.i(TAG, "Starting playback with Final Content Type: $contentType")

        // Build playlist JSON for history persistence
        val plistJson = if (playlistItems.isNotEmpty()) {
            try {
                com.playbridge.protocol.protocolJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(com.playbridge.protocol.PlayPayload.serializer()),
                    playlistItems
                )
            } catch (e: Exception) { null }
        } else null

        if (plistJson == null) {
            applyPlaybackSpeed(1.0f)
            applyVideoScalingMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT)
        }

        progressManager.setCurrentMedia(url, title, contentType, intentHeaders, plistJson, playlistIndex)
        controlsManager.setTitle(title)

        // 1. Prepare Headers
        val requestProperties = HashMap<String, String>()
        intentHeaders?.forEach { (key, value) ->
            // Filter out headers that interfere with ExoPlayer's own chunking and buffering
            if (!key.equals("Range", ignoreCase = true) && !key.equals("Accept-Encoding", ignoreCase = true)) {
                requestProperties[key] = value
            } else {
                FileLogger.i(TAG, "Stripping header to prevent ExoPlayer buffering issues: $key: $value")
            }
        }

        if (!requestProperties.containsKey("Referer")) {
            try {
                val uri = android.net.Uri.parse(url)
                val scheme = uri.scheme ?: "https"
                val host = uri.host
                if (host != null) {
                    val referer = "$scheme://$host/"
                    requestProperties["Referer"] = referer
                    FileLogger.i(TAG, "Added fallback Referer: $referer")

                    if (!requestProperties.containsKey("Origin")) {
                        requestProperties["Origin"] = "$scheme://$host"
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Error parsing URL for Referer fallback", e)
            }
        }

        val userAgent = intentHeaders?.get("User-Agent")
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        requestProperties["User-Agent"] = userAgent

        FileLogger.i(TAG, "Final Request Headers: $requestProperties")

        // Print cURL command for debugging
        val curlBuilder = StringBuilder("curl -v '$url'")
        requestProperties.forEach { (key, value) ->
            // Escape single quotes in the value to avoid breaking the shell command
            val escapedValue = value.replace("'", "'\\''")
            curlBuilder.append(" -H '$key: $escapedValue'")
        }
        FileLogger.i(TAG, "CURL COMMAND: $curlBuilder")

        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(requestProperties)
            .setAllowCrossProtocolRedirects(true)
            // Debrid redirect chains can take >8s on first open — raise both timeouts
            // to avoid spurious connection failures before playback even starts.
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)

        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpDataSourceFactory)

        // 3. Configure Load Control — caps scale with available device RAM (see PlayerActivity.computeBufferConfig)
        val bufCfg = computeBufferConfig()
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, bufCfg.maxBufferMs, 1000, 2500)
            .setTargetBufferBytes(bufCfg.targetBytes)
            .setPrioritizeTimeOverSizeThresholds(bufCfg.prioritizeTime)
            .setBackBuffer(0, false)
            .build()

        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this).apply {
            val params = buildUponParameters()
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)

            // Reapply saved language preferences for playlist continuity
            if (playlistItems.isNotEmpty()) {
                preferredAudioLanguage?.let {
                    FileLogger.i(TAG, "Reapplying preferred audio language: $it")
                    params.setPreferredAudioLanguage(it)
                }
                preferredSubtitleLanguage?.let {
                    FileLogger.i(TAG, "Reapplying preferred subtitle language: $it")
                    params.setPreferredTextLanguage(it)
                    params.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                }
            }

            // Apply video quality preference from phone as a max-size constraint.
            // setExceedVideoConstraintsIfNecessary(true) means ExoPlayer falls back gracefully
            // when the stream only has higher-quality tracks (e.g. phone wants 1080p, stream
            // only has 4K — ExoPlayer picks 4K rather than refusing to play).
            defaultVideoQuality?.let { quality ->
                val (maxW, maxH) = when (quality.lowercase()) {
                    "720p"        -> Pair(1280, 720)
                    "1080p"       -> Pair(1920, 1080)
                    "2160p", "4k" -> Pair(3840, 2160)
                    else          -> null
                } ?: run { null to null }
                if (maxW != null && maxH != null) {
                    FileLogger.i(TAG, "Applying video quality preference: $quality → maxSize=${maxW}x${maxH}")
                    params.setMaxVideoSize(maxW, maxH)
                }
            }

            // Apply explicit bitrate cap if provided (takes precedence over quality size alone
            // for streams that report per-variant bitrates, e.g. HLS EXT-X-STREAM-INF:BANDWIDTH).
            maxBitrateCapMbps?.let { cap ->
                val capBps = (cap * 1_000_000).toInt()
                FileLogger.i(TAG, "Applying max bitrate cap: ${cap} Mbps → ${capBps} bps")
                params.setMaxVideoBitrate(capBps)
            }

            setParameters(params)
        }

        val isHls = (detectedBy == "body_content_m3u8") ||
                    (detectedBy == "url_pattern_m3u8") ||
                    (contentType == "application/vnd.apple.mpegurl") ||
                    (contentType == "application/x-mpegurl") ||
                    (contentType == androidx.media3.common.MimeTypes.APPLICATION_M3U8) ||
                    (contentType.isNullOrEmpty() && (url.contains(".m3u8") || url.contains(".jpg")))

        // 4. Build Player
        val mediaSourceFactory = if (isHls) {
            FileLogger.i(TAG, "Using HlsMediaSource.Factory")
            androidx.media3.exoplayer.hls.HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(CustomLoadErrorHandlingPolicy())
        } else {
            FileLogger.i(TAG, "Using DefaultMediaSourceFactory")
            val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpDataSourceFactory, extractorsFactory)
                .setLoadErrorHandlingPolicy(CustomLoadErrorHandlingPolicy())
        }

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildTextRenderers(
                context: android.content.Context,
                output: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                out.forEach {
                    if (it is androidx.media3.exoplayer.text.TextRenderer) {
                        it.experimentalSetLegacyDecodingEnabled(true)
                    }
                }
            }
        }

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .also { exoPlayer ->
                playerView.player = exoPlayer
                videoFilterManager.setPlayer(exoPlayer)

                exoPlayer.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
                exoPlayer.playWhenReady = true
                exoPlayer.addListener(createPlayerListener())
            }

        // 5. Create Media Item
        val builder = MediaItem.Builder()
            .setUri(url)
            .apply { title?.let { setMediaId(it) } }

        this.subtitleUrls = subtitles ?: emptyList()
        if (subtitleUrls.isNotEmpty()) {
             FileLogger.i(TAG, "Subtitles available for manual selection: ${subtitleUrls.size}")

             // Auto-select if there is exactly one subtitle available
             if (subtitleUrls.size == 1) {
                 val url = subtitleUrls[0]
                 FileLogger.i(TAG, "Auto-selecting the single available subtitle: $url")
                 currentSubtitleUrl = url
                 subtitleManager.loadSubtitle(url)

                 // Disable internal text tracks since we are showing an external one
                 val parametersBuilder = trackSelector.parameters.buildUpon()
                 parametersBuilder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                 trackSelector.parameters = parametersBuilder.build()
             } else {
                 currentSubtitleUrl = null
                 subtitleManager.disable()
             }
        } else {
            currentSubtitleUrl = null
            subtitleManager.disable()
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
        lifecycleScope.launch {
            val historyItem = progressManager.restoreProgress(url)
            if (historyItem != null) {
                if (historyItem.playbackSpeed != null) {
                    applyPlaybackSpeed(historyItem.playbackSpeed)
                }
                if (historyItem.videoScalingMode != null) {
                    applyVideoScalingMode(historyItem.videoScalingMode)
                }
            }
        }

        player?.play()

        audioDiscontinuityRetryCount = 0
        videoDecoderRetryCount = 0
        malformedContentRetryCount = 0
        stuckBufferRetryCount = 0
        networkErrorRetryCount = 0
        stuckBufferHandler.removeCallbacksAndMessages(null)
    }

    private fun createPlayerListener() = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                androidx.media3.common.Player.STATE_BUFFERING -> {
                    FileLogger.d(TAG, "Buffering...")
                    if (player?.playWhenReady == true) {
                        controlsManager.showBuffering()
                        val lastBuffered = player?.bufferedPosition ?: 0L
                        scheduleStuckBufferCheck(lastBuffered)
                    }
                }
                androidx.media3.common.Player.STATE_READY -> {
                    FileLogger.i(TAG, "Playback ready")
                    controlsManager.hideBuffering()
                    stuckBufferHandler.removeCallbacksAndMessages(null)
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
            // Live stream fell behind the available DVR window — seek back to the live edge and resume.
            // Without this, the error falls through to the playlist-skip logic and drops the channel.
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                FileLogger.w(TAG, "Behind live window, seeking to live edge")
                player?.seekToDefaultPosition()
                player?.prepare()
                return
            }

            val isAudioDiscontinuity = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                                       error.cause is androidx.media3.exoplayer.audio.AudioSink.UnexpectedDiscontinuityException

            if (isAudioDiscontinuity) {
                if (audioDiscontinuityRetryCount < 3) {
                    audioDiscontinuityRetryCount++
                    FileLogger.w(TAG, "Audio discontinuity detected. Attempting recovery (attempt $audioDiscontinuityRetryCount)...")
                    val currentPos = player?.currentPosition ?: 0L
                    player?.seekTo(currentPos)
                    player?.prepare()
                    player?.play()
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
                player?.let { p ->
                    val currentPos = p.currentPosition
                    val playWhenReady = p.playWhenReady
                    val params = p.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
                        .build()
                    p.trackSelectionParameters = params
                    p.seekTo(currentPos)
                    p.prepare()
                    p.playWhenReady = playWhenReady
                }
                return
            }

            // Video decoder crash (e.g. codec race on track switch) — retry once
            val isVideoDecoderCrash = error.cause is androidx.media3.exoplayer.video.MediaCodecVideoDecoderException
            if (isVideoDecoderCrash && videoDecoderRetryCount < 1) {
                videoDecoderRetryCount++
                FileLogger.w(TAG, "Video decoder crash detected. Attempting recovery (attempt $videoDecoderRetryCount)...")
                player?.let { p ->
                    val pos = p.currentPosition
                    val play = p.playWhenReady
                    p.seekTo(pos)
                    p.prepare()
                    p.playWhenReady = play
                }
                return
            }

            // Unrecognized Input Format (e.g. WMV) — Transition to internal VLC player automatically
            val isUnrecognizedFormat = error.cause is androidx.media3.exoplayer.source.UnrecognizedInputFormatException ||
                                       (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
            if (isUnrecognizedFormat) {
                FileLogger.e(TAG, "Unrecognized format detected, automatically transitioning to VlcPlayerActivity")
                val currentUrl = player?.currentMediaItem?.localConfiguration?.uri?.toString() ?: ""
                val currentTitle = player?.currentMediaItem?.mediaMetadata?.title?.toString()

                // Pause player just in case, though it's likely already stopped due to the error
                player?.pause()

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
                val currentPos = player?.currentPosition ?: 0L
                FileLogger.w(TAG, "Malformed content at ${currentPos}ms. Seeking forward ${skipAheadMs}ms (attempt $malformedContentRetryCount)...")
                player?.let { p ->
                    val duration = p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                    p.seekTo((currentPos + skipAheadMs).coerceAtMost(duration))
                    p.prepare()
                    p.play()
                }
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
                    val p = player ?: return@launch
                    val pos = p.currentPosition
                    p.stop()
                    p.seekTo(pos)
                    p.prepare()
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
        if (player == null) {
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
        try {
            player?.clearVideoSurface()
            videoFilterManager.setPlayer(null)
            player?.release()
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error releasing player: ${e.message}", e)
        }
        player = null
    }
    /**
     * Play the previous item in the playlist queue.
     */
    private fun playPreviousInPlaylist() {
        // Series navigation path (no playlist queue active)
        if (playlistItems.isEmpty()) {
            val nav = seriesNavigator
            if (nav != null && nav.hasPrev()) {
                lifecycleScope.launch {
                    FileLogger.i(TAG, "SeriesNavigator: resolving previous episode")

                    stopPlayback()
                    controlsManager.showBuffering()

                    val stream = nav.resolvePrev()
                    if (stream != null) {
                        val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                        controlsManager.setSeasonInfo(seasonInfo)

                        val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"
                        controlsManager.setTitle(mainTitle)

                        // Update intent
                        intent?.putExtra(ServerService.EXTRA_URL, stream.url)
                        intent?.putExtra(ServerService.EXTRA_TITLE, mainTitle)

                        playVideo(url = stream.url, title = mainTitle)
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
            val state = player?.playbackState
            val hasPlayed = state == androidx.media3.common.Player.STATE_READY || state == androidx.media3.common.Player.STATE_ENDED || (player?.currentPosition ?: 0) > 0L
            if (hasPlayed && player?.playerError == null) {
                syncSelectionsToProgressManager()
                try {
                    val bitmap = progressManager.captureBitmapSuspend()
                    progressManager.saveProgress(bitmap)
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to capture/save progress before previous: ${e.message}")
                }
            }

            playlistIndex--
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
            val p = player ?: return@postDelayed
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
        lifecycleScope.launch {
            // Save progress for the current episode before advancing,
            // but only if playback was actually ready (to avoid crashes on failed streams)
            val state = player?.playbackState
            val hasPlayed = state == androidx.media3.common.Player.STATE_READY || state == androidx.media3.common.Player.STATE_ENDED || (player?.currentPosition ?: 0) > 0L
            if (hasPlayed && player?.playerError == null) {
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
                        val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                        controlsManager.setSeasonInfo(seasonInfo)

                        val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"
                        controlsManager.setTitle(mainTitle)

                        // Update intent so that history saving and re-init works with the new stream
                        intent?.putExtra(ServerService.EXTRA_URL, stream.url)
                        intent?.putExtra(ServerService.EXTRA_TITLE, mainTitle)

                        playVideo(url = stream.url, title = mainTitle)
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
            val state = player?.playbackState
            val hasPlayed = state == androidx.media3.common.Player.STATE_READY || state == androidx.media3.common.Player.STATE_ENDED || (player?.currentPosition ?: 0) > 0L
            if (hasPlayed && player?.playerError == null) {
                syncSelectionsToProgressManager()
                try {
                    val bitmap = progressManager.captureBitmapSuspend()
                    progressManager.saveProgress(bitmap)
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to capture/save progress before jump: ${e.message}")
                }
            }

            playlistIndex = index
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
        player?.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        controlsManager.updateLoopIcon(enabled)
        FileLogger.i(TAG, "Loop mode: $enabled")
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
        val currentUrl = player?.currentMediaItem?.localConfiguration?.uri?.toString()

        val wasPlaying = player?.isPlaying == true
        if (wasPlaying) player?.pause()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            var streams by remember { mutableStateOf<List<com.playbridge.player.stremio.ScoredStremioStream>>(emptyList()) }
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
                        onStreamSelected = { stream ->
                            dialog.dismiss()
                            val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"

                            // Update intent
                            intent?.putExtra(ServerService.EXTRA_URL, stream.url)

                            playVideo(url = stream.url, title = mainTitle)
                            videoFilterManager.reapplyFilter()
                            controlsManager.hideUI()
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
            if (wasPlaying) player?.play()
            controlsManager.showControlsUI()
        }
        dialog.show()
    }

    private fun showPlaylistPicker() {
        val displayItems: List<com.playbridge.protocol.PlayPayload>
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
                com.playbridge.protocol.PlayPayload(
                    url = "", // Not needed for UI
                    title = "S${s}E${e} - ${ep.title ?: "Episode ${ep.episode}"}"
                )
            }
            displayIndex = nav.currentIndex ?: 0
            isSeriesMode = true
        } else {
            return
        }

        val wasPlaying = player?.isPlaying == true
        if (wasPlaying) player?.pause()

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
            if (wasPlaying) player?.play()
            controlsManager.showControlsUI()
        }
        dialog.show()
    }

    private fun playSeriesEpisodeAtIndex(index: Int) {
        val nav = seriesNavigator ?: return
        lifecycleScope.launch {
            FileLogger.i(TAG, "SeriesNavigator: resolving episode at index $index")

            stopPlayback()
            controlsManager.showBuffering()
            val stream = nav.resolveAndAdvanceToIndex(index)
            if (stream != null) {
                // Display season info on top left (e.g. "Season 1 (1x5)")
                val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                controlsManager.setSeasonInfo(seasonInfo)

                // Use the series title for the main title bar if available, else SxE
                val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"
                controlsManager.setTitle(mainTitle)

                // Update intent
                intent?.putExtra(ServerService.EXTRA_URL, stream.url)
                intent?.putExtra(ServerService.EXTRA_TITLE, mainTitle)

                playVideo(url = stream.url, title = mainTitle)
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
        player?.playbackParameters = androidx.media3.common.PlaybackParameters(speed)
    }

    private fun applyVideoScalingMode(mode: Int) {
        currentVideoScalingMode = mode
        playerView.resizeMode = mode
    }

    private fun showVideoFilterDialog() {
        val p = player
        val wasPlaying = p?.playWhenReady == true
        val surfaceView = playerView.videoSurfaceView as? android.view.SurfaceView

        if (!wasPlaying && p != null && surfaceView != null && surfaceView.width > 0 && surfaceView.height > 0) {
            lifecycleScope.launch {
                val origPos = p.currentPosition
                val origVol = p.volume

                // 1. Temporarily clear ExoPlayer GPU filter to grab a "clean" frame
                videoFilterManager.colorMatrixEffect.setMatrix(VideoFilter.matrixFor(VideoFilter.NONE))

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
            if (wasPlaying) player?.play()
            controlsManager.hideUI()
        }

        dialog.show()
    }

    private fun showTrackSelectionDialog() {
        val player = this.player ?: return

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
