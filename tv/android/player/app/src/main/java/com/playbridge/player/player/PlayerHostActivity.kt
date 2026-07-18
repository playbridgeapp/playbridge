package com.playbridge.player.player

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.createBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.playbridge.player.R
import com.playbridge.player.data.HistoryStore
import com.playbridge.player.data.HistoryThumbnailMode
import com.playbridge.player.data.HistoryThumbnailStore
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.server.ServerService
import com.playbridge.player.ui.player.ActiveOverlay
import com.playbridge.player.ui.player.PlayerControlsOverlay
import com.playbridge.player.ui.player.PlayerControlsViewModel
import com.playbridge.player.ui.player.PlaybackCapabilities
import com.playbridge.player.ui.player.SettingsTab
import com.playbridge.player.ui.player.UnifiedTrack
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import com.playbridge.shared.protocol.createStatusJson
import com.playbridge.shared.protocol.encodePlayPayloadListJson
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import playbridge.PlayPayload
import java.io.File
import kotlin.math.abs

private data class RendererTrack(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
)

internal fun defaultQualityMaxHeight(payload: PlayPayload?): Int {
    val value = payload?.default_video_quality.orEmpty().lowercase()
    return when {
        value == "4k" || value == "uhd" -> 2160
        else -> value.filter(Char::isDigit).toIntOrNull() ?: 0
    }
}

