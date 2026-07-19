package com.playbridge.player.player

import com.playbridge.shared.player.MpvPlayerEngine

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
import com.playbridge.player.util.getStringMapExtra
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import com.playbridge.player.logging.FileLogger
import com.playbridge.shared.logging.redactUrlForLog
import androidx.annotation.OptIn
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
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
import androidx.compose.runtime.collectAsState
import com.playbridge.player.ui.player.PlayerControlsOverlay
import com.playbridge.player.ui.player.PlayerControlsViewModel
import com.playbridge.player.ui.player.PlayerControlsState
import com.playbridge.player.ui.player.SettingsTab
import com.playbridge.player.ui.player.ActiveOverlay
import com.playbridge.player.ui.player.UnifiedTrack
// ComposeOptIn alias removed — androidx.compose.runtime.OptIn does not exist

data class MpvTrack(
    val id: Int,
    val type: String,
    val title: String,
    val lang: String? = null,
    val codec: String? = null,
    val isSelected: Boolean = false
)

/**
 * MPV-based video player activity.
 *
 * Wraps the mpv-android library (MPVLib) behind the same PlayerActivity interface used by
 * ExoPlayerActivity.  The activity owns the MPVLib lifecycle: create →
 * init → [surface attached] → loadfile → … → destroy.
 *
 * Prerequisites
 * -------------
 * Download the prebuilt AAR from the mpv-android project (see tv/app/libs/README.md) and place
 * it at tv/app/libs/mpv-android.aar before building.
 */
class MpvPlayerActivity : PlayerActivity(), MPVLib.EventObserver {

    override val playerProcessEngineId: String = "mpv"

    companion object {
        private const val TAG = "MpvPlayerActivity"
        private const val DECODER_RELEASE_GRACE_MS = 250L
        private const val REPLACEMENT_STOP_TIMEOUT_MS = 2_000L
        private const val STOP_FINISH_TIMEOUT_MS = 3_000L
        private val MPV_RECOVERABLE_ERROR_CODES = setOf(
            "mpv_control_timeout",
            "mpv_instance_busy",
        )

        @Volatile
        private var activeInstance: java.lang.ref.WeakReference<MpvPlayerActivity>? = null
    }


    private lateinit var surfaceView: SurfaceView
    private val controlsViewModel = PlayerControlsViewModel()
    private lateinit var inputHandler: InputHandler
    private lateinit var engineAdapter: PlayerEngineAdapter
    private lateinit var progressManager: ProgressManager
    private lateinit var historyStore: HistoryStore
    private var engine: MpvPlayerEngine? = null
    private lateinit var viewModel: com.playbridge.shared.player.PlayerViewModel
    private lateinit var resumeStore: com.playbridge.player.data.HistoryResumeStore
    private var vmUiJob: kotlinx.coroutines.Job? = null
    private var receiverRegistered = false
    private var mpvObserverRegistered = false
    private val playbackRequestGate = PlaybackRequestGate()
    private var controlFallbackRequested = false
    private var pendingPlayerSwitchIntent: Intent? = null

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
    private var subtitleDelayMs: Long = 0L
    private var isAudioBoostEnabled: Boolean = false
    @Volatile
    private var trackListJson: String = "[]"

    // Position to seek to once MPV_EVENT_FILE_LOADED fires
    private var pendingResumePositionMs: Long = 0L

