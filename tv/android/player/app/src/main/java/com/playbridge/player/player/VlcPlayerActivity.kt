package com.playbridge.player.player

import com.playbridge.shared.player.PlaybackEngine
import com.playbridge.shared.player.PlaybackState
import com.playbridge.shared.player.VlcPlayerEngine
import com.playbridge.shared.player.VideoFilter
import com.playbridge.shared.player.M3uParser

import android.content.Intent
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceView
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.runtime.getValue
import com.playbridge.player.R
import com.playbridge.player.server.ServerService
import com.playbridge.player.util.getStringMapExtra
import androidx.compose.runtime.collectAsState
import com.playbridge.player.data.HistoryStore
import com.playbridge.player.ui.player.PlayerControlsOverlay
import com.playbridge.player.ui.player.PlayerControlsViewModel
import com.playbridge.player.ui.player.PlayerControlsState
import com.playbridge.player.ui.player.SettingsTab
import com.playbridge.player.ui.player.ActiveOverlay
import com.playbridge.player.ui.player.UnifiedTrack
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import com.playbridge.player.logging.FileLogger

class VlcPlayerActivity : PlayerActivity(), IVLCVout.Callback {

    companion object {
        private const val TAG = "VlcPlayerActivity"
    }

    private var engine: VlcPlayerEngine? = null
    private lateinit var viewModel: com.playbridge.shared.player.PlayerViewModel
    private lateinit var resumeStore: com.playbridge.player.data.HistoryResumeStore
    private var vmUiJob: kotlinx.coroutines.Job? = null
    private lateinit var surfaceView: SurfaceView
    private val controlsViewModel = PlayerControlsViewModel()
    private val videoFilterManager = com.playbridge.shared.player.VideoFilterManager()
    private lateinit var inputHandler: InputHandler
    private lateinit var engineAdapter: PlayerEngineAdapter

    private val playerLock = Any()