/**
 * Permanent host shell for renderer-process playback.
 *
 * Owns the stable playback shell and swaps isolated renderer services behind it. Legacy
 * player activity source remains temporarily for comparison during stress testing, but those
 * activities are no longer registered runtime entry points.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerHostActivity : ComponentActivity(), PlaybackProgressSource {
    private lateinit var playerRoot: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var subtitleView: SubtitleView
    private lateinit var composeView: ComposeView
    private val controlsViewModel: PlayerControlsViewModel by viewModels()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val sessionCoordinator = RendererSessionCoordinator()
    private val pendingSeekTracker = PendingSeekTracker()
    private val playbackRequestGate = PlaybackRequestGate()
    private var rendererService: IRendererService? = null
    private var preparedSessionId = 0L
    private var rendererKind: RendererKind = RendererKind.EXO
    private var session: RendererSessionSnapshot? = null
    private var connection: ServiceConnection? = null
    private var surface: Surface? = null
    private var hostPlaying = false
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private var finishingSession = false
    private val attemptedRenderers = linkedSetOf<RendererKind>()
    private var startupWatchdog: Runnable? = null
    private var failureFinishRunnable: Runnable? = null
    private var prePlayCountdownJob: Job? = null
    private val historyStore by lazy { HistoryStore(applicationContext) }
    private val historyThumbnailStore by lazy { HistoryThumbnailStore(applicationContext) }
    private val stillWatchingController by lazy {
        StillWatchingController(
            scope = lifecycleScope,
            pausePlayback = { handleControl("pause") },
            resumePlayback = { handleControl("play") },
            stopPlayback = ::finishPlaybackSession,
        ).also { controller ->
            val prefs = getSharedPreferences("browser_prefs", MODE_PRIVATE)
            controller.updateSettings(
                prefs.getBoolean(PlayerActivity.PREF_STILL_WATCHING_ENABLED, false),
                PlayerActivity.normalizeStillWatchingThreshold(
                    prefs.getInt(PlayerActivity.PREF_STILL_WATCHING_THRESHOLD_MIN, 90),
                ),
                PlayerActivity.normalizeStillWatchingResponseSeconds(
                    prefs.getInt(PlayerActivity.PREF_STILL_WATCHING_RESPONSE_SEC, 300),
                ),
            )
        }
    }
    private val progressManager by lazy {
        ProgressManager(
            context = applicationContext,
            historyStore = historyStore,
            lifecycleScope = lifecycleScope,
            playbackSource = this,
        )
    }
    private var requestedStartPositionMs: Long? = null
    private var lastSavedPositionMs = 0L
    private var currentHistoryId: String? = null
    private var currentHistoryHasThumbnail = false
    private var thumbnailCaptureInFlightGeneration: Long? = null
    private var thumbnailCaptureGeneration = 0L
    private var thumbnailPlaybackElapsedMs = 0L
    private var thumbnailPlayingSinceElapsedMs: Long? = null
    private var lastThumbnailCapturePlaybackMs: Long? = null
    private var videoTracks: List<RendererTrack> = emptyList()
    private var videoFrameRate = 0f
    private var audioTracks: List<RendererTrack> = emptyList()
    private var subtitleTracks: List<RendererTrack> = emptyList()
    private var externalSubtitleUrls: List<String> = emptyList()
    private var currentExternalSubtitleUrl: String? = null
    private var externalSubtitleOverlayActive = false
    private val externalSubtitleStager by lazy { ExternalSubtitleStager(applicationContext) }
    private val stagedSubtitleFiles = linkedSetOf<File>()
    private var subtitleStageJob: Job? = null
    private var pendingNativeSubtitleUri: String? = null
    private var pendingNativeSubtitleMode: SubtitleRenderingMode? = null
    private var initialSubtitleHandledSessionId = 0L
    private var initialSubtitleHandledUrl: String? = null
    private val controlsAdapter = object : PlayerEngineAdapter {
        override val isPlaying: Boolean get() = hostPlaying
        override val currentPosition: Long get() = lastPositionMs
        override val duration: Long get() = lastDurationMs
        override val bufferedPosition: Long get() = lastPositionMs
        override val streamInfo: String? get() = null
        override val frameRate: Float get() = videoFrameRate
        override val hdrFormat: String? get() = null

        override fun setLoudnessEnhancer(enabled: Boolean) {
            val currentSession = session ?: return
            runCatching { rendererService?.setAudioBoost(enabled, currentSession.sessionId) }
        }

        override fun setSubtitleDelay(delayMs: Long) {
            val currentSession = session ?: return
            runCatching { rendererService?.setSubtitleDelay(delayMs, currentSession.sessionId) }
        }

        override fun setPlaybackSpeed(speed: Float) {
            val currentSession = session ?: return
            runCatching { rendererService?.setPlaybackSpeed(speed, currentSession.sessionId) }
        }

        override fun play() = handleControl("play")

        override fun pause() = handleControl("pause")

        override fun seekTo(positionMs: Long) = this@PlayerHostActivity.seekTo(positionMs)
    }
    private val playbackCoordinator by lazy {
        PlaybackCoordinator(object : PlaybackCoordinator.Host {
            override fun loadItem(item: PlayPayload, displayTitle: String?) {
                startPlaylistItem()
            }

            override suspend fun saveProgressBeforeAdvance(captureThumbnail: Boolean) {
                progressManager.saveProgress()
            }

            override fun onPlaylistChanged(items: List<PlayPayload>, index: Int) {
                broadcastPlaylistStatus(items, index)
            }

            override fun showMessage(message: String) {
                Toast.makeText(this@PlayerHostActivity, message, Toast.LENGTH_SHORT).show()
            }

            override fun onPlaylistFinished() {
                finishPlaybackSession()
            }
        })
    }

    private val controlReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ServerService.ACTION_CONTROL -> handleControl(
                    intent.getStringExtra(ServerService.EXTRA_COMMAND),
                )
                ServerService.ACTION_REMOTE -> handleRemote(
                    intent.getStringExtra(ServerService.EXTRA_REMOTE_KEY),
                )
                ServerService.ACTION_QUEUE_ADD -> playbackCoordinator.queueAdd(
                    ServerService.drainPendingQueueItems(this@PlayerHostActivity),
                )
                ServerService.ACTION_PLAYLIST_JUMP -> {
                    val index = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_JUMP_INDEX, -1)
                    if (index >= 0) lifecycleScope.launch { playbackCoordinator.jumpTo(index) }
                }
                ServerService.ACTION_RESYNC -> broadcastCurrentState()
            }
        }
    }

    private val callback = object : IRendererCallback.Stub() {
        override fun onRendererEvent(event: Bundle?) {
            val eventBundle = event ?: return
            runOnUiThread { handleRendererEvent(eventBundle) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeHostCount.incrementAndGet()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        surfaceView = newRendererSurfaceView()
        subtitleView = createSubtitleView()
        composeView = ComposeView(this)
        playerRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
            addView(subtitleView, FrameLayout.LayoutParams(-1, -1))
            addView(composeView, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(playerRoot)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBackPressed()
        })
        rendererKind = rendererKindFromIntent(intent)
        setupControlsOverlay()
        if (!acceptPlaybackRequest(intent)) {
            finish()
            return
        }
        val playlist = PlayerLauncher.playlistFromIntent(intent)
        if (playlist == null || playlist.items.isEmpty()) {
            failEmptyInitialRequest()
            return
        }
        playbackCoordinator.setPlaylist(playlist.items, playlist.start_index)
        controlsViewModel.resetSessionSettings(
            defaultQualityMaxHeight(playlist.items.getOrNull(playlist.start_index)),
        )
        requestedStartPositionMs = intent
            .getLongExtra(ServerService.EXTRA_START_POSITION, 0L)
            .takeIf { it > 0L }
        broadcastPlaylistStatus(playbackCoordinator.playlist, playbackCoordinator.index)
        updateControlsForCurrentItem(
            showPrePlay = !intent.getBooleanExtra(ServerService.EXTRA_SKIP_PREPLAY, false),
        )
        attemptedRenderers += rendererKind
        session = sessionCoordinator.begin(rendererKind)
        ServerService.notifyContextPlayer(this, rendererKind.engineId)
        bindRenderer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!acceptPlaybackRequest(intent)) return

        val playlist = PlayerLauncher.playlistFromIntent(intent)
        if (playlist == null || playlist.items.isEmpty()) {
            FileLogger.w(TAG, "Ignoring empty replacement playback request")
            return
        }

        setIntent(intent)
        replacePlayback(intent, playlist)
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter().apply {
            addAction(ServerService.ACTION_CONTROL)
            addAction(ServerService.ACTION_REMOTE)
            addAction(ServerService.ACTION_QUEUE_ADD)
            addAction(ServerService.ACTION_PLAYLIST_JUMP)
            addAction(ServerService.ACTION_RESYNC)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(controlReceiver, filter)
        }
        // Register before draining to avoid a lost wake-up: phone library queue items can arrive
        // while the Activity is starting. If the broadcast preceded registration, the pending
        // store is drained here; if it followed registration, the receiver drains it first.
        playbackCoordinator.queueAdd(ServerService.drainPendingQueueItems(this))
    }

    override fun onStop() {
        if (!isFinishing && !isChangingConfigurations) {
            handleControl("pause")
        }
        runCatching { unregisterReceiver(controlReceiver) }
        super.onStop()
    }

    override fun onPause() {
        requestHistoryThumbnailCapture(exitFallback = true)
        super.onPause()
    }

    private fun setupControlsOverlay() {
        controlsViewModel.setEngine(controlsAdapter, rendererKind.name.lowercase(), this)
        if (getSharedPreferences("browser_prefs", MODE_PRIVATE)
                .getBoolean("loudness_enhancer", false)
        ) {
            controlsViewModel.toggleAudioBoost()
        }
        composeView.setContent {
            PlayBridgeTVTheme {
                val state by controlsViewModel.controlsState.collectAsState()
                val stillWatching by stillWatchingController.state.collectAsState()
                PlayerControlsOverlay(
                    state = state,
                    stillWatchingState = stillWatching,
                    onContinueWatching = stillWatchingController::continueWatching,
                    onTogglePlay = controlsViewModel::togglePlayPause,
                    onTrackSelection = { controlsViewModel.showSettings(SettingsTab.AUDIO) },
                    onSubtitles = controlsViewModel::showSubtitles,
                    onPlaylist = {
                        controlsViewModel.showPlaylist(
                            playbackCoordinator.playlist,
                            playbackCoordinator.index,
                        )
                    },
                    onPrev = { lifecycleScope.launch { playbackCoordinator.previous() } },
                    onNext = { lifecycleScope.launch { playbackCoordinator.next() } },
                    onLoop = {
                        val enabled = !controlsViewModel.controlsState.value.isLooping
                        session?.let { currentSession ->
                            runCatching {
                                rendererService?.setLooping(enabled, currentSession.sessionId)
                            }
                        }
                        controlsViewModel.setLooping(enabled)
                        broadcastPlayerSettings()
                    },
                    onSwitchPlayer = controlsViewModel::showSwitchPlayer,
                    onSeek = controlsViewModel::handleScrubbing,
                    onPrePlayStartNow = ::startPrePlayNow,
                    onPrePlayBack = ::finishPlaybackSession,
                    onSettingsTabSelected = controlsViewModel::showSettings,
                    onTrackSelected = ::selectTrack,
                    onSpeedSelected = { speed ->
                        controlsViewModel.setPlaybackSpeed(speed)
                        broadcastPlayerSettings()
                    },
                    onScalingSelected = { mode ->
                        session?.let { currentSession ->
                            runCatching {
                                rendererService?.setVideoScaling(mode, currentSession.sessionId)
                            }
                        }
                        controlsViewModel.setVideoScaling(mode)
                        updateVideoSurfaceLayout()
                        broadcastPlayerSettings()
                    },
                    onSettingsDismiss = controlsViewModel::hideOverlay,
                    onOverlayDismiss = controlsViewModel::hideOverlay,
                    onPlaylistItemPicked = { index ->
                        controlsViewModel.hideOverlay()
                        lifecycleScope.launch { playbackCoordinator.jumpTo(index) }
                    },
                    onPlayerSwitched = { playerId ->
                        controlsViewModel.hideControls()
                        switchRenderer(
                            if (playerId.equals("mpv", ignoreCase = true)) {
                                RendererKind.MPV
                            } else {
                                RendererKind.EXO
                            },
                        )
                    },
                    onToggleAudioBoost = {
                        controlsViewModel.toggleAudioBoost()
                        getSharedPreferences("browser_prefs", MODE_PRIVATE).edit()
                            .putBoolean(
                                "loudness_enhancer",
                                controlsViewModel.controlsState.value.isAudioBoostEnabled,
                            )
                            .apply()
                        broadcastPlayerSettings()
                    },
                    onAdjustSubtitleDelay = { delta ->
                        controlsViewModel.adjustSubtitleDelay(delta)
                        broadcastPlayerSettings()
                    },
                    onPreloadSubtitles = controlsViewModel::preloadSubtitleCues,
                    onSkipSegment = controlsViewModel::skipCurrentSegment,
                    onSkipButtonFocusChanged = controlsViewModel::setSkipButtonFocused,
                )
            }
        }
    }

    private fun createSubtitleView(): SubtitleView = SubtitleView(this).apply {
        setViewType(SubtitleView.VIEW_TYPE_CANVAS)
        setApplyEmbeddedStyles(true)
        setApplyEmbeddedFontSizes(true)
        setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                null,
            ),
        )
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun selectTrack(track: UnifiedTrack) {
        val currentSession = session ?: return
        val renderer = rendererService ?: return
        when (track.type) {
            "video" -> {
                val maxHeight = track.id.removePrefix("max:").toIntOrNull() ?: 0
                controlsViewModel.setVideoQuality(maxHeight)
                runCatching { renderer.setVideoQuality(maxHeight, currentSession.sessionId) }
                broadcastPlayerSettings()
            }
            "audio" -> runCatching { renderer.setAudioTrack(track.id, currentSession.sessionId) }
            "sub" -> {
                selectRendererSubtitle(track.id, renderer, currentSession.sessionId)
            }
            "external_sub" -> {
                selectExternalSubtitle(track.id, renderer, currentSession.sessionId)
            }
        }
    }

    private fun selectRendererSubtitle(
        trackId: String,
        renderer: IRendererService,
        sessionId: Long,
    ) {
        subtitleStageJob?.cancel()
        subtitleStageJob = null
        clearPendingNativeSubtitle()
        clearInitialSubtitleHandled()
        currentExternalSubtitleUrl = null
        externalSubtitleOverlayActive = false
        controlsViewModel.clearSubtitle()
        subtitleView.setCues(emptyList())
        subtitleTracks = subtitleTracks.map { it.copy(selected = it.id == trackId) }
        runCatching { renderer.setSubtitleTrack(trackId, sessionId) }
        updateTrackControls()
        broadcastTracks()
    }

    private fun selectExternalSubtitle(
        url: String,
        renderer: IRendererService,
        sessionId: Long,
    ) {
        if (url.isBlank()) return
        externalSubtitleUrls = (externalSubtitleUrls + url).distinct()
        currentExternalSubtitleUrl = url
        clearInitialSubtitleHandled()
        applyExternalSubtitleSelection(renderer, sessionId)
        progressManager.updateSelections(externalSubtitleUrl = url)
        updateTrackControls()
        broadcastTracks()
    }

    private fun applyExternalSubtitleSelection(
        renderer: IRendererService,
        sessionId: Long,
    ) {
        val url = currentExternalSubtitleUrl ?: return
        val item = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index) ?: return
        subtitleStageJob?.cancel()
        subtitleStageJob = null
        clearPendingNativeSubtitle()
        subtitleView.setCues(emptyList())
        externalSubtitleOverlayActive = false
        runCatching { renderer.setSubtitleTrack("off", sessionId) }
        val renderingMode = SubtitleRenderingMode.read(this)

        // Native sidecars are attached after the renderer reports READY. Attaching while MPV is
        // still opening the file can be ignored, while Exo may rebuild an incomplete MediaItem.
        if (renderingMode != SubtitleRenderingMode.PLAYBRIDGE_OVERLAY &&
            sessionCoordinator.current().phase == RendererSessionPhase.PREPARING
        ) {
            controlsViewModel.clearSubtitle()
            return
        }

        when (renderingMode) {
            SubtitleRenderingMode.PLAYBRIDGE_OVERLAY -> {
                externalSubtitleOverlayActive = true
                controlsViewModel.loadExternalSubtitle(url, item.headers)
            }
            SubtitleRenderingMode.AUTO,
            SubtitleRenderingMode.BUILT_IN,
            -> {
                controlsViewModel.clearSubtitle()
                val requestedMode = renderingMode
                subtitleStageJob = lifecycleScope.launch {
                    try {
                        val file = externalSubtitleStager.stage(url, item.headers)
                        if (session?.sessionId != sessionId || currentExternalSubtitleUrl != url) {
                            externalSubtitleStager.delete(file)
                            return@launch
                        }
                        stagedSubtitleFiles += file
                        val activeRenderer = rendererService ?: error("Renderer disconnected")
                        val stagedUri = externalSubtitleStager.uriFor(file)
                        pendingNativeSubtitleUri = stagedUri
                        pendingNativeSubtitleMode = requestedMode
                        activeRenderer.addExternalSubtitle(
                            stagedUri,
                            externalSubtitleName(url),
                            sessionId,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        clearPendingNativeSubtitle()
                        FileLogger.w(TAG, "Unable to stage external subtitle for built-in rendering")
                        if (requestedMode == SubtitleRenderingMode.AUTO &&
                            session?.sessionId == sessionId &&
                            currentExternalSubtitleUrl == url
                        ) {
                            externalSubtitleOverlayActive = true
                            controlsViewModel.loadExternalSubtitle(url, item.headers)
                        } else if (requestedMode == SubtitleRenderingMode.BUILT_IN) {
                            Toast.makeText(
                                this@PlayerHostActivity,
                                "Unable to load subtitle with the built-in player",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun switchRenderer(target: RendererKind) {
        if (target == rendererKind || target !in setOf(RendererKind.MPV, RendererKind.EXO)) return
        FileLogger.i(TAG, "Switching renderer from $rendererKind to $target")
        showTransition(R.string.player_switching, force = true)
        cancelStartupWatchdog()
        cancelPrePlayCountdown()
        progressManager.saveProgress()
        val oldKind = rendererKind
        val oldSession = session
        requestedStartPositionMs = lastPositionMs.takeIf { it > 0L }
        pendingSeekTracker.clear()
        subtitleStageJob?.cancel()
        subtitleStageJob = null
        clearPendingNativeSubtitle()
        clearInitialSubtitleHandled()
        subtitleView.setCues(emptyList())
        controlsViewModel.clearSubtitle()
        runCatching { oldSession?.let { rendererService?.release(it.sessionId) } }
        unbindCurrentRenderer()
        rotateRendererSurface()
        terminateRendererProcess(oldKind)
        attemptedRenderers.clear()
        attemptedRenderers += target
        rendererKind = target
        session = sessionCoordinator.begin(target)
        hostPlaying = false
        controlsViewModel.setPlaying(false)
        controlsViewModel.setBuffering(true)
        controlsViewModel.setEngine(controlsAdapter, target.name.lowercase(), this)
        ServerService.notifyContextPlayer(this, target.engineId)
        resetVideoSurfaceLayout()
        bindRenderer(R.string.player_switching)
    }

    override fun onDestroy() {
        finishingSession = true
        cancelStartupWatchdog()
        cancelPrePlayCountdown()
        cancelFailureFinish()
        subtitleStageJob?.cancel()
        subtitleStageJob = null
        progressManager.saveProgress()
        val currentSession = session
        if (currentSession != null) {
            runCatching { rendererService?.stop(currentSession.sessionId) }
            runCatching { rendererService?.release(currentSession.sessionId) }
        }
        unbindCurrentRenderer()
        subtitleView.setCues(emptyList())
        stagedSubtitleFiles.forEach(externalSubtitleStager::delete)
        stagedSubtitleFiles.clear()
        surfaceView.holder.removeCallback(surfaceCallback)
        controlsViewModel.detach()
        stillWatchingController.dispose()
        ServerService.notifyContextIdle(this, rendererKind.engineId)
        activeHostCount.decrementAndGet()
        super.onDestroy()
    }

    private fun replacePlayback(requestIntent: Intent, playlist: playbridge.PlaylistPayload) {
        FileLogger.i(
            TAG,
            "Replacement intent received " +
                "(requestId=${playbackRequestId(requestIntent) ?: "legacy"})",
        )
        progressManager.saveProgress()
        cancelStartupWatchdog()
        cancelPrePlayCountdown()
        cancelFailureFinish()

        val oldKind = rendererKind
        val oldSession = session
        val oldRenderer = rendererService
        val targetKind = rendererKindFromIntent(requestIntent)

        // Begin the new logical session before touching the old renderer. Any stop/release
        // callbacks already in flight retain the old session id and are ignored by the host.
        rendererKind = targetKind
        session = sessionCoordinator.begin(targetKind)
        finishingSession = false
        attemptedRenderers.clear()
        attemptedRenderers += targetKind
        playbackCoordinator.setPlaylist(playlist.items, playlist.start_index)
        controlsViewModel.resetSessionSettings(
            defaultQualityMaxHeight(playlist.items.getOrNull(playlist.start_index)),
        )
        playbackCoordinator.queueAdd(ServerService.drainPendingQueueItems(this))
        requestedStartPositionMs = requestIntent
            .getLongExtra(ServerService.EXTRA_START_POSITION, 0L)
            .takeIf { it > 0L }
        resetPlaybackUi()
        broadcastPlaylistStatus(playbackCoordinator.playlist, playbackCoordinator.index)
        updateControlsForCurrentItem(
            showPrePlay = !requestIntent.getBooleanExtra(
                ServerService.EXTRA_SKIP_PREPLAY,
                false,
            ),
        )
        controlsViewModel.setEngine(controlsAdapter, targetKind.engineId, this)
        ServerService.notifyContextPlayer(this, targetKind.engineId)

        if (targetKind != oldKind) {
            runCatching { oldSession?.let { oldRenderer?.release(it.sessionId) } }
            unbindCurrentRenderer()
            rotateRendererSurface()
            terminateRendererProcess(oldKind)
            bindRenderer(R.string.player_switching)
            return
        }

        val renderer = oldRenderer
        if (renderer == null) {
            // A replacement may arrive while the original bind is still in flight. Rebind with
            // the new session captured by the connection so the stale callback cannot prepare it.
            unbindCurrentRenderer()
            bindRenderer()
            return
        }

        val currentSession = session ?: return
        showTransition(R.string.player_preparing)
        scheduleRendererOpenWatchdog(currentSession.sessionId, targetKind)
        try {
            renderer.setCallback(callback)
            prepareRenderer(renderer)
        } catch (error: RemoteException) {
            handleRendererFailure(error.message ?: "Renderer failed to replace playback")
        }
    }

    private fun resetPlaybackUi() {
        pendingSeekTracker.clear()
        hostPlaying = false
        lastPositionMs = 0L
        lastDurationMs = 0L
        resetVideoSurfaceLayout()
        lastSavedPositionMs = 0L
        videoTracks = emptyList()
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        externalSubtitleUrls = emptyList()
        currentExternalSubtitleUrl = null
        externalSubtitleOverlayActive = false
        subtitleStageJob?.cancel()
        subtitleStageJob = null
        clearPendingNativeSubtitle()
        clearInitialSubtitleHandled()
        controlsViewModel.setPlaying(false)
        controlsViewModel.setBuffering(true)
        controlsViewModel.clearPlaybackTransition()
        controlsViewModel.hideControls()
        controlsViewModel.clearSubtitle()
        subtitleView.setCues(emptyList())
        controlsViewModel.updateTracks(
            audio = emptyList(),
            subtitles = emptyList(),
            video = emptyList(),
            currentSubtitleUrl = null,
        )
        broadcastTracks()
    }

    private fun acceptPlaybackRequest(intent: Intent?): Boolean {
        val requestId = playbackRequestId(intent)
        val accepted = playbackRequestGate.accept(requestId)
        if (!accepted) FileLogger.i(TAG, "Ignoring stale playback request $requestId")
        return accepted
    }

    private fun playbackRequestId(intent: Intent?): Long? =
        intent?.takeIf { it.hasExtra(PlayerLauncher.EXTRA_PLAYBACK_REQUEST_ID) }
            ?.getLongExtra(PlayerLauncher.EXTRA_PLAYBACK_REQUEST_ID, Long.MIN_VALUE)

    private fun rendererKindFromIntent(intent: Intent): RendererKind =
        intent.getStringExtra(EXTRA_RENDERER)
            ?.let { value -> runCatching { RendererKind.valueOf(value.uppercase()) }.getOrNull() }
            ?.takeIf { it == RendererKind.MPV || it == RendererKind.EXO }
            ?: RendererKind.EXO

    private fun failEmptyInitialRequest() {
        showTransition(R.string.player_empty_request, force = true)
        finishingSession = true
        val finishRunnable = Runnable {
            failureFinishRunnable = null
            finish()
        }
        failureFinishRunnable = finishRunnable
        mainHandler.postDelayed(finishRunnable, FAILURE_VISIBLE_MS)
    }

    private fun cancelFailureFinish() {
        failureFinishRunnable?.let(mainHandler::removeCallbacks)
        failureFinishRunnable = null
    }

    private fun bindRenderer(@StringRes transitionMessage: Int = R.string.player_preparing) {
        val currentSession = session ?: return
        val expectedKind = rendererKind
        val expectedSessionId = currentSession.sessionId
        val serviceClass = when (rendererKind) {
            RendererKind.MPV -> MpvRendererService::class.java
            RendererKind.EXO -> ExoRendererService::class.java
            else -> null
        }
        if (serviceClass == null) {
            handleRendererFailure("Renderer $rendererKind is not available yet")
            return
        }

        showTransition(transitionMessage)
        scheduleRendererOpenWatchdog(expectedSessionId, expectedKind)

        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (connection !== this || rendererKind != expectedKind) return
                val renderer = IRendererService.Stub.asInterface(service) ?: run {
                    handleRendererFailure("Renderer service returned no Binder")
                    return
                }
                rendererService = renderer
                try {
                    renderer.setCallback(callback)
                    prepareRenderer(renderer)
                } catch (error: RemoteException) {
                    handleRendererFailure(error.message ?: "Renderer connection failed")
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (connection !== this || rendererKind != expectedKind) return
                rendererService = null
                if (!finishingSession) {
                    handleRendererFailure("$expectedKind renderer process disconnected")
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                onServiceDisconnected(name)
            }

            override fun onNullBinding(name: ComponentName?) {
                if (connection === this && rendererKind == expectedKind) {
                    handleRendererFailure("$expectedKind renderer service rejected the binding")
                }
            }
        }
        connection = newConnection
        if (!bindService(Intent(this, serviceClass), newConnection, BIND_AUTO_CREATE)) {
            connection = null
            handleRendererFailure("Unable to bind $rendererKind renderer service")
        }
    }

    private fun isCurrentSession(expectedSessionId: Long, expectedKind: RendererKind): Boolean =
        session?.sessionId == expectedSessionId && rendererKind == expectedKind

    private fun unbindCurrentRenderer() {
        val currentConnection = connection
        connection = null
        if (currentConnection != null) runCatching { unbindService(currentConnection) }
        rendererService = null
        preparedSessionId = 0L
    }

    private fun prepareRenderer(renderer: IRendererService) {
        val currentSession = session ?: return
        if (surface == null) {
            FileLogger.d(TAG, "Waiting for a fresh surface before preparing $rendererKind")
            return
        }
        if (preparedSessionId == currentSession.sessionId) return
        val payload = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index)
        if (payload == null) {
            handleRendererFailure("Host launch did not include a PlayPayload")
            return
        }

        configureProgress(payload)
        preparedSessionId = currentSession.sessionId
        val externalUrl = currentExternalSubtitleUrl
        val renderingMode = SubtitleRenderingMode.read(this)
        val stageForInitialExoPrepare = rendererKind == RendererKind.EXO &&
            externalUrl != null &&
            renderingMode != SubtitleRenderingMode.PLAYBRIDGE_OVERLAY

        if (!stageForInitialExoPrepare) {
            if (externalUrl != null && renderingMode == SubtitleRenderingMode.PLAYBRIDGE_OVERLAY) {
                markInitialSubtitleHandled(currentSession.sessionId, externalUrl)
            }
            prepareRendererNow(renderer, payload, currentSession.sessionId)
            return
        }
        val initialExternalUrl = checkNotNull(externalUrl)

        cancelStartupWatchdog()
        subtitleStageJob?.cancel()
        subtitleStageJob = lifecycleScope.launch {
            try {
                val file = externalSubtitleStager.stage(initialExternalUrl, payload.headers)
                if (session?.sessionId != currentSession.sessionId ||
                    currentExternalSubtitleUrl != initialExternalUrl ||
                    rendererKind != RendererKind.EXO ||
                    rendererService?.asBinder() != renderer.asBinder()
                ) {
                    externalSubtitleStager.delete(file)
                    if (preparedSessionId == currentSession.sessionId) preparedSessionId = 0L
                    return@launch
                }
                stagedSubtitleFiles += file
                val stagedUri = externalSubtitleStager.uriFor(file)
                pendingNativeSubtitleUri = stagedUri
                pendingNativeSubtitleMode = renderingMode
                markInitialSubtitleHandled(currentSession.sessionId, initialExternalUrl)
                prepareRendererNow(
                    renderer,
                    payload,
                    currentSession.sessionId,
                    initialSubtitleUri = stagedUri,
                    initialSubtitleLabel = externalSubtitleName(initialExternalUrl),
                )
            } catch (error: CancellationException) {
                if (preparedSessionId == currentSession.sessionId) preparedSessionId = 0L
                throw error
            } catch (error: Exception) {
                clearPendingNativeSubtitle()
                markInitialSubtitleHandled(currentSession.sessionId, initialExternalUrl)
                FileLogger.w(TAG, "Unable to stage subtitle before Exo playback")
                if (renderingMode == SubtitleRenderingMode.AUTO &&
                    session?.sessionId == currentSession.sessionId &&
                    currentExternalSubtitleUrl == initialExternalUrl
                ) {
                    externalSubtitleOverlayActive = true
                    controlsViewModel.loadExternalSubtitle(initialExternalUrl, payload.headers)
                } else if (renderingMode == SubtitleRenderingMode.BUILT_IN) {
                    Toast.makeText(
                        this@PlayerHostActivity,
                        "Unable to load subtitle with the built-in player",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                prepareRendererNow(renderer, payload, currentSession.sessionId)
            }
        }
    }

    private fun prepareRendererNow(
        renderer: IRendererService,
        payload: PlayPayload,
        sessionId: Long,
        initialSubtitleUri: String? = null,
        initialSubtitleLabel: String? = null,
    ) {
        if (session?.sessionId != sessionId || preparedSessionId != sessionId) return
        val rendererSurface = surface ?: run {
            preparedSessionId = 0L
            return
        }
        val request = Bundle().apply {
            putString(RendererProtocol.KEY_PAYLOAD_JSON, encodePlayPayloadListJson(listOf(payload)))
            initialSubtitleUri?.let {
                putString(RendererProtocol.KEY_INITIAL_SUBTITLE_URI, it)
                putString(RendererProtocol.KEY_INITIAL_SUBTITLE_LABEL, initialSubtitleLabel)
            }
        }
        scheduleRendererOpenWatchdog(sessionId, rendererKind)
        try {
            renderer.prepare(request, sessionId)
            renderer.attachSurface(rendererSurface, sessionId)
            applyRendererSettings(renderer, sessionId)
            renderer.play(sessionId)
            // Request resume immediately. MPV retains this until FILE_LOADED, while Exo can
            // accept a seek during preparation; waiting for a rendered-ready event can briefly
            // start long-form library media from zero.
            restoreProgress(sessionId)
        } catch (error: RemoteException) {
            preparedSessionId = 0L
            handleRendererFailure(error.message ?: "Renderer failed to prepare playback")
        }
    }

    private fun applyRendererSettings(renderer: IRendererService, sessionId: Long) {
        val state = controlsViewModel.controlsState.value
        renderer.setPlaybackSpeed(state.playbackSpeed, sessionId)
        renderer.setVideoScaling(state.videoScalingMode, sessionId)
        renderer.setLooping(state.isLooping, sessionId)
        renderer.setAudioBoost(state.isAudioBoostEnabled, sessionId)
        renderer.setSubtitleDelay(state.subtitleDelayMs, sessionId)
        renderer.setVideoQuality(state.videoQualityMaxHeight, sessionId)
    }

    private fun configureProgress(payload: PlayPayload) {
        val items = playbackCoordinator.playlist
        val index = playbackCoordinator.index
        val payloadJson = runCatching { PlayerLauncher.historyPayloadJson(items, index) }
            .getOrDefault("")
        val visualMetadata = payload.visual_metadata
        val historyId = PlayerLauncher.historyId(items)
        val thumbnailUrl = visualMetadata?.backdrop_url
            ?: visualMetadata?.poster_url
            ?: historyThumbnailStore.existingUrl(historyId)
        currentHistoryId = historyId
        currentHistoryHasThumbnail = thumbnailUrl != null
        resetHistoryThumbnailCaptureClock()
        progressManager.setCurrentMedia(
            url = payload.url,
            title = payload.title,
            contentType = payload.content_type,
            headers = payload.headers,
            payloadJson = payloadJson,
            historyId = historyId,
            thumbnailUrl = thumbnailUrl,
            preferredAudioLanguage = payload.preferred_audio_language,
            preferredSubtitleLanguage = payload.preferred_subtitle_language,
            externalSubtitleUrl = currentExternalSubtitleUrl,
            playbackSpeed = controlsViewModel.controlsState.value.playbackSpeed,
        )
        progressManager.recordLanded(requestedStartPositionMs ?: 0L)
        lastSavedPositionMs = 0L
    }

    private fun resetHistoryThumbnailCaptureClock() {
        thumbnailCaptureGeneration += 1L
        thumbnailCaptureInFlightGeneration = null
        thumbnailPlaybackElapsedMs = 0L
        thumbnailPlayingSinceElapsedMs = null
        lastThumbnailCapturePlaybackMs = null
    }

    private fun updateHistoryThumbnailPlaybackClock(state: String, nowMs: Long) {
        val playingSince = thumbnailPlayingSinceElapsedMs
        if (state == "playing") {
            if (playingSince == null) thumbnailPlayingSinceElapsedMs = nowMs
        } else if (playingSince != null) {
            thumbnailPlaybackElapsedMs += (nowMs - playingSince).coerceAtLeast(0L)
            thumbnailPlayingSinceElapsedMs = null
        }
    }

    private fun historyThumbnailPlaybackElapsedMs(nowMs: Long): Long =
        thumbnailPlaybackElapsedMs +
            (thumbnailPlayingSinceElapsedMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L)

    private fun requestHistoryThumbnailCapture(exitFallback: Boolean = false) {
        val historyId = currentHistoryId ?: return
        if (thumbnailCaptureInFlightGeneration != null) return
        if (!getSharedPreferences("browser_prefs", MODE_PRIVATE)
                .getBoolean("enable_history", true)
        ) return

        val playbackElapsedMs = historyThumbnailPlaybackElapsedMs(SystemClock.elapsedRealtime())
        if (!shouldCaptureHistoryThumbnail(
                mode = HistoryThumbnailMode.read(this),
                hasThumbnail = currentHistoryHasThumbnail,
                playbackElapsedMs = playbackElapsedMs,
                lastCapturePlaybackMs = lastThumbnailCapturePlaybackMs,
                exitFallback = exitFallback,
            )
        ) return

        thumbnailCaptureInFlightGeneration = thumbnailCaptureGeneration
        captureHistoryThumbnail(
            historyId = historyId,
            generation = thumbnailCaptureGeneration,
            playbackElapsedMs = playbackElapsedMs,
            attempt = 0,
        )
    }

    private fun captureHistoryThumbnail(
        historyId: String,
        generation: Long,
        playbackElapsedMs: Long,
        attempt: Int,
    ) {
        if (currentHistoryId != historyId ||
            thumbnailCaptureGeneration != generation ||
            !surfaceView.holder.surface.isValid
        ) {
            if (thumbnailCaptureInFlightGeneration == generation) {
                thumbnailCaptureInFlightGeneration = null
            }
            return
        }
        val bitmap = createBitmap(
            HistoryThumbnailStore.WIDTH,
            HistoryThumbnailStore.HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        runCatching {
            PixelCopy.request(
                surfaceView,
                bitmap,
                { result ->
                    if (result != PixelCopy.SUCCESS) {
                        bitmap.recycle()
                        if (attempt == 0 &&
                            currentHistoryId == historyId &&
                            thumbnailCaptureGeneration == generation
                        ) {
                            mainHandler.postDelayed(
                                {
                                    captureHistoryThumbnail(
                                        historyId,
                                        generation,
                                        playbackElapsedMs,
                                        attempt = 1,
                                    )
                                },
                                500L,
                            )
                        } else {
                            if (thumbnailCaptureInFlightGeneration == generation) {
                                thumbnailCaptureInFlightGeneration = null
                            }
                        }
                    } else if (currentHistoryId != historyId ||
                        thumbnailCaptureGeneration != generation
                    ) {
                        bitmap.recycle()
                        if (thumbnailCaptureInFlightGeneration == generation) {
                            thumbnailCaptureInFlightGeneration = null
                        }
                    } else {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val thumbnailUrl = try {
                                historyThumbnailStore.save(historyId, bitmap)
                            } finally {
                                bitmap.recycle()
                            }
                            if (thumbnailUrl == null) {
                                withContext(Dispatchers.Main) {
                                    if (thumbnailCaptureInFlightGeneration == generation) {
                                        thumbnailCaptureInFlightGeneration = null
                                    }
                                }
                                return@launch
                            }
                            val historyUpdated = runCatching {
                                historyStore.updateThumbnail(historyId, thumbnailUrl)
                            }.onFailure {
                                FileLogger.w(TAG, "Unable to update the Library thumbnail")
                            }.isSuccess
                            withContext(Dispatchers.Main) {
                                if (historyUpdated &&
                                    currentHistoryId == historyId &&
                                    thumbnailCaptureGeneration == generation
                                ) {
                                    currentHistoryHasThumbnail = true
                                    lastThumbnailCapturePlaybackMs = playbackElapsedMs
                                    progressManager.updateThumbnail(thumbnailUrl)
                                    progressManager.saveProgress()
                                }
                                if (thumbnailCaptureInFlightGeneration == generation) {
                                    thumbnailCaptureInFlightGeneration = null
                                }
                            }
                        }
                    }
                },
                mainHandler,
            )
        }.onFailure {
            bitmap.recycle()
            if (thumbnailCaptureInFlightGeneration == generation) {
                thumbnailCaptureInFlightGeneration = null
            }
        }
    }

    private fun updateControlsForCurrentItem(showPrePlay: Boolean = false) {
        val item = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index) ?: return
        controlsViewModel.setTitle(item.title ?: "")
        controlsViewModel.subtitleRequestHeaders = item.headers
        val shouldShowPrePlay = showPrePlay && item.visual_metadata != null
        controlsViewModel.setPrePlay(
            item.visual_metadata,
            this,
            showCountdown = shouldShowPrePlay,
        )
        controlsViewModel.setPrePlayLaunching(shouldShowPrePlay)
        controlsViewModel.setPrePlayCountdown(if (shouldShowPrePlay) -1 else 0)
        externalSubtitleUrls = item.subtitles.filter(String::isNotBlank).distinct()
        currentExternalSubtitleUrl = currentExternalSubtitleUrl
            ?.takeIf { it in externalSubtitleUrls }
            ?: externalSubtitleUrls.firstOrNull()
        val externalUrl = currentExternalSubtitleUrl
        if (externalUrl != null &&
            SubtitleRenderingMode.read(this) == SubtitleRenderingMode.PLAYBRIDGE_OVERLAY
        ) {
            externalSubtitleOverlayActive = true
            controlsViewModel.loadExternalSubtitle(externalUrl, item.headers)
        } else {
            externalSubtitleOverlayActive = false
            controlsViewModel.clearSubtitle()
        }
        subtitleView.setCues(emptyList())
        updateTrackControls()
        broadcastTracks()
        controlsViewModel.updatePlaylistData(playbackCoordinator.playlist, playbackCoordinator.index)
        controlsViewModel.setPlaylistVisible(playbackCoordinator.hasPlaylist)
        controlsViewModel.setBuffering(true)
    }

    private fun restoreProgress(sessionId: Long) {
        val explicitPosition = requestedStartPositionMs
        requestedStartPositionMs = null
        if (explicitPosition != null && explicitPosition > 0L) {
            seekForSession(explicitPosition, sessionId)
            return
        }

        val url = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index)?.url ?: return
        lifecycleScope.launch {
            progressManager.restoreProgress(url) { position ->
                seekForSession(position, sessionId)
            }
        }
    }

    private fun seekForSession(positionMs: Long, expectedSessionId: Long) {
        val currentSession = session ?: return
        if (currentSession.sessionId != expectedSessionId) return
        val target = positionMs.coerceAtLeast(0L)
        val origin = lastPositionMs
        pendingSeekTracker.start(
            sessionId = expectedSessionId,
            originMs = origin,
            targetMs = target,
            nowMs = SystemClock.elapsedRealtime(),
        )
        lastPositionMs = target
        controlsViewModel.setPendingSeekTime(target)
        runCatching { rendererService?.seekTo(target, expectedSessionId) }
    }

    private fun handleRendererEvent(event: Bundle) {
        val currentSession = session ?: return
        if (event.getLong(RendererProtocol.KEY_SESSION_ID) != currentSession.sessionId ||
            sessionCoordinator.current().sessionId != currentSession.sessionId
        ) return
        when (event.getString(RendererProtocol.KEY_EVENT)) {
            RendererProtocol.EVENT_READY -> {
                sessionCoordinator.markReady(currentSession.sessionId)
                cancelStartupWatchdog()
                controlsViewModel.setBuffering(false)
                controlsViewModel.clearPlaybackTransition()
                currentExternalSubtitleUrl?.let { externalUrl ->
                    if (!wasInitialSubtitleHandled(currentSession.sessionId, externalUrl)) {
                        rendererService?.let { renderer ->
                            applyExternalSubtitleSelection(renderer, currentSession.sessionId)
                        }
                    }
                }
                val hasPrePlay = controlsViewModel.controlsState.value.prePlayMetadata != null
                if (hasPrePlay) {
                    startPrePlayCountdown(currentSession.sessionId)
                }
            }
            RendererProtocol.EVENT_FIRST_FRAME -> {
                controlsViewModel.clearPlaybackTransition()
                markPlaybackStarted(currentSession.sessionId)
            }
            RendererProtocol.EVENT_STATE -> {
                val state = event.getString(RendererProtocol.KEY_STATE) ?: return
                updateHistoryThumbnailPlaybackClock(state, SystemClock.elapsedRealtime())
                hostPlaying = state == "playing"
                stillWatchingController.onPlayingChanged(hostPlaying && state != "buffering")
                controlsViewModel.setPlaying(hostPlaying)
                controlsViewModel.setBuffering(state == "buffering")
                if (state == "playing") controlsViewModel.clearPlaybackTransition()
                val reportedPositionMs = event
                    .getLong(RendererProtocol.KEY_POSITION_MS)
                    .coerceAtLeast(0L)
                lastPositionMs = pendingSeekTracker.displayPosition(
                    sessionId = currentSession.sessionId,
                    reportedPositionMs = reportedPositionMs,
                    nowMs = SystemClock.elapsedRealtime(),
                )
                lastDurationMs = event.getLong(RendererProtocol.KEY_DURATION_MS).coerceAtLeast(0L)
                if (state == "playing" &&
                    !pendingSeekTracker.isPending(currentSession.sessionId) &&
                    abs(lastPositionMs - lastSavedPositionMs) >= PROGRESS_SAVE_INTERVAL_MS
                ) {
                    lastSavedPositionMs = lastPositionMs
                    progressManager.saveProgress()
                    requestHistoryThumbnailCapture()
                }
                val status = createStatusJson(
                    state = state,
                    position = lastPositionMs,
                    duration = event.getLong(RendererProtocol.KEY_DURATION_MS).coerceAtLeast(0L),
                    title = event.getString(RendererProtocol.KEY_TITLE),
                )
                ServerService.broadcastStatus(this, status)
            }
            RendererProtocol.EVENT_ENDED -> {
                if (sessionCoordinator.canHandleEnded(currentSession.sessionId)) {
                    lifecycleScope.launch { playbackCoordinator.next() }
                } else {
                    FileLogger.d(
                        TAG,
                        "Ignoring end event before session ${currentSession.sessionId} became ready",
                    )
                }
            }
            RendererProtocol.EVENT_VIDEO_SIZE -> {
                videoWidth = event.getInt(RendererProtocol.KEY_VIDEO_WIDTH).coerceAtLeast(0)
                videoHeight = event.getInt(RendererProtocol.KEY_VIDEO_HEIGHT).coerceAtLeast(0)
                videoFrameRate = event.getFloat(RendererProtocol.KEY_VIDEO_FPS)
                    .takeIf { it.isFinite() && it > 0f } ?: 0f
                applyFrameRateMatching()
                updateVideoSurfaceLayout()
            }
            RendererProtocol.EVENT_VIDEO_RATE -> {
                videoFrameRate = event.getFloat(RendererProtocol.KEY_VIDEO_FPS)
                    .takeIf { it.isFinite() && it > 0f } ?: 0f
                applyFrameRateMatching()
            }
            RendererProtocol.EVENT_TRACKS -> handleTracks(event)
            RendererProtocol.EVENT_CAPABILITIES -> handleCapabilities(event)
            RendererProtocol.EVENT_CUES -> handleSubtitleCues(event)
            RendererProtocol.EVENT_EXTERNAL_SUBTITLE_RESULT -> {
                handleExternalSubtitleResult(event, currentSession.sessionId)
            }
            RendererProtocol.EVENT_STOPPED -> {
                hostPlaying = false
                pendingSeekTracker.clear()
                sessionCoordinator.markStopped(currentSession.sessionId)
                runCatching { rendererService?.release(currentSession.sessionId) }
                session = null
                finish()
            }
            RendererProtocol.EVENT_ERROR -> handleRendererFailure(
                event.getString(RendererProtocol.KEY_ERROR) ?: "Renderer error",
            )
        }
    }

    private fun startPlaylistItem() {
        if (finishingSession) return
        stillWatchingController.onMediaChanged()
        val renderer = rendererService ?: run {
            handleRendererFailure("Renderer disconnected while advancing the playlist")
            return
        }
        session = sessionCoordinator.begin(rendererKind)
        cancelPrePlayCountdown()
        pendingSeekTracker.clear()
        hostPlaying = false
        lastPositionMs = 0L
        lastDurationMs = 0L
        resetVideoSurfaceLayout()
        videoTracks = emptyList()
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        externalSubtitleUrls = emptyList()
        currentExternalSubtitleUrl = null
        externalSubtitleOverlayActive = false
        subtitleStageJob?.cancel()
        subtitleStageJob = null
        clearPendingNativeSubtitle()
        clearInitialSubtitleHandled()
        subtitleView.setCues(emptyList())
        controlsViewModel.clearSubtitle()
        requestedStartPositionMs = null
        updateControlsForCurrentItem()
        showTransition(R.string.player_preparing_next)
        scheduleRendererOpenWatchdog(session!!.sessionId, rendererKind)
        try {
            renderer.setCallback(callback)
            prepareRenderer(renderer)
        } catch (error: RemoteException) {
            handleRendererFailure(error.message ?: "Renderer failed while advancing the playlist")
        }
    }

    private fun finishPlaybackSession() {
        if (finishingSession) return
        finishingSession = true
        cancelStartupWatchdog()
        cancelPrePlayCountdown()
        progressManager.saveProgress()
        session?.let { currentSession ->
            sessionCoordinator.requestStop(currentSession.sessionId)
            runCatching { rendererService?.stop(currentSession.sessionId) }
        }
        finish()
    }

    private fun broadcastPlaylistStatus(items: List<PlayPayload>, index: Int) {
        controlsViewModel.updatePlaylistData(items, index)
        controlsViewModel.setPlaylistVisible(items.size > 1)
        val itemsJson = org.json.JSONArray().apply {
            items.forEachIndexed { itemIndex, item ->
                put(org.json.JSONObject().apply {
                    put("index", itemIndex)
                    put("title", item.title ?: "Item ${itemIndex + 1}")
                    item.visual_metadata?.season?.let { put("season", it) }
                    item.visual_metadata?.episode?.let { put("episode", it) }
                    item.visual_metadata?.imdb_id?.let { put("imdbId", it) }
                    item.binge_group?.let { put("bingeGroup", it) }
                })
            }
        }
        val status = org.json.JSONObject().apply {
            put("type", "playlist_status")
            put("items", itemsJson)
            put("currentIndex", if (items.isEmpty()) 0 else index)
            put("totalCount", items.size)
        }.toString()
        ServerService.broadcastPlaylistStatus(this, status)
    }

    private fun broadcastCurrentState() {
        val item = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index)
        ServerService.broadcastStatus(
            this,
            createStatusJson(
                state = if (hostPlaying) "playing" else "paused",
                position = lastPositionMs,
                duration = lastDurationMs,
                title = item?.title,
            ),
        )
        broadcastPlaylistStatus(playbackCoordinator.playlist, playbackCoordinator.index)
        broadcastTracks()
        broadcastPlayerSettings()
    }

    private fun broadcastPlayerSettings() {
        val state = controlsViewModel.controlsState.value
        val status = org.json.JSONObject().apply {
            put("type", "player_settings")
            put("speed", state.playbackSpeed.toDouble())
            put("scaling", state.videoScalingMode)
            put("audioBoost", state.isAudioBoostEnabled)
            put("subtitleOffsetMs", state.subtitleDelayMs)
            put("engine", rendererKind.name.lowercase())
            put("qualityMaxHeight", state.videoQualityMaxHeight)
            put("currentVideoHeight", state.currentVideoHeight)
            put("isLive", state.capabilities.isLive)
            put("isSeekable", state.capabilities.isSeekable)
            put("speedAvailable", state.capabilities.speedAvailable)
            put("scalingAvailable", state.capabilities.scalingAvailable)
            put("audioBoostAvailable", state.capabilities.audioBoostAvailable)
            put("qualityAvailable", state.capabilities.qualityAvailable)
        }.toString()
        ServerService.broadcastStatus(this, status)
    }

    @Suppress("DEPRECATION")
    private fun handleTracks(event: Bundle) {
        fun decode(key: String): List<RendererTrack> =
            event.getParcelableArrayList<Bundle>(key).orEmpty().map { track ->
                RendererTrack(
                    id = track.getString(RendererProtocol.KEY_TRACK_ID).orEmpty(),
                    label = track.getString(RendererProtocol.KEY_TRACK_LABEL).orEmpty(),
                    language = track.getString(RendererProtocol.KEY_TRACK_LANGUAGE),
                    selected = track.getBoolean(RendererProtocol.KEY_TRACK_SELECTED),
                )
            }
        videoTracks = decode(RendererProtocol.KEY_VIDEO_TRACKS)
        audioTracks = decode(RendererProtocol.KEY_AUDIO_TRACKS)
        subtitleTracks = decode(RendererProtocol.KEY_SUBTITLE_TRACKS)
        progressManager.updateSelections(
            preferredAudioLanguage = audioTracks.firstOrNull { it.selected }?.language,
            preferredSubtitleLanguage = subtitleTracks.firstOrNull { it.selected }?.language,
            externalSubtitleUrl = currentExternalSubtitleUrl,
        )
        updateTrackControls()
        broadcastTracks()
    }

    private fun handleCapabilities(event: Bundle) {
        controlsViewModel.updateCapabilities(
            capabilities = PlaybackCapabilities(
                isLive = event.getBoolean(RendererProtocol.KEY_IS_LIVE),
                isSeekable = event.getBoolean(RendererProtocol.KEY_IS_SEEKABLE, true),
                speedAvailable = event.getBoolean(RendererProtocol.KEY_SPEED_AVAILABLE, true),
                scalingAvailable = event.getBoolean(RendererProtocol.KEY_SCALING_AVAILABLE, true),
                audioBoostAvailable = event.getBoolean(
                    RendererProtocol.KEY_AUDIO_BOOST_AVAILABLE,
                    true,
                ),
                qualityAvailable = event.getBoolean(RendererProtocol.KEY_QUALITY_AVAILABLE),
            ),
            currentVideoHeight = event.getInt(RendererProtocol.KEY_CURRENT_VIDEO_HEIGHT),
            qualityMaxHeight = event.getInt(RendererProtocol.KEY_QUALITY_MAX_HEIGHT),
        )
        broadcastPlayerSettings()
    }

    private fun handleSubtitleCues(event: Bundle) {
        if (rendererKind != RendererKind.EXO || externalSubtitleOverlayActive) {
            subtitleView.setCues(emptyList())
            return
        }
        val cueGroup = event.getBundle(RendererProtocol.KEY_CUE_GROUP)
            ?.let(CueGroup::fromBundle)
        subtitleView.setCues(cueGroup?.cues.orEmpty())
    }

    private fun handleExternalSubtitleResult(event: Bundle, sessionId: Long) {
        val subtitleUri = event.getString(RendererProtocol.KEY_SUBTITLE_URI) ?: return
        if (subtitleUri != pendingNativeSubtitleUri) return
        val requestedMode = pendingNativeSubtitleMode
        clearPendingNativeSubtitle()
        if (event.getBoolean(RendererProtocol.KEY_SUCCESS)) return

        val url = currentExternalSubtitleUrl ?: return
        val item = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index) ?: return
        FileLogger.w(TAG, "Built-in player did not expose the external subtitle track")
        when (requestedMode) {
            SubtitleRenderingMode.AUTO -> {
                externalSubtitleOverlayActive = true
                subtitleView.setCues(emptyList())
                runCatching { rendererService?.setSubtitleTrack("off", sessionId) }
                controlsViewModel.loadExternalSubtitle(url, item.headers)
            }
            SubtitleRenderingMode.BUILT_IN -> Toast.makeText(
                this,
                "The built-in player could not display this subtitle",
                Toast.LENGTH_SHORT,
            ).show()
            else -> Unit
        }
    }

    private fun clearPendingNativeSubtitle() {
        pendingNativeSubtitleUri = null
        pendingNativeSubtitleMode = null
    }

    private fun markInitialSubtitleHandled(sessionId: Long, url: String) {
        initialSubtitleHandledSessionId = sessionId
        initialSubtitleHandledUrl = url
    }

    private fun wasInitialSubtitleHandled(sessionId: Long, url: String): Boolean =
        initialSubtitleHandledSessionId == sessionId && initialSubtitleHandledUrl == url

    private fun clearInitialSubtitleHandled() {
        initialSubtitleHandledSessionId = 0L
        initialSubtitleHandledUrl = null
    }

    private fun updateTrackControls() {
        val hasExternalSelection = currentExternalSubtitleUrl != null
        controlsViewModel.updateTracks(
            audio = audioTracks.map { UnifiedTrack(it.id, it.label, it.selected, "audio") },
            subtitles = subtitleTracks.map {
                UnifiedTrack(it.id, it.label, it.selected && !hasExternalSelection, "sub")
            } + externalSubtitleUrls.map { url ->
                UnifiedTrack(
                    id = url,
                    name = externalSubtitleName(url),
                    isSelected = url == currentExternalSubtitleUrl,
                    type = "external_sub",
                )
            },
            video = videoTracks.map { UnifiedTrack(it.id, it.label, it.selected, "video") },
            currentSubtitleUrl = currentExternalSubtitleUrl,
        )
    }

    private fun broadcastTracks() {
        fun encode(tracks: List<RendererTrack>, type: String) = org.json.JSONArray().apply {
            tracks.forEach { track ->
                put(org.json.JSONObject().apply {
                    put("id", track.id)
                    put("name", track.label)
                    put("selected", track.selected)
                    put("type", type)
                })
            }
        }
        val encodedSubtitles = encode(
            subtitleTracks.map { track ->
                track.copy(selected = track.selected && currentExternalSubtitleUrl == null)
            },
            "sub",
        ).apply {
            externalSubtitleUrls.forEach { url ->
                put(org.json.JSONObject().apply {
                    put("id", url)
                    put("name", externalSubtitleName(url))
                    put("selected", url == currentExternalSubtitleUrl)
                    put("type", "external_sub")
                })
            }
        }
        val status = org.json.JSONObject().apply {
            put("type", "tracks")
            put("video", encode(videoTracks, "video"))
            put("audio", encode(audioTracks, "audio"))
            put("subtitle", encodedSubtitles)
        }.toString()
        ServerService.broadcastStatus(this, status)
    }

    private fun handleRendererFailure(message: String) {
        if (finishingSession || isFinishing || isDestroyed) return
        val failedKind = rendererKind
        FileLogger.w(TAG, "Renderer $failedKind failed: $message")
        val failedSession = session
        requestedStartPositionMs = lastPositionMs.takeIf { it > 0L } ?: requestedStartPositionMs
        pendingSeekTracker.clear()
        cancelPrePlayCountdown()
        failedSession?.let { sessionCoordinator.markFailed(it.sessionId, message) }
        cancelStartupWatchdog()
        runCatching { failedSession?.let { rendererService?.release(it.sessionId) } }
        unbindCurrentRenderer()
        rotateRendererSurface()
        terminateRendererProcess(failedKind)

        val fallback = when (failedKind) {
            RendererKind.MPV -> RendererKind.EXO
            RendererKind.EXO -> RendererKind.MPV
            else -> null
        }
        if (fallback == null || fallback in attemptedRenderers) {
            showTransition(R.string.player_failed, force = true)
            finishingSession = true
            val finishRunnable = Runnable {
                failureFinishRunnable = null
                finish()
            }
            failureFinishRunnable = finishRunnable
            mainHandler.postDelayed(finishRunnable, FAILURE_VISIBLE_MS)
            return
        }

        attemptedRenderers += fallback
        rendererKind = fallback
        session = sessionCoordinator.begin(fallback)
        resetVideoSurfaceLayout()
        controlsViewModel.setEngine(controlsAdapter, fallback.name.lowercase(), this)
        ServerService.notifyContextPlayer(this, fallback.engineId)
        controlsViewModel.setBuffering(true)
        bindRenderer(R.string.player_switching)
    }

    private fun terminateRendererProcess(kind: RendererKind) {
        when (kind) {
            RendererKind.MPV -> MpvProcess.terminateRunningProcess(applicationContext)
            RendererKind.EXO -> ExoProcess.terminateRunningProcess(applicationContext)
            else -> Unit
        }
    }

    private fun scheduleRendererOpenWatchdog(sessionId: Long, kind: RendererKind) {
        cancelStartupWatchdog()
        val watchdog = Runnable {
            startupWatchdog = null
            if (isCurrentSession(sessionId, kind) &&
                sessionCoordinator.current().phase == RendererSessionPhase.PREPARING
            ) {
                handleRendererFailure("$kind renderer did not become ready in time")
            }
        }
        startupWatchdog = watchdog
        mainHandler.postDelayed(watchdog, RENDERER_OPEN_TIMEOUT_MS)
    }

    private fun cancelStartupWatchdog() {
        startupWatchdog?.let(mainHandler::removeCallbacks)
        startupWatchdog = null
    }

    private fun markPlaybackStarted(sessionId: Long) {
        hostPlaying = true
        controlsViewModel.setPlaying(true)
        sessionCoordinator.markFirstFrame(sessionId)
        if (sessionCoordinator.current().phase == RendererSessionPhase.PLAYING) {
            cancelStartupWatchdog()
        }
    }

    private fun startPrePlayCountdown(sessionId: Long) {
        if (prePlayCountdownJob?.isActive == true) return
        val renderer = rendererService ?: return
        runCatching { renderer.pause(sessionId) }
        hostPlaying = false
        controlsViewModel.setPlaying(false)
        prePlayCountdownJob = lifecycleScope.launch {
            controlsViewModel.setPrePlayLaunching(true)
            for (seconds in 5 downTo 1) {
                if (session?.sessionId != sessionId) return@launch
                controlsViewModel.setPrePlayCountdown(seconds)
                delay(1_000L)
            }
            if (session?.sessionId != sessionId) return@launch
            controlsViewModel.setPrePlayCountdown(0)
            controlsViewModel.setPrePlay(null, clearOnlineSubs = false)
            showTransition(R.string.player_starting, force = true)
            runCatching { rendererService?.play(sessionId) }
        }
    }

    private fun startPrePlayNow() {
        val currentSession = session ?: return
        cancelPrePlayCountdown()
        controlsViewModel.setPrePlayCountdown(0)
        controlsViewModel.setPrePlay(null, clearOnlineSubs = false)
        showTransition(R.string.player_starting, force = true)
        runCatching { rendererService?.play(currentSession.sessionId) }
    }

    private fun cancelPrePlayCountdown() {
        prePlayCountdownJob?.cancel()
        prePlayCountdownJob = null
    }

    private fun handleControl(command: String?) {
        if (command?.startsWith("switch_player:") == true) {
            switchRenderer(
                if (command.removePrefix("switch_player:").equals("mpv", ignoreCase = true)) {
                    RendererKind.MPV
                } else {
                    RendererKind.EXO
                },
            )
            return
        }

        val currentSession = session ?: return
        val renderer = rendererService ?: return
        when {
            command == "play" -> {
                hostPlaying = true
                controlsViewModel.setPlaying(true)
                runCatching { renderer.play(currentSession.sessionId) }
            }
            command == "pause" -> {
                hostPlaying = false
                runCatching { renderer.pause(currentSession.sessionId) }
                controlsViewModel.showControls(full = true, playing = false)
                progressManager.saveProgress()
            }
            command == "toggle" -> {
                if (hostPlaying) {
                    hostPlaying = false
                    runCatching { renderer.pause(currentSession.sessionId) }
                    controlsViewModel.showControls(full = true, playing = false)
                    progressManager.saveProgress()
                } else {
                    hostPlaying = true
                    controlsViewModel.setPlaying(true)
                    runCatching { renderer.play(currentSession.sessionId) }
                }
            }
            command == "stop" -> {
                finishingSession = true
                runCatching { renderer.stop(currentSession.sessionId) }
                finish()
            }
            command?.startsWith("seek_to:") == true -> command
                .removePrefix("seek_to:")
                .toLongOrNull()
                ?.let { position ->
                    seekForSession(position, currentSession.sessionId)
                    controlsViewModel.showSeekUI()
                }
            command?.startsWith("audio_track:") == true -> {
                val trackId = command.removePrefix("audio_track:")
                runCatching { renderer.setAudioTrack(trackId, currentSession.sessionId) }
            }
            command?.startsWith("sub_track:") == true -> {
                val trackId = command.removePrefix("sub_track:")
                if (trackId in externalSubtitleUrls) {
                    selectExternalSubtitle(trackId, renderer, currentSession.sessionId)
                } else {
                    selectRendererSubtitle(trackId, renderer, currentSession.sessionId)
                }
            }
            command?.startsWith("add_subtitle:") == true -> {
                val url = command.removePrefix("add_subtitle:")
                if (url.isNotBlank()) {
                    selectExternalSubtitle(url, renderer, currentSession.sessionId)
                }
            }
            command?.startsWith("speed:") == true -> command
                .removePrefix("speed:")
                .toFloatOrNull()
                ?.let { speed ->
                    controlsViewModel.setPlaybackSpeed(speed)
                    broadcastPlayerSettings()
                }
            command?.startsWith("video_quality:") == true -> {
                val value = command.removePrefix("video_quality:")
                val maxHeight = value.takeUnless { it.equals("auto", true) }?.toIntOrNull() ?: 0
                controlsViewModel.setVideoQuality(maxHeight)
                runCatching { renderer.setVideoQuality(maxHeight, currentSession.sessionId) }
                broadcastPlayerSettings()
            }
            command?.startsWith("sub_offset:") == true -> command
                .removePrefix("sub_offset:")
                .toLongOrNull()
                ?.let { delta ->
                    controlsViewModel.adjustSubtitleDelay(delta)
                    broadcastPlayerSettings()
                }
            command?.startsWith("scaling:") == true -> {
                val mode = command.removePrefix("scaling:")
                runCatching { renderer.setVideoScaling(mode, currentSession.sessionId) }
                controlsViewModel.setVideoScaling(mode)
                updateVideoSurfaceLayout()
                broadcastPlayerSettings()
            }
            command == "audio_boost" -> {
                controlsViewModel.toggleAudioBoost()
                getSharedPreferences("browser_prefs", MODE_PRIVATE).edit()
                    .putBoolean(
                        "loudness_enhancer",
                        controlsViewModel.controlsState.value.isAudioBoostEnabled,
                    )
                    .apply()
                broadcastPlayerSettings()
            }
            command == "loop_on" || command == "loop_off" -> {
                val enabled = command == "loop_on"
                runCatching { renderer.setLooping(enabled, currentSession.sessionId) }
                controlsViewModel.setLooping(enabled)
                broadcastPlayerSettings()
            }
            command == "seek_back" -> seekRelative(-10_000L, currentSession.sessionId)
            command == "seek_forward" -> seekRelative(10_000L, currentSession.sessionId)
            command == "next" -> lifecycleScope.launch { playbackCoordinator.next() }
            command == "previous" -> lifecycleScope.launch { playbackCoordinator.previous() }
        }
    }

    private fun seekRelative(deltaMs: Long, sessionId: Long) {
        val target = (lastPositionMs + deltaMs).coerceAtLeast(0L)
        val bounded = if (lastDurationMs > 0L) target.coerceAtMost(lastDurationMs) else target
        seekForSession(bounded, sessionId)
        controlsViewModel.showSeekUI()
    }

    override fun getMediaDuration(): Long = lastDurationMs

    override fun getCurrentPosition(): Long = lastPositionMs

    override fun seekTo(position: Long) {
        session?.let { seekForSession(position, it.sessionId) }
    }

    private fun handleRemote(key: String?) {
        if (key != "back" &&
            controlsViewModel.controlsState.value.playbackTransitionMessage != null
        ) return
        when (key) {
            "back" -> {
                handleBackPressed()
            }
            "dpad_center" -> {
                val state = controlsViewModel.controlsState.value
                if (state.isFullControlsVisible) handleControl("toggle")
                else handleControl("pause")
            }
            "dpad_left" -> handleDirectionalInput(KeyEvent.KEYCODE_DPAD_LEFT)
            "dpad_right" -> handleDirectionalInput(KeyEvent.KEYCODE_DPAD_RIGHT)
            "dpad_up" -> handleDirectionalInput(KeyEvent.KEYCODE_DPAD_UP)
            "dpad_down" -> handleDirectionalInput(KeyEvent.KEYCODE_DPAD_DOWN)
        }
    }

    private fun handleDirectionalInput(keyCode: Int, repeatCount: Int = 0): Boolean {
        val state = controlsViewModel.controlsState.value
        if (state.playbackTransitionMessage != null) return true
        val playbackShortcutsActive = !state.isVisible ||
            (!state.isFullControlsVisible && state.activeOverlay == ActiveOverlay.NONE)
        if (!playbackShortcutsActive) return false

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1L else 1L
                val multiplier = if (repeatCount > 10) 5L else 1L
                session?.let { seekRelative(direction * 10_000L * multiplier, it.sessionId) }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                adjustVolume(AudioManager.ADJUST_RAISE)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                adjustVolume(AudioManager.ADJUST_LOWER)
                true
            }
            else -> false
        }
    }

    private fun adjustVolume(direction: Int) {
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI,
        )
    }

    private fun applyFrameRateMatching() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        val enabled = getSharedPreferences("browser_prefs", MODE_PRIVATE)
            .getBoolean("frame_rate_matching", false)
        val requested = if (enabled) videoFrameRate else 0f
        runCatching {
            surfaceView.holder.surface.takeIf(Surface::isValid)?.setFrameRate(
                requested,
                if (requested > 0f) Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                else Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
            )
        }.onFailure { FileLogger.w(TAG, "Unable to apply frame-rate matching") }
    }

    private fun handleBackPressed() {
        val state = controlsViewModel.controlsState.value
        when {
            state.activeOverlay != ActiveOverlay.NONE -> controlsViewModel.hideOverlay()
            state.isVisible -> controlsViewModel.hideControls()
            else -> {
                finishingSession = true
                finish()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        stillWatchingController.onUserActivity()
        if (controlsViewModel.controlsState.value.playbackTransitionMessage != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                handleControl("toggle")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                handleControl("play")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                handleControl("pause")
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                val state = controlsViewModel.controlsState.value
                if (!state.isVisible || !state.isFullControlsVisible) {
                    // Preserve the established TV behavior: the first center press pauses and
                    // reveals the full controls. Center also promotes the lightweight seek UI to
                    // paused full controls; once those are visible, Compose handles the action.
                    handleControl("pause")
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (handleDirectionalInput(keyCode, event.repeatCount)) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surface = holder.surface
            val renderer = rendererService ?: return
            session?.let { currentSession ->
                runCatching {
                    if (preparedSessionId == currentSession.sessionId) {
                        renderer.attachSurface(holder.surface, currentSession.sessionId)
                    } else {
                        prepareRenderer(renderer)
                    }
                }.onFailure { error ->
                    handleRendererFailure(error.message ?: "Renderer surface attachment failed")
                }
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surface = null
            session?.let { currentSession ->
                runCatching { rendererService?.detachSurface(currentSession.sessionId) }
            }
        }
    }

    private fun newRendererSurfaceView(): SurfaceView = SurfaceView(this).also { view ->
        view.holder.addCallback(surfaceCallback)
    }

    private fun showTransition(@StringRes message: Int, force: Boolean = false) {
        if (force || controlsViewModel.controlsState.value.prePlayMetadata == null) {
            controlsViewModel.showPlaybackTransition(getString(message))
        } else {
            controlsViewModel.clearPlaybackTransition()
        }
    }

    private fun resetVideoSurfaceLayout() {
        videoWidth = 0
        videoHeight = 0
        videoFrameRate = 0f
        applyFrameRateMatching()
        updateVideoSurfaceLayout()
    }

    private fun updateVideoSurfaceLayout() {
        if (!::playerRoot.isInitialized || !::surfaceView.isInitialized) return
        val targetView = surfaceView
        val containerWidth = playerRoot.width
        val containerHeight = playerRoot.height
        if (containerWidth <= 0 || containerHeight <= 0) {
            playerRoot.post {
                if (surfaceView === targetView) updateVideoSurfaceLayout()
            }
            return
        }

        val mode = controlsViewModel.controlsState.value.videoScalingMode
        val preserveAspect = rendererKind == RendererKind.EXO &&
            mode != "Fill" &&
            mode != "Zoom" &&
            videoWidth > 0 &&
            videoHeight > 0
        val dimensions = if (preserveAspect) {
            fittedVideoSurfaceDimensions(
                containerWidth = containerWidth,
                containerHeight = containerHeight,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
            )
        } else {
            VideoSurfaceDimensions(containerWidth, containerHeight)
        }
        val layoutParams = (targetView.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(dimensions.width, dimensions.height)
        if (layoutParams.width == dimensions.width &&
            layoutParams.height == dimensions.height &&
            layoutParams.gravity == Gravity.CENTER
        ) return
        layoutParams.width = dimensions.width
        layoutParams.height = dimensions.height
        layoutParams.gravity = Gravity.CENTER
        targetView.layoutParams = layoutParams
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * A killed vendor decoder can leave its producer connected to the old BufferQueue for several
     * seconds. Reusing that Surface makes the next renderer fail with `nativeWindowConnect -22`.
     * Keep the Activity and controls stable, but give every renderer handoff a fresh BufferQueue.
     */
    private fun rotateRendererSurface() {
        if (!::playerRoot.isInitialized) return
        val oldView = surfaceView
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching {
                oldView.holder.surface.takeIf(Surface::isValid)?.setFrameRate(
                    0f,
                    Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                )
            }
        }
        oldView.holder.removeCallback(surfaceCallback)
        surface = null
        playerRoot.removeView(oldView)

        surfaceView = newRendererSurfaceView()
        playerRoot.addView(surfaceView, 0, FrameLayout.LayoutParams(-1, -1))
        updateVideoSurfaceLayout()
        FileLogger.d(TAG, "Created a fresh renderer surface for $rendererKind")
    }

    companion object {
        private const val TAG = "PlayerHostActivity"
        const val EXTRA_RENDERER = "renderer"
        private const val RENDERER_OPEN_TIMEOUT_MS = 60_000L
        private const val FAILURE_VISIBLE_MS = 600L
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        private val activeHostCount = java.util.concurrent.atomic.AtomicInteger(0)

        internal fun isActive(): Boolean = activeHostCount.get() > 0
    }
}

private val RendererKind.engineId: String
    get() = name.lowercase()
