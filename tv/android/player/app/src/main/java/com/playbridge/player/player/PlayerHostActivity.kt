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
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.playbridge.player.data.PlaybackContext
import com.playbridge.player.data.PlaybackTrackPreference
import com.playbridge.player.data.toSafeLogString
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.server.ServerService
import com.playbridge.player.ui.player.ActiveOverlay
import com.playbridge.player.ui.player.MediaPresentation
import com.playbridge.player.ui.player.PlayerControlsOverlay
import com.playbridge.player.ui.player.PlayerControlsViewModel
import com.playbridge.player.ui.player.PlaybackCapabilities
import com.playbridge.player.ui.player.SettingsTab
import com.playbridge.player.ui.player.UnifiedTrack
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import com.playbridge.shared.protocol.MediaKind
import com.playbridge.shared.protocol.createStatusJson
import com.playbridge.shared.protocol.encodePlayPayloadListJson
import com.playbridge.shared.protocol.resolveMediaKind
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

internal fun shouldKeepPlayerScreenOn(
    isHostStarted: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    hasTransition: Boolean,
    hasPrePlay: Boolean,
    isStillWatchingPrompting: Boolean,
): Boolean = isHostStarted && (
    isPlaying || isBuffering || hasTransition || hasPrePlay || isStillWatchingPrompting
    )

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
    private var pendingPlayingState: Boolean? = null
    private var isHostStarted = false
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private var finishingSession = false
    private var skipPreplayForSession = false
    private var currentMediaKind by mutableStateOf(MediaKind.VIDEO)
    private var presentationPayload by mutableStateOf<PlayPayload?>(null)
    private var imageScale by mutableFloatStateOf(1f)
    private var imageOffsetX by mutableFloatStateOf(0f)
    private var imageOffsetY by mutableFloatStateOf(0f)
    private var imageRotation by mutableFloatStateOf(0f)
    private var imageTransformAnchorX = 0.5f
    private var imageTransformAnchorY = 0.5f
    private var imageTransformAnchorActive = false
    private var imageTimerJob: Job? = null
    private var imageTimerRunning = false
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
            onPromptChanged = { refreshKeepScreenOn() },
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
    private var activeHistoryId: String? = null
    private var sessionPlaybackContext: PlaybackContext? = null
    private var restoreSavedExternalForCurrentItem = false
    private var pendingAudioContextRestore = false
    private var pendingSubtitleContextRestore = false
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
                ServerService.ACTION_MOUSE -> handleImagePointer(
                    event = intent.getStringExtra(ServerService.EXTRA_MOUSE_EVENT),
                    dx = intent.getFloatExtra(ServerService.EXTRA_MOUSE_DX, 0f),
                    dy = intent.getFloatExtra(ServerService.EXTRA_MOUSE_DY, 0f),
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
        activeHistoryId = intent.getStringExtra(PlayerLauncher.EXTRA_HISTORY_ID)
            ?: PlayerLauncher.historyId(playlist.items)
        sessionPlaybackContext = PlayerLauncher.playbackContextFromIntent(intent)
        FileLogger.i(
            TAG,
            "Initial playback request context: ${sessionPlaybackContext.toSafeLogString()}",
        )
        restoreSavedExternalForCurrentItem = sessionPlaybackContext != null
        controlsViewModel.resetSessionSettings(
            defaultQualityMaxHeight(playlist.items.getOrNull(playlist.start_index)),
        )
        applySavedPlaybackSettings()
        requestedStartPositionMs = intent
            .getLongExtra(ServerService.EXTRA_START_POSITION, 0L)
            .takeIf { it > 0L }
        broadcastPlaylistStatus(playbackCoordinator.playlist, playbackCoordinator.index)
        skipPreplayForSession = intent.getBooleanExtra(ServerService.EXTRA_SKIP_PREPLAY, false)
        currentMediaKind = resolveMediaKind(playlist.items[playbackCoordinator.index])
        updateControlsForCurrentItem(
            showPrePlay = !skipPreplayForSession && currentMediaKind == MediaKind.VIDEO,
        )
        attemptedRenderers += rendererKind
        session = sessionCoordinator.begin(
            if (currentMediaKind == MediaKind.IMAGE) RendererKind.IMAGE else rendererKind,
        )
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
        isHostStarted = true
        val filter = android.content.IntentFilter().apply {
            addAction(ServerService.ACTION_CONTROL)
            addAction(ServerService.ACTION_REMOTE)
            addAction(ServerService.ACTION_MOUSE)
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
        refreshKeepScreenOn()
    }

    override fun onStop() {
        isHostStarted = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        logPlaybackContextCheckpoint(
            "onStop(finishing=$isFinishing, changingConfigurations=$isChangingConfigurations)",
        )
        if (!isFinishing && !isChangingConfigurations) {
            handleControl("pause")
        }
        runCatching { unregisterReceiver(controlReceiver) }
        super.onStop()
    }

    override fun onPause() {
        logPlaybackContextCheckpoint("onPause")
        requestHistoryThumbnailCapture(exitFallback = true)
        super.onPause()
    }

    private fun setupControlsOverlay() {
        controlsViewModel.setEngine(controlsAdapter, rendererKind.name.lowercase(), this)
        controlsViewModel.setEndSegmentSkipHandler(::advanceAfterEndSegment)
        if (getSharedPreferences("browser_prefs", MODE_PRIVATE)
                .getBoolean("loudness_enhancer", false)
        ) {
            controlsViewModel.toggleAudioBoost()
        }
        composeView.setContent {
            PlayBridgeTVTheme {
                val state by controlsViewModel.controlsState.collectAsState()
                val stillWatching by stillWatchingController.state.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    MediaPresentation(
                        payload = presentationPayload,
                        mediaKind = currentMediaKind.wireValue,
                        imageScale = imageScale,
                        imageOffsetX = imageOffsetX,
                        imageOffsetY = imageOffsetY,
                        imageRotation = imageRotation,
                    )
                    PlayerControlsOverlay(
                    state = state,
                    mediaKind = currentMediaKind.wireValue,
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
                        syncPlaybackContext()
                        FileLogger.i(TAG, "Loop changed to $enabled and was added to playback context")
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
                        syncPlaybackContext()
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
                        syncPlaybackContext()
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
                        syncPlaybackContext()
                        broadcastPlayerSettings()
                    },
                    onResetSubtitleDelay = {
                        controlsViewModel.resetSubtitleDelay()
                        syncPlaybackContext()
                        broadcastPlayerSettings()
                    },
                    onPreloadSubtitles = controlsViewModel::preloadSubtitleCues,
                    onSkipSegment = controlsViewModel::skipCurrentSegment,
                    onSkipButtonFocusChanged = controlsViewModel::setSkipButtonFocused,
                    )
                }
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
                FileLogger.i(TAG, "Video quality selected: ${track.id}")
                val maxHeight = track.id.removePrefix("max:").toIntOrNull() ?: 0
                controlsViewModel.setVideoQuality(maxHeight)
                runCatching { renderer.setVideoQuality(maxHeight, currentSession.sessionId) }
                syncPlaybackContext()
                broadcastPlayerSettings()
            }
            "audio" -> {
                FileLogger.i(
                    TAG,
                    "Audio track requested: id=${track.id}, name=${track.name}, " +
                        "details=${track.secondaryText}; awaiting renderer track confirmation",
                )
                runCatching { renderer.setAudioTrack(track.id, currentSession.sessionId) }
            }
            "sub" -> {
                FileLogger.i(
                    TAG,
                    "Renderer subtitle requested: id=${track.id}, name=${track.name}, " +
                        "details=${track.secondaryText}",
                )
                selectRendererSubtitle(track.id, renderer, currentSession.sessionId)
            }
            "external_sub" -> {
                FileLogger.i(TAG, "External subtitle requested from overlay (URL redacted)")
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
        syncPlaybackContext()
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
        syncPlaybackContext()
        broadcastTracks()
    }

    private fun applyExternalSubtitleSelection(
        renderer: IRendererService,
        sessionId: Long,
    ) {
        val url = currentExternalSubtitleUrl ?: return
        val item = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index) ?: return
        val subtitleHeaders = item.headersForSubtitle(url)
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
                controlsViewModel.loadExternalSubtitle(url, subtitleHeaders)
            }
            SubtitleRenderingMode.AUTO,
            SubtitleRenderingMode.BUILT_IN,
            -> {
                controlsViewModel.clearSubtitle()
                val requestedMode = renderingMode
                subtitleStageJob = lifecycleScope.launch {
                    try {
                        val file = externalSubtitleStager.stage(
                            url,
                            subtitleHeaders,
                            enforcePageNetworkPolicy = item.isPageControlledMedia(),
                            allowedPrivateOrigins = item.allowed_private_origins,
                        )
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
                            controlsViewModel.loadExternalSubtitle(url, subtitleHeaders)
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
        val pageControlled = playbackCoordinator.playlist
            .getOrNull(playbackCoordinator.index)
            ?.isPageControlledMedia() == true
        if (!rendererAllowedForPageMedia(pageControlled, target)) {
            FileLogger.w(TAG, "Rejected renderer switch to $target for page-controlled media")
            return
        }
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
        pendingPlayingState = null
        controlsViewModel.setPlaying(false)
        controlsViewModel.setBuffering(true)
        controlsViewModel.setEngine(controlsAdapter, target.name.lowercase(), this)
        ServerService.notifyContextPlayer(this, target.engineId)
        resetVideoSurfaceLayout()
        bindRenderer(R.string.player_switching)
    }

    override fun onDestroy() {
        finishingSession = true
        logPlaybackContextCheckpoint("onDestroy")
        cancelStartupWatchdog()
        cancelPrePlayCountdown()
        imageTimerJob?.cancel()
        imageTimerJob = null
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
        imageTimerJob?.cancel()
        imageTimerJob = null
        imageTimerRunning = false
        cancelFailureFinish()

        val oldKind = rendererKind
        val oldSession = session
        val oldRenderer = rendererService
        val targetKind = rendererKindFromIntent(requestIntent)

        // Begin the new logical session before touching the old renderer. Any stop/release
        // callbacks already in flight retain the old session id and are ignored by the host.
        rendererKind = targetKind
        finishingSession = false
        attemptedRenderers.clear()
        attemptedRenderers += targetKind
        playbackCoordinator.setPlaylist(playlist.items, playlist.start_index)
        currentMediaKind = resolveMediaKind(playlist.items[playbackCoordinator.index])
        presentationPayload = playlist.items[playbackCoordinator.index]
            .takeIf { currentMediaKind != MediaKind.VIDEO }
        session = sessionCoordinator.begin(
            if (currentMediaKind == MediaKind.IMAGE) RendererKind.IMAGE else targetKind,
        )
        activeHistoryId = requestIntent.getStringExtra(PlayerLauncher.EXTRA_HISTORY_ID)
            ?: PlayerLauncher.historyId(playlist.items)
        sessionPlaybackContext = PlayerLauncher.playbackContextFromIntent(requestIntent)
        FileLogger.i(
            TAG,
            "Replacement playback request context: ${sessionPlaybackContext.toSafeLogString()}",
        )
        restoreSavedExternalForCurrentItem = sessionPlaybackContext != null
        controlsViewModel.resetSessionSettings(
            defaultQualityMaxHeight(playlist.items.getOrNull(playlist.start_index)),
        )
        applySavedPlaybackSettings()
        playbackCoordinator.queueAdd(ServerService.drainPendingQueueItems(this))
        requestedStartPositionMs = requestIntent
            .getLongExtra(ServerService.EXTRA_START_POSITION, 0L)
            .takeIf { it > 0L }
        resetPlaybackUi()
        broadcastPlaylistStatus(playbackCoordinator.playlist, playbackCoordinator.index)
        skipPreplayForSession = requestIntent.getBooleanExtra(
            ServerService.EXTRA_SKIP_PREPLAY,
            false,
        )
        updateControlsForCurrentItem(
            showPrePlay = !skipPreplayForSession && currentMediaKind == MediaKind.VIDEO,
        )
        controlsViewModel.setEngine(controlsAdapter, targetKind.engineId, this)
        ServerService.notifyContextPlayer(this, targetKind.engineId)

        // Image presentation bypasses the renderer, so explicitly tear down the old
        // media session even when the selected renderer kind has not changed.
        val oldRendererReleased = currentMediaKind == MediaKind.IMAGE && oldSession != null && oldRenderer != null
        if (oldRendererReleased) {
            runCatching { oldRenderer.detachSurface(oldSession.sessionId) }
            runCatching { oldRenderer.release(oldSession.sessionId) }
        }

        if (targetKind != oldKind) {
            if (!oldRendererReleased) {
                runCatching { oldSession?.let { oldRenderer?.release(it.sessionId) } }
            }
            unbindCurrentRenderer()
            rotateRendererSurface()
            terminateRendererProcess(oldKind)
            // This renderer replacement belongs to a new cast request, not an in-session
            // player switch. Describe the user-facing action as playback preparation even when
            // the previous session happened to use a different renderer.
            bindRenderer(R.string.player_preparing)
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
        if (currentMediaKind != MediaKind.IMAGE) {
            scheduleRendererOpenWatchdog(currentSession.sessionId, targetKind)
        }
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
        pendingPlayingState = null
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
        if (preparedSessionId == currentSession.sessionId) return
        val payload = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index)
        if (payload == null) {
            handleRendererFailure("Host launch did not include a PlayPayload")
            return
        }
        currentMediaKind = resolveMediaKind(payload)
        presentationPayload = payload.takeIf { currentMediaKind != MediaKind.VIDEO }
        if (currentMediaKind == MediaKind.IMAGE) {
            configureProgress(payload)
            startImagePresentation(payload, currentSession.sessionId)
            return
        }
        if (surface == null) {
            FileLogger.d(TAG, "Waiting for a fresh surface before preparing $rendererKind")
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
                val file = externalSubtitleStager.stage(
                    initialExternalUrl,
                    payload.headersForSubtitle(initialExternalUrl),
                    enforcePageNetworkPolicy = payload.isPageControlledMedia(),
                    allowedPrivateOrigins = payload.allowed_private_origins,
                )
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
                    controlsViewModel.loadExternalSubtitle(
                        initialExternalUrl,
                        payload.headersForSubtitle(initialExternalUrl),
                    )
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

    private fun startImagePresentation(payload: PlayPayload, sessionId: Long) {
        if (session?.sessionId != sessionId) return
        resetImageTransform()
        cancelStartupWatchdog()
        cancelPrePlayCountdown()
        imageTimerJob?.cancel()
        preparedSessionId = sessionId
        hostPlaying = payload.display_duration_ms?.let { it > 0L } == true
        imageTimerRunning = hostPlaying
        lastPositionMs = 0L
        lastDurationMs = payload.display_duration_ms?.coerceIn(0L, 86_400_000L) ?: 0L
        pendingPlayingState = null
        sessionCoordinator.markReady(sessionId)
        sessionCoordinator.markFirstFrame(sessionId)
        controlsViewModel.setPlaying(hostPlaying)
        controlsViewModel.setBuffering(false)
        stillWatchingController.onPlayingChanged(hostPlaying)
        controlsViewModel.clearPlaybackTransition()
        controlsViewModel.updateCapabilities(
            PlaybackCapabilities(
                isLive = false,
                isSeekable = false,
                speedAvailable = false,
                scalingAvailable = false,
                audioBoostAvailable = false,
                qualityAvailable = false,
            ),
            currentVideoHeight = 0,
            qualityMaxHeight = 0,
        )
        broadcastTracks()
        broadcastCurrentState()
        if (lastDurationMs > 0L) startImageTimer(sessionId)
        refreshKeepScreenOn()
    }

    private fun startImageTimer(sessionId: Long) {
        imageTimerJob?.cancel()
        if (!imageTimerRunning || lastDurationMs <= 0L) return
        imageTimerJob = lifecycleScope.launch {
            var previous = SystemClock.elapsedRealtime()
            while (session?.sessionId == sessionId && imageTimerRunning) {
                delay(250L)
                val now = SystemClock.elapsedRealtime()
                lastPositionMs = (lastPositionMs + now - previous).coerceAtMost(lastDurationMs)
                previous = now
                broadcastPlaybackStatus()
                if (lastPositionMs >= lastDurationMs) {
                    imageTimerRunning = false
                    playbackCoordinator.next()
                    return@launch
                }
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
            pendingPlayingState = true
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

    private fun applySavedPlaybackSettings() {
        val context = sessionPlaybackContext ?: run {
            FileLogger.d(TAG, "No saved playback context to apply before renderer preparation")
            return
        }
        FileLogger.i(
            TAG,
            "Applying saved scalar playback settings: ${context.toSafeLogString()}",
        )
        context.playbackSpeed
            ?.takeIf { it in 0.25f..2f }
            ?.let(controlsViewModel::setPlaybackSpeed)
        context.videoScalingMode
            ?.takeIf { it in setOf("Fit", "Zoom", "Fill") }
            ?.let(controlsViewModel::setVideoScaling)
        context.videoQualityMaxHeight
            ?.coerceAtLeast(0)
            ?.let(controlsViewModel::setVideoQuality)
        context.subtitleDelayMs?.let(controlsViewModel::setSubtitleDelay)
        context.isLooping?.let(controlsViewModel::setLooping)
    }

    private fun restoreSavedTrackSelections() {
        val context = sessionPlaybackContext ?: return
        val currentSession = session ?: return
        val renderer = rendererService ?: return
        val item = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index)

        if (pendingAudioContextRestore) {
            val audioCandidates = audioTracks.map { it.asPlaybackCandidate() }
            val excludedAudioIds = setOf("auto")
            if (!hasRestorableTrackCandidates(audioCandidates, excludedAudioIds)) {
                FileLogger.i(
                    TAG,
                    "Saved audio restoration deferred: renderer has only placeholder tracks",
                )
            } else {
                FileLogger.i(
                    TAG,
                    "Resolving saved audio track from ${audioTracks.size} candidates: " +
                        context.audioTrack.toSafeLogString(),
                )
                val match = resolveTrackPreference(
                    tracks = audioCandidates,
                    saved = context.audioTrack,
                    fallbackLanguage = item?.preferred_audio_language,
                    excludedIds = excludedAudioIds,
                )
                when {
                    match == null -> {
                        pendingAudioContextRestore = false
                        FileLogger.i(
                            TAG,
                            "Saved audio resolution result: <no match; renderer default retained>",
                        )
                    }
                    audioTracks.any { it.id == match.id && it.selected } -> {
                        pendingAudioContextRestore = false
                        FileLogger.i(
                            TAG,
                            "Saved audio restoration confirmed: " +
                                PlaybackTrackPreference(
                                    match.id,
                                    match.label,
                                    match.language,
                                ).toSafeLogString(),
                        )
                    }
                    else -> {
                        FileLogger.i(
                            TAG,
                            "Saved audio restoration requested; awaiting renderer confirmation: " +
                                PlaybackTrackPreference(
                                    match.id,
                                    match.label,
                                    match.language,
                                ).toSafeLogString(),
                        )
                        audioTracks = audioTracks.map { it.copy(selected = it.id == match.id) }
                        runCatching { renderer.setAudioTrack(match.id, currentSession.sessionId) }
                    }
                }
            }
        }

        if (pendingSubtitleContextRestore) {
            if (context.subtitlesDisabled) {
                val alreadyDisabled = subtitleTracks.any {
                    it.selected && it.id in setOf("off", "none")
                }
                if (alreadyDisabled) {
                    pendingSubtitleContextRestore = false
                    FileLogger.i(TAG, "Saved subtitle-disabled state confirmed")
                } else {
                    FileLogger.i(
                        TAG,
                        "Saved subtitle-disabled state requested; awaiting renderer confirmation",
                    )
                    subtitleTracks = subtitleTracks.map {
                        it.copy(selected = it.id == "off" || it.id == "none")
                    }
                    runCatching { renderer.setSubtitleTrack("off", currentSession.sessionId) }
                }
            } else {
                val subtitleCandidates = subtitleTracks.map { it.asPlaybackCandidate() }
                val excludedSubtitleIds = setOf("off", "none", "auto")
                if (!hasRestorableTrackCandidates(subtitleCandidates, excludedSubtitleIds)) {
                    FileLogger.i(
                        TAG,
                        "Saved subtitle restoration deferred: renderer has only placeholder tracks",
                    )
                } else {
                    FileLogger.i(
                        TAG,
                        "Resolving saved subtitle track from ${subtitleTracks.size} candidates: " +
                            context.subtitleTrack.toSafeLogString(),
                    )
                    val match = resolveTrackPreference(
                        tracks = subtitleCandidates,
                        saved = context.subtitleTrack,
                        fallbackLanguage = item?.preferred_subtitle_language,
                        excludedIds = excludedSubtitleIds,
                    )
                    when {
                        match == null -> {
                            pendingSubtitleContextRestore = false
                            FileLogger.i(
                                TAG,
                                "Saved subtitle resolution result: " +
                                    "<no match; renderer default retained>",
                            )
                        }
                        subtitleTracks.any { it.id == match.id && it.selected } -> {
                            pendingSubtitleContextRestore = false
                            FileLogger.i(
                                TAG,
                                "Saved subtitle restoration confirmed: " +
                                    PlaybackTrackPreference(
                                        match.id,
                                        match.label,
                                        match.language,
                                    ).toSafeLogString(),
                            )
                        }
                        else -> {
                            FileLogger.i(
                                TAG,
                                "Saved subtitle restoration requested; " +
                                    "awaiting renderer confirmation: " +
                                    PlaybackTrackPreference(
                                        match.id,
                                        match.label,
                                        match.language,
                                    ).toSafeLogString(),
                            )
                            subtitleTracks = subtitleTracks.map {
                                it.copy(selected = it.id == match.id)
                            }
                            runCatching {
                                renderer.setSubtitleTrack(match.id, currentSession.sessionId)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun syncPlaybackContext() {
        val state = controlsViewModel.controlsState.value
        val selectedAudio = audioTracks.firstOrNull {
            it.selected && it.id != "auto"
        }?.asPreference()
        val selectedSubtitle = subtitleTracks.firstOrNull {
            it.selected && it.id !in setOf("off", "none", "auto")
        }?.asPreference()
        val rendererReportsSubtitlesDisabled = currentExternalSubtitleUrl == null && subtitleTracks.any {
            it.selected && it.id in setOf("off", "none")
        }
        val subtitlesDisabled = if (pendingSubtitleContextRestore) {
            sessionPlaybackContext?.subtitlesDisabled ?: rendererReportsSubtitlesDisabled
        } else {
            rendererReportsSubtitlesDisabled
        }
        sessionPlaybackContext = PlaybackContext(
            audioTrack = selectedAudio ?: sessionPlaybackContext?.audioTrack,
            subtitleTrack = selectedSubtitle ?: sessionPlaybackContext?.subtitleTrack,
            subtitlesDisabled = subtitlesDisabled,
            externalSubtitleUrl = currentExternalSubtitleUrl,
            playbackSpeed = state.playbackSpeed,
            videoScalingMode = state.videoScalingMode,
            videoQualityMaxHeight = state.videoQualityMaxHeight,
            subtitleDelayMs = state.subtitleDelayMs,
            isLooping = state.isLooping,
        )
        progressManager.updatePlaybackContext(checkNotNull(sessionPlaybackContext))
        FileLogger.i(
            TAG,
            "Synchronized playback context from renderer/UI: " +
                sessionPlaybackContext.toSafeLogString(),
        )
    }

    private fun logPlaybackContextCheckpoint(reason: String) {
        val state = controlsViewModel.controlsState.value
        FileLogger.i(
            TAG,
            "Playback context checkpoint [$reason]: " +
                "context=${sessionPlaybackContext.toSafeLogString()}, " +
                "loop=${state.isLooping}, position=$lastPositionMs/$lastDurationMs",
        )
    }

    private fun RendererTrack.asPlaybackCandidate(): PlaybackTrackCandidate =
        PlaybackTrackCandidate(id = id, label = label, language = language)

    private fun RendererTrack.asPreference(): PlaybackTrackPreference =
        PlaybackTrackPreference(id = id, label = label, language = language)

    private fun configureProgress(payload: PlayPayload) {
        val items = playbackCoordinator.playlist
        val index = playbackCoordinator.index
        val payloadJson = runCatching {
            PlayerLauncher.historyPayloadJson(items, index, skipPreplayForSession)
        }
            .getOrDefault("")
        val visualMetadata = payload.visual_metadata
        val historyId = activeHistoryId ?: PlayerLauncher.historyId(items).also {
            activeHistoryId = it
        }
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
            playbackContext = sessionPlaybackContext,
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
        if (currentMediaKind != MediaKind.VIDEO) return
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
        val pageControlled = item.isPageControlledMedia()
        controlsViewModel.setPlayerSwitchAllowed(!pageControlled)
        controlsViewModel.subtitleRequestHeaders = if (pageControlled) null else item.headers
        controlsViewModel.subtitleRequestHeadersByUrl = item.subtitleHeadersByUrl()
        controlsViewModel.enforcePageSubtitleNetworkPolicy = pageControlled
        controlsViewModel.allowedPrivateSubtitleOrigins = item.allowed_private_origins
        val shouldShowPrePlay = showPrePlay && item.visual_metadata != null
        controlsViewModel.setPrePlay(
            item.visual_metadata,
            this,
            showCountdown = shouldShowPrePlay,
        )
        controlsViewModel.setPrePlayLaunching(shouldShowPrePlay)
        controlsViewModel.setPrePlayCountdown(if (shouldShowPrePlay) -1 else 0)
        val savedExternal = sessionPlaybackContext?.externalSubtitleUrl
            ?.takeIf { restoreSavedExternalForCurrentItem }
        FileLogger.i(
            TAG,
            "Preparing item context restore: savedContext=" +
                "${sessionPlaybackContext.toSafeLogString()}, " +
                "restoreSavedExternal=$restoreSavedExternalForCurrentItem, " +
                "savedExternalPresent=${savedExternal != null}",
        )
        externalSubtitleUrls = (item.externalSubtitleUrls() + listOfNotNull(savedExternal))
            .filter(String::isNotBlank)
            .distinct()
        currentExternalSubtitleUrl = when {
            sessionPlaybackContext?.subtitlesDisabled == true -> null
            savedExternal != null -> savedExternal
            else -> externalSubtitleUrls.firstOrNull()
        }
        pendingAudioContextRestore = sessionPlaybackContext?.audioTrack != null
        pendingSubtitleContextRestore = currentExternalSubtitleUrl == null &&
            (sessionPlaybackContext?.subtitleTrack != null ||
                sessionPlaybackContext?.subtitlesDisabled == true)
        val externalUrl = currentExternalSubtitleUrl
        if (externalUrl != null &&
            SubtitleRenderingMode.read(this) == SubtitleRenderingMode.PLAYBRIDGE_OVERLAY
        ) {
            externalSubtitleOverlayActive = true
            controlsViewModel.loadExternalSubtitle(externalUrl, item.headersForSubtitle(externalUrl))
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
                val rendererPlaying = state == "playing"
                val pendingState = pendingPlayingState
                if (pendingState == null || pendingState == rendererPlaying) {
                    hostPlaying = rendererPlaying
                    if (pendingState == rendererPlaying) pendingPlayingState = null
                }
                stillWatchingController.onPlayingChanged(rendererPlaying && state != "buffering")
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
                    mediaKind = currentMediaKind.wireValue,
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
                pendingPlayingState = null
                pendingSeekTracker.clear()
                sessionCoordinator.markStopped(currentSession.sessionId)
                runCatching { rendererService?.release(currentSession.sessionId) }
                session = null
                finish()
            }
            RendererProtocol.EVENT_ERROR -> handleRendererFailure(
                message = event.getString(RendererProtocol.KEY_ERROR) ?: "Renderer error",
                severity = ExoPlaybackErrorPolicy.parseSeverity(
                    event.getString(RendererProtocol.KEY_ERROR_SEVERITY),
                ),
                hadFirstFrame = event.getBoolean(
                    RendererProtocol.KEY_HAD_FIRST_FRAME,
                    sessionCoordinator.current().phase == RendererSessionPhase.PLAYING,
                ),
            )
        }
        refreshKeepScreenOn()
    }

    private fun startPlaylistItem() {
        if (finishingSession) return
        restoreSavedExternalForCurrentItem = false
        stillWatchingController.onMediaChanged()
        val renderer = rendererService ?: run {
            handleRendererFailure("Renderer disconnected while advancing the playlist")
            return
        }
        val payload = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index) ?: return
        val outgoingSession = session
        imageTimerJob?.cancel()
        imageTimerJob = null
        imageTimerRunning = false
        currentMediaKind = resolveMediaKind(payload)
        presentationPayload = payload.takeIf { currentMediaKind != MediaKind.VIDEO }
        if (currentMediaKind == MediaKind.IMAGE && outgoingSession != null) {
            runCatching { renderer.detachSurface(outgoingSession.sessionId) }
            runCatching { renderer.release(outgoingSession.sessionId) }
        }
        session = sessionCoordinator.begin(
            if (currentMediaKind == MediaKind.IMAGE) RendererKind.IMAGE else rendererKind,
        )
        cancelPrePlayCountdown()
        pendingSeekTracker.clear()
        hostPlaying = false
        pendingPlayingState = null
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
        showTransition(R.string.player_preparing)
        if (currentMediaKind != MediaKind.IMAGE) {
            scheduleRendererOpenWatchdog(session!!.sessionId, rendererKind)
        }
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
        logPlaybackContextCheckpoint("finishPlaybackSession")
        cancelStartupWatchdog()
        cancelPrePlayCountdown()
        progressManager.saveProgress()
        session?.let { currentSession ->
            sessionCoordinator.requestStop(currentSession.sessionId)
            if (currentMediaKind != MediaKind.IMAGE) {
                runCatching { rendererService?.stop(currentSession.sessionId) }
            }
        }
        imageTimerJob?.cancel()
        imageTimerJob = null
        finish()
    }

    private fun advanceAfterEndSegment() {
        val currentSession = session ?: return
        if (!sessionCoordinator.requestStop(currentSession.sessionId)) return
        if (lastDurationMs > 0L) {
            lastPositionMs = lastDurationMs
            controlsViewModel.setPendingSeekTime(lastDurationMs)
        }
        showTransition(R.string.player_preparing)
        controlsViewModel.setBuffering(true)
        lifecycleScope.launch { playbackCoordinator.next() }
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
                    put("mediaKind", resolveMediaKind(item).wireValue)
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

    private fun broadcastPlaybackStatus() {
        val item = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index)
        ServerService.broadcastStatus(
            this,
            createStatusJson(
                state = if (hostPlaying) "playing" else "paused",
                position = lastPositionMs,
                duration = lastDurationMs,
                title = item?.title,
                mediaKind = currentMediaKind.wireValue,
            ),
        )
    }

    private fun broadcastCurrentState() {
        broadcastPlaybackStatus()
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
            put("isSeekable", currentMediaKind != MediaKind.IMAGE && state.capabilities.isSeekable)
            put("speedAvailable", currentMediaKind != MediaKind.IMAGE && state.capabilities.speedAvailable)
            put("scalingAvailable", currentMediaKind == MediaKind.VIDEO && state.capabilities.scalingAvailable)
            put("audioBoostAvailable", currentMediaKind != MediaKind.IMAGE && state.capabilities.audioBoostAvailable)
            put("qualityAvailable", currentMediaKind == MediaKind.VIDEO && state.capabilities.qualityAvailable)
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
        FileLogger.i(
            TAG,
            "Renderer tracks confirmed: audioSelected=" +
                audioTracks.firstOrNull { it.selected }?.asPreference().toSafeLogString() +
                ", subtitleSelected=" +
                subtitleTracks.firstOrNull { it.selected }?.asPreference().toSafeLogString() +
                ", audioCount=${audioTracks.size}, subtitleCount=${subtitleTracks.size}",
        )
        restoreSavedTrackSelections()
        progressManager.updateSelections(
            preferredAudioLanguage = audioTracks.firstOrNull { it.selected }?.language,
            preferredSubtitleLanguage = subtitleTracks.firstOrNull { it.selected }?.language,
            externalSubtitleUrl = currentExternalSubtitleUrl,
        )
        syncPlaybackContext()
        updateTrackControls()
        broadcastTracks()
    }

    private fun handleCapabilities(event: Bundle) {
        val previousState = controlsViewModel.controlsState.value
        val capabilities = PlaybackCapabilities(
            isLive = event.getBoolean(RendererProtocol.KEY_IS_LIVE),
            isSeekable = event.getBoolean(RendererProtocol.KEY_IS_SEEKABLE, true),
            speedAvailable = event.getBoolean(RendererProtocol.KEY_SPEED_AVAILABLE, true),
            scalingAvailable = event.getBoolean(RendererProtocol.KEY_SCALING_AVAILABLE, true),
            audioBoostAvailable = event.getBoolean(
                RendererProtocol.KEY_AUDIO_BOOST_AVAILABLE,
                true,
            ),
            qualityAvailable = event.getBoolean(RendererProtocol.KEY_QUALITY_AVAILABLE),
        )
        controlsViewModel.updateCapabilities(
            capabilities = capabilities,
            currentVideoHeight = event.getInt(RendererProtocol.KEY_CURRENT_VIDEO_HEIGHT),
            qualityMaxHeight = event.getInt(RendererProtocol.KEY_QUALITY_MAX_HEIGHT),
        )
        val currentSession = session
        val renderer = rendererService
        if (sessionPlaybackContext != null && currentSession != null && renderer != null) {
            if (!capabilities.speedAvailable && previousState.playbackSpeed != 1f) {
                controlsViewModel.setPlaybackSpeed(1f)
            }
            if (!capabilities.scalingAvailable && previousState.videoScalingMode != "Fit") {
                controlsViewModel.setVideoScaling("Fit")
                runCatching { renderer.setVideoScaling("Fit", currentSession.sessionId) }
                updateVideoSurfaceLayout()
            }
            if (!capabilities.qualityAvailable && previousState.videoQualityMaxHeight != 0) {
                controlsViewModel.setVideoQuality(0)
                runCatching { renderer.setVideoQuality(0, currentSession.sessionId) }
            }
            syncPlaybackContext()
        }
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
                controlsViewModel.loadExternalSubtitle(url, item.headersForSubtitle(url))
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

    /**
     * Handle a renderer failure.
     *
     * Hard startup failures and exhausted in-engine recovery may switch engines once. The
     * attempted-renderer guard prevents Exo↔MPV loops; terminal source/authentication failures
     * still end the item without switching.
     */
    private fun handleRendererFailure(
        message: String,
        severity: ExoPlaybackErrorPolicy.EscalationSeverity? = null,
        hadFirstFrame: Boolean = false,
    ) {
        if (finishingSession || isFinishing || isDestroyed) return
        val failedKind = rendererKind
        val firstFrameSeen = hadFirstFrame ||
            sessionCoordinator.current().phase == RendererSessionPhase.PLAYING
        val isStartupWatchdog = message.contains("did not become ready", ignoreCase = true)
        val effectiveSeverity = severity
            ?: if (isStartupWatchdog || !firstFrameSeen) {
                // Legacy/MPV errors without severity: allow one startup failover only.
                ExoPlaybackErrorPolicy.EscalationSeverity.STARTUP_ENGINE_FAILOVER
            } else {
                ExoPlaybackErrorPolicy.EscalationSeverity.TERMINAL
            }
        val allowEngineFailover = ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
            severity = effectiveSeverity,
            hasFirstFrame = firstFrameSeen,
        )

        FileLogger.w(
            TAG,
            "Renderer $failedKind failed: $message " +
                "(severity=$effectiveSeverity firstFrame=$firstFrameSeen allowFailover=$allowEngineFailover)",
        )
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

        val pageControlled = playbackCoordinator.playlist
            .getOrNull(playbackCoordinator.index)
            ?.isPageControlledMedia() == true
        val fallback = fallbackRenderer(failedKind, pageControlled)
        if (!allowEngineFailover || fallback == null || fallback in attemptedRenderers) {
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
        // First-frame delivery can race a user pause during startup. Playback state events are
        // the acknowledgement for play/pause intent; a rendered frame only advances readiness.
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
        pendingPlayingState = false
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
            pendingPlayingState = true
            runCatching { rendererService?.play(sessionId) }
        }
    }

    private fun startPrePlayNow() {
        val currentSession = session ?: return
        cancelPrePlayCountdown()
        controlsViewModel.setPrePlayCountdown(0)
        controlsViewModel.setPrePlay(null, clearOnlineSubs = false)
        showTransition(R.string.player_starting, force = true)
        pendingPlayingState = true
        runCatching { rendererService?.play(currentSession.sessionId) }
    }

    private fun cancelPrePlayCountdown() {
        prePlayCountdownJob?.cancel()
        prePlayCountdownJob = null
    }

    private fun handleControl(command: String?) {
        if (currentMediaKind == MediaKind.IMAGE) {
            val currentSession = session ?: return
            when (command) {
                "play" -> {
                    if (lastDurationMs > 0L) {
                        imageTimerRunning = true
                        hostPlaying = true
                        controlsViewModel.setPlaying(true)
                        stillWatchingController.onPlayingChanged(true)
                        startImageTimer(currentSession.sessionId)
                    }
                }
                "pause" -> {
                    imageTimerRunning = false
                    hostPlaying = false
                    imageTimerJob?.cancel()
                    stillWatchingController.onPlayingChanged(false)
                    controlsViewModel.showControls(full = true, playing = false)
                }
                "toggle" -> handleControl(if (imageTimerRunning) "pause" else "play")
                "stop" -> finishPlaybackSession()
            }
            broadcastCurrentState()
            return
        }
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
                pendingPlayingState = true
                controlsViewModel.setPlaying(true)
                runCatching { renderer.play(currentSession.sessionId) }
            }
            command == "pause" -> {
                hostPlaying = false
                pendingPlayingState = false
                runCatching { renderer.pause(currentSession.sessionId) }
                controlsViewModel.showControls(full = true, playing = false)
                progressManager.saveProgress()
            }
            command == "toggle" -> {
                if (hostPlaying) {
                    hostPlaying = false
                    pendingPlayingState = false
                    runCatching { renderer.pause(currentSession.sessionId) }
                    controlsViewModel.showControls(full = true, playing = false)
                    progressManager.saveProgress()
                } else {
                    hostPlaying = true
                    pendingPlayingState = true
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
                    syncPlaybackContext()
                    broadcastPlayerSettings()
                }
            command?.startsWith("video_quality:") == true -> {
                val value = command.removePrefix("video_quality:")
                val maxHeight = value.takeUnless { it.equals("auto", true) }?.toIntOrNull() ?: 0
                controlsViewModel.setVideoQuality(maxHeight)
                runCatching { renderer.setVideoQuality(maxHeight, currentSession.sessionId) }
                syncPlaybackContext()
                broadcastPlayerSettings()
            }
            command?.startsWith("sub_offset:") == true -> command
                .removePrefix("sub_offset:")
                .toLongOrNull()
                ?.let { delta ->
                    controlsViewModel.adjustSubtitleDelay(delta)
                    syncPlaybackContext()
                    broadcastPlayerSettings()
                }
            command?.startsWith("scaling:") == true -> {
                val mode = command.removePrefix("scaling:")
                runCatching { renderer.setVideoScaling(mode, currentSession.sessionId) }
                controlsViewModel.setVideoScaling(mode)
                updateVideoSurfaceLayout()
                syncPlaybackContext()
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
                syncPlaybackContext()
                FileLogger.i(
                    TAG,
                    "Remote loop changed to $enabled and was added to playback context",
                )
                broadcastPlayerSettings()
            }
            command == "seek_back" -> seekRelative(-10_000L, currentSession.sessionId)
            command == "seek_forward" -> seekRelative(10_000L, currentSession.sessionId)
            command == "next" -> lifecycleScope.launch { playbackCoordinator.next() }
            command == "previous" -> lifecycleScope.launch { playbackCoordinator.previous() }
        }
        refreshKeepScreenOn()
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

    private fun resetImageTransform() {
        imageScale = 1f
        imageOffsetX = 0f
        imageOffsetY = 0f
        imageRotation = 0f
        imageTransformAnchorX = 0.5f
        imageTransformAnchorY = 0.5f
        imageTransformAnchorActive = false
    }

    private fun handleImagePointer(event: String?, dx: Float, dy: Float) {
        if (currentMediaKind != MediaKind.IMAGE || !dx.isFinite() || !dy.isFinite()) return
        when (event) {
            "reset" -> resetImageTransform()
            "transform_anchor" -> {
                if (dx < 0f || dy < 0f) {
                    imageTransformAnchorActive = false
                } else {
                    imageTransformAnchorX = dx.coerceIn(0f, 1f)
                    imageTransformAnchorY = dy.coerceIn(0f, 1f)
                    imageTransformAnchorActive = true
                }
            }
            "rotate" -> {
                val degrees = dx.coerceIn(-90f, 90f)
                val radians = Math.toRadians(degrees.toDouble())
                val anchorX = if (imageTransformAnchorActive) {
                    (imageTransformAnchorX - 0.5f) * window.decorView.width
                } else {
                    imageOffsetX
                }
                val anchorY = if (imageTransformAnchorActive) {
                    (imageTransformAnchorY - 0.5f) * window.decorView.height
                } else {
                    imageOffsetY
                }
                val relativeX = imageOffsetX - anchorX
                val relativeY = imageOffsetY - anchorY
                val cosine = kotlin.math.cos(radians).toFloat()
                val sine = kotlin.math.sin(radians).toFloat()
                imageOffsetX = anchorX + relativeX * cosine - relativeY * sine
                imageOffsetY = anchorY + relativeX * sine + relativeY * cosine
                imageRotation += degrees
            }
            "move", "scroll" -> {
                if (imageScale <= 1f) return
                val maxX = window.decorView.width * (imageScale - 1f) / 2f
                val maxY = window.decorView.height * (imageScale - 1f) / 2f
                imageOffsetX = (imageOffsetX + dx).coerceIn(-maxX, maxX)
                imageOffsetY = (imageOffsetY + dy).coerceIn(-maxY, maxY)
            }
            "zoom" -> {
                if (dx <= 0f) return
                val oldScale = imageScale
                imageScale = (imageScale * dx.coerceIn(0.5f, 2f)).coerceIn(1f, 8f)
                if (imageScale == 1f) {
                    imageOffsetX = 0f
                    imageOffsetY = 0f
                } else {
                    val ratio = imageScale / oldScale
                    val anchorX = if (imageTransformAnchorActive) {
                        (imageTransformAnchorX - 0.5f) * window.decorView.width
                    } else {
                        imageOffsetX
                    }
                    val anchorY = if (imageTransformAnchorActive) {
                        (imageTransformAnchorY - 0.5f) * window.decorView.height
                    } else {
                        imageOffsetY
                    }
                    val maxX = window.decorView.width * (imageScale - 1f) / 2f
                    val maxY = window.decorView.height * (imageScale - 1f) / 2f
                    imageOffsetX = (anchorX + (imageOffsetX - anchorX) * ratio)
                        .coerceIn(-maxX, maxX)
                    imageOffsetY = (anchorY + (imageOffsetY - anchorY) * ratio)
                        .coerceIn(-maxY, maxY)
                }
            }
        }
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
                logPlaybackContextCheckpoint("Back finishing player")
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
        refreshKeepScreenOn()
    }

    private fun refreshKeepScreenOn() {
        val state = controlsViewModel.controlsState.value
        val shouldKeepScreenOn = shouldKeepPlayerScreenOn(
            isHostStarted = isHostStarted,
            isPlaying = hostPlaying || currentMediaKind == MediaKind.IMAGE,
            isBuffering = state.isBuffering,
            hasTransition = state.playbackTransitionMessage != null,
            hasPrePlay = state.prePlayMetadata != null,
            isStillWatchingPrompting = stillWatchingController.state.value.isPrompting,
        )
        if (shouldKeepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