    private fun setupMediaPlayer() {
        synchronized(playerLock) {
            // Recreate engine to ensure a clean native state and avoid resource race conditions
            engine?.release()
            vmUiJob?.cancel()
            if (::viewModel.isInitialized) {
                viewModel.dispose()
            }
            try {
                engine = VlcPlayerEngine(this)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to initialize VlcPlayerEngine", e)
                return
            }

            val currentEngine = engine ?: return
            val player = currentEngine.getMediaPlayer() ?: return

            viewModel = com.playbridge.shared.player.PlayerViewModel(
                engine = currentEngine,
                resumeStore = resumeStore,
                scope = lifecycleScope,
            )
            vmUiJob = lifecycleScope.launch {
                viewModel.ui.collect { state -> handleVmUiState(state) }
            }

            runOnUiThread {
                if (::surfaceView.isInitialized) {
                    surfaceView.visibility = android.view.View.VISIBLE
                }
            }
            player.setEventListener { event ->
                handlePlayerEvent(event)
            }
            player.vlcVout.apply {
                setVideoView(surfaceView)
                addCallback(this@VlcPlayerActivity)
                attachViews()
            }
            player.scale = 0f

            // 5a proxy: sync Activity-owned state into the VM so it stays consistent.
            if (playlistItems.isNotEmpty()) {
                viewModel.setPlaylist(playlistItems, playlistIndex)
            }
        }
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

    private fun handlePlayerEvent(event: MediaPlayer.Event) {
        val player = engine?.getMediaPlayer() ?: return
        when (event.type) {
            MediaPlayer.Event.Opening ->
                FileLogger.i(TAG, "Opening: ${originalM3u8Url ?: "(unknown)"}")

            MediaPlayer.Event.Buffering -> {
                val pct = event.buffering.toInt()
                if (pct < 100) FileLogger.d(TAG, "Buffering: $pct%")
            }

            MediaPlayer.Event.Playing -> {
                FileLogger.i(TAG, "Playing at ${player.time}ms")
                isLoadingNewStream = false
                updateStreamInfo()
                // Defer resume seek until VLC is actually playing and ready to accept seeks.
                pendingResumeTime?.let { resumeAt ->
                    pendingResumeTime = null
                    runOnUiThread { player.time = resumeAt }
                }
                runOnUiThread { applyPreferredLanguages() }
                cancelPlaybackWatchdog()
                
                // Trigger cinematic countdown only after we are connected and ready
                if (controlsViewModel.controlsState.value.prePlayMetadata != null) {
                    player.pause()
                    triggerPrePlayCountdown(controlsViewModel) {
                        FileLogger.i(TAG, "Countdown finished - starting playback")
                        player.play()
                        controlsViewModel.setPrePlay(null)
                    }
                }
                
                // Detect and apply refresh rate matching
                val fps = calculateVlcFrameRate()
                if (fps > 0f) {
                    runOnUiThread { updateRefreshRate(fps) }
                }

                // Apply Loudness Enhancer if enabled
                if (isLoudnessEnhancerEnabled) {
                    player.volume = 150 // +50% boost
                } else {
                    player.volume = 100
                }
            }

            MediaPlayer.Event.Paused ->
                FileLogger.i(TAG, "Paused at ${player.time}ms")

            MediaPlayer.Event.Stopped ->
                FileLogger.i(TAG, "Stopped")

            MediaPlayer.Event.EncounteredError -> {
                FileLogger.e(TAG, "VLC encountered an error (url=${originalM3u8Url ?: "(unknown)"})")
                isLoadingNewStream = false
                cancelPlaybackWatchdog()
                handleVlcError()
            }

            MediaPlayer.Event.PositionChanged -> {
                // UnifiedControlsManager handles progress updates automatically via its internal runnable
            }

            MediaPlayer.Event.EndReached -> {
                FileLogger.i(TAG, "End reached — loop=$isLooping playlist size=${playlistItems.size}, index=$playlistIndex")
                if (isLooping) {
                    runOnUiThread {
                        player.time = 0
                        player.play()
                    }
                } else {
                    runOnUiThread {
                        if (isLoadingNewStream) {
                            FileLogger.i(TAG, "Ignoring EndReached because new stream is loading")
                            return@runOnUiThread
                        }

                        if (playlistItems.isNotEmpty() && playlistIndex < playlistItems.size - 1) {
                            playNextInPlaylist()
                        } else {
                            finish()
                        }
                    }
                }
            }
        }
    }
    private var currentSubtitleUrl: String? = null
    private var currentPlaybackSpeed: Float = 1.0f
    private var currentVideoScalingMode: String = "Fit"

    // Settings state
    private var originalM3u8Url: String? = null
    private var currentHeaders: Map<String, String>? = null

    // Playlist state
    private var playlistItems: MutableList<playbridge.PlayPayload> = mutableListOf()
    private var playlistIndex: Int = 0

    // Preferred language selections — persisted across stream switches and app restarts
    private var preferredAudioLanguage: String? = null
    private var preferredSubtitleLanguage: String? = null

    // Loop state
    private var isLooping = false
    private var isLoadingNewStream = false

    // Settings dialog state


    private lateinit var composeView: androidx.compose.ui.platform.ComposeView

    private var surfaceLayoutListener: android.view.View.OnLayoutChangeListener? = null

    private lateinit var progressManager: ProgressManager
    private lateinit var historyStore: HistoryStore
    private var receiverRegistered = false

    // Settings state
    private var subtitleUrls: List<String> = emptyList()
    private var subtitleUrl: String? = null

    // Seek buffering
    private var pendingSeekTime: Long? = null
    private var pendingResumeTime: Long? = null
    private val seekHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var seekCommitCount = 0L

    private val performSeekRunnable = Runnable {
        pendingSeekTime?.let { targetTime ->
            engine?.getMediaPlayer()?.time = targetTime
            pendingSeekTime = null
            schedulePostSeekVideoResync()
        }
    }

    /**
     * Intelligently schedules a post-seek video decoder resync to fix frozen video.
     * Uses Media.Stats to detect if frames are actually stuck before applying the track toggle.
     * If stuck, attempts up to 4 track toggles with exponential backoff.
     */
    private fun schedulePostSeekVideoResync() {
        val commitId = ++seekCommitCount

        fun attemptResync(delayMs: Long, retryCount: Int) {
            if (retryCount > 4) return // Max 4 retries

            // Wait to let video resume. If it hasn't, we will check.
            seekHandler.postDelayed({
                if (commitId != seekCommitCount) return@postDelayed
                val player = engine?.getMediaPlayer() ?: return@postDelayed
                if (!player.isPlaying || pendingSeekTime != null) return@postDelayed

                val media = player.media ?: return@postDelayed
                val statsBefore = media.stats?.displayedPictures?.toInt() ?: return@postDelayed

                // Wait a short duration to see if pictures are actually being displayed
                seekHandler.postDelayed({
                    if (commitId != seekCommitCount) return@postDelayed
                    if (!player.isPlaying || pendingSeekTime != null) return@postDelayed

                    val statsAfter = media.stats?.displayedPictures?.toInt() ?: return@postDelayed
                    if (statsAfter <= statsBefore) {
                        // Video is stuck! No new pictures displayed in the last interval.
                        val videoTrack = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Video)
                            ?: return@postDelayed

                        FileLogger.w(TAG, "Post-seek video stuck (frames: $statsBefore -> $statsAfter). Resync attempt $retryCount")
                        player.unselectTrackType(org.videolan.libvlc.interfaces.IMedia.Track.Type.Video)
                        seekHandler.postDelayed({
                            if (commitId == seekCommitCount) {
                                engine?.getMediaPlayer()?.selectTrack(videoTrack.id)
                                attemptResync(delayMs * 2, retryCount + 1)
                            }
                        }, 200)
                    } else {
                        FileLogger.d(TAG, "Post-seek video is rendering normally (frames: $statsBefore -> $statsAfter).")
                    }
                }, 500)
            }, delayMs)
        }

        // Start checking 500ms after the seek committed
        attemptResync(500L, 1)
    }

    override fun play() { engine?.play() }
    override fun pause() { engine?.pause() }
    override fun isPlaying(): Boolean = engine?.getMediaPlayer()?.isPlaying == true
    override fun getMediaDuration(): Long = engine?.getMediaPlayer()?.length ?: 0L
    override fun getCurrentPosition(): Long = (engine?.getMediaPlayer()?.time) ?: 0L
    override fun seekTo(position: Long) {
        val player = engine?.getMediaPlayer() ?: return
        player.time = position
        pendingSeekTime = null
        seekHandler.removeCallbacks(performSeekRunnable)
    }
    override fun getVideoSurfaceView(): android.view.SurfaceView? = if (this::surfaceView.isInitialized) surfaceView else null

