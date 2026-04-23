package com.playbridge.player.player

import com.playbridge.shared.player.PlaybackEngine
import com.playbridge.shared.player.PlaybackState
import com.playbridge.shared.player.MpvPlayerEngine
import com.playbridge.shared.player.VideoFilter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.media.AudioManager
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.playbridge.player.R
import com.playbridge.player.data.HistoryStore
import com.playbridge.player.server.ServerService
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import com.playbridge.player.logging.FileLogger
import androidx.annotation.OptIn
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
// ComposeOptIn alias removed — androidx.compose.runtime.OptIn does not exist

/**
 * MPV-based video player activity.
 *
 * Wraps the mpv-android library (MPVLib) behind the same PlayerActivity interface used by
 * ExoPlayerActivity and VlcPlayerActivity.  The activity owns the MPVLib lifecycle: create →
 * init → [surface attached] → loadfile → … → destroy.
 *
 * Prerequisites
 * -------------
 * Download the prebuilt AAR from the mpv-android project (see tv/app/libs/README.md) and place
 * it at tv/app/libs/mpv-android.aar before building.
 */
class MpvPlayerActivity : PlayerActivity(), MPVLib.EventObserver {

    companion object {
        private const val TAG = "MpvPlayerActivity"
    }


    private lateinit var surfaceView: SurfaceView
    private lateinit var controlsManager: UnifiedControlsManager
    private lateinit var inputHandler: InputHandler
    private lateinit var progressManager: ProgressManager
    private lateinit var historyStore: HistoryStore
    private var engine: MpvPlayerEngine? = null
    private lateinit var viewModel: com.playbridge.shared.player.PlayerViewModel
    private lateinit var resumeStore: com.playbridge.player.data.HistoryResumeStore
    private var vmUiJob: kotlinx.coroutines.Job? = null
    private var receiverRegistered = false

    // Current media state (updated via MPV property observers)
    private var positionMs: Long = 0L
    private var durationMs: Long = 0L
    private var isPlayingState: Boolean = false
    private var containerFps: Double = 0.0
    // Buffered-ahead time in ms (from demuxer-cache-time, observed as seconds Double)
    private var bufferAheadMs: Long = 0L

    // Settings state
    private var subtitleUrls: List<String> = emptyList()
    private var currentSubtitleUrl: String? = null
    private var currentHeaders: Map<String, String>? = null
    private var currentUrl: String? = null
    private var currentPlaybackSpeed: Float = 1.0f

    // Stream info (updated via property observers)
    private var videoCodecRaw: String = ""
    private var videoHeight: Long = 0L
    private var videoBitrateBps: Long = 0L
    private var audioCodecRaw: String = ""
    private var audioChannels: String = ""

    // Position to seek to once MPV_EVENT_FILE_LOADED fires
    private var pendingResumePositionMs: Long = 0L

    private var isLooping = false
    private var isLoadingNewStream = false

    /**
     * Counter for intentional [engine?.stop()] calls. MPV fires END_FILE for every
     * stop() command, but it also fires END_FILE when a load fails. We increment this
     * before each intentional stop and decrement it in the END_FILE handler so that
     * load-failure END_FILE events are handled (spinner hidden, error surfaced) rather
     * than being swallowed by the isLoadingNewStream guard.
     */
    private var pendingStops = 0

    // Seek-stuck detection: if MPV_EVENT_PLAYBACK_RESTART doesn't follow a seek within this
    // window, ffmpeg is looping on a failed range request (e.g. "partial file" from the server).
    private val seekHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val seekTimeoutMs = 10_000L
    private var preSeePositionMs: Long = 0L
    private val seekTimeoutRunnable = Runnable {
        FileLogger.w(TAG, "Seek timeout — MPV stuck retrying failed range request. Recovering to ${preSeePositionMs}ms.")
        runOnUiThread {
            controlsManager.hideBuffering()
            Toast.makeText(this, "Seek failed (network error)", Toast.LENGTH_SHORT).show()
            // Seek back to the pre-seek position (known-good). Using positionMs here would
            // re-seek to the stuck target because MPV reports the target as time-pos mid-seek.
            MPVLib.command("seek", (preSeePositionMs / 1000.0).toString(), "absolute+keyframes")
        }
    }

    // Playlist state
    private var playlistItems: MutableList<com.playbridge.shared.protocol.PlayPayload> = mutableListOf()
    private var playlistIndex: Int = 0

    private var activeDialog: android.app.Dialog? = null