    private var isLooping = false
    private var isLoadingNewStream = false
    @Volatile
    private var finishAfterStop = false
    private var stopFinishJob: kotlinx.coroutines.Job? = null
    private val decoderTransitionLock = Any()
    @Volatile
    private var hasActiveFile = false
    @Volatile
    private var lastDecoderEndElapsedRealtime = 0L
    @Volatile
    private var decoderStopCompletion: CompletableDeferred<Unit>? = null

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
            controlsViewModel.setBuffering(false)
            Toast.makeText(this, "Seek failed (network error)", Toast.LENGTH_SHORT).show()
            // Seek back to the pre-seek position (known-good). Using positionMs here would
            // re-seek to the stuck target because MPV reports the target as time-pos mid-seek.
            engine?.seek(preSeePositionMs)
        }
    }

    // Playlist queue / auto-advance — single source of truth, shared with ExoPlayerActivity.
    private val coordinator = PlaybackCoordinator(object : PlaybackCoordinator.Host {
        override fun loadItem(item: playbridge.PlayPayload, displayTitle: String?) {
            recordStillWatchingMediaChanged()
            runOnUiThread {
                displayTitle?.takeIf { it.isNotBlank() }?.let {
                    Toast.makeText(this@MpvPlayerActivity, it, Toast.LENGTH_SHORT).show()
                    controlsViewModel.setTitle(it)
                }
                controlsViewModel.setPrePlay(item.visual_metadata, context = this@MpvPlayerActivity, showCountdown = false)
                controlsViewModel.hideControls()
            }
            currentHeaders = item.headers
            playVideo(item.url, item.headers)
        }

        override suspend fun saveProgressBeforeAdvance(captureThumbnail: Boolean) {
            if (::progressManager.isInitialized) progressManager.saveProgress()
        }

        override fun onPlaylistChanged(items: List<playbridge.PlayPayload>, index: Int) {
            // Do NOT feed the queue to the shared PlayerViewModel — the PlaybackCoordinator owns
            // playlist advancement here; mirroring would make the VM auto-advance and race with it.
            controlsViewModel.updatePlaylistData(items, index)
            broadcastPlaylistStatus()
        }

        override fun showMessage(message: String) {
            runOnUiThread { Toast.makeText(this@MpvPlayerActivity, message, Toast.LENGTH_SHORT).show() }
        }

        override fun onPlaylistFinished() {
            finish()
        }
    })

    /** Live queue (incl. queue_add-appended episodes) for engine switches. */
    override fun playlistSnapshot(): Pair<List<playbridge.PlayPayload>, Int> =
        coordinator.playlist to coordinator.index

    private var playJob: kotlinx.coroutines.Job? = null
    private lateinit var composeView: androidx.compose.ui.platform.ComposeView
    private var navigationJob: kotlinx.coroutines.Job? = null
    private val contentSniffer = ContentSniffer()
    private var surfaceReadyDeferred = kotlinx.coroutines.CompletableDeferred<Unit>()

    // ── PlayerActivity abstract impl ─────────────────────────────────────────
    override fun play() { engine?.play() }
    override fun pause() { engine?.pause() }
    override fun isPlaying(): Boolean = isPlayingState
    override fun getMediaDuration(): Long = durationMs
    override fun getCurrentPosition(): Long = positionMs
    override fun getVideoSurfaceView(): android.view.SurfaceView? = surfaceView
    override fun retainExistingInstanceOnDuplicateLaunch(): Boolean = true

    override fun stopPlayback() {
        FileLogger.i(TAG, "stopPlayback() — clearing MPV state for transition")
        launchJob?.cancel()
        playJob?.cancel()
        isLoadingNewStream = true
        engine?.isTransitioning = true
        pendingStops++
        engine?.stop()
        runOnUiThread {
            controlsViewModel.hideControls()
            // Do NOT set surfaceView.visibility = INVISIBLE here.
            // That triggers surfaceDestroyed → engine.detachSurface(), causing
            // black screen on the next load. The old frame clears when MPV stops.
        }
    }

    override fun requestStop() {
        stopThenFinish(null)
    }

    override fun launchPlayerSwitch(intent: Intent) {
        stopThenFinish(intent)
    }

    private fun stopThenFinish(nextPlayerIntent: Intent?) {
        if (isFinishing || finishAfterStop) return

        val currentEngine = engine
        if (currentEngine == null) {
            nextPlayerIntent?.let(::launchReplacementPlayer)
            finish()
            return
        }

        FileLogger.i(
            TAG,
            if (nextPlayerIntent == null) {
                "Stop requested — waiting for MPV to end before finishing activity"
            } else {
                "Player switch requested — waiting for MPV to end before launching replacement"
            },
        )
        launchJob?.cancel()
        playJob?.cancel()
        navigationJob?.cancel()
        finishAfterStop = true
        pendingPlayerSwitchIntent = nextPlayerIntent
        currentEngine.isTransitioning = true
        val stopCompletion = requestDecoderStop(
            currentEngine,
            if (nextPlayerIntent == null) "ending player session" else "switching player engines",
        )

        stopFinishJob?.cancel()
        stopFinishJob = lifecycleScope.launch {
            val stopped = stopCompletion == null || withTimeoutOrNull(STOP_FINISH_TIMEOUT_MS) {
                stopCompletion.await()
                true
            } == true
            if (!stopped) {
                FileLogger.w(TAG, "Timed out waiting for MPV stop; finishing with guarded surface teardown")
            }
            awaitRemainingDecoderReleaseGrace()
            clearDecoderStopCompletion(stopCompletion)
            if (finishAfterStop && !isFinishing) {
                pendingPlayerSwitchIntent?.also {
                    pendingPlayerSwitchIntent = null
                    launchReplacementPlayer(it)
                }
                finish()
            }
        }
    }

    private fun launchReplacementPlayer(intent: Intent) {
        ServerService.launchPlayerFromPrivateProcess(this, intent)
    }

    private fun cancelPendingStopFinish(reason: String) {
        if (!finishAfterStop) return
        FileLogger.i(TAG, "Cancelling pending stop finish: $reason")
        finishAfterStop = false
        pendingPlayerSwitchIntent = null
        stopFinishJob?.cancel()
        stopFinishJob = null
    }

    private fun requestDecoderStop(currentEngine: MpvPlayerEngine, reason: String): CompletableDeferred<Unit>? {
        var issueStop = false
        val completion = synchronized(decoderTransitionLock) {
            decoderStopCompletion?.takeUnless { it.isCancelled } ?: if (hasActiveFile) {
                CompletableDeferred<Unit>().also {
                    decoderStopCompletion = it
                    issueStop = true
                }
            } else {
                null
            }
        }

        if (issueStop) {
            FileLogger.i(TAG, "Stopping current MPV file before $reason")
            currentEngine.stop()
        }
        return completion
    }

    private suspend fun awaitDecoderReleaseForReplacement() {
        val currentEngine = engine ?: return
        val stopCompletion = requestDecoderStop(currentEngine, "loading replacement")
        if (stopCompletion != null) {
            val stopped = withTimeoutOrNull(REPLACEMENT_STOP_TIMEOUT_MS) {
                stopCompletion.await()
                true
            } == true
            if (!stopped) {
                FileLogger.w(TAG, "Timed out waiting for MPV END_FILE before replacement")
            }
        }

        awaitRemainingDecoderReleaseGrace()
        clearDecoderStopCompletion(stopCompletion)
    }

    private suspend fun awaitRemainingDecoderReleaseGrace() {
        val endedAt = lastDecoderEndElapsedRealtime
        if (endedAt == 0L) return

        val elapsed = android.os.SystemClock.elapsedRealtime() - endedAt
        val remaining = DECODER_RELEASE_GRACE_MS - elapsed
        if (remaining > 0L) {
            FileLogger.d(TAG, "Waiting ${remaining}ms for MediaCodec release")
            delay(remaining)
        }
    }

    private fun clearDecoderStopCompletion(completion: CompletableDeferred<Unit>?) {
        if (completion == null) return
        synchronized(decoderTransitionLock) {
            if (decoderStopCompletion === completion) {
                decoderStopCompletion = null
            }
        }
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
        MpvProcess.cancelScheduledTermination()
        val existingInstance = activeInstance?.get()?.takeIf { it !== this && !it.isFinishing }
        super.onCreate(savedInstanceState)
        if (isFinishing) {
            if (existingInstance != null) {
                FileLogger.w(TAG, "Forwarding duplicate activity launch to the existing MPV owner")
                existingInstance.handleReplacementIntent(Intent(intent))
            }
            return
        }
        activeInstance = java.lang.ref.WeakReference(this)

        FileLogger.i(TAG, "=== MpvPlayerActivity CREATED ===")
        FileLogger.i(TAG, "Intent action: ${intent?.action}")

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
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
            }
        )

        setupStillWatching(controlsViewModel)
        setContentView(R.layout.activity_mpv_player)
        surfaceView = findViewById(R.id.surface_view)
        composeView = findViewById(R.id.modern_controls_view)

        composeView.apply {
            setContent {
                PlayBridgeTVTheme {
                    val state by controlsViewModel.controlsState.collectAsState()
                    val stillWatching by stillWatchingController.state.collectAsState()
                    PlayerControlsOverlay(
                        state = state,
                        stillWatchingState = stillWatching,
                        onContinueWatching = stillWatchingController::continueWatching,
                        onTogglePlay = { controlsViewModel.togglePlayPause() },
                        onTrackSelection = {
                            updateUnifiedTracks()
                            controlsViewModel.showSettings(SettingsTab.AUDIO)
                        },
                        onSubtitles = {
                            updateUnifiedTracks()
                            controlsViewModel.showSubtitles()
                        },
                        onPlaylist = { showPlaylistOverlay() },
                        onPrev = { navigationJob?.cancel(); navigationJob = lifecycleScope.launch { coordinator.previous() } },
                        onNext = { navigationJob?.cancel(); navigationJob = lifecycleScope.launch { coordinator.next() } },
                        onLoop = { setLooping(!isLooping) },
                        onSwitchPlayer = { controlsViewModel.showSwitchPlayer() },
                        onSeek = { controlsViewModel.handleScrubbing(it) },
                        onSkipSegment = { controlsViewModel.skipCurrentSegment() },
                        onSkipButtonFocusChanged = { controlsViewModel.setSkipButtonFocused(it) },
                        onPrePlayStartNow = {
                            launchJob?.cancel()
                            controlsViewModel.setPrePlayLaunching(true)
                            controlsViewModel.setPrePlay(null, clearOnlineSubs = false)
                        },
                        onPrePlayBack = {
                            launchJob?.cancel()
                            controlsViewModel.setPrePlay(null)
                            ServerService.notifyContextIdle(this@MpvPlayerActivity, playerProcessEngineId)
                            finish()
                        },
                        onSettingsTabSelected = { controlsViewModel.showSettings(it) },
                        onTrackSelected = { track ->
                            applyMpvTrackSelection(track.type, track.id)
                        },
                        onSpeedSelected = { speed ->
                            engine?.setPlaybackSpeed(speed)
                            controlsViewModel.setPlaybackSpeed(speed)
                        },
                        onScalingSelected = { mode ->
                             // MPV scaling logic if available
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
                        onAdjustSubtitleDelay = { controlsViewModel.adjustSubtitleDelay(it) },
                        onPreloadSubtitles = { controlsViewModel.preloadSubtitleCues(it) },
                    )
                }
            }
        }

        historyStore = HistoryStore(this)
        resumeStore = com.playbridge.player.data.HistoryResumeStore(historyStore)
        progressManager = ProgressManager(
            context = this,
            historyStore = historyStore,
            lifecycleScope = lifecycleScope,
            playbackSource = this
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
                surfaceReadyDeferred.complete(Unit)
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
        engineAdapter = object : PlayerEngineAdapter {
            override val isPlaying: Boolean get() = isPlayingState
            override val currentPosition: Long get() = positionMs
            override val duration: Long get() = durationMs
            override val bufferedPosition: Long get() = (positionMs + bufferAheadMs)
            override val streamInfo: String? get() = "${videoCodecRaw} | ${videoHeight}p | ${formatBitrate(videoBitrateBps)} | ${audioCodecRaw} (${audioChannels}ch)"
            override val frameRate: Float get() = containerFps.toFloat()
            override val hdrFormat: String? get() = null // MPV doesn't expose this easily via a simple property string here

            override fun setLoudnessEnhancer(enabled: Boolean) {
                // 'loudnorm' is too CPU intensive for many TVs.
                // Using 'acompressor' provides similar dialogue boosting with much lower overhead.
                val filter = if (enabled) "lavfi=[acompressor=threshold=-21dB:ratio=9:attack=5:release=50:makeup=8dB]" else ""
                engine?.setAudioFilter(filter)
                isAudioBoostEnabled = enabled
            }
            override fun setSubtitleDelay(delayMs: Long) {
                engine?.setSubtitleDelay(delayMs)
            }
            override fun setPlaybackSpeed(speed: Float) {
                engine?.setPlaybackSpeed(speed)
            }

            override fun play() { engine?.play() }
            override fun pause() { engine?.pause() }
            override fun seekTo(positionMs: Long) { this@MpvPlayerActivity.seekTo(positionMs) }
        }

        inputHandler = InputHandler(
            activity = this,
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            engine = engineAdapter,
            controls = controlsViewModel,
            isExternalOverlayVisible = { controlsViewModel.controlsState.value.prePlayMetadata != null || controlsViewModel.controlsState.value.activeOverlay != ActiveOverlay.NONE },
            isStillWatchingVisible = { stillWatchingController.state.value.isPrompting },
            onContinueWatching = stillWatchingController::continueWatching,
            onUserActivity = stillWatchingController::onUserActivity,
        )

        controlsViewModel.setEngine(engineAdapter, "mpv", this)

        // Register MPV observer NOW — after controlsManager is initialized — so that
        // property-change callbacks can safely reference controlsManager without crashing.
        // Format IDs: MPV_FORMAT_STRING=1, MPV_FORMAT_FLAG=3, MPV_FORMAT_INT64=4, MPV_FORMAT_DOUBLE=5
        engine?.registerObserver(
            this,
            linkedMapOf(
                "pause" to 3,              // Boolean
                "time-pos" to 5,           // Double (seconds)
                "duration" to 4,           // Long (seconds)
                "height" to 4,             // Long (px)
                "video-bitrate" to 4,      // Long (bits/s)
                "video-codec" to 1,        // String
                "audio-codec" to 1,        // String
                "audio-channels" to 1,     // String e.g. "stereo", "5.1"
                "demuxer-cache-time" to 5, // Double (seconds buffered ahead)
                "container-fps" to 5,      // Double (fps)
                "sub-delay" to 5,          // Double (seconds)
                "track-list" to 1,         // String (JSON)
            ),
        )
        mpvObserverRegistered = true

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

        if (acceptPlaybackRequest(intent)) {
            handleIntent(intent)
        }

        // Drain any queue items that arrived before our receiver was registered.
        // Must happen AFTER handleIntent because handleIntent replaces the coordinator's queue.
        coordinator.queueAdd(ServerService.drainPendingQueueItems(this))
        if (coordinator.hasPlaylist) {
            controlsViewModel.setPlaylistVisible(true)
        }
    }

    private var lastOnNewIntentUrl: String? = null
    private var lastOnNewIntentTime = 0L

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleReplacementIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Stream now-playing status + available tracks to the phone (shared base impl).
        startNowPlayingBroadcasts(controlsViewModel)
    }

    override fun onPause() {
        super.onPause()
        if (::progressManager.isInitialized) progressManager.saveProgress()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        val ownedActiveInstance = activeInstance?.get() === this
        finishAfterStop = false
        pendingPlayerSwitchIntent = null
        stopFinishJob?.cancel()
        stopFinishJob = null
        synchronized(decoderTransitionLock) {
            hasActiveFile = false
            decoderStopCompletion?.cancel()
            decoderStopCompletion = null
        }
        if (receiverRegistered) {
            unregisterReceiver(remoteReceiver)
            receiverRegistered = false
        }
        vmUiJob?.cancel()
        if (::viewModel.isInitialized) {
            viewModel.dispose()
        }
        engine?.let { currentEngine ->
            if (mpvObserverRegistered) {
                currentEngine.unregisterObserver(this)
                mpvObserverRegistered = false
            }
            currentEngine.release()
        }
        engine = null
        if (ownedActiveInstance) {
            activeInstance = null
        }
        super.onDestroy()
        if (ownedActiveInstance) {
            MpvProcess.terminateAfterActivityDestroy(applicationContext)
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
                FileLogger.d(TAG, "VM state: Loading ${redactUrlForLog(state.payload.url)}")
            is com.playbridge.shared.player.PlayerUiState.Playing ->
                FileLogger.d(TAG, "VM state: Playing")
            is com.playbridge.shared.player.PlayerUiState.Error -> {
                FileLogger.d(TAG, "VM state: Error ${state.code}: ${state.message}")
                if (
                    state.code in MPV_RECOVERABLE_ERROR_CODES &&
                    !controlFallbackRequested &&
                    !isFinishing
                ) {
                    controlFallbackRequested = true
                    FileLogger.e(TAG, "MPV control thread is unresponsive; falling back to ExoPlayer")
                    switchPlayer("exo", automatic = true)
                }
            }
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
                controlsViewModel.setPlaying(!value)
                if (value) {
                    if (!stillWatchingController.state.value.isPrompting) {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                } else {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                // Invariant: controls/overlays are only shown while paused. On the
                // play→playing transition (pause property false) dismiss any leftover overlay.
                if (!value) controlsViewModel.hideControls()
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
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
            "track-list" -> {
                trackListJson = value
                runOnUiThread { updateUnifiedTracks() }
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                positionMs = (value * 1000).toLong()
            }
            "demuxer-cache-time" -> {
                bufferAheadMs = (value * 1000).toLong()
            }
            "container-fps" -> {
                containerFps = value
            }
            "sub-delay" -> {
                subtitleDelayMs = (value * 1000).toLong()
                runOnUiThread {
                    controlsViewModel.setSubtitleDelay(subtitleDelayMs)
                }
            }
        }
    }

    // Required by MPVLib.EventObserver — no-op for untyped property changes
    override fun eventProperty(property: String) {}

    // Required by MPVLib.EventObserver — no-op for MPVNode-typed property changes
    override fun eventProperty(property: String, value: MPVNode) {}

    private fun setLooping(loop: Boolean) {
        isLooping = loop
        controlsViewModel.setLooping(loop)
    }

    override fun event(eventId: Int, data: MPVNode) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                synchronized(decoderTransitionLock) {
                    hasActiveFile = true
                }
                // A new file is starting; any pending stops from prior transitions are moot.
                pendingStops = 0
                runOnUiThread { controlsViewModel.setBuffering(true) }
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                isLoadingNewStream = false
                pendingStops = 0
                runOnUiThread {
                    controlsViewModel.setBuffering(false)
                    engine?.isTransitioning = false
                    // Restore the surface that stopPlayback() hid for the transition
                    surfaceView.visibility = android.view.View.VISIBLE
                    
                    // Trigger cinematic countdown only after we are connected and ready
                    if (controlsViewModel.controlsState.value.prePlayMetadata != null) {
                        engine?.pause()
                        triggerPrePlayCountdown(controlsViewModel) {
                            FileLogger.i(TAG, "Countdown finished - starting playback")
                            engine?.play()
                            controlsViewModel.setPrePlay(null, clearOnlineSubs = false)
                        }
                    }

                    // Apply Loudness Enhancer if enabled
                    if (isAudioBoostEnabled) {
                        val filter = "lavfi=[acompressor=threshold=-21dB:ratio=9:attack=5:release=50:makeup=8dB]"
                        engine?.setAudioFilter(filter)
                    }

                    if (containerFps > 0.0) {
                        updateRefreshRate(containerFps.toFloat())
                    }
                    if (pendingResumePositionMs > 0) {
                        engine?.seek(pendingResumePositionMs)
                        pendingResumePositionMs = 0
                    }
                    cancelPlaybackWatchdog()
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                // Fired when playback resumes after buffering or seek
                seekHandler.removeCallbacks(seekTimeoutRunnable)
                runOnUiThread {
                    controlsViewModel.setBuffering(false)
                    // Ensure surface is visible (safety net for transition flows)
                    surfaceView.visibility = android.view.View.VISIBLE
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                val stopCompletion = synchronized(decoderTransitionLock) {
                    hasActiveFile = false
                    lastDecoderEndElapsedRealtime = android.os.SystemClock.elapsedRealtime()
                    decoderStopCompletion
                }
                stopCompletion?.complete(Unit)

                if (finishAfterStop) {
                    return
                }

                if (pendingStops > 0) {
                    pendingStops--
                    runOnUiThread { controlsViewModel.setBuffering(false) }
                    return
                }
                
                // If we are currently transitioning to a new stream (e.g. sniffing or loading),
                // ignore any END_FILE events from aborted previous streams to prevent
                // "Next" loops/skipping.
                if (isLoadingNewStream || engine?.isTransitioning == true) {
                    FileLogger.d(TAG, "Ignoring END_FILE while transitioning (isLoadingNewStream=$isLoadingNewStream, isTransitioning=${engine?.isTransitioning})")
                    return
                }

                runOnUiThread {
                    controlsViewModel.setBuffering(false)
                    if (!coordinator.isEmpty && coordinator.index < coordinator.playlist.size - 1) {
                        navigationJob?.cancel()
                        navigationJob = lifecycleScope.launch { coordinator.next() }
                    } else {
                        // If it ended very quickly without playing anything, it might be a load error
                        if (positionMs == 0L && durationMs == 0L) {
                            FileLogger.w(TAG, "MPV ended file immediately — likely a load error. Failing over to ExoPlayer.")
                            switchPlayer("exo", automatic = true)
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
            if (intent == null || !isCommandForThisPlayer(intent)) return
            when (intent.action) {
                ServerService.ACTION_REMOTE -> {
                    val key = intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY)
                    handleRemoteKey(key)
                }
                ServerService.ACTION_CONTROL -> {
                    val cmd = intent.getStringExtra(ServerService.EXTRA_COMMAND)
                    if (stillWatchingController.state.value.isPrompting && cmd != "stop") {
                        stillWatchingController.continueWatching()
                        return
                    }
                    if (cmd != null && cmd != "stop") recordStillWatchingActivity()
                    when {
                        cmd?.startsWith("audio_track:") == true ->
                            applyMpvTrackSelection("audio", cmd.removePrefix("audio_track:"))
                        cmd?.startsWith("sub_track:") == true -> {
                            val id = cmd.removePrefix("sub_track:")
                            val type = controlsViewModel.controlsState.value.subtitleTracks
                                .firstOrNull { it.id == id }?.type ?: "sub"
                            applyMpvTrackSelection(type, id)
                        }
                        cmd?.startsWith("scaling:") == true ->
                            controlsViewModel.setVideoScaling(cmd.removePrefix("scaling:"))
                        cmd?.startsWith("switch_player:") == true ->
                            switchPlayer(cmd.removePrefix("switch_player:"))
                        else -> handleControlCommand(cmd)
                    }
                }
                ServerService.ACTION_QUEUE_ADD -> {
                    recordStillWatchingActivity()
                    coordinator.queueAdd(ServerService.drainPendingQueueItems(this@MpvPlayerActivity))
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
                ServerService.ACTION_PLAY -> {
                    if (intent.getStringExtra(ServerService.EXTRA_URL) != null) {
                        handleReplacementIntent(intent)
                    }
                }
            }
        }
    }

    private fun handleReplacementIntent(intent: Intent) {
        if (isFinishing || !acceptPlaybackRequest(intent)) return

        val requestId = playbackRequestId(intent)
        val url = intent.getStringExtra(ServerService.EXTRA_URL)
        val now = System.currentTimeMillis()
        if (
            requestId == null &&
            !finishAfterStop &&
            url != null &&
            url == lastOnNewIntentUrl &&
            (now - lastOnNewIntentTime) < 2_000
        ) {
            FileLogger.i(TAG, "Debounced duplicate replacement intent for ${redactUrlForLog(url)}")
            return
        }

        cancelPendingStopFinish("replacement intent received")
        lastOnNewIntentUrl = url
        lastOnNewIntentTime = now
        setIntent(intent)
        FileLogger.i(TAG, "Replacement intent received (requestId=${requestId ?: "legacy"})")

        launchJob?.cancel()

        // Keep the surface attached. playVideoInternal() serializes decoder teardown while
        // retaining the same Activity, MPV instance, and rendering surface.
        handleIntent(intent)
    }

    private fun acceptPlaybackRequest(intent: Intent?): Boolean {
        val requestId = playbackRequestId(intent)
        val accepted = playbackRequestGate.accept(requestId)
        if (!accepted) {
            FileLogger.i(TAG, "Ignoring stale playback request $requestId")
        }
        return accepted
    }

    private fun playbackRequestId(intent: Intent?): Long? =
        intent?.takeIf { it.hasExtra(PlayerLauncher.EXTRA_PLAYBACK_REQUEST_ID) }
            ?.getLongExtra(PlayerLauncher.EXTRA_PLAYBACK_REQUEST_ID, Long.MIN_VALUE)

    private fun handleIntent(intent: Intent?) {
        if (isFinishing) return
        recordStillWatchingMediaChanged()
        setupPlaybackExtras(intent)

        // Read playlist if present
        val isPlaylist = intent?.getBooleanExtra(ServerService.EXTRA_IS_PLAYLIST, false) ?: false
        val intentPlaylist = PlayerLauncher.playlistFromIntent(intent)
        val availablePlaylist = intentPlaylist?.items ?: PlaylistStore.currentPlaylist
        if (isPlaylist && !availablePlaylist.isNullOrEmpty()) {
            val startIndex = intentPlaylist?.start_index
                ?: intent.getIntExtra(ServerService.EXTRA_PLAYLIST_INDEX, 0)
            coordinator.setPlaylist(availablePlaylist, startIndex)
            FileLogger.i(TAG, "Playlist loaded: ${coordinator.playlist.size} items, starting at index ${coordinator.index}")
        } else {
            coordinator.setPlaylist(emptyList(), 0)
        }

        // Update button visibility based on playlist. A single video is modelled as an
        // empty queue, so only treat it as a "playlist" once there's >1 item.
        val hasPlaylist = coordinator.hasPlaylist

        // Show playlist button when a playlist is active
        controlsViewModel.setPlaylistVisible(hasPlaylist)

        // Sync the phone's episode list to this new content — refreshes it for a
        // new playlist, or clears it when a single video replaces an earlier one.
        broadcastPlaylistStatus()

        // Show streams button - currently disabled in dumb mode

        controlsViewModel.setSeasonInfo(null)

        // Ensure prev/next buttons are visible if a playlist is active.
        if (hasPlaylist) {
            controlsViewModel.setNavigationVisible(true)
        }


        // Standard direct URL path
        handlePrePlayMetadata(intent, controlsViewModel)

        val url = intent?.getStringExtra(ServerService.EXTRA_URL) ?: return
        val headers = intent.getStringMapExtra(ServerService.EXTRA_HEADERS)
        val title = intent.getStringExtra(ServerService.EXTRA_TITLE)
        val subtitles = intent.getStringArrayListExtra(ServerService.EXTRA_SUBTITLES)

        // Apply title immediately if known
        controlsViewModel.setTitle(title ?: "")

        this.subtitleUrls = subtitles ?: emptyList()
        if (subtitles != null && currentSubtitleUrl == null) {
            currentSubtitleUrl = subtitles.firstOrNull()
        }
        this.currentHeaders = headers
        controlsViewModel.subtitleRequestHeaders = headers
        SubtitleCueLoader.clear()
        playVideo(url, headers, subtitles = subtitles)
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
        controlsViewModel.setPendingSeekTime(newPos)
        seekTo(newPos)
        controlsViewModel.showSeekUI()
    }

    private fun scheduleSeekTimeout() {
        seekHandler.removeCallbacks(seekTimeoutRunnable)
        seekHandler.postDelayed(seekTimeoutRunnable, seekTimeoutMs)
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    private suspend fun playVideoInternal(url: String, headers: Map<String, String>?, startPaused: Boolean = false, subtitles: ArrayList<String>? = null) {
        currentUrl = url
        // A replacement keeps the same engine instance, so clear the previous file's values
        // before history restore. Otherwise seekTo() can seek the old file instead of staging
        // the resume position for MPV_EVENT_FILE_LOADED.
        positionMs = 0L
        durationMs = 0L
        bufferAheadMs = 0L
        controlsViewModel.setSeasonInfo(null)
        FileLogger.i(TAG, "========== PLAY COMMAND RECEIVED ==========")
        FileLogger.i(TAG, "URL: ${redactUrlForLog(url)}")
        FileLogger.i(TAG, "Title: ${controlsViewModel.getTitle()}")
        FileLogger.i(TAG, "Request headers: ${headers?.size ?: 0} field(s)")
        FileLogger.i(TAG, "Start Paused: $startPaused")
        FileLogger.i(TAG, "===========================================")


        // seekTo() will intercept this call while durationMs == 0 (file not yet loaded)
        // and stash the position in pendingResumePositionMs; it is applied on FILE_LOADED.
        // History only carries the position now — the subtitle comes from the payload.
        progressManager.restoreProgress(url)
        currentSubtitleUrl = subtitles?.firstOrNull()

        // History persistence: raw PlaylistPayload (items carry subtitles/headers) + a
        // stable, index-independent key. Prefer the live coordinator queue; fall back to a
        // one-item payload when there is no queue.
        val historyItems = if (!coordinator.isEmpty) coordinator.playlist else listOf(
            playbridge.PlayPayload(
                url = url,
                title = controlsViewModel.getTitle(),
                headers = headers ?: emptyMap(),
                subtitles = subtitles ?: emptyList(),
            )
        )
        val historyIndex = if (!coordinator.isEmpty) coordinator.index else 0
        val historyPayloadJson = try {
            PlayerLauncher.historyPayloadJson(historyItems, historyIndex)
        } catch (e: Exception) { "" }
        val historyId = PlayerLauncher.historyId(historyItems)
        val historyVm = historyItems.getOrNull(historyIndex)?.visual_metadata
        // History card is a 16:9 box, so prefer the landscape backdrop; fall back to poster.
        val historyThumbnailUrl = historyVm?.backdrop_url ?: historyVm?.poster_url

        progressManager.setCurrentMedia(
            url = url,
            title = controlsViewModel.getTitle(),
            contentType = null,
            headers = headers,
            payloadJson = historyPayloadJson,
            historyId = historyId,
            thumbnailUrl = historyThumbnailUrl,
            externalSubtitleUrl = currentSubtitleUrl,
            playbackSpeed = currentPlaybackSpeed
        )

        val startPos = intent?.getLongExtra(ServerService.EXTRA_START_POSITION, -1L) ?: -1L
        if (startPos > 0L) {
            pendingResumePositionMs = startPos
        }
        // Write the entry immediately so it survives a force-kill before the first save.
        progressManager.recordLanded(startPos.coerceAtLeast(0L))

        if (startPaused) {
            engine?.pause()
        }

        // No need to hide surface between videos, the PrePlay overlay covers it.
        // Surface remains alive, so we only wait for surfaceReadyDeferred on first launch.

        // Wait for sniffing and surface transition
        val sniffedType = contentSniffer.sniffContent(url, headers)
        coroutineContext.ensureActive() // Stop if the playJob was cancelled (e.g. user clicked "Next" again)
        
        // Wait for surfaceCreated to fire before we call load
        try {
            kotlinx.coroutines.withTimeout(2000) {
                surfaceReadyDeferred.await()
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Surface took too long to become ready, proceeding anyway...")
        }
        coroutineContext.ensureActive()

        val demuxerFormat = if (sniffedType != null) {
            FileLogger.i(TAG, "Pre-flight sniff detected: $sniffedType")
            // If HLS was detected, hint MPV after the old decoder has stopped.
            if (sniffedType == "application/x-mpegURL") "hls" else null
        } else null

        // Some Android TV MediaCodec implementations start the replacement decoder before
        // the old one has released its output surface. Stop explicitly, wait for END_FILE,
        // then leave a short release grace before allocating the next decoder.
        awaitDecoderReleaseForReplacement()
        coroutineContext.ensureActive()

        engine?.setDemuxerFormat(demuxerFormat)
        val payload = playbridge.PlayPayload(url = url, headers = headers ?: emptyMap())
        engine?.load(payload)

        // Re-apply external subtitle if present via SubtitleManager
        currentSubtitleUrl?.let { subUrl ->
            controlsViewModel.loadExternalSubtitle(subUrl, headers)
            engine?.setSubtitleTrack(null) // Disable internal native subs to avoid double rendering
        }

        if (!startPaused) play()
        startPlaybackWatchdog("mpv")
    }

    private fun playVideo(url: String, headers: Map<String, String>?, startPaused: Boolean = false, subtitles: ArrayList<String>? = null) {
        // Guard against MPV_EVENT_END_FILE from a prior stop() or a failed first HTTP attempt
        // triggering finish() before the new stream has loaded.
        isLoadingNewStream = true
        controlsViewModel.clearSubtitle()
        engine?.isTransitioning = true

        playJob?.cancel()
        playJob = lifecycleScope.launch {
            playVideoInternal(url, headers, startPaused, subtitles = subtitles)
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

    private fun showStreamSelectionOverlay() {
        resolveStreamsForCurrentVideo()
    }

    /**
     * Resolve streams for the current video and update the picker.
     */
    private fun resolveStreamsForCurrentVideo() {
        // Not supported in dumb mode
    }

    private fun showPlaylistOverlay() {
        if (coordinator.isEmpty) return

        val displayItems = coordinator.playlist
        val displayIndex = coordinator.index

        val wasPlaying = isPlayingState
        if (wasPlaying) pause()

        controlsViewModel.showPlaylist(displayItems, displayIndex)
    }

    private fun showPlaylistPicker() {
        showPlaylistOverlay()
    }

    private fun playSeriesEpisodeAtIndex(index: Int) {
        // Not implemented in dumb mode
    }

    /**
     * Apply an audio/subtitle/video track selection by its [UnifiedTrack] id.
     * Shared by the TV's own track dialog and phone `audio_track:`/`sub_track:` commands.
     */
    private fun applyMpvTrackSelection(type: String, id: String) {
        when (type) {
            "audio" -> engine?.setAudioTrack(id)
            "video" -> engine?.setVideoTrack(id)
            "sub" -> {
                // Switching to an embedded/Off subtitle clears any active external one.
                currentSubtitleUrl = null
                controlsViewModel.clearSubtitle()
                if (id == "none") engine?.setSubtitleTrack(null)
                else engine?.setSubtitleTrack(id)
            }
            "external_sub" -> {
                engine?.setSubtitleTrack(null)  // turn off embedded
                currentSubtitleUrl = id
                controlsViewModel.loadExternalSubtitle(id, currentHeaders)
            }
        }
        updateUnifiedTracks()
    }

    /** Broadcast current queue state to the phone (delegates to the shared base implementation). */
    private fun broadcastPlaylistStatus() {
        broadcastPlaylistStatus(coordinator.playlist, coordinator.index)
    }

    private fun updateUnifiedTracks() {
        val allTracks = buildTrackList()
        val audio = allTracks.filter { it.type == "audio" }.map { 
            UnifiedTrack(it.id.toString(), it.title, it.isSelected, "audio") 
        }
        val video = allTracks.filter { it.type == "video" }.map { 
            UnifiedTrack(it.id.toString(), it.title, it.isSelected, "video") 
        }
        val embeddedSubs = allTracks.filter { it.type == "sub" }.map { 
            UnifiedTrack(it.id.toString(), it.title, it.isSelected && currentSubtitleUrl == null, "sub") 
        }
        
        val externalSubs = subtitleUrls.map { url ->
            UnifiedTrack(url, externalSubtitleName(url), url == currentSubtitleUrl, "external_sub")
        }
        
        val noSubSelected = allTracks.none { it.type == "sub" && it.isSelected } && currentSubtitleUrl == null
        val offSub = UnifiedTrack("none", "Off", noSubSelected, "sub")
        
        controlsViewModel.updateTracks(audio, listOf(offSub) + embeddedSubs + externalSubs, video, currentSubtitleUrl)
        controlsViewModel.setPlaybackSpeed(currentPlaybackSpeed)
    }

    private fun showTrackSelectionDialog() {
        updateUnifiedTracks()
        controlsViewModel.showSettings(SettingsTab.AUDIO)
    }


    private fun buildTrackList(): List<MpvTrack> {
        val json = trackListJson
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.getInt("id")
                val type = obj.getString("type")
                val lang = if (obj.has("lang") && !obj.isNull("lang")) obj.getString("lang") else null
                val codec = if (obj.has("codec") && !obj.isNull("codec")) obj.getString("codec") else null

                // Build a descriptive title
                val rawTitle = obj.optString("title", "")
                val descriptiveTitle = buildString {
                    if (rawTitle.isNotEmpty()) {
                        append(rawTitle)
                    } else if (lang != null) {
                        append(java.util.Locale.forLanguageTag(lang).displayLanguage)
                    } else {
                        append("Track $id")
                    }
                    
                    if (codec != null || (type == "audio" && obj.has("audio-channels"))) {
                        append(" (")
                        codec?.let { append(it.uppercase()) }
                        if (type == "audio" && obj.has("audio-channels")) {
                            if (codec != null) append(" ")
                            append(obj.getString("audio-channels"))
                        }
                        append(")")
                    }
                }

                MpvTrack(
                    id = id,
                    type = type,
                    title = descriptiveTitle,
                    lang = lang,
                    codec = codec,
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

    override fun addExternalSubtitle(url: String) {
        if (url !in subtitleUrls) {
            subtitleUrls = subtitleUrls + url
        }
        applyMpvTrackSelection("external_sub", url)
    }
}