    override fun stopPlayback() {
        FileLogger.i(TAG, "stopPlayback() — clearing surface for transition")
        launchJob?.cancel()
        playJob?.cancel()
        isLoadingNewStream = true
        synchronized(playerLock) {
            engine?.let { e ->
                e.getMediaPlayer()?.vlcVout?.detachViews()
                e.stop()
            }
        }
        runOnUiThread {
            controlsViewModel.hideControls()
            if (::surfaceView.isInitialized) {
                surfaceView.visibility = android.view.View.INVISIBLE
            }
        }
    }

    override fun getPlayerProgressManager(): ProgressManager? = if (::progressManager.isInitialized) progressManager else null

    private val remoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ServerService.ACTION_REMOTE -> {
                    val key = intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY)
                    inputHandler.handleRemoteCommand(key)
                }
                ServerService.ACTION_CONTROL -> {
                    val command = intent.getStringExtra(ServerService.EXTRA_COMMAND)
                    when {
                        command == "loop_on"  -> setLooping(true)
                        command == "loop_off" -> setLooping(false)
                        command?.startsWith("audio_track:") == true ->
                            applyVlcTrackSelection("audio", command.removePrefix("audio_track:"))
                        command?.startsWith("sub_track:") == true -> {
                            val id = command.removePrefix("sub_track:")
                            // Resolve embedded vs external subtitle from the track list.
                            val type = controlsViewModel.controlsState.value.subtitleTracks
                                .firstOrNull { it.id == id }?.type ?: "sub"
                            applyVlcTrackSelection(type, id)
                        }
                        command?.startsWith("scaling:") == true -> {
                            val mode = command.removePrefix("scaling:")
                            engine?.setVideoScale(mode)
                            controlsViewModel.setVideoScaling(mode)
                        }
                        command?.startsWith("switch_player:") == true ->
                            switchPlayer(command.removePrefix("switch_player:"))
                        else -> inputHandler.handleControlCommand(command)
                    }
                }
                ServerService.ACTION_PLAY -> {
                    val url = intent.getStringExtra(ServerService.EXTRA_URL)
                    val title = intent.getStringExtra(ServerService.EXTRA_TITLE)
                    val headers = intent.getStringMapExtra(ServerService.EXTRA_HEADERS)

                    val subtitles = intent.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)

                    if (url != null) {
                        stopPlayback()
                        playVideo(url, headers, subtitles = subtitles)
                    }
                }
                ServerService.ACTION_QUEUE_ADD -> {
                    ServerService.drainPendingQueueItems().forEach { payload ->
                        playlistItems.add(payload)
                    }
                    controlsViewModel.setPlaylistVisible(true)
                    broadcastPlaylistStatus()
                }
                ServerService.ACTION_PLAYLIST_JUMP -> {
                    val index = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_JUMP_INDEX, -1)
                    if (index >= 0) {
                        playItemAtIndex(index) // broadcasts status internally
                    }
                }
                ServerService.ACTION_RESYNC -> {
                    broadcastNowPlayingResync(controlsViewModel)
                    broadcastPlaylistStatus()
                }
            }
        }
    }

    private fun handleRemoteKey(key: String?) {
        inputHandler.handleRemoteCommand(key)
    }

    private fun handleControlCommand(command: String?) {
        inputHandler.handleControlCommand(command)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.i(TAG, "=== VlcPlayerActivity CREATED ===")
        FileLogger.i(TAG, "Intent action: ${intent?.action}")

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

        setContentView(R.layout.activity_vlc_player)
        findViewById<androidx.compose.ui.platform.ComposeView>(R.id.modern_controls_view).apply {
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
                        onPrev = { playPreviousInPlaylist() },
                        onNext = { playNextInPlaylist() },
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
                            applyVlcTrackSelection(track.type, track.id)
                        },
                        onSpeedSelected = { speed ->
                            engine?.setPlaybackSpeed(speed)
                            controlsViewModel.setPlaybackSpeed(speed)
                        },
                        onScalingSelected = { mode ->
                            engine?.setVideoScale(mode)
                            controlsViewModel.setVideoScaling(mode)
                        },
                        onPlaylistItemPicked = { index ->
                            controlsViewModel.hideOverlay()
                            playItemAtIndex(index)
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
        surfaceView = findViewById(R.id.surface_view)

        surfaceView = findViewById(R.id.surface_view)

        historyStore = HistoryStore(this)
        resumeStore = com.playbridge.player.data.HistoryResumeStore(historyStore)
        progressManager = ProgressManager(
            context = this,
            historyStore = historyStore,
            lifecycleScope = lifecycleScope,
            playerActivity = this
        )
        
        val engineAdapter = object : PlayerEngineAdapter {
            override val isPlaying: Boolean get() = engine?.getMediaPlayer()?.isPlaying == true
            override val currentPosition: Long get() = engine?.getMediaPlayer()?.time ?: 0L
            override val duration: Long get() = engine?.getMediaPlayer()?.length ?: 0L
            override val bufferedPosition: Long get() = (engine?.getMediaPlayer()?.time ?: 0) + 1000 // VLC dummy buffer for UI
            override val streamInfo: String? get() = formatVlcStreamInfo()
            override val frameRate: Float get() = calculateVlcFrameRate()
            override val hdrFormat: String? get() = null // VLC doesn't expose HDR format details easily via JNI

            override fun setLoudnessEnhancer(enabled: Boolean) {
                engine?.getMediaPlayer()?.volume = if (enabled) 150 else 100
            }

            override fun setSubtitleDelay(delayMs: Long) {
                // VLC handles this via spu-delay (ms)
                engine?.getMediaPlayer()?.setSpuDelay(delayMs)
            }

            override fun setPlaybackSpeed(speed: Float) {
                engine?.getMediaPlayer()?.rate = speed
            }

            override fun play() { engine?.getMediaPlayer()?.play() }
            override fun pause() { engine?.getMediaPlayer()?.pause() }
            override fun seekTo(positionMs: Long) { engine?.getMediaPlayer()?.time = positionMs }
        }

        controlsViewModel.setEngine(engineAdapter, "internal_vlc")

        // Stream now-playing status + available tracks to the phone (shared base impl).
        startNowPlayingBroadcasts(controlsViewModel)

        inputHandler = InputHandler(
            activity = this,
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            engine = engineAdapter,
            controls = controlsViewModel,
            isExternalOverlayVisible = { controlsViewModel.controlsState.value.prePlayMetadata != null || controlsViewModel.controlsState.value.activeOverlay != ActiveOverlay.NONE }
        )

        val filter = IntentFilter().apply {
            addAction(ServerService.ACTION_REMOTE)
            addAction(ServerService.ACTION_CONTROL)
            addAction(ServerService.ACTION_QUEUE_ADD)
            addAction(ServerService.ACTION_PLAYLIST_JUMP)
            addAction(ServerService.ACTION_PLAY)
            addAction(ServerService.ACTION_RESYNC)
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
            controlsViewModel.setPlaylistVisible(true)
            broadcastPlaylistStatus()
        }
    }

    private var lastOnNewIntentUrl: String? = null
    private var lastOnNewIntentTime = 0L

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.getStringExtra(ServerService.EXTRA_URL)
        val now = System.currentTimeMillis()
        if (url != null && url == lastOnNewIntentUrl && (now - lastOnNewIntentTime) < 2000) {
            FileLogger.i(TAG, "Debounced duplicate onNewIntent for $url")
            return
        }
        lastOnNewIntentUrl = url
        lastOnNewIntentTime = now
        FileLogger.i(TAG, "onNewIntent received")

        launchJob?.cancel()

        stopPlayback()
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        setupPlaybackExtras(intent)

        // Read playlist if present
        val isPlaylist = intent.getBooleanExtra(ServerService.EXTRA_IS_PLAYLIST, false)
        val inMemoryPlaylist = PlaylistStore.currentPlaylist
        if (isPlaylist && inMemoryPlaylist != null && inMemoryPlaylist.isNotEmpty()) {
            playlistItems = inMemoryPlaylist.toMutableList()
            playlistIndex = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_INDEX, 0)
        } else {
            playlistItems = mutableListOf()
        }

        val hasPlaylist = playlistItems.isNotEmpty()

        // Show playlist button when a playlist is active
        controlsViewModel.setPlaylistVisible(hasPlaylist)

        // Sync the phone's episode list to this new content — refreshes it for a
        // new playlist, or clears it when a single video replaces an earlier one.
        broadcastPlaylistStatus()

        // Show navigation buttons if a playlist is active.
        controlsViewModel.setNavigationVisible(hasPlaylist)

        controlsViewModel.setSeasonInfo(null)


        // Standard direct URL path
        handlePrePlayMetadata(intent, controlsViewModel)

        val url = intent.getStringExtra(ServerService.EXTRA_URL)
        val title = intent.getStringExtra(ServerService.EXTRA_TITLE)

        val subtitles = intent.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)
        if (subtitles != null) {
            subtitleUrls = subtitles
            if (currentSubtitleUrl == null) {
                currentSubtitleUrl = subtitles.firstOrNull()
            }
        }

        val headers = intent.getStringMapExtra(ServerService.EXTRA_HEADERS)

        // Restore saved language preferences from history intent so they survive app restarts.
        intent.getStringExtra(ServerService.EXTRA_PREFERRED_AUDIO_LANG)?.let {
            preferredAudioLanguage = it
            FileLogger.i(TAG, "Restored preferred audio language: $it")
        }
        intent.getStringExtra(ServerService.EXTRA_PREFERRED_SUBTITLE_LANG)?.let {
            preferredSubtitleLanguage = it
            FileLogger.i(TAG, "Restored preferred subtitle language: $it")
        }

        controlsViewModel.setTitle(title ?: "")



        if (url == null) {
            Toast.makeText(this, "No URL provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        originalM3u8Url = url
        currentHeaders = headers

        startPlaybackWatchdog("internal_vlc")
        synchronized(playerLock) {
            playVideo(url, headers, subtitles = subtitles)
        }
    }

    private fun playNextInPlaylist() {
        if (playlistItems.isEmpty()) {
            finish()
            return
        }

        if (playlistIndex >= playlistItems.size - 1) {
            finish()
            return
        }
        playItemAtIndex(playlistIndex + 1)
    }

    private fun playItemAtIndex(index: Int) {
        if (playlistItems.isEmpty() || index < 0 || index >= playlistItems.size) return
        playlistIndex = index
        if (::viewModel.isInitialized) {
            viewModel.setPlaylist(playlistItems, playlistIndex)
        }
        controlsViewModel.showPlaylist(playlistItems, playlistIndex)
        playPlaylistItem(playlistItems[index])
        broadcastPlaylistStatus()
    }

    private fun playPreviousInPlaylist() {
        if (playlistItems.isEmpty()) {
            android.widget.Toast.makeText(this, "Already on first item", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        if (playlistIndex <= 0) {
            android.widget.Toast.makeText(this, "Already on first episode", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        playItemAtIndex(playlistIndex - 1)
    }

    /**
     * Broadcast current playlist state to the phone via WebSocket.
     */
    /**
     * Apply an audio/subtitle/video track selection by its [UnifiedTrack] id.
     * Shared by the TV's own track dialog and phone `audio_track:`/`sub_track:` commands.
     */
    private fun applyVlcTrackSelection(type: String, id: String) {
        when (type) {
            "audio" -> engine?.setAudioTrack(id)
            "video" -> engine?.setVideoTrack(id)
            "sub" -> {
                subtitleUrl = null
                engine?.setSubtitleTrack(id)
            }
            "external_sub" -> {
                engine?.setSubtitleTrack(null)
                currentSubtitleUrl = id
                controlsViewModel.loadExternalSubtitle(id, currentHeaders)
            }
        }
        updateUnifiedTracks() // Refresh selection state
    }

    private fun broadcastPlaylistStatus() {
        // Always send (even empty) so the phone clears a stale episode list when
        // single/non-playlist content replaces an earlier playlist.
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
                put("currentIndex", if (playlistItems.isEmpty()) 0 else playlistIndex)
                put("totalCount", playlistItems.size)
            }.toString()

            ServerService.broadcastPlaylistStatus(statusJson)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to broadcast playlist status: ${e.message}")
        }
    }

    /**
     * Show the stream selection dialog for Stremio sources.
     */
    @OptIn(ExperimentalTvMaterial3Api::class)
    // Not supported in dumb mode
    private fun showStreamSelectionOverlay() {
    }

    /**
     * Resolve streams for the current video and update the picker.
     */
    private fun resolveStreamsForCurrentVideo() {
        // Not supported in dumb mode
    }

    private fun showPlaylistOverlay() {
        if (playlistItems.isEmpty()) return

        val displayItems = playlistItems
        val displayIndex = playlistIndex

        val wasPlaying = engine?.getMediaPlayer()?.isPlaying == true
        if (wasPlaying) engine?.pause()

        controlsViewModel.showPlaylist(displayItems, displayIndex)
    }

    private fun showVideoFilterOverlay() {
        val wasPlaying = engine?.getMediaPlayer()?.isPlaying == true
        if (wasPlaying) engine?.pause()

        val currentFilter = videoFilterManager.currentFilter
        val customVals = floatArrayOf(videoFilterManager.customBrightness, videoFilterManager.customContrast, videoFilterManager.customSaturation)

        // Get a screenshot frame from the video for preview
        // Note: Assuming getVideoSurfaceView() exists or is accessible
        val surfaceView = findViewById<android.view.SurfaceView>(com.playbridge.player.R.id.surface_view)
        surfaceView?.let { sv ->
            try {
                val bitmap = android.graphics.Bitmap.createBitmap(sv.width, sv.height, android.graphics.Bitmap.Config.ARGB_8888)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    android.view.PixelCopy.request(sv, bitmap, { result ->
                        if (result == android.view.PixelCopy.SUCCESS) {
                            controlsViewModel.showVideoFilter(currentFilter, customVals[0], customVals[1], customVals[2], bitmap)
                        } else {
                            controlsViewModel.showVideoFilter(currentFilter, customVals[0], customVals[1], customVals[2], null)
                        }
                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                } else {
                    controlsViewModel.showVideoFilter(currentFilter, customVals[0], customVals[1], customVals[2], null)
                }
            } catch (e: Exception) {
                controlsViewModel.showVideoFilter(currentFilter, customVals[0], customVals[1], customVals[2], null)
            }
        } ?: run {
            controlsViewModel.showVideoFilter(currentFilter, customVals[0], customVals[1], customVals[2], null)
        }
    }

    private fun showPlaylistPicker() {
        showPlaylistOverlay()
    }



    private fun playPlaylistItem(item: playbridge.PlayPayload) {
        isLoadingNewStream = true
        syncSelectionsToProgressManager()
        progressManager.saveProgress()

        controlsViewModel.updateMetadata(
            title = item.title ?: "",
            subtitle = null,
            streamInfo = null,
            hdrFormat = null
        )
        
        originalM3u8Url = item.url
        val itemHeaders = item.headers.takeIf { it.isNotEmpty() }
        currentHeaders = itemHeaders
        subtitleUrls = item.subtitles
        currentSubtitleUrl = null

        if (item.url.contains(".m3u8", ignoreCase = true)) {
            lifecycleScope.launch {
                M3uParser.parseMasterPlaylist(item.url, itemHeaders)
                playVideo(item.url, itemHeaders)
            }
        } else {
            playVideo(item.url, itemHeaders)
        }
    }

    /**
     * Enable or disable single-video loop mode for VLC.
     * Looping is implemented by seeking to 0 and replaying on EndReached.
     */
    private fun setLooping(enabled: Boolean) {
        isLooping = enabled
        controlsViewModel.setLooping(enabled)
        FileLogger.i(TAG, "Loop mode: $enabled")
        if (::viewModel.isInitialized) {
            viewModel.setLooping(enabled)
        }
    }

    // ── Video scaling helpers ─────────────────────────────────────────────────

    /**
     * Map VLC scaling mode string → Int for [ProgressManager] persistence.
     *
     * Media3 RESIZE_MODE constants are re-used as numeric keys since ProgressManager
     * stores a single Int.  "Center" (original size) uses RESIZE_MODE_ZOOM (4) so it
     * round-trips distinctly from "Fit" (0).  "16:9" / "4:3" use the fixed-dimension
     * constants (1 / 2) which ExoPlayer never writes via this path.
     */
    private fun vlcScalingModeToInt(mode: String): Int? = when (mode) {
        "Fit"    -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT          // 0
        "16:9"   -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH  // 1
        "4:3"    -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT // 2
        "Fill"   -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL         // 3
        "Center" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM         // 4
        else     -> null
    }

    /** Reverse of [vlcScalingModeToInt]. */
    private fun vlcScalingModeFromInt(value: Int): String = when (value) {
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH  -> "16:9"
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT -> "4:3"
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL         -> "Fill"
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM         -> "Center"
        else                                                                -> "Fit"
    }

    /**
     * Apply [mode] to the live [engine?.getMediaPlayer()].  Single source of truth used by both
     * the settings dialog callback and history restore so they stay in sync.
     */
    private fun applyVlcScalingMode(mode: String) {
        val player = engine?.getMediaPlayer() ?: return
        when (mode) {
            "Fit" -> { player.scale = 0f; player.aspectRatio = null }
            "Fill" -> { player.scale = 0f; player.aspectRatio = "16:9" }
            "16:9" -> { player.scale = 0f; player.aspectRatio = "16:9" }
            "4:3"  -> { player.scale = 0f; player.aspectRatio = "4:3" }
            // Center: scale=1f renders at the video's native pixel size, no stretching.
            "Center" -> { player.scale = 1f; player.aspectRatio = null }
        }
    }

    /**
     * Auto-select tracks matching [preferredAudioLanguage] / [preferredSubtitleLanguage].
     *
     * Called once per stream on the first Playing event. If a track with the preferred language
     * exists and is not already selected, it is selected silently. If no match is found the
     * current VLC default is left unchanged — we never force an unexpected track.
     */
    private fun applyPreferredLanguages() {
        val player = engine?.getMediaPlayer() ?: return

        preferredAudioLanguage?.let { lang ->
            val tracks = player.getTracks(org.videolan.libvlc.interfaces.IMedia.Track.Type.Audio) ?: return@let
            val current = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Audio)
            if (current?.language == lang) return@let // already correct
            val match = tracks.firstOrNull { it.language == lang }
            if (match != null) {
                FileLogger.i(TAG, "Auto-selecting preferred audio language '$lang' (track ${match.id})")
                player.selectTrack(match.id)
            }
        }

        preferredSubtitleLanguage?.let { lang ->
            val tracks = player.getTracks(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text) ?: return@let
            val current = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text)
            if (current?.language == lang) return@let
            val match = tracks.firstOrNull { it.language == lang }
            if (match != null) {
                FileLogger.i(TAG, "Auto-selecting preferred subtitle language '$lang' (track ${match.id})")
                player.selectTrack(match.id)
            }
        }
    }

    private fun formatVlcStreamInfo(): String? {
        val player = engine?.getMediaPlayer() ?: return null
        val videoTrack = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Video)
        val audioTrack = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Audio)

        return buildString {
            if (videoTrack != null) {
                append(videoTrack.name ?: "Video")
            }
            if (audioTrack != null) {
                if (isNotEmpty()) append("  •  ")
                append("\uD83D\uDD0A ")
                append(audioTrack.name ?: "Audio")
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun updateStreamInfo() {
        // Handled via adapter streamInfo property in UnifiedControlsManager
    }

    private fun handleVlcError() {
        FileLogger.e(TAG, "handleVlcError — playlist size=${playlistItems.size}, index=$playlistIndex, url=${originalM3u8Url ?: "(unknown)"}")
        runOnUiThread {
            if (playlistItems.isNotEmpty() && playlistIndex < playlistItems.size - 1) {
                android.widget.Toast.makeText(this, "Link failed, skipping to next...", android.widget.Toast.LENGTH_SHORT).show()
                playNextInPlaylist()
            } else {
                android.widget.Toast.makeText(this, "VLC encountered an error", android.widget.Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private var playJob: kotlinx.coroutines.Job? = null
    /** Serialises Stremio series navigation. Cancelled before each new nav request. */
    private var navigationJob: kotlinx.coroutines.Job? = null

    private fun playVideo(url: String, headers: Map<String, String>?, resumeTime: Long? = null, startPaused: Boolean = false, subtitles: ArrayList<String>? = null) {
        isLoadingNewStream = true
        val title = controlsViewModel.controlsState.value.title
        FileLogger.i(TAG, "========== PLAY COMMAND RECEIVED ==========")
        FileLogger.i(TAG, "URL: $url")
        FileLogger.i(TAG, "Title: $title")
        FileLogger.i(TAG, "Headers: $headers")
        FileLogger.i(TAG, "===========================================")

        playJob?.cancel()
        playJob = lifecycleScope.launch {
            val urlWithoutQuery = url.substringBefore("?")
            if (urlWithoutQuery.endsWith(".m3u")) {
                try {
                    val parsedPlaylist = M3uParser.fetchAndParseM3u(url, headers)
                    if (parsedPlaylist != null && parsedPlaylist.isNotEmpty()) {
                        playlistItems = parsedPlaylist.toMutableList()
                        com.playbridge.player.player.PlaylistStore.currentPlaylist = parsedPlaylist
                        playlistIndex = 0
                        runOnUiThread { controlsViewModel.setPlaylistVisible(true) }

                        val firstItem = parsedPlaylist[0]
                        runOnUiThread { controlsViewModel.setTitle(firstItem.title ?: title ?: "") }
                        originalM3u8Url = firstItem.url
                        val firstHeaders = firstItem.headers.takeIf { it.isNotEmpty() }
                        currentHeaders = firstHeaders
                        subtitleUrls = firstItem.subtitles
                        currentSubtitleUrl = null

                        if (firstItem.url.contains(".m3u8", ignoreCase = true)) {
                            M3uParser.parseMasterPlaylist(firstItem.url, firstHeaders)
                        }
                        // Continue playback with the first item from the playlist
                        playVideoInternal(firstItem.url, firstHeaders, resumeTime, startPaused, subtitles = firstItem.subtitles.takeIf { it.isNotEmpty() }?.let { ArrayList(it) })
                        return@launch
                    }
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Error parsing M3U", e)
                }
            }
            playVideoInternal(url, headers, resumeTime, startPaused, subtitles = subtitles)
        }
    }

    private suspend fun playVideoInternal(url: String, headers: Map<String, String>?, resumeTime: Long? = null, startPaused: Boolean = false, subtitles: ArrayList<String>? = null) {
        controlsViewModel.clearSubtitle()
        val historyItem = try {
            progressManager.restoreProgress(url)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            FileLogger.w(TAG, "Failed to restore history for $url: ${e.message}")
            null
        }

        if (playlistItems.isEmpty() && historyItem?.playlistJson != null) {
            try {
                val decoded = com.playbridge.shared.protocol.decodePlayPayloadListJson(historyItem.playlistJson!!)
                    ?: emptyList()
                if (decoded.isNotEmpty()) {
                    playlistItems = decoded.toMutableList()
                    playlistIndex = historyItem.playlistIndex
                    runOnUiThread { controlsViewModel.setPlaylistVisible(true) }
                    FileLogger.i(TAG, "Restored playlist from history: ${playlistItems.size} items at index $playlistIndex")
                }
            } catch (e: Exception) {
                FileLogger.w(TAG, "Failed to restore playlist from history: ${e.message}")
            }
        }

        // Build playlist JSON for history persistence
        val plistJson = if (playlistItems.isNotEmpty()) {
            try {
                com.playbridge.shared.protocol.encodePlayPayloadListJson(playlistItems)
            } catch (e: Exception) {
                null
            }
        } else null

        if (historyItem?.externalSubtitleUrl != null) {
            currentSubtitleUrl = historyItem.externalSubtitleUrl
        } else if (!subtitles.isNullOrEmpty()) {
            currentSubtitleUrl = subtitles[0]
        } else {
            currentSubtitleUrl = null
        }

        progressManager.setCurrentMedia(
            url = url,
            title = controlsViewModel.getTitle(),
            contentType = null,
            headers = headers,
            playlistJson = plistJson,
            playlistIndex = playlistIndex,
            preferredAudioLanguage = preferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            externalSubtitleUrl = currentSubtitleUrl,
            playbackSpeed = currentPlaybackSpeed,
            videoScalingMode = vlcScalingModeToInt(currentVideoScalingMode)
        )

        val startPos = intent?.getLongExtra(ServerService.EXTRA_START_POSITION, -1L) ?: -1L
        val finalResumeTime = if (startPos > 0L) startPos else (resumeTime ?: historyItem?.position)

        val payload = playbridge.PlayPayload(
            url = url,
            headers = headers ?: emptyMap()
        )

        // Recreate setup to ensure a clean native state and avoid resource race conditions
        setupMediaPlayer()

        // Brief delay to allow native resources (MediaCodec) to fully return to the system pool
        kotlinx.coroutines.delay(300)

        engine?.let { e ->
            // Restore settings from history if present
            historyItem?.playbackSpeed?.let {
                currentPlaybackSpeed = it
                runOnUiThread { e.getMediaPlayer()?.setRate(it) }
            }
            historyItem?.videoScalingMode?.let {
                currentVideoScalingMode = vlcScalingModeFromInt(it)
                runOnUiThread { applyVlcScalingMode(currentVideoScalingMode) }
            }
            if (historyItem?.externalSubtitleUrl != null) {
                currentSubtitleUrl = historyItem.externalSubtitleUrl
                val subUrl = historyItem.externalSubtitleUrl
                controlsViewModel.loadExternalSubtitle(subUrl, headers)
                e.setSubtitleTrack(null) // Disable internal native subs
            } else {
                currentSubtitleUrl?.let { subUrl ->
                    controlsViewModel.loadExternalSubtitle(subUrl, headers)
                    e.setSubtitleTrack(null) // Disable internal native subs
                }
            }

            pendingResumeTime = if (finalResumeTime != null && finalResumeTime > 0) finalResumeTime else null

            e.load(payload)
            if (startPaused) {
                e.pause()
            }
        }
    }

    private fun syncSelectionsToProgressManager() {
        progressManager.updateSelections(
            externalSubtitleUrl = currentSubtitleUrl,
            playbackSpeed = currentPlaybackSpeed,
            videoScalingMode = vlcScalingModeToInt(currentVideoScalingMode)
        )
    }

    override fun showVideoFilterDialog() {
        showVideoFilterOverlay()
    }

    private fun updateUnifiedTracks() {
        val currentEngine = engine ?: return
        val player = currentEngine.getMediaPlayer() ?: return
        
        val audio = player.getTracks(org.videolan.libvlc.interfaces.IMedia.Track.Type.Audio)?.map { 
            UnifiedTrack(it.id, it.name ?: "Audio ${it.id}", it.id == player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Audio)?.id, "audio")
        } ?: emptyList()
        
        val video = player.getTracks(org.videolan.libvlc.interfaces.IMedia.Track.Type.Video)?.map { 
            UnifiedTrack(it.id, it.name ?: "Video ${it.id}", it.id == player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Video)?.id, "video")
        } ?: emptyList()
        
        val embeddedSubs = player.getTracks(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text)?.map { 
            UnifiedTrack(it.id, it.name ?: "Subtitle ${it.id}", it.id == player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text)?.id && subtitleUrl == null, "sub")
        } ?: emptyList()
        
        val externalSubs = subtitleUrls.map { url ->
            val name = try {
                val path = android.net.Uri.parse(url).path ?: ""
                val n = path.substringAfterLast('/')
                if (n.isNotEmpty()) java.net.URLDecoder.decode(n, "UTF-8") else "External Sub"
            } catch (e: Exception) { "External Sub" }
            UnifiedTrack(url, name, url == subtitleUrl, "external_sub")
        }
        
        val offSelected = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text) == null && subtitleUrl == null
        val offSub = UnifiedTrack("none", "Off", offSelected, "sub")
        
        controlsViewModel.updateTracks(audio, listOf(offSub) + embeddedSubs + externalSubs, video)
        controlsViewModel.setPlaybackSpeed(player.rate)
        controlsViewModel.setVideoScaling(player.aspectRatio ?: "Fit")
    }

    private fun showSettingsDialog() {
        updateUnifiedTracks()
        controlsViewModel.showSettings(SettingsTab.AUDIO)
    }


    override fun onDestroy() {
        FileLogger.i(TAG, "=== VlcPlayerActivity DESTROYED ===")
        controlsViewModel.hideOverlay()
        if (::progressManager.isInitialized) {
            syncSelectionsToProgressManager()
            progressManager.saveProgress()
        }
        super.onDestroy()
        seekHandler.removeCallbacks(performSeekRunnable)
        if (receiverRegistered) {
            unregisterReceiver(remoteReceiver)
            receiverRegistered = false
        }

        // Capture and null out references before any cleanup so nothing else uses them.
        vmUiJob?.cancel()
        if (::viewModel.isInitialized) {
            viewModel.dispose()
        }
        val engineToRelease = engine
        engine = null

        // UI-side cleanup is fast and must stay on the main thread.
        engineToRelease?.getMediaPlayer()?.setEventListener(null)
        controlsViewModel.detach()
        engineToRelease?.getMediaPlayer()?.vlcVout?.apply {
            removeCallback(this@VlcPlayerActivity)
            detachViews()
        }

        // Native VLC teardown can be slow; run it off the main thread to avoid an ANR in
        // MainActivity (same process, same main thread) when the user presses Back.
        Thread {
            engineToRelease?.release()
        }.start()
    }

    // IVLCVout.Callback
    override fun onSurfacesCreated(vout: IVLCVout?) {
        val width = surfaceView.width
        val height = surfaceView.height
        if (width > 0 && height > 0) {
            vout?.setWindowSize(width, height)
        }
    }

    override fun onSurfacesDestroyed(vout: IVLCVout?) {}

    private fun handleSeek(multiplier: Int) {
        val player = engine?.getMediaPlayer() ?: return
        val length = player.length

        val currentTime = pendingSeekTime ?: player.time
        val offset = 10000L * multiplier
        val newTime = (currentTime + offset).coerceIn(0, if (length > 0) length else Long.MAX_VALUE)

        pendingSeekTime = newTime
        controlsViewModel.setPendingSeekTime(newTime)

        seekHandler.removeCallbacks(performSeekRunnable)
        seekHandler.postDelayed(performSeekRunnable, 400)

        controlsViewModel.showSeekUI()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (inputHandler.handleKeyEvent(event.keyCode, event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // Handle physical remote/keyboard events
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // Some devices might need volume handling if inputHandler doesn't take it
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        engine?.pause()
        syncSelectionsToProgressManager()
        lifecycleScope.launch {
            val bitmap = progressManager.captureBitmapSuspend()
            progressManager.saveProgress(bitmap)
        }
    }

    override fun onResume() {
        super.onResume()
        surfaceLayoutListener?.let { surfaceView.removeOnLayoutChangeListener(it) }
        val listener = android.view.View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0) {
                engine?.getMediaPlayer()?.vlcVout?.setWindowSize(width, height)
            }
        }
        surfaceLayoutListener = listener
        surfaceView.addOnLayoutChangeListener(listener)
    }

    private fun getVlcHdrFormat(): String? {
        // VLC 4.x metadata fields for HDR (colorTransfer/colorSpace) seem to be elusive or named differently in this version.
        // Returning null for now to stabilize the build. Exo and MPV implementations remain active.
        return null
    }

    private fun calculateVlcFrameRate(): Float {
        val player = engine?.getMediaPlayer() ?: return 0f
        val track = player.getSelectedTrack(org.videolan.libvlc.interfaces.IMedia.Track.Type.Video)
        if (track is org.videolan.libvlc.interfaces.IMedia.VideoTrack) {
            if (track.frameRateDen > 0) {
                return track.frameRateNum.toFloat() / track.frameRateDen.toFloat()
            }
        }
        return 0f
    }
}