    // Pre-play state
    private var prePlayPayload by mutableStateOf<com.playbridge.shared.protocol.ContentPlayPayload?>(null)
    private var isPrePlayLaunching by mutableStateOf(false)
    private var prePlayCountdown by androidx.compose.runtime.mutableIntStateOf(0)
    private var isPreBuffering = false
    private lateinit var composeView: androidx.compose.ui.platform.ComposeView
    private var resolutionJob: kotlinx.coroutines.Job? = null
    private var launchJob: kotlinx.coroutines.Job? = null
    private var playJob: kotlinx.coroutines.Job? = null
    /** Serialises Stremio series navigation. Cancelled before each new nav request. */
    private var navigationJob: kotlinx.coroutines.Job? = null

    // ── PlayerActivity abstract impl ─────────────────────────────────────────
    override fun play() { engine?.play() }
    override fun pause() { engine?.pause() }
    override fun isPlaying(): Boolean = isPlayingState
    override fun getMediaDuration(): Long = durationMs
    override fun getCurrentPosition(): Long = positionMs
    override fun getVideoSurfaceView(): android.view.SurfaceView? = surfaceView

    override fun stopPlayback() {
        FileLogger.i(TAG, "stopPlayback() — clearing MPV state for transition")
        resolutionJob?.cancel()
        launchJob?.cancel()
        playJob?.cancel()
        isLoadingNewStream = true
        pendingStops++
        engine?.stop()
    }

    override fun seekTo(position: Long) {
        if (durationMs > 0) {
            preSeePositionMs = positionMs
            engine?.seek(position)
            scheduleSeekTimeout()
        } else {
            // Restore position on load
            pendingResumePositionMs = position
        }
    }

    override fun getPlayerProgressManager(): ProgressManager? = if (::progressManager.isInitialized) progressManager else null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.i(TAG, "=== MpvPlayerActivity CREATED ===")
        FileLogger.i(TAG, "Intent action: ${intent?.action}")

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::controlsManager.isInitialized && controlsManager.isControlsVisible()) {
                        controlsManager.hideUI()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        setContentView(R.layout.activity_mpv_player)
        surfaceView = findViewById(R.id.surface_view)

        composeView = findViewById(R.id.preplay_compose_view)
        composeView.setContent {
            val p = prePlayPayload
            if (p != null) {
                com.playbridge.player.preplay.PrePlayScreen(
                    payload = p,
                    isLaunching = isPrePlayLaunching,
                    launchCountdown = prePlayCountdown,
                    onStreamSelected = { stream ->
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

        historyStore = HistoryStore(this)
        resumeStore = com.playbridge.player.data.HistoryResumeStore(historyStore)
        progressManager = ProgressManager(
            context = this,
            historyStore = historyStore,
            lifecycleScope = lifecycleScope,
            playerActivity = this
        )

        // Setup MpvPlayerEngine
        try {
            engine = MpvPlayerEngine(this)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start MpvPlayerEngine", e)
            Toast.makeText(this, "Failed to start MPV player", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        engine?.let { currentEngine ->
            viewModel = com.playbridge.shared.player.PlayerViewModel(
                engine = currentEngine,
                resumeStore = resumeStore,
                scope = lifecycleScope,
            )
            vmUiJob = lifecycleScope.launch {
                viewModel.ui.collect { state -> handleVmUiState(state) }
            }
        }

        // Attach MPV rendering surface
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                engine?.attachSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                engine?.setSurfaceSize(w, h)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                engine?.detachSurface()
            }
        })
        // Eagerly attach if surface already exists (e.g. singleTop reuse)
        if (surfaceView.holder.surface?.isValid == true) {
            engine?.attachSurface(surfaceView.holder.surface)
        }

        // Engine adapter for MPV
        val engineAdapter = object : PlayerEngineAdapter {
            override val isPlaying: Boolean get() = isPlayingState
            override val currentPosition: Long get() = positionMs
            override val duration: Long get() = durationMs
            override val bufferedPosition: Long get() = (positionMs + bufferAheadMs).coerceAtMost(durationMs)
            override val streamInfo: String? get() = formatMpvStreamInfo()
            override val frameRate: Float get() = containerFps.toFloat()
            override val hdrFormat: String? get() = getMpvHdrFormat()

            override fun setLoudnessEnhancer(enabled: Boolean) {
                if (enabled) {
                    // Set audio filter for 15dB gain
                    MPVLib.setPropertyString("af", "volume=gain=15")
                    FileLogger.i(TAG, "MPV Loudness Enhancer enabled (+15dB)")
                } else {
                    MPVLib.setPropertyString("af", "")
                    FileLogger.i(TAG, "MPV Loudness Enhancer disabled")
                }
            }

            override fun play() { engine?.play() }
            override fun pause() { engine?.pause() }
            override fun seekTo(positionMs: Long) { this@MpvPlayerActivity.seekTo(positionMs) }
        }

        controlsManager = UnifiedControlsManager(
            controlsRoot          = findViewById(R.id.controls_root),
            controlsPanel         = findViewById(R.id.controls_panel),
            seekBar               = findViewById(R.id.player_seekbar),
            playPauseButton       = findViewById(R.id.btn_play_pause),
            streamInfoText        = findViewById(R.id.tv_stream_info),
            seasonInfoText        = findViewById(R.id.tv_season_info),
            elapsedText           = findViewById(R.id.tv_elapsed),
            remainingText         = findViewById(R.id.tv_remaining),
            titleText             = findViewById(R.id.title_text),
            hdrBadge              = findViewById(R.id.tv_hdr_badge),
            metaContainer         = findViewById(R.id.ll_stream_meta_container),
            bufferingSpinner      = findViewById(R.id.buffering_spinner),
            engine                = engineAdapter,
            engineType            = "MPV",
            tracksButton          = findViewById(R.id.btn_tracks),
            playlistButton        = findViewById(R.id.btn_playlist),
            streamsButton         = findViewById(R.id.btn_streams),
            prevButton            = findViewById(R.id.btn_prev),
            nextButton            = findViewById(R.id.btn_next),
            filterButton          = findViewById(R.id.btn_filter),
            loopButton            = findViewById(R.id.btn_loop),
            switchPlayerButton    = findViewById(R.id.btn_switch_player),
            onShowTrackSelection  = { showTrackSelectionDialog() },
            onShowPlaylist        = { showPlaylistPicker() },
            onShowStreams         = { showStreamSelectionDialog() },
            onSwitchPlayer        = { showSwitchPlayerDialog("internal_mpv") },
            onPrevious            = { playPreviousInPlaylist() },
            onNext                = { playNextInPlaylist() },
            onToggleLoop          = { setLooping(!isLooping) }
        )

        inputHandler = InputHandler(
            activity = this,
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            engine = engineAdapter,
            controls = controlsManager,
            isExternalOverlayVisible = { prePlayPayload != null || activeDialog != null }
        )

        // Register MPV observer NOW — after controlsManager is initialized — so that
        // property-change callbacks can safely reference controlsManager without crashing.
        // Format IDs: MPV_FORMAT_STRING=1, MPV_FORMAT_FLAG=3, MPV_FORMAT_INT64=4, MPV_FORMAT_DOUBLE=5
        MPVLib.addObserver(this)
        MPVLib.observeProperty("pause",               3) // Boolean
        MPVLib.observeProperty("time-pos",            4) // Long (seconds)
        MPVLib.observeProperty("duration",            4) // Long (seconds)
        MPVLib.observeProperty("height",              4) // Long (px)
        MPVLib.observeProperty("video-bitrate",       4) // Long (bits/s)
        MPVLib.observeProperty("video-codec",         1) // String
        MPVLib.observeProperty("audio-codec",         1) // String
        MPVLib.observeProperty("audio-channels",      1) // String e.g. "stereo", "5.1"
        MPVLib.observeProperty("demuxer-cache-time",  5) // Double (seconds buffered ahead)
        MPVLib.observeProperty("container-fps",       5) // Double (fps)

        val filter = IntentFilter().apply {
            addAction(ServerService.ACTION_REMOTE)
            addAction(ServerService.ACTION_CONTROL)
            addAction(ServerService.ACTION_QUEUE_ADD)
            addAction(ServerService.ACTION_PLAYLIST_JUMP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(remoteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(remoteReceiver, filter)
        }
        receiverRegistered = true

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

    private var lastOnNewIntentUrl: String? = null
    private var lastOnNewIntentTime = 0L

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (isFinishing) return
        val url = intent.getStringExtra(ServerService.EXTRA_URL)
        val now = System.currentTimeMillis()
        if (url != null && url == lastOnNewIntentUrl && (now - lastOnNewIntentTime) < 2000) {
            FileLogger.i(TAG, "Debounced duplicate onNewIntent for $url")
            return
        }
        lastOnNewIntentUrl = url
        lastOnNewIntentTime = now
        FileLogger.i(TAG, "onNewIntent received")

        // Cancel any pending resolution or countdown from a previous intent
        resolutionJob?.cancel()
        launchJob?.cancel()
        isPrePlayLaunching = false
        isPreBuffering = false

        stopPlayback()
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onPause() {
        super.onPause()
        if (::progressManager.isInitialized) progressManager.saveProgress()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(remoteReceiver)
            receiverRegistered = false
        }
        activeDialog?.dismiss()
        activeDialog = null
        vmUiJob?.cancel()
        if (::viewModel.isInitialized) {
            viewModel.dispose()
        }
        engine?.release()
        engine = null
        super.onDestroy()
    }

    /**
     * React to [PlayerViewModel] UI state changes (Step 5a).
     *
     * The Activity still owns all UI updates.  The VM collector runs in
     * parallel purely for logging and future load-bearing migration.
     */
    private fun handleVmUiState(state: com.playbridge.shared.player.PlayerUiState) {
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

    // ── MPV Events ───────────────────────────────────────────────────────────

    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") {
            isPlayingState = !value
            // Must post to the UI thread: this callback fires on MPV's native event thread.
            runOnUiThread {
                if (::controlsManager.isInitialized) {
                    controlsManager.updatePlayPauseIcon()
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "time-pos" -> {
                positionMs = value * 1000
            }
            "duration" -> {
                durationMs = value * 1000
            }
            "height" -> {
                videoHeight = value
                updateStreamInfo()
            }
            "video-bitrate" -> {
                videoBitrateBps = value
                updateStreamInfo()
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "video-codec" -> {
                videoCodecRaw = value
                updateStreamInfo()
            }
            "audio-codec" -> {
                audioCodecRaw = value
                updateStreamInfo()
            }
            "audio-channels" -> {
                audioChannels = value
                updateStreamInfo()
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "demuxer-cache-time" -> {
                bufferAheadMs = (value * 1000).toLong()
            }
            "container-fps" -> {
                containerFps = value
            }
        }
    }

    // Required by MPVLib.EventObserver — no-op for untyped property changes
    override fun eventProperty(property: String) {}

    // Required by MPVLib.EventObserver — no-op for MPVNode-typed property changes
    override fun eventProperty(property: String, value: MPVNode) {}

    private fun setLooping(loop: Boolean) {
        isLooping = loop
        controlsManager.updateLoopIcon(loop)
    }

    override fun event(eventId: Int, data: MPVNode) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                // A new file is starting; any pending stops from prior transitions are moot.
                pendingStops = 0
                runOnUiThread { controlsManager.showBuffering() }
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                isLoadingNewStream = false
                pendingStops = 0
                runOnUiThread {
                    controlsManager.hideBuffering()
                    
                    // Apply Loudness Enhancer if enabled
                    if (isLoudnessEnhancerEnabled) {
                        MPVLib.setPropertyString("af", "volume=gain=15")
                    }

                    if (containerFps > 0.0) {
                        updateRefreshRate(containerFps.toFloat())
                    }
                    if (pendingResumePositionMs > 0) {
                        engine?.seek(pendingResumePositionMs)
                        pendingResumePositionMs = 0
                    }
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                // Fired when playback resumes after buffering or seek
                seekHandler.removeCallbacks(seekTimeoutRunnable)
                runOnUiThread { controlsManager.hideBuffering() }
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                isLoadingNewStream = false
                if (pendingStops > 0) {
                    pendingStops--
                    runOnUiThread { controlsManager.hideBuffering() }
                    return
                }
                runOnUiThread {
                    controlsManager.hideBuffering()
                    if (playlistItems.isNotEmpty() && playlistIndex < playlistItems.size - 1) {
                        playNextInPlaylist()
                    } else {
                        val nav = seriesNavigator
                        if (nav != null && nav.hasNext()) {
                            playNextInPlaylist()
                        } else {
                            finish()
                        }
                    }
                }
            }
        }
    }

    // ── Internal Helpers ─────────────────────────────────────────────────────

    private val remoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ServerService.ACTION_REMOTE -> {
                    val key = intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY)
                    handleRemoteKey(key)
                }
                ServerService.ACTION_CONTROL -> {
                    val cmd = intent.getStringExtra(ServerService.EXTRA_COMMAND)
                    handleControlCommand(cmd)
                }
                ServerService.ACTION_QUEUE_ADD -> {
                    ServerService.drainPendingQueueItems().forEach { payload ->
                        playlistItems.add(payload)
                        FileLogger.i(TAG, "Queue add: ${payload.title ?: payload.url}")
                    }
                    controlsManager.setPlaylistVisible(true)
                    broadcastPlaylistStatus()
                }
                ServerService.ACTION_PLAYLIST_JUMP -> {
                    val index = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_JUMP_INDEX, -1)
                    if (index >= 0) {
                        FileLogger.i(TAG, "Playlist jump to index: $index")
                        playItemAtIndex(index)
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (isFinishing) return
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

        // Ensure prev/next buttons are visible for ANY series
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

        // Standard direct URL path
        composeView.visibility = android.view.View.GONE
        prePlayPayload = null

        val url = intent?.getStringExtra(ServerService.EXTRA_URL) ?: return
        val headers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(ServerService.EXTRA_HEADERS, java.util.HashMap::class.java) as? Map<String, String>
        } else {
            @Suppress("UNCHECKED_CAST")
            intent.getSerializableExtra(ServerService.EXTRA_HEADERS) as? Map<String, String>
        }
        val title = intent.getStringExtra(ServerService.EXTRA_TITLE)
        val subtitles = intent.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)

        // Apply title immediately if known
        title?.let { controlsManager.setTitle(it) }

        val displayTitle = if (seriesNavigator != null && seriesNavigator?.seriesTitle != null) {
            seriesNavigator?.seriesTitle
        } else {
            title
        }
        displayTitle?.let { controlsManager.setTitle(it) }

        this.subtitleUrls = subtitles ?: emptyList()
        this.currentHeaders = headers
        playVideo(url, headers)
    }

    private fun handleRemoteKey(key: String?) {
        inputHandler.handleRemoteCommand(key)
    }

    private fun handleControlCommand(command: String?) {
        inputHandler.handleControlCommand(command)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (inputHandler.handleKeyEvent(event.keyCode, event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    private fun handleSeek(direction: Int) {
        val delta = 10_000L * direction
        val newPos = (positionMs + delta).coerceIn(0, durationMs)
        controlsManager.setPendingSeekTime(newPos)
        seekTo(newPos)
        controlsManager.showSeekUI()
    }

    private fun scheduleSeekTimeout() {
        seekHandler.removeCallbacks(seekTimeoutRunnable)
        seekHandler.postDelayed(seekTimeoutRunnable, seekTimeoutMs)
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    private suspend fun playVideoInternal(url: String, headers: Map<String, String>?, startPaused: Boolean = false) {
        // Note: setupSeriesNavigator is already called by handleIntent before playVideo;
        // calling it again here would create a redundant SeriesNavigator instance.
        currentUrl = url
        if (seriesNavigator == null) {
            controlsManager.setSeasonInfo(null)
        }
        controlsManager.setStreamsVisible(seriesNavigator != null)
        FileLogger.i(TAG, "========== PLAY COMMAND RECEIVED ==========")
        FileLogger.i(TAG, "URL: $url")
        FileLogger.i(TAG, "Title: ${controlsManager.getTitle()}")
        FileLogger.i(TAG, "Headers: $headers")
        FileLogger.i(TAG, "Start Paused: $startPaused")
        FileLogger.i(TAG, "===========================================")


        // seekTo() will intercept this call while durationMs == 0 (file not yet loaded)
        // and stash the position in pendingResumePositionMs; it is applied on FILE_LOADED.
        val historyItem = progressManager.restoreProgress(url)
        if (historyItem != null) {
            // Apply playback speed
            if (historyItem.playbackSpeed != null) {
                currentPlaybackSpeed = historyItem.playbackSpeed
                engine?.setRate(currentPlaybackSpeed)
            }
            // Apply saved external subtitle URL; takes priority over any session-carried sub
            if (historyItem.externalSubtitleUrl != null) {
                currentSubtitleUrl = historyItem.externalSubtitleUrl
            }
        }

        progressManager.setCurrentMedia(
            url = url,
            title = controlsManager.getTitle(),
            contentType = null,
            headers = headers,
            playlistJson = if (playlistItems.isNotEmpty()) {
                try {
                    com.playbridge.shared.protocol.protocolJson.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(com.playbridge.shared.protocol.PlayPayload.serializer()),
                        playlistItems
                    )
                } catch (e: Exception) { null }
            } else null,
            playlistIndex = playlistIndex,
            externalSubtitleUrl = currentSubtitleUrl,
            playbackSpeed = currentPlaybackSpeed
        )

        val startPos = intent?.getLongExtra("extra_start_position", -1L) ?: -1L
        if (startPos > 0L) {
            pendingResumePositionMs = startPos
        }

        pendingStops++
        engine?.stop()

        if (startPaused) {
            engine?.pause()
        }

        val payload = com.playbridge.shared.protocol.PlayPayload(url = url, headers = headers)
        engine?.load(payload)

        // Re-apply external subtitle if present.
        currentSubtitleUrl?.let { subUrl ->
            engine?.attachExternalSubtitle(subUrl, null)
        }

        if (!startPaused) play()
    }

    private fun playVideo(url: String, headers: Map<String, String>?, startPaused: Boolean = false) {
        // Guard against MPV_EVENT_END_FILE from a prior stop() or a failed first HTTP attempt
        // triggering finish() before the new stream has loaded.
        isLoadingNewStream = true

        playJob?.cancel()
        playJob = lifecycleScope.launch {
            playVideoInternal(url, headers, startPaused)
        }
    }

    private fun getMpvHdrFormat(): String? {
        val colormatrix = MPVLib.getPropertyString("video-params/colormatrix") ?: ""
        val primaries = MPVLib.getPropertyString("video-params/primaries") ?: ""
        val gamma = MPVLib.getPropertyString("video-params/gamma") ?: ""
        val pixelformat = MPVLib.getPropertyString("video-params/pixelformat") ?: ""

        // Common HDR markers in MPV
        return when {
            gamma == "pq" || colormatrix == "bt.2020-ncl" || primaries == "bt.2020" -> {
                if (gamma == "hlg") "HLG" else "HDR10"
            }
            "10" in pixelformat || "12" in pixelformat -> {
                // If 10-bit but not caught by above, might be generic HDR or High Bit Depth
                if (gamma == "pq") "HDR10" else null 
            }
            else -> null
        }
    }

    private fun formatMpvStreamInfo(): String? {
        return buildString {
            if (videoHeight > 0) append("${videoHeight}p")
            if (videoCodecRaw.isNotEmpty()) {
                val short = when {
                    videoCodecRaw.contains("h264") -> "H.264"
                    videoCodecRaw.contains("hevc") -> "H.265"
                    videoCodecRaw.contains("vp9")  -> "VP9"
                    videoCodecRaw.contains("av1")  -> "AV1"
                    else -> videoCodecRaw.uppercase()
                }
                if (isNotEmpty()) append(" • ")
                append(short)
            }
            if (videoBitrateBps > 0) {
                val mbps = videoBitrateBps / 1_000_000.0
                if (isNotEmpty()) append(" • ")
                append("%.1f Mbps".format(mbps))
            }
            if (audioCodecRaw.isNotEmpty()) {
                val short = audioCodecRaw.uppercase()
                if (isNotEmpty()) append("  •  \uD83D\uDD0A $short")
                val ch = when (audioChannels.lowercase()) {
                    "stereo"              -> "2.0"
                    "5.1", "5.1(side)"   -> "5.1"
                    "7.1", "7.1(wide-side)" -> "7.1"
                    "mono"               -> "Mono"
                    else                 -> audioChannels
                }
                if (isNotEmpty()) append(" $ch")
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun updateStreamInfo() {
        // Handled via adapter streamInfo property in UnifiedControlsManager
    }

    /**
     * Show the stream selection dialog for Stremio sources.
     */
    @OptIn(ExperimentalTvMaterial3Api::class)
    private fun showStreamSelectionDialog() {
        val nav = seriesNavigator ?: return
        val currentUrl = this.currentUrl

        val wasPlaying = isPlayingState
        if (wasPlaying) pause()

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

                            // Update currentUrl so history saving works
                            this@MpvPlayerActivity.currentUrl = stream.url

                            playVideo(url = stream.url, headers = currentHeaders)
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
            if (wasPlaying) play()
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

        val wasPlaying = isPlayingState
        if (wasPlaying) pause()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setContent {
            PlayBridgeTVTheme {
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
                    onDismiss = { dialog.dismiss() }
                )
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            activeDialog = null
            if (wasPlaying) play()
            controlsManager.showControlsUI()
        }
        dialog.show()
    }

    private fun playSeriesEpisodeAtIndex(index: Int) {
        val nav = seriesNavigator ?: return
        navigationJob?.cancel()
        navigationJob = lifecycleScope.launch {
            isLoadingNewStream = true
            FileLogger.i(TAG, "SeriesNavigator: resolving episode at index $index")

            // Save progress for the current episode before switching
            progressManager.saveProgress()

            stopPlayback()
            controlsManager.showBuffering()
            val stream = nav.resolveAndAdvanceToIndex(index)
            if (stream != null) {
                // Early-return guard: a cancelled coroutine that slipped through the mutex
                // might resolve the same URL we are already playing — skip to avoid flicker.
                if (stream.url == currentUrl) {
                    controlsManager.hideBuffering()
                    return@launch
                }

                // Display season info on top left (e.g. "Season 1 (1x5)")
                val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                controlsManager.setSeasonInfo(seasonInfo)

                // Use the series title for the main title bar if available, else SxE
                val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"
                controlsManager.setTitle(mainTitle)

                // Update intent
                intent?.putExtra(ServerService.EXTRA_URL, stream.url)
                intent?.putExtra(ServerService.EXTRA_TITLE, mainTitle)

                playVideo(url = stream.url, headers = null)
                controlsManager.hideUI()
            } else {
                isLoadingNewStream = false
                controlsManager.hideBuffering()
                android.widget.Toast.makeText(this@MpvPlayerActivity, "Could not resolve episode", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Jump to a specific item in the playlist by index.
     */
    private fun playItemAtIndex(index: Int) {
        if (index < 0 || index >= playlistItems.size) return

        lifecycleScope.launch {
            // Save progress for the current episode before jumping, if it was playing
            if (::progressManager.isInitialized) progressManager.saveProgress()

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
            runOnUiThread {
                Toast.makeText(this@MpvPlayerActivity, title, Toast.LENGTH_SHORT).show()
                controlsManager.setTitle(title)
                controlsManager.hideUI()
            }

            stopPlayback()
            currentHeaders = item.headers
            playVideo(item.url, item.headers)
            broadcastPlaylistStatus()
        }
    }

    private fun playPreviousInPlaylist() {
        if (playlistItems.isEmpty()) {
            val nav = seriesNavigator
            if (nav != null && nav.hasPrev()) {
                navigationJob?.cancel()
                navigationJob = lifecycleScope.launch {
                    isLoadingNewStream = true
                    FileLogger.i(TAG, "SeriesNavigator: resolving previous episode")
                    progressManager.saveProgress()
                    stopPlayback()
                    controlsManager.showBuffering()

                    val stream = nav.resolvePrev()
                    if (stream != null) {
                        if (stream.url == currentUrl) {
                            controlsManager.hideBuffering()
                            return@launch
                        }
                        val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                        controlsManager.setSeasonInfo(seasonInfo)
                        val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"
                        controlsManager.setTitle(mainTitle)
                        intent?.putExtra(ServerService.EXTRA_URL, stream.url)
                        intent?.putExtra(ServerService.EXTRA_TITLE, mainTitle)
                        playVideo(url = stream.url, headers = null)
                        controlsManager.hideUI()
                    } else {
                        isLoadingNewStream = false
                        controlsManager.hideBuffering()
                        Toast.makeText(this@MpvPlayerActivity, "Could not resolve previous episode", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Already on first episode", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (playlistIndex <= 0) {
            Toast.makeText(this, "Already on first episode", Toast.LENGTH_SHORT).show()
            return
        }
        playItemAtIndex(playlistIndex - 1)
    }

    private fun playNextInPlaylist() {
        if (playlistItems.isEmpty()) {
            val nav = seriesNavigator
            if (nav != null && nav.hasNext()) {
                navigationJob?.cancel()
                navigationJob = lifecycleScope.launch {
                    isLoadingNewStream = true
                    FileLogger.i(TAG, "SeriesNavigator: resolving next episode")
                    progressManager.saveProgress()
                    stopPlayback()
                    controlsManager.showBuffering()

                    val stream = nav.resolveNext()
                    if (stream != null) {
                        if (stream.url == currentUrl) {
                            controlsManager.hideBuffering()
                            return@launch
                        }
                        val seasonInfo = "Season ${nav.currentSeason} (${nav.currentSeason}x${nav.currentEpisode})"
                        controlsManager.setSeasonInfo(seasonInfo)
                        val mainTitle = nav.seriesTitle ?: "S${nav.currentSeason}E${nav.currentEpisode}"
                        controlsManager.setTitle(mainTitle)
                        intent?.putExtra(ServerService.EXTRA_URL, stream.url)
                        intent?.putExtra(ServerService.EXTRA_TITLE, mainTitle)
                        playVideo(url = stream.url, headers = null)
                        controlsManager.hideUI()
                    } else {
                        isLoadingNewStream = false
                        controlsManager.hideBuffering()
                        Toast.makeText(this@MpvPlayerActivity, "No more episodes found", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } else {
                finish()
            }
            return
        }

        if (playlistIndex >= playlistItems.size - 1) {
            finish()
            return
        }
        playItemAtIndex(playlistIndex + 1)
    }

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

    private fun showTrackSelectionDialog() {
        val wasPlaying = isPlayingState
        if (wasPlaying) pause()

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        activeDialog = dialog
        val composeView = androidx.compose.ui.platform.ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setContent {
            PlayBridgeTVTheme {
                MpvTrackSelectionDialog(
                    audioTracks = buildTrackList().filter { it.type == "audio" },
                    subtitleTracks = buildTrackList().filter { it.type == "sub" },
                    externalSubtitleUrls = subtitleUrls,
                    currentExternalSubtitleUrl = currentSubtitleUrl,
                    currentPlaybackSpeed = currentPlaybackSpeed,
                    onDismiss = { dialog.dismiss() },
                    onAudioTrackSelected = { id ->
                        engine?.setAudioTrack(id?.toString())
                        dialog.dismiss()
                    },
                    onSubtitleTrackSelected = { id ->
                        currentSubtitleUrl = null
                        engine?.setSubtitleTrack(id?.toString())
                        dialog.dismiss()
                    },
                    onExternalSubtitleSelected = { url ->
                        currentSubtitleUrl = url
                        if (url != null) {
                            lifecycleScope.launch {
                                engine?.attachExternalSubtitle(url, null)
                            }
                        } else {
                            engine?.setSubtitleTrack(null)
                        }
                        dialog.dismiss()
                    },
                    onPlaybackSpeedSelected  = { speed ->
                        currentPlaybackSpeed = speed
                        engine?.setRate(speed)
                        dialog.dismiss()
                    }
                )
            }
        }

        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            activeDialog = null
            if (wasPlaying) play()
            controlsManager.showControlsUI()
        }
        dialog.show()
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

        controlsManager.setTitle(displayTitle)

        // Initialize player and start buffering but KEEP composeView visible
        playVideo(url, null, startPaused = true)

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
            play() // Ensure it starts playing
        }
    }

    private fun buildTrackList(): List<MpvTrack> {
        val json = try { MPVLib.getPropertyString("track-list") ?: return emptyList() }
                   catch (e: Exception) { return emptyList() }
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                MpvTrack(
                    id = obj.getInt("id"),
                    type = obj.getString("type"),
                    title = obj.optString("title", "Track ${obj.getInt("id")}"),
                    lang = if (obj.has("lang")) obj.getString("lang") else null,
                    codec = if (obj.has("codec")) obj.getString("codec") else null,
                    isSelected = obj.optBoolean("selected", false)
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Stage a system TTF as libass's fallback font at `$HOME/.mpv/subfont.ttf` — the path
     * libmpv actually reads. This relies on [onCreate] having already overridden $HOME to
     * [filesDir] via Os.setenv; otherwise mpv's config dir resolves to a path the app
     * can't write to and this is a no-op.
     *
     * Overwrites on every launch (cheap, ~150KB) so a prior build that staged the file at
     * a wrong path doesn't leave a stale empty marker behind. If no readable system font
     * is found, we log and continue — playback still works, only subtitle rendering suffers.
     */
    private fun ensureSubtitleFallbackFont() {
        // Android TV / Android Q+ ship Roboto; older stock ROMs fall back to DroidSans.
        // Noto is included as a last-resort wide-glyph option (covers most scripts).
        val candidates = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/DroidSans.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/NotoSansCJK-Regular.ttc"
        )
        val src = candidates
            .map { java.io.File(it) }
            .firstOrNull { it.exists() && it.canRead() && it.length() > 0L }

        if (src == null) {
            FileLogger.w(TAG, "No readable /system/fonts candidate — libass will have no fallback and subtitles will not render")
            return
        }

        // libmpv looks for `$HOME/.mpv/subfont.ttf`.
        // Also stage it in the scannable 'fonts/' directory with its original name
        // so libass can resolve the family name (e.g. "Roboto").
        val destinations = listOf(
            java.io.File(filesDir, ".mpv/subfont.ttf"),
            java.io.File(filesDir, "fonts/${src.name}")
        )
        for (dest in destinations) {
            try {
                dest.parentFile?.mkdirs()
                src.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                FileLogger.i(TAG, "Staged libass fallback font: ${src.name} -> ${dest.absolutePath} (${dest.length()} bytes)")
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to stage subtitle fallback font at ${dest.absolutePath}", e)
                if (dest.exists() && dest.length() == 0L) dest.delete()
            }
        }
    }
}
