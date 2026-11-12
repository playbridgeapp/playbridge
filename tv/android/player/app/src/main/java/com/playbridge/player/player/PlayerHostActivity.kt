package com.playbridge.player.player

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
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
import androidx.media3.ui.SubtitleView
import com.playbridge.player.R
import com.playbridge.player.data.HistoryStore
import com.playbridge.player.together.TvTogether
import com.playbridge.shared.together.TogetherPlayerCommand
import com.playbridge.player.data.HistoryThumbnailMode
import com.playbridge.player.data.HistoryThumbnailStore
import com.playbridge.player.data.PlaybackContext
import com.playbridge.player.data.PlaybackTrackPreference
import com.playbridge.player.data.toSafeLogString
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.proxy.ReceiverStreamProxy
import com.playbridge.player.server.ServerService
import com.playbridge.player.ui.player.ActiveOverlay
import com.playbridge.player.ui.player.BACK_CONFIRM_WINDOW_MS
import com.playbridge.player.ui.player.BookmarkState
import com.playbridge.player.ui.player.ControlsSurface
import com.playbridge.player.ui.player.KeyAction
import com.playbridge.player.ui.player.KeyContext
import com.playbridge.player.ui.player.PhoneChangeKind
import com.playbridge.player.ui.player.PhoneOrigin
import com.playbridge.player.ui.player.PrePlayResume
import com.playbridge.player.ui.player.audioChangeTitle
import com.playbridge.player.ui.player.phoneChangeCard
import com.playbridge.player.ui.player.queuePositionTitle
import com.playbridge.player.ui.player.speedChangeTitle
import com.playbridge.player.ui.player.subtitleLiftDp
import com.playbridge.player.ui.player.subtitleChangeTitle
import com.playbridge.player.ui.player.subtitleOffsetTitle
import com.playbridge.player.ui.player.MediaPresentation
import com.playbridge.player.ui.player.MusicTransport
import com.playbridge.player.ui.player.RemoteKey
import com.playbridge.player.ui.player.TransportBadge
import com.playbridge.player.ui.player.bookmarkNoticeText
import com.playbridge.player.ui.player.controlsSurface
import com.playbridge.player.ui.player.photoCounterText
import com.playbridge.player.ui.player.resolveKeyAction
import com.playbridge.player.ui.player.PlayerControlsOverlay
import com.playbridge.player.ui.player.PlayerControlsViewModel
import com.playbridge.player.ui.player.PlaybackCapabilities
import com.playbridge.player.ui.player.UnifiedTrack
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import com.playbridge.shared.protocol.createSubtitleStyleJson
import com.playbridge.shared.logging.runCatchingLogged
import com.playbridge.shared.logging.suspendRunCatchingLogged
import com.playbridge.shared.player.PlayerChapter
import com.playbridge.shared.protocol.MediaKind
import com.playbridge.shared.protocol.createStatusJson
import com.playbridge.shared.protocol.encodePlayPayloadListJson
import com.playbridge.shared.protocol.resolveMediaKind
import playbridge.SubtitleResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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

private fun logPlayerFailure(tag: String, message: String, error: Throwable) {
    FileLogger.w(tag, message, error)
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
    /** Time (elapsedRealtime) until which a second Back leaves the player; 0 when not armed. */
    private var backArmedUntilMs = 0L
    /** History id to bookmarked flag for stored items; just enough for the bookmark button. */
    private var historySnapshot: Map<String, Boolean> = emptyMap()
    private val sessionCoordinator = RendererSessionCoordinator()
    private val failureReporter = RendererFailureReporter()
    private val stallDetector = PlaybackStallDetector()
    private var stallMonitor: Runnable? = null
    private var stallPlaybackRequested = false
    private var stallState = "buffering"
    private var stallPositionMs = 0L
    private var stallBufferedAheadMs = 0L
    private val pendingSeekTracker = PendingSeekTracker()
    private val playbackRequestGate = PlaybackRequestGate()
    private var rendererService: IRendererService? = null
    private var preparedSessionId = 0L
    private val subtitleStyles by lazy { SubtitleStyleStore.get(this) }
    /** The custom subtitle font, loaded off the main thread for ExoPlayer and the overlay. */
    private var subtitleTypeface by androidx.compose.runtime.mutableStateOf<android.graphics.Typeface?>(null)
    private var rendererKind: RendererKind = RendererKind.EXO
    private var session: RendererSessionSnapshot? = null
    private var connection: ServiceConnection? = null
    private var surface: Surface? = null
    private var hostPlaying = false
    private var pendingPlayingState: Boolean? = null
    private var isHostStarted = false
    private var lastPositionMs = 0L
    private var lastDurationMs = 0L
    // Buffered position and chapters are tagged with the renderer session that reported
    // them, so a new item or engine never shows the previous one's values.
    private var lastBufferedMs = 0L
    private var bufferedSessionId = 0L
    private var currentChapters: List<PlayerChapter> = emptyList()
    private var chaptersSessionId = 0L
    private var lastTimelineJson: String? = null
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
    /** URL of the photo now on screen; a timed photo's clock only runs once it is visible. */
    private var shownImageUrl: String? = null
    private var imageLoadFallback: Job? = null
    private val attemptedRenderers = linkedSetOf<RendererKind>()
    private var startupWatchdog: Runnable? = null
    private var failureFinishRunnable: Runnable? = null
    private var prePlayCountdownJob: Job? = null
    /** The saved place this session resumes from (a phone's start position or history), for the pre-play choice. */
    private var prePlayResumeMs: Long? = null
    private var prePlayResumeDurationMs: Long? = null
    private val historyStore by lazy { HistoryStore(applicationContext) }
    private val historyThumbnailStore by lazy { HistoryThumbnailStore(applicationContext) }
    private val stillWatchingController by lazy {
        StillWatchingController(
            scope = lifecycleScope,
            pausePlayback = { handleControl("pause") },
            resumePlayback = { handleControl("play") },
            stopPlayback = ::finishPlaybackSession,
            onPromptChanged = { prompting ->
                refreshKeepScreenOn()
                // The prompt pauses playback and opens the controls beneath it; close them with it.
                if (!prompting) controlsViewModel.hideControls()
            },
        ).also { controller ->
            val prefs = getSharedPreferences(StillWatchingPrefs.STILL_WATCHING_PREFS, MODE_PRIVATE)
            controller.updateSettings(
                prefs.getBoolean(StillWatchingPrefs.PREF_STILL_WATCHING_ENABLED, false),
                StillWatchingPrefs.normalizeStillWatchingThreshold(
                    prefs.getInt(StillWatchingPrefs.PREF_STILL_WATCHING_THRESHOLD_MIN, 90),
                ),
                StillWatchingPrefs.normalizeStillWatchingResponseSeconds(
                    prefs.getInt(StillWatchingPrefs.PREF_STILL_WATCHING_RESPONSE_SEC, 300),
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
    /** The Watch Together video this session plays; null outside a party. */
    private var partyItem: String? = null
    private var partyLoadedReported = false
    /** The renderer said the party video is ready; its length goes out once we know it isn't live. */
    private var partyReady = false
    /** Whether the party video is live, once the renderer has said; null until then. */
    private var partyLive: Boolean? = null
    private val reportPartyLoadedAnyway = Runnable { reportPartyLoaded(force = true) }
    private val partyPlayer = object : TvTogether.PartyPlayer {
        override fun applyPartyCommand(command: TogetherPlayerCommand) = this@PlayerHostActivity.applyPartyCommand(command)
    }
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
    private val lateSubtitleResources = mutableMapOf<String, SubtitleResource>()
    // Labels sent with the item (subtitle_resources), e.g. "English · BLURAY 1080p" from a bridged page.
    private var itemSubtitleLabels: Map<String, String> = emptyMap()
    private data class LateSubtitleRequest(
        val connectionId: Long,
        val requestId: String?,
    )
    private val pendingLateSubtitleRequests = mutableMapOf<String, LateSubtitleRequest>()
    private var currentExternalSubtitleUrl: String? = null
    private var externalSubtitleOverlayActive = false
    private val externalSubtitleStager by lazy { ExternalSubtitleStager(applicationContext) }
    private val stagedSubtitleFiles = linkedSetOf<File>()
    private val pageProxyRouter = PageMediaProxyRouter()
    private var itemPrepareJob: Job? = null
    private var lateSubtitleRegisterJob: Job? = null
    private var subtitleStageJob: Job? = null
    private var pendingNativeSubtitleUri: String? = null
    private var pendingNativeSubtitleMode: SubtitleRenderingMode? = null
    private var initialSubtitleHandledSessionId = 0L
    private var initialSubtitleHandledUrl: String? = null
    private val controlsAdapter = object : PlayerEngineAdapter {
        override val isPlaying: Boolean get() = hostPlaying
        override val currentPosition: Long get() = lastPositionMs
        override val duration: Long get() = lastDurationMs
        override val bufferedPosition: Long get() = currentBufferedMs() ?: lastPositionMs
        override val streamInfo: String? get() = null
        override val frameRate: Float get() = videoFrameRate
        override val hdrFormat: String? get() = null

        override fun setLoudnessEnhancer(enabled: Boolean) {
            val currentSession = session ?: return
            runCatchingLogged(TAG, "Renderer audio boost failed", ::logPlayerFailure) {
                rendererService?.setAudioBoost(enabled, currentSession.sessionId)
            }
        }

        override fun setSubtitleDelay(delayMs: Long) {
            val currentSession = session ?: return
            runCatchingLogged(TAG, "Renderer subtitle delay failed", ::logPlayerFailure) {
                rendererService?.setSubtitleDelay(delayMs, currentSession.sessionId)
            }
        }

        override fun setPlaybackSpeed(speed: Float) {
            val currentSession = session ?: return
            runCatchingLogged(TAG, "Renderer playback speed failed", ::logPlayerFailure) {
                rendererService?.setPlaybackSpeed(speed, currentSession.sessionId)
            }
        }

        override fun play() = handleControl("play")

        override fun pause() = handleControl("pause")

        override fun seekTo(positionMs: Long) = this@PlayerHostActivity.seekTo(positionMs)
    }
    private var deferQueueCommandPlaylistStatus = false
    private val playbackCoordinator by lazy {
        PlaybackCoordinator(object : PlaybackCoordinator.Host {
            override fun loadItem(item: PlayPayload, displayTitle: String?) {
                startPlaylistItem()
            }

            override suspend fun saveProgressBeforeAdvance(captureThumbnail: Boolean) {
                progressManager.saveProgress()
            }

            override fun onPlaylistChanged(items: List<PlayPayload>, index: Int) {
                if (!deferQueueCommandPlaylistStatus) {
                    broadcastPlaylistStatus(items, index)
                }
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
                    decodeLateSubtitleResource(intent.getStringExtra(ServerService.EXTRA_SUBTITLE_RESOURCE)),
                    intent.getStringExtra(ServerService.EXTRA_SUBTITLE_REQUEST_ID),
                    intent.getLongExtra(ServerService.EXTRA_SUBTITLE_CONNECTION_ID, 0L),
                    phone = phoneOriginOf(intent),
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
                    filterPendingQueueItems(
                        ServerService.drainPendingQueueItems(this@PlayerHostActivity),
                    ),
                )
                ServerService.ACTION_PLAYLIST_JUMP -> {
                    val index = intent.getIntExtra(ServerService.EXTRA_PLAYLIST_JUMP_INDEX, -1)
                    val phone = phoneOriginOf(intent)
                    if (index >= 0) lifecycleScope.launch {
                        val before = playbackCoordinator.index
                        playbackCoordinator.jumpTo(index)
                        announceQueueChange(phone, before)
                    }
                }
                ServerService.ACTION_QUEUE_MUTATE -> lifecycleScope.launch {
                    ServerService.drainPendingQueueCommands().forEach { applyQueueCommand(it) }
                }
                ServerService.ACTION_RESYNC -> broadcastCurrentState()
            }
        }
    }

    /** Who sent an in-process command: set only for commands that came from a paired phone. */
    private fun phoneOriginOf(intent: Intent): PhoneOrigin? =
        if (intent.getBooleanExtra(ServerService.EXTRA_FROM_PHONE, false)) {
            PhoneOrigin(intent.getStringExtra(ServerService.EXTRA_SENDER_NAME))
        } else {
            null
        }

    /** A phone moved the queue: say which item is now playing. Nothing when the position did not change. */
    private fun announceQueueChange(phone: PhoneOrigin?, indexBefore: Int) {
        if (phone == null || playbackCoordinator.index == indexBefore) return
        val index = playbackCoordinator.index
        val item = playbackCoordinator.playlist.getOrNull(index) ?: return
        controlsViewModel.showPhoneCard(
            phoneChangeCard(
                PhoneChangeKind.QUEUE,
                queuePositionTitle(item.title, index, playbackCoordinator.playlist.size),
                phone.name,
            ),
        )
    }

    private val callback = object : IRendererCallback.Stub() {
        override fun onRendererEvent(event: Bundle?) {
            val eventBundle = event ?: return
            runOnUiThread { handleRendererEvent(eventBundle) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
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
        lifecycleScope.launch {
            // Settings or a phone changed the style or font: restyle ExoPlayer's cues and mpv live.
            kotlinx.coroutines.flow.combine(subtitleStyles.style, subtitleStyles.fontRevision) { style, _ -> style }
                .collect { style ->
                val typeface = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    subtitleStyles.typeface(style)
                }
                subtitleTypeface = typeface
                subtitleView.applySubtitleStyle(style, typeface)
                val currentSession = session ?: return@collect
                runCatchingLogged(TAG, "Renderer subtitle style failed", ::logPlayerFailure) {
                    rendererService?.setSubtitleStyle(createSubtitleStyleJson(style), currentSession.sessionId)
                }
            }
        }
        lifecycleScope.launch {
            // ExoPlayer's own subtitle view (Automatic / Built-in modes) is lifted the same way as the
            // Compose overlay: one translation while the seek strip or controls are drawn.
            controlsViewModel.controlsState
                .map { it.subtitleLiftDp() }
                .distinctUntilChanged()
                .collect { liftDp ->
                    subtitleView.translationY =
                        -liftDp * com.playbridge.player.ui.theme.designDensity(resources.displayMetrics)
                }
        }
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
        ServerService.activatePlaybackWebhook(playbackRequestId(intent))
        playbackCoordinator.setPlaylist(playlist.items, playlist.start_index)
        activeHistoryId = intent.getStringExtra(PlayerLauncher.EXTRA_HISTORY_ID)
            ?: PlayerLauncher.historyId(playlist.items)
        sessionPlaybackContext = PlayerLauncher.playbackContextFromIntent(intent)
        FileLogger.i(
            TAG,
            "Initial playback request context: ${sessionPlaybackContext.toSafeLogString()}",
        )
        beginPartySession(intent)
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
        lifecycleScope.launch {
            controlsViewModel.controlsState
                .map { it.skipSegments }
                .distinctUntilChanged()
                .collect { broadcastTimelineIfChanged() }
        }
        lifecycleScope.launch {
            // Keeps the bookmark button in step with the stored history (bookmark toggles and
            // the entry appearing once playback has landed).
            // Decoded off the main thread: the history list is rewritten every few seconds.
            historyStore.history
                .map { items -> items.associate { it.id to it.isFavorite } }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .collect { snapshot ->
                    historySnapshot = snapshot
                    refreshBookmarkState()
                }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!acceptPlaybackRequest(intent)) return

        val playlist = PlayerLauncher.playlistFromIntent(intent)
        if (playlist == null || playlist.items.isEmpty()) {
            FileLogger.w(TAG, "Ignoring empty replacement playback request")
            return
        }

        ServerService.activatePlaybackWebhook(playbackRequestId(intent))
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
            addAction(ServerService.ACTION_QUEUE_MUTATE)
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
        playbackCoordinator.queueAdd(
            filterPendingQueueItems(ServerService.drainPendingQueueItems(this)),
        )
        lifecycleScope.launch {
            ServerService.drainPendingQueueCommands().forEach { applyQueueCommand(it) }
        }
        refreshKeepScreenOn()
    }

    override fun onStop() {
        isHostStarted = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        logPlaybackContextCheckpoint(
            "onStop(finishing=$isFinishing, changingConfigurations=$isChangingConfigurations)",
        )
        if (!isFinishing && !isChangingConfigurations) {
            if (partyItem != null) {
                // Leaving the player screen leaves the party; the others keep watching.
                TvTogether.leave()
                handleControl("pause", fromParty = true)
            } else {
                handleControl("pause")
            }
        }
        runCatchingLogged(TAG, "Could not unregister playback control receiver", ::logPlayerFailure) {
            unregisterReceiver(controlReceiver)
        }
        super.onStop()
    }

    override fun onPause() {
        logPlaybackContextCheckpoint("onPause")
        requestHistoryThumbnailCapture(exitFallback = true)
        super.onPause()
    }

    private fun setupControlsOverlay() {
        controlsViewModel.setEngine(controlsAdapter, rendererKind.name.lowercase(), this)
        controlsViewModel.subtitleTargetResolver = ::resolveControlsSubtitleTarget
        controlsViewModel.setEndSegmentSkipHandler(::advanceAfterEndSegment)
        if (getSharedPreferences("browser_prefs", MODE_PRIVATE)
                .getBoolean("loudness_enhancer", false)
        ) {
            controlsViewModel.toggleAudioBoost()
        }
        composeView.setContent {
            PlayBridgeTVTheme {
                // Rounded-position state (about 1 change a second); the seek bars read exact
                // position from seekProgress so this tree does not recompose every tick.
                val state by controlsViewModel.uiState.collectAsState()
                val stillWatching by stillWatchingController.state.collectAsState()
                val together by TvTogether.state.collectAsState()
                val party = if (state.isPartySession) together as? TvTogether.State.InParty else null
                Box(Modifier.fillMaxSize()) {
                    MediaPresentation(
                        payload = presentationPayload,
                        mediaKind = currentMediaKind.wireValue,
                        imageScale = imageScale,
                        imageOffsetX = imageOffsetX,
                        imageOffsetY = imageOffsetY,
                        imageRotation = imageRotation,
                        onImageShown = ::onImageShown,
                        senderName = state.senderName,
                        photoCounter = photoCounterText(state),
                        showPhotoHints = !state.isVisible && state.prePlayMetadata == null,
                        musicControls = {
                            MusicTransport(
                                state = state,
                                progress = controlsViewModel.seekProgress,
                                onTogglePlay = controlsViewModel::togglePlayPause,
                                onPrev = { lifecycleScope.launch { playbackCoordinator.previous() } },
                                onNext = { lifecycleScope.launch { playbackCoordinator.next() } },
                                onPlaylist = {
                                    controlsViewModel.showPlaylist(
                                        playbackCoordinator.playlist,
                                        playbackCoordinator.index,
                                    )
                                },
                            )
                        },
                    )
                    PlayerControlsOverlay(
                    state = state,
                    progress = controlsViewModel.seekProgress,
                    mediaKind = currentMediaKind.wireValue,
                    subtitleStyle = subtitleStyles.style.collectAsState().value,
                    subtitleTypeface = subtitleTypeface,
                    stillWatchingState = stillWatching,
                    onContinueWatching = stillWatchingController::continueWatching,
                    onStopWatching = stillWatchingController::stopWatching,
                    onBookmark = ::toggleBookmark,
                    onTogglePlay = controlsViewModel::togglePlayPause,
                    onSubtitles = controlsViewModel::showSubtitles,
                    onPlaylist = {
                        controlsViewModel.showPlaylist(
                            playbackCoordinator.playlist,
                            playbackCoordinator.index,
                        )
                    },
                    onChapters = controlsViewModel::showChapters,
                    onPrev = { lifecycleScope.launch { playbackCoordinator.previous() } },
                    onNext = { lifecycleScope.launch { playbackCoordinator.next() } },
                    onLoop = {
                        val enabled = !controlsViewModel.controlsState.value.isLooping
                        session?.let { currentSession ->
                            runCatchingLogged(TAG, "Overlay setLooping failed", ::logPlayerFailure) {
                                rendererService?.setLooping(enabled, currentSession.sessionId)
                            }
                        }
                        controlsViewModel.setLooping(enabled)
                        syncPlaybackContext()
                        FileLogger.i(TAG, "Loop changed to $enabled and was added to playback context")
                        broadcastPlayerSettings()
                    },
                    onSeek = controlsViewModel::handleScrubbing,
                    onPrePlayStartNow = ::startPrePlayNow,
                    onPrePlayStartOver = ::startPrePlayOver,
                    onPrePlayBack = ::finishPlaybackSession,
                    party = party,
                    onLeaveParty = ::leaveParty,
                    onSettingsTabSelected = controlsViewModel::showSettings,
                    onTrackSelected = ::selectTrack,
                    onSpeedSelected = { speed ->
                        controlsViewModel.setPlaybackSpeed(speed)
                        syncPlaybackContext()
                        broadcastPlayerSettings()
                    },
                    onScalingSelected = { mode ->
                        session?.let { currentSession ->
                            runCatchingLogged(TAG, "Overlay setVideoScaling failed", ::logPlayerFailure) {
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
                    onChapterPicked = ::jumpToChapterIndex,
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
        setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        applySubtitleStyle(subtitleStyles.style.value)
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
                runCatchingLogged(TAG, "Track selection setVideoQuality failed", ::logPlayerFailure) {
                    renderer.setVideoQuality(maxHeight, currentSession.sessionId)
                }
                syncPlaybackContext()
                broadcastPlayerSettings()
            }
            "audio" -> {
                FileLogger.i(
                    TAG,
                    "Audio track requested: id=${track.id}, name=${track.name}, " +
                        "details=${track.secondaryText}; awaiting renderer track confirmation",
                )
                runCatchingLogged(TAG, "Track selection setAudioTrack failed", ::logPlayerFailure) {
                    renderer.setAudioTrack(track.id, currentSession.sessionId)
                }
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
        failPendingLateSubtitleRequests()
        lateSubtitleRegisterJob?.cancel()
        lateSubtitleRegisterJob = null
        subtitleStageJob?.cancel()
        subtitleStageJob = null
        clearPendingNativeSubtitle()
        clearInitialSubtitleHandled()
        currentExternalSubtitleUrl = null
        externalSubtitleOverlayActive = false
        controlsViewModel.clearSubtitle()
        subtitleView.setCues(emptyList())
        subtitleTracks = subtitleTracks.map { it.copy(selected = it.id == trackId) }
        runCatchingLogged(TAG, "Subtitle selection setSubtitleTrack failed", ::logPlayerFailure) {
            renderer.setSubtitleTrack(trackId, sessionId)
        }
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
        failPendingLateSubtitleRequests(exceptUrl = url)
        val item = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index) ?: run {
            completeLateSubtitleRequest(url, false)
            return
        }
        if (item.isPageControlledMedia() && pageProxyRouter.resolveSubtitleFetch(url, null) == null) {
            val lateResource = lateSubtitleResources[url]
            val subHeaders = lateResource?.headers ?: item.headersForSubtitle(url)
            lateSubtitleRegisterJob?.cancel()
            lateSubtitleRegisterJob = lifecycleScope.launch {
                val registered = pageProxyRouter.registerLateSubtitle(
                    item = item,
                    subtitleUrl = url,
                    headers = subHeaders,
                    contentType = null,
                )
                if (session?.sessionId != sessionId) return@launch
                registered.onSuccess {
                    val activeRenderer = rendererService ?: run {
                        completeLateSubtitleRequest(url, false)
                        return@launch
                    }
                    selectExternalSubtitleReady(url, activeRenderer, sessionId)
                }.onFailure { error ->
                    FileLogger.w(TAG, "Rejected webpage subtitle registration: ${error.message}")
                    completeLateSubtitleRequest(url, false)
                    Toast.makeText(
                        this@PlayerHostActivity,
                        "Unable to proxy webpage subtitle",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            return
        }
        selectExternalSubtitleReady(url, renderer, sessionId)
    }

    private fun selectExternalSubtitleReady(
        url: String,
        renderer: IRendererService,
        sessionId: Long,
    ) {
        externalSubtitleUrls = (externalSubtitleUrls + url).distinct()
        currentExternalSubtitleUrl = url
        clearInitialSubtitleHandled()
        applyExternalSubtitleSelection(renderer, sessionId)
        progressManager.updateSelections(externalSubtitleUrl = url)
        updateTrackControls()
        syncPlaybackContext()
        broadcastTracks()
    }

    private fun resolveControlsSubtitleTarget(
        canonicalUrl: String,
        fallbackHeaders: Map<String, String>?,
    ): PlayerControlsViewModel.SubtitleLoadTarget? {
        val item = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index)
            ?: return null
        if (!item.isPageControlledMedia()) {
            return PlayerControlsViewModel.SubtitleLoadTarget(
                fetchUrl = canonicalUrl,
                headers = fallbackHeaders,
                trustedLocalProxyOrigin = null,
            )
        }
        val resolved = pageProxyRouter.resolveSubtitleFetch(canonicalUrl, fallbackHeaders)
            ?: return null
        return PlayerControlsViewModel.SubtitleLoadTarget(
            fetchUrl = resolved.fetchUrl,
            headers = resolved.headers,
            trustedLocalProxyOrigin = resolved.trustedLocalProxyOrigin,
        )
    }

    private fun filterPendingQueueItems(items: List<PlayPayload>): List<PlayPayload> =
        items.filter { item ->
            pageProxyRouter.canAcceptItem(item).also { allowed ->
                if (!allowed) {
                    FileLogger.w(
                        TAG,
                        "Dropped queued webpage item because local proxy/policy rejected it",
                    )
                }
            }
        }

    private fun completeLateSubtitleRequest(url: String, ok: Boolean) {
        val request = pendingLateSubtitleRequests.remove(url) ?: return
        ServerService.completeLateSubtitleCommand(
            request.connectionId,
            request.requestId,
            ok,
            if (ok) null else "subtitle_unavailable",
        )
        if (!ok) {
            lateSubtitleResources.remove(url)
            externalSubtitleUrls = externalSubtitleUrls.filterNot { it == url }
            if (currentExternalSubtitleUrl == url) {
                currentExternalSubtitleUrl = null
                externalSubtitleOverlayActive = false
                controlsViewModel.clearSubtitle()
            }
            updateTrackControls()
            broadcastTracks()
        }
    }

    private fun failPendingLateSubtitleRequests(exceptUrl: String? = null) {
        pendingLateSubtitleRequests.keys.toList().filterNot { it == exceptUrl }.forEach { url ->
            completeLateSubtitleRequest(url, false)
        }
    }

    private fun applyExternalSubtitleSelection(
        renderer: IRendererService,
        sessionId: Long,
    ) {
        val url = currentExternalSubtitleUrl ?: return
        val item = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index) ?: return
        val lateResource = lateSubtitleResources[url]
        val subtitleHeaders = lateResource?.headers ?: item.headersForSubtitle(url)
        subtitleStageJob?.cancel()
        subtitleStageJob = null
        clearPendingNativeSubtitle()
        subtitleView.setCues(emptyList())
        externalSubtitleOverlayActive = false
        runCatchingLogged(TAG, "External subtitle clear setSubtitleTrack failed", ::logPlayerFailure) {
            renderer.setSubtitleTrack("off", sessionId)
        }
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
                controlsViewModel.loadExternalSubtitle(
                    url, subtitleHeaders, userAdded = lateResource != null,
                    onResult = { completeLateSubtitleRequest(url, it) },
                )
            }
            SubtitleRenderingMode.AUTO,
            SubtitleRenderingMode.BUILT_IN,
            -> {
                controlsViewModel.clearSubtitle()
                val requestedMode = renderingMode
                subtitleStageJob = lifecycleScope.launch {
                    try {
                        val subTarget = if (item.isPageControlledMedia()) {
                            pageProxyRouter.resolveSubtitleFetch(url, subtitleHeaders)
                                ?: throw IllegalStateException(
                                    "Webpage subtitle is not registered with local proxy",
                                )
                        } else {
                            SubtitleFetchTarget(
                                fetchUrl = url,
                                headers = subtitleHeaders,
                                trustedLocalProxyOrigin = null,
                            )
                        }
                        val file = externalSubtitleStager.stage(
                            subTarget.fetchUrl,
                            subTarget.headers,
                            enforcePageNetworkPolicy = lateResource != null || item.isPageControlledMedia(),
                            allowedPrivateOrigins = if (lateResource != null) {
                                PageMediaProxyRouter.withUserChosenOrigin(item.allowed_private_origins, url)
                            } else {
                                item.allowed_private_origins
                            },
                            trustedLocalProxyOrigin = subTarget.trustedLocalProxyOrigin,
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
                            externalSubtitleLabel(url),
                            sessionId,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        clearPendingNativeSubtitle()
                        FileLogger.w(TAG, "Unable to stage external subtitle for built-in rendering")
                        if (requestedMode == SubtitleRenderingMode.AUTO &&
                            session?.sessionId == sessionId &&
                            currentExternalSubtitleUrl == url &&
                            (!item.isPageControlledMedia() ||
                                pageProxyRouter.resolveSubtitleFetch(url, subtitleHeaders) != null)
                        ) {
                            externalSubtitleOverlayActive = true
                            controlsViewModel.loadExternalSubtitle(
                                url, subtitleHeaders, userAdded = lateResource != null,
                                onResult = { completeLateSubtitleRequest(url, it) },
                            )
                        } else {
                            completeLateSubtitleRequest(url, false)
                            if (requestedMode == SubtitleRenderingMode.BUILT_IN ||
                                item.isPageControlledMedia()
                            ) {
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
    }

    private fun switchRenderer(target: RendererKind) {
        if (target == rendererKind || target !in setOf(RendererKind.MPV, RendererKind.EXO)) return
        val pageControlled = playbackCoordinator.playlist
            .getOrNull(playbackCoordinator.index)
            ?.isPageControlledMedia() == true
        if (!rendererAllowedForPageMedia(
                pageControlled = pageControlled,
                target = target,
                proxyAvailable = pageProxyRouter.isProxyAvailable(),
            )
        ) {
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
        itemPrepareJob?.cancel()
        itemPrepareJob = null
        lateSubtitleRegisterJob?.cancel()
        lateSubtitleRegisterJob = null
        subtitleStageJob?.cancel()
        subtitleStageJob = null
        clearPendingNativeSubtitle()
        clearInitialSubtitleHandled()
        subtitleView.setCues(emptyList())
        controlsViewModel.clearSubtitle()
        runCatchingLogged(TAG, "Renderer release before switch failed", ::logPlayerFailure) {
            oldSession?.let { rendererService?.release(it.sessionId) }
        }
        unbindCurrentRenderer()
        rotateRendererSurface()
        terminateRendererProcess(oldKind)
        attemptedRenderers.clear()
        attemptedRenderers += target
        rendererKind = target
        session = sessionCoordinator.begin(target)
        currentChapters = emptyList()
        chaptersSessionId = 0L
        controlsViewModel.setChapters(emptyList())
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
        cancelStallMonitor()
        finishingSession = true
        logPlaybackContextCheckpoint("onDestroy")
        cancelStartupWatchdog()
        cancelPrePlayCountdown()
        imageTimerJob?.cancel()
        imageLoadFallback?.cancel()
        imageTimerJob = null
        cancelFailureFinish()
        itemPrepareJob?.cancel()
        itemPrepareJob = null
        lateSubtitleRegisterJob?.cancel()
        lateSubtitleRegisterJob = null
        subtitleStageJob?.cancel()
        subtitleStageJob = null
        pageProxyRouter.close()
        progressManager.saveProgress()
        val currentSession = session
        if (currentSession != null) {
            runCatchingLogged(TAG, "Renderer stop on destroy failed", ::logPlayerFailure) {
                rendererService?.stop(currentSession.sessionId)
            }
            runCatchingLogged(TAG, "Renderer release on destroy failed", ::logPlayerFailure) {
                rendererService?.release(currentSession.sessionId)
            }
        }
        unbindCurrentRenderer()
        subtitleView.setCues(emptyList())
        stagedSubtitleFiles.forEach(externalSubtitleStager::delete)
        stagedSubtitleFiles.clear()
        surfaceView.holder.removeCallback(surfaceCallback)
        controlsViewModel.detach()
        stillWatchingController.dispose()
        mainHandler.removeCallbacks(reportPartyLoadedAnyway)
        TvTogether.detach(partyPlayer)
        ServerService.notifyContextIdle(this, rendererKind.engineId)
        if (activeHostCount.decrementAndGet() <= 0) {
            ReceiverStreamProxy.shutdownIfRunning()
        }
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
        imageLoadFallback?.cancel()
        imageTimerJob = null
        imageTimerRunning = false
        cancelFailureFinish()
        itemPrepareJob?.cancel()
        itemPrepareJob = null
        lateSubtitleRegisterJob?.cancel()
        lateSubtitleRegisterJob = null
        pageProxyRouter.clearActiveItem()

        val oldKind = rendererKind
        val oldSession = session
        val oldRenderer = rendererService
        val targetKind = rendererKindFromIntent(requestIntent)

        // Begin the new logical session before touching the old renderer. Any stop/release
        // callbacks already in flight retain the old session id and are ignored by the host.
        rendererKind = targetKind
        finishingSession = false
        failureReporter.reset()
        attemptedRenderers.clear()
        attemptedRenderers += targetKind
        playbackCoordinator.setPlaylist(playlist.items, playlist.start_index)
        val initialItem = playlist.items[playbackCoordinator.index]
        currentMediaKind = resolveMediaKind(initialItem)
        setPresentation(
            initialItem.takeIf {
                currentMediaKind != MediaKind.VIDEO && !initialItem.isPageControlledMedia()
            },
        )
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
        beginPartySession(requestIntent)
        restoreSavedExternalForCurrentItem = sessionPlaybackContext != null
        controlsViewModel.resetSessionSettings(
            defaultQualityMaxHeight(playlist.items.getOrNull(playlist.start_index)),
        )
        applySavedPlaybackSettings()
        playbackCoordinator.queueAdd(
            filterPendingQueueItems(ServerService.drainPendingQueueItems(this)),
        )
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
            runCatchingLogged(TAG, "Renderer detach before replacement failed", ::logPlayerFailure) {
                oldRenderer.detachSurface(oldSession.sessionId)
            }
            runCatchingLogged(TAG, "Renderer release before replacement failed", ::logPlayerFailure) {
                oldRenderer.release(oldSession.sessionId)
            }
        }

        if (targetKind != oldKind) {
            if (!oldRendererReleased) {
                runCatchingLogged(TAG, "Renderer release on kind change failed", ::logPlayerFailure) {
                    oldSession?.let { oldRenderer?.release(it.sessionId) }
                }
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
        currentChapters = emptyList()
        chaptersSessionId = 0L
        lastBufferedMs = 0L
        bufferedSessionId = 0L
        lastTimelineJson = null
        controlsViewModel.setChapters(emptyList())
        externalSubtitleUrls = emptyList()
        itemSubtitleLabels = emptyMap()
        lateSubtitleResources.clear()
        failPendingLateSubtitleRequests()
        currentExternalSubtitleUrl = null
        externalSubtitleOverlayActive = false
        subtitleStageJob?.cancel()
        subtitleStageJob = null
        clearPendingNativeSubtitle()
        clearInitialSubtitleHandled()
        controlsViewModel.setPlaying(false)
        controlsViewModel.setBuffering(true)
        controlsViewModel.clearPlaybackTransition()
        controlsViewModel.clearPlaybackError()
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
        if (currentConnection != null) {
            runCatchingLogged(TAG, "Could not unbind renderer service", ::logPlayerFailure) {
                unbindService(currentConnection)
            }
        }
        rendererService = null
        preparedSessionId = 0L
    }

    private fun prepareRenderer(renderer: IRendererService) {
        val currentSession = session ?: return
        if (preparedSessionId == currentSession.sessionId || itemPrepareJob?.isActive == true) {
            return
        }
        val payload = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index)
        if (payload == null) {
            handleRendererFailure("Host launch did not include a PlayPayload")
            return
        }
        currentMediaKind = resolveMediaKind(payload)
        if (payload.isPageControlledMedia() && !pageProxyRouter.canAcceptItem(payload)) {
            FileLogger.w(TAG, "Refusing webpage media item because local proxy/policy rejected it")
            handleRendererFailure(
                message = "Local media proxy rejected webpage stream",
                severity = ExoPlaybackErrorPolicy.EscalationSeverity.TERMINAL,
            )
            return
        }
        if (currentMediaKind != MediaKind.IMAGE && surface == null) {
            FileLogger.d(TAG, "Waiting for a fresh surface before preparing $rendererKind")
            return
        }

        val savedExternal = sessionPlaybackContext?.externalSubtitleUrl
            ?.takeIf { restoreSavedExternalForCurrentItem }
        itemPrepareJob = lifecycleScope.launch {
            val preparedResult = pageProxyRouter.prepareItem(payload, savedExternal)
            if (finishingSession || session?.sessionId != currentSession.sessionId) return@launch
            val prepared = preparedResult.getOrElse { error ->
                FileLogger.w(
                    TAG,
                    "Refusing webpage media playback without local proxy: ${error.message}",
                )
                handleRendererFailure(
                    message = error.message ?: "Unable to start local media proxy for webpage media",
                    severity = ExoPlaybackErrorPolicy.EscalationSeverity.TERMINAL,
                )
                return@launch
            }
            controlsViewModel.trustedLocalSubtitleProxyOrigin = prepared.proxyOrigin
            setPresentation(
                prepared.presentationPayload().takeIf { currentMediaKind != MediaKind.VIDEO },
            )
            if (currentMediaKind == MediaKind.IMAGE) {
                configureProgress(payload)
                startImagePresentation(prepared.presentationPayload(), currentSession.sessionId)
                return@launch
            }
            if (surface == null || rendererService?.asBinder() != renderer.asBinder()) {
                return@launch
            }

            configureProgress(payload)
            preparedSessionId = currentSession.sessionId
            val enginePayload = prepared.enginePayload(rendererKind)
            val trustedProxyOrigin = prepared.proxyOrigin
            val externalUrl = currentExternalSubtitleUrl
            val renderingMode = SubtitleRenderingMode.read(this@PlayerHostActivity)
            if (payload.isPageControlledMedia() &&
                externalUrl != null &&
                renderingMode == SubtitleRenderingMode.PLAYBRIDGE_OVERLAY
            ) {
                externalSubtitleOverlayActive = true
                controlsViewModel.loadExternalSubtitle(externalUrl, emptyMap())
            }
            val stageForInitialExoPrepare = rendererKind == RendererKind.EXO &&
                externalUrl != null &&
                renderingMode != SubtitleRenderingMode.PLAYBRIDGE_OVERLAY

            if (!stageForInitialExoPrepare) {
                if (externalUrl != null && renderingMode == SubtitleRenderingMode.PLAYBRIDGE_OVERLAY) {
                    markInitialSubtitleHandled(currentSession.sessionId, externalUrl)
                }
                prepareRendererNow(
                    renderer = renderer,
                    payload = enginePayload,
                    sessionId = currentSession.sessionId,
                    trustedLocalProxyOrigin = trustedProxyOrigin,
                )
                return@launch
            }
            val initialExternalUrl = checkNotNull(externalUrl)
            val initialLateResource = lateSubtitleResources[initialExternalUrl]
            val fallbackHeaders = initialLateResource?.headers
                ?: payload.headersForSubtitle(initialExternalUrl)

            cancelStartupWatchdog()
            subtitleStageJob?.cancel()
            subtitleStageJob = lifecycleScope.launch {
                try {
                    val subTarget = if (payload.isPageControlledMedia()) {
                        prepared.resolveSubtitleFetch(initialExternalUrl, fallbackHeaders)
                            ?: throw IllegalStateException(
                                "Webpage subtitle is not registered with local proxy",
                            )
                    } else {
                        SubtitleFetchTarget(
                            fetchUrl = initialExternalUrl,
                            headers = fallbackHeaders,
                            trustedLocalProxyOrigin = null,
                        )
                    }
                    val file = externalSubtitleStager.stage(
                        subTarget.fetchUrl,
                        subTarget.headers,
                        enforcePageNetworkPolicy = initialLateResource != null || payload.isPageControlledMedia(),
                        allowedPrivateOrigins = if (initialLateResource != null) {
                            PageMediaProxyRouter.withUserChosenOrigin(
                                payload.allowed_private_origins,
                                initialExternalUrl,
                            )
                        } else {
                            payload.allowed_private_origins
                        },
                        trustedLocalProxyOrigin = subTarget.trustedLocalProxyOrigin,
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
                        renderer = renderer,
                        payload = enginePayload,
                        sessionId = currentSession.sessionId,
                        initialSubtitleUri = stagedUri,
                        initialSubtitleLabel = initialLateResource?.label?.takeIf(String::isNotBlank)
                            ?: externalSubtitleName(initialExternalUrl),
                        trustedLocalProxyOrigin = trustedProxyOrigin,
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
                        currentExternalSubtitleUrl == initialExternalUrl &&
                        (!payload.isPageControlledMedia() ||
                            prepared.resolveSubtitleFetch(initialExternalUrl, fallbackHeaders) != null)
                    ) {
                        externalSubtitleOverlayActive = true
                        controlsViewModel.loadExternalSubtitle(
                            initialExternalUrl,
                            fallbackHeaders,
                            userAdded = initialLateResource != null,
                        )
                    } else if (renderingMode == SubtitleRenderingMode.BUILT_IN ||
                        payload.isPageControlledMedia()
                    ) {
                        Toast.makeText(
                            this@PlayerHostActivity,
                            "Unable to load subtitle with the built-in player",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    prepareRendererNow(
                        renderer = renderer,
                        payload = enginePayload,
                        sessionId = currentSession.sessionId,
                        trustedLocalProxyOrigin = trustedProxyOrigin,
                    )
                }
            }
        }
    }

    private fun startImagePresentation(payload: PlayPayload, sessionId: Long) {
        if (session?.sessionId != sessionId) return
        resetImageTransform()
        cancelStartupWatchdog()
        cancelPrePlayCountdown()
        imageTimerJob?.cancel()
        imageLoadFallback?.cancel()
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
        if (lastDurationMs > 0L && shownImageUrl != payload.url) {
            // Slow phone-served photos can take seconds to arrive; don't let their time run
            // out before they are seen. A photo that never loads still advances eventually.
            imageLoadFallback = lifecycleScope.launch {
                delay(IMAGE_LOAD_TIMEOUT_MS)
                onImageShown(payload.url)
            }
        }
        if (lastDurationMs > 0L) startImageTimer(sessionId)
        refreshKeepScreenOn()
    }

    private fun setPresentation(payload: PlayPayload?) {
        if (payload?.url != presentationPayload?.url) shownImageUrl = null
        presentationPayload = payload
    }

    /** The photo for [url] finished loading (or failed): start its display time. */
    private fun onImageShown(url: String) {
        if (url != presentationPayload?.url || shownImageUrl == url) return
        shownImageUrl = url
        imageLoadFallback?.cancel()
        val currentSession = session ?: return
        if (currentMediaKind == MediaKind.IMAGE && preparedSessionId == currentSession.sessionId) {
            startImageTimer(currentSession.sessionId)
        }
    }

    private fun startImageTimer(sessionId: Long) {
        imageTimerJob?.cancel()
        if (!imageTimerRunning || lastDurationMs <= 0L) return
        if (shownImageUrl == null || shownImageUrl != presentationPayload?.url) return
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
        trustedLocalProxyOrigin: String? = null,
    ) {
        if (session?.sessionId != sessionId || preparedSessionId != sessionId) return
        val rendererSurface = surface ?: run {
            preparedSessionId = 0L
            return
        }
        val request = Bundle().apply {
            putString(RendererProtocol.KEY_PAYLOAD_JSON, encodePlayPayloadListJson(listOf(payload)))
            trustedLocalProxyOrigin?.let {
                putString(RendererProtocol.KEY_TRUSTED_LOCAL_PROXY_ORIGIN, it)
            }
            initialSubtitleUri?.let {
                putString(RendererProtocol.KEY_INITIAL_SUBTITLE_URI, it)
                putString(RendererProtocol.KEY_INITIAL_SUBTITLE_LABEL, initialSubtitleLabel)
            }
        }
        startStallMonitor(sessionId)
        scheduleRendererOpenWatchdog(sessionId, rendererKind)
        try {
            renderer.prepare(request, sessionId)
            renderer.attachSurface(rendererSurface, sessionId)
            applyRendererSettings(renderer, sessionId)
            // A party video loads paused; the room starts everyone together.
            if (partyItem == null) {
                stallPlaybackRequested = true
                pendingPlayingState = true
                renderer.play(sessionId)
            }
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
        renderer.setSubtitleStyle(createSubtitleStyleJson(subtitleStyles.style.value), sessionId)
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
                        runCatchingLogged(TAG, "Saved audio restore setAudioTrack failed", ::logPlayerFailure) {
                            renderer.setAudioTrack(match.id, currentSession.sessionId)
                        }
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
                    runCatchingLogged(TAG, "Saved subtitle-off setSubtitleTrack failed", ::logPlayerFailure) {
                        renderer.setSubtitleTrack("off", currentSession.sessionId)
                    }
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
                            runCatchingLogged(TAG, "Saved subtitle restore setSubtitleTrack failed", ::logPlayerFailure) {
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
        currentHistoryId = historyId.takeUnless { items.any { it.skip_history == true } }
        refreshBookmarkState()
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
                            val historyUpdated = suspendRunCatchingLogged(
                                TAG,
                                "Unable to update the Library thumbnail",
                                ::logPlayerFailure,
                            ) {
                                historyStore.updateThumbnail(historyId, thumbnailUrl)
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
        SubtitleCueLoader.clear()
        controlsViewModel.setTitle(item.title ?: "")
        val pageControlled = item.isPageControlledMedia()
        controlsViewModel.setPlayerSwitchAllowed(!pageControlled || pageProxyRouter.isProxyAvailable())
        controlsViewModel.subtitleRequestHeaders = if (pageControlled) null else item.headers
        controlsViewModel.subtitleRequestHeadersByUrl =
            if (pageControlled) emptyMap() else item.subtitleHeadersByUrl()
        controlsViewModel.enforcePageSubtitleNetworkPolicy = pageControlled
        controlsViewModel.allowedPrivateSubtitleOrigins = item.allowed_private_origins
        controlsViewModel.trustedLocalSubtitleProxyOrigin = pageProxyRouter.currentTrustedProxyOrigin()
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
        itemSubtitleLabels = item.subtitleLabelsByUrl()
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
        if (!pageControlled &&
            externalUrl != null &&
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
        prePlayResumeMs = null
        prePlayResumeDurationMs = null
        if (explicitPosition != null && explicitPosition > 0L) {
            // The phone's start position, or a replay from Home: remembered for the pre-play choice.
            prePlayResumeMs = explicitPosition
            seekForSession(explicitPosition, sessionId)
            return
        }
        // Party videos never resume from history.
        if (partyItem != null) return

        val url = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index)?.url ?: return
        lifecycleScope.launch {
            val saved = progressManager.restoreProgress(url) { position ->
                prePlayResumeMs = position
                seekForSession(position, sessionId)
            }
            if (prePlayResumeMs != null) prePlayResumeDurationMs = saved?.duration?.takeIf { it > 0L }
        }
    }

    private fun seekForSession(positionMs: Long, expectedSessionId: Long) {
        val currentSession = session ?: return
        if (currentSession.sessionId != expectedSessionId) return
        val target = positionMs.coerceAtLeast(0L)
        stallDetector.reset()
        val origin = lastPositionMs
        pendingSeekTracker.start(
            sessionId = expectedSessionId,
            originMs = origin,
            targetMs = target,
            nowMs = SystemClock.elapsedRealtime(),
        )
        lastPositionMs = target
        controlsViewModel.setPendingSeekTime(target)
        runCatchingLogged(TAG, "Renderer seek failed", ::logPlayerFailure) {
            rendererService?.seekTo(target, expectedSessionId)
        }
    }

    private fun handleRendererEvent(event: Bundle) {
        // A late renderer tick must not overwrite the terminal status during the failure transition.
        if (finishingSession) return
        val currentSession = session ?: return
        if (event.getLong(RendererProtocol.KEY_SESSION_ID) != currentSession.sessionId ||
            sessionCoordinator.current().sessionId != currentSession.sessionId
        ) return
        when (event.getString(RendererProtocol.KEY_EVENT)) {
            RendererProtocol.EVENT_READY -> {
                sessionCoordinator.markReady(currentSession.sessionId)
                cancelStartupWatchdog()
                if (partyItem != null && !partyLoadedReported) {
                    partyReady = true
                    reportPartyLoaded(force = false)
                    mainHandler.removeCallbacks(reportPartyLoadedAnyway)
                    mainHandler.postDelayed(reportPartyLoadedAnyway, PARTY_LIVE_WAIT_MS)
                }
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
                broadcastTimelineIfChanged()
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
                if (partyItem != null) {
                    TvTogether.onPlayerState(lastPositionMs, rendererPlaying, state == "buffering")
                }
                if (event.containsKey(RendererProtocol.KEY_BUFFERED_MS)) {
                    lastBufferedMs = event.getLong(RendererProtocol.KEY_BUFFERED_MS)
                    bufferedSessionId = currentSession.sessionId
                }
                if (state == "playing" &&
                    !pendingSeekTracker.isPending(currentSession.sessionId) &&
                    abs(lastPositionMs - lastSavedPositionMs) >= PROGRESS_SAVE_INTERVAL_MS
                ) {
                    lastSavedPositionMs = lastPositionMs
                    progressManager.saveProgress()
                    requestHistoryThumbnailCapture()
                }
                stallState = state
                stallPositionMs = reportedPositionMs
                stallBufferedAheadMs = (lastBufferedMs - reportedPositionMs).coerceAtLeast(0L)
                val status = createStatusJson(
                    state = state,
                    position = lastPositionMs,
                    duration = event.getLong(RendererProtocol.KEY_DURATION_MS).coerceAtLeast(0L),
                    title = event.getString(RendererProtocol.KEY_TITLE),
                    mediaKind = currentMediaKind.wireValue,
                    playbackId = playbackCoordinator.playbackId,
                    currentItemId = playbackCoordinator.itemIdAt(playbackCoordinator.index),
                    buffered = currentBufferedMs(),
                )
                ServerService.broadcastStatus(this, status)
            }
            RendererProtocol.EVENT_ENDED -> {
                if (sessionCoordinator.canHandleEnded(currentSession.sessionId)) {
                    broadcastWebhookTerminalStatus("ended")
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
            RendererProtocol.EVENT_CHAPTERS -> {
                val normalized = normalizedChapters(decodeChapters(event))
                currentChapters = normalized
                chaptersSessionId = currentSession.sessionId
                controlsViewModel.setChapters(normalized)
                broadcastTimelineIfChanged()
            }
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
                runCatchingLogged(TAG, "Renderer release after stop failed", ::logPlayerFailure) {
                    rendererService?.release(currentSession.sessionId)
                }
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
        failureReporter.reset()
        restoreSavedExternalForCurrentItem = false
        stillWatchingController.onMediaChanged()
        val renderer = rendererService ?: run {
            handleRendererFailure("Renderer disconnected while advancing the playlist")
            return
        }
        val payload = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index) ?: return
        val outgoingSession = session
        itemPrepareJob?.cancel()
        itemPrepareJob = null
        lateSubtitleRegisterJob?.cancel()
        lateSubtitleRegisterJob = null
        pageProxyRouter.clearActiveItem()
        imageTimerJob?.cancel()
        imageLoadFallback?.cancel()
        imageTimerJob = null
        imageTimerRunning = false
        currentMediaKind = resolveMediaKind(payload)
        setPresentation(
            payload.takeIf {
                currentMediaKind != MediaKind.VIDEO && !payload.isPageControlledMedia()
            },
        )
        if (currentMediaKind == MediaKind.IMAGE && outgoingSession != null) {
            runCatchingLogged(TAG, "Renderer detach before playlist item failed", ::logPlayerFailure) {
                renderer.detachSurface(outgoingSession.sessionId)
            }
            runCatchingLogged(TAG, "Renderer release before playlist item failed", ::logPlayerFailure) {
                renderer.release(outgoingSession.sessionId)
            }
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
        currentChapters = emptyList()
        chaptersSessionId = 0L
        lastBufferedMs = 0L
        bufferedSessionId = 0L
        lastTimelineJson = null
        controlsViewModel.setChapters(emptyList())
        externalSubtitleUrls = emptyList()
        itemSubtitleLabels = emptyMap()
        lateSubtitleResources.clear()
        failPendingLateSubtitleRequests()
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
        broadcastWebhookTerminalStatus("stopped")
        failPendingLateSubtitleRequests()
        itemPrepareJob?.cancel()
        itemPrepareJob = null
        lateSubtitleRegisterJob?.cancel()
        lateSubtitleRegisterJob = null
        pageProxyRouter.clearActiveItem()
        logPlaybackContextCheckpoint("finishPlaybackSession")
        cancelStartupWatchdog()
        cancelPrePlayCountdown()
        progressManager.saveProgress()
        session?.let { currentSession ->
            sessionCoordinator.requestStop(currentSession.sessionId)
            if (currentMediaKind != MediaKind.IMAGE) {
                runCatchingLogged(TAG, "Renderer stop on finish failed", ::logPlayerFailure) {
                    rendererService?.stop(currentSession.sessionId)
                }
            }
        }
        imageTimerJob?.cancel()
        imageLoadFallback?.cancel()
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

    private fun playlistStatusJson(items: List<PlayPayload>, index: Int): String {
        controlsViewModel.updatePlaylistData(items, index)
        controlsViewModel.setPlaylistVisible(items.size > 1)
        val itemsJson = org.json.JSONArray().apply {
            items.forEachIndexed { itemIndex, item ->
                put(org.json.JSONObject().apply {
                    put("index", itemIndex)
                    put("title", item.title ?: "Item ${itemIndex + 1}")
                    playbackCoordinator.itemIdAt(itemIndex)?.let { put("itemId", it) }
                    item.progress_identity?.let { put("progressIdentity", org.json.JSONObject(com.playbridge.player.server.progressIdentityJson(it))) }
                    item.visual_metadata?.season?.let { put("season", it) }
                    item.visual_metadata?.episode?.let { put("episode", it) }
                    item.visual_metadata?.imdb_id?.let { put("imdbId", it) }
                    item.visual_metadata?.tmdb_id?.let { put("tmdbId", it) }
                    item.binge_group?.let { put("bingeGroup", it) }
                    put("mediaKind", resolveMediaKind(item).wireValue)
                })
            }
        }
        return org.json.JSONObject().apply {
            put("type", "playlist_status")
            put("items", itemsJson)
            put("currentIndex", if (items.isEmpty()) 0 else index)
            put("totalCount", items.size)
            playbackCoordinator.playbackId?.let { put("playbackId", it) }
            put("queueRevision", playbackCoordinator.queueRevision)
            playbackCoordinator.itemIdAt(index)?.let { put("currentItemId", it) }
        }.toString()
    }

    private fun broadcastPlaylistStatus(items: List<PlayPayload>, index: Int) {
        ServerService.broadcastPlaylistStatus(this, playlistStatusJson(items, index))
    }

    private suspend fun applyQueueCommand(command: ServerService.Companion.PendingQueueCommand) {
        deferQueueCommandPlaylistStatus = command.requestId != null
        val result = try {
            when (val message = command.message) {
            is com.playbridge.shared.protocol.IncomingMessage.QueueAdd -> {
                val items = buildList {
                    message.payload.item?.let(::add)
                    addAll(message.payload.items)
                }
                if (items.any { !pageProxyRouter.canAcceptItem(it) }) {
                    PlaybackCoordinator.MutationResult.InvalidCommand
                } else {
                    playbackCoordinator.queueAdd(items, message.payload.if_playback_id)
                }
            }
            is com.playbridge.shared.protocol.IncomingMessage.PlaylistJump -> {
                val before = playbackCoordinator.index
                (message.payload.item_id?.let {
                    playbackCoordinator.jumpToItem(it, message.payload.if_playback_id)
                } ?: playbackCoordinator.jumpToIndex(
                    message.payload.index,
                    message.payload.if_playback_id,
                )).also { announceQueueChange(PhoneOrigin(command.senderName), before) }
            }
            is com.playbridge.shared.protocol.IncomingMessage.QueueRemove ->
                playbackCoordinator.remove(message.payload.item_ids.toSet(), message.payload.if_playback_id)
            is com.playbridge.shared.protocol.IncomingMessage.QueueMove ->
                playbackCoordinator.move(
                    message.payload.item_id,
                    message.payload.before_item_id,
                    message.payload.if_playback_id,
                )
            is com.playbridge.shared.protocol.IncomingMessage.QueueClear ->
                playbackCoordinator.clear(message.payload.if_playback_id).also {
                    if (it == PlaybackCoordinator.MutationResult.Applied) handleControl("stop")
                }
            is com.playbridge.shared.protocol.IncomingMessage.QueueQuery -> {
                PlaybackCoordinator.MutationResult.Applied
            }
            else -> PlaybackCoordinator.MutationResult.InvalidCommand
            }
        } finally {
            deferQueueCommandPlaylistStatus = false
        }
        val error = when (result) {
            PlaybackCoordinator.MutationResult.Applied -> null
            PlaybackCoordinator.MutationResult.NoActivePlayback -> "no_active_playback"
            PlaybackCoordinator.MutationResult.StalePlayback -> "stale_playback"
            PlaybackCoordinator.MutationResult.ItemNotFound -> "item_not_found"
            PlaybackCoordinator.MutationResult.QueueFull -> "queue_full"
            PlaybackCoordinator.MutationResult.InvalidCommand -> "invalid_command"
        }
        ServerService.completeQueueCommand(
            command = command,
            ok = error == null,
            error = error,
            playbackId = playbackCoordinator.playbackId,
            queueRevision = playbackCoordinator.queueRevision,
            playlistStatusJson = if (
                error == null &&
                (command.requestId != null || command.message is com.playbridge.shared.protocol.IncomingMessage.QueueQuery)
            ) {
                playlistStatusJson(playbackCoordinator.playlist, playbackCoordinator.index)
            } else {
                null
            },
        )
    }

    private fun broadcastWebhookTerminalStatus(state: String) {
        ServerService.broadcastStatus(this, createStatusJson(
            state = state,
            position = lastPositionMs,
            duration = lastDurationMs,
            title = null,
            playbackId = playbackCoordinator.playbackId,
            currentItemId = playbackCoordinator.itemIdAt(playbackCoordinator.index),
        ))
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
                playbackId = playbackCoordinator.playbackId,
                currentItemId = playbackCoordinator.itemIdAt(playbackCoordinator.index),
                buffered = currentBufferedMs(),
            ),
        )
    }

    private fun broadcastCurrentState() {
        broadcastPlaybackStatus()
        broadcastPlaylistStatus(playbackCoordinator.playlist, playbackCoordinator.index)
        broadcastTracks()
        broadcastPlayerSettings()
        broadcastTimeline(force = true)
    }

    /** Buffered position for the current session, or null when unknown or live. */
    private fun currentBufferedMs(): Long? {
        val currentSession = session ?: return null
        if (bufferedSessionId != currentSession.sessionId || currentMediaKind == MediaKind.IMAGE) return null
        return reportedBufferedMs(lastBufferedMs, lastPositionMs, lastDurationMs)
    }

    private fun broadcastTimelineIfChanged() = broadcastTimeline(force = false)

    /**
     * Sends the `timeline` frame (chapters + skip segments) when it differs from the last
     * one sent, or always with [force] (a phone asked to resync).
     */
    private fun broadcastTimeline(force: Boolean) {
        val currentSession = session ?: return
        val chapters = currentChapters.takeIf { chaptersSessionId == currentSession.sessionId }.orEmpty()
        val segments = if (currentMediaKind == MediaKind.VIDEO) {
            controlsViewModel.controlsState.value.skipSegments
        } else {
            emptyList()
        }
        val json = buildTimelineJson(
            playbackId = playbackCoordinator.playbackId,
            currentItemId = playbackCoordinator.itemIdAt(playbackCoordinator.index),
            chapters = chapters,
            segments = segments,
        )
        if (!force && json == lastTimelineJson) return
        lastTimelineJson = json
        ServerService.broadcastStatus(this, json)
    }

    @Suppress("DEPRECATION")
    private fun decodeChapters(event: Bundle): List<PlayerChapter> =
        event.getParcelableArrayList<Bundle>(RendererProtocol.KEY_CHAPTERS).orEmpty().map { chapter ->
            PlayerChapter(
                startMs = chapter.getLong(RendererProtocol.KEY_CHAPTER_START_MS),
                title = chapter.getString(RendererProtocol.KEY_CHAPTER_TITLE),
            )
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
            put("loop", state.isLooping)
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
        if (partyItem != null) {
            partyLive = event.getBoolean(RendererProtocol.KEY_IS_LIVE)
            reportPartyLoaded(force = false)
        }
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
                runCatchingLogged(TAG, "Capability fallback setVideoScaling failed", ::logPlayerFailure) {
                    renderer.setVideoScaling("Fit", currentSession.sessionId)
                }
                updateVideoSurfaceLayout()
            }
            if (!capabilities.qualityAvailable && previousState.videoQualityMaxHeight != 0) {
                controlsViewModel.setVideoQuality(0)
                runCatchingLogged(TAG, "Capability fallback setVideoQuality failed", ::logPlayerFailure) {
                    renderer.setVideoQuality(0, currentSession.sessionId)
                }
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
        if (event.getBoolean(RendererProtocol.KEY_SUCCESS)) {
            currentExternalSubtitleUrl?.let { completeLateSubtitleRequest(it, true) }
            return
        }

        val url = currentExternalSubtitleUrl ?: return
        val item = playbackCoordinator.playlist.getOrNull(playbackCoordinator.index) ?: return
        FileLogger.w(TAG, "Built-in player did not expose the external subtitle track")
        when (requestedMode) {
            SubtitleRenderingMode.AUTO -> {
                externalSubtitleOverlayActive = true
                subtitleView.setCues(emptyList())
                runCatchingLogged(TAG, "External subtitle fallback setSubtitleTrack failed", ::logPlayerFailure) {
                    rendererService?.setSubtitleTrack("off", sessionId)
                }
                val lateResource = lateSubtitleResources[url]
                controlsViewModel.loadExternalSubtitle(
                    url,
                    lateResource?.headers ?: item.headersForSubtitle(url),
                    userAdded = lateResource != null,
                    onResult = { completeLateSubtitleRequest(url, it) },
                )
            }
            SubtitleRenderingMode.BUILT_IN -> {
                completeLateSubtitleRequest(url, false)
                Toast.makeText(
                    this,
                    "The built-in player could not display this subtitle",
                    Toast.LENGTH_SHORT,
                ).show()
            }
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
                    name = externalSubtitleLabel(url),
                    isSelected = url == currentExternalSubtitleUrl,
                    type = "external_sub",
                )
            },
            video = videoTracks.map { UnifiedTrack(it.id, it.label, it.selected, "video") },
            currentSubtitleUrl = currentExternalSubtitleUrl,
        )
    }

    /** A late add's label, else the item's label, else the link's `#label` fragment or file name. */
    private fun externalSubtitleLabel(url: String): String =
        lateSubtitleResources[url]?.label?.takeIf(String::isNotBlank) ?: itemSubtitleLabels[url] ?: externalSubtitleName(url)

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
                        put("name", externalSubtitleLabel(url))
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
        cancelStallMonitor()
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

        val safeMessage = RendererFailureReporter.userMessage(message)
        FileLogger.w(
            TAG,
            "Renderer $failedKind failed: $safeMessage " +
                "(severity=$effectiveSeverity firstFrame=$firstFrameSeen allowFailover=$allowEngineFailover)",
        )
        val failedSession = session
        requestedStartPositionMs = lastPositionMs.takeIf { it > 0L } ?: requestedStartPositionMs
        pendingSeekTracker.clear()
        cancelPrePlayCountdown()
        failedSession?.let { sessionCoordinator.markFailed(it.sessionId, safeMessage) }
        cancelStartupWatchdog()
        runCatchingLogged(TAG, "Renderer release after failure failed", ::logPlayerFailure) {
            failedSession?.let { rendererService?.release(it.sessionId) }
        }
        unbindCurrentRenderer()
        rotateRendererSurface()
        terminateRendererProcess(failedKind)

        val pageControlled = playbackCoordinator.playlist
            .getOrNull(playbackCoordinator.index)
            ?.isPageControlledMedia() == true
        val fallback = failureReporter.fallbackOrReport(
            allowEngineFailover = allowEngineFailover,
            fallback = fallbackRenderer(
                failed = failedKind,
                pageControlled = pageControlled,
                proxyAvailable = pageProxyRouter.isProxyAvailable(),
            ),
            attemptedRenderers = attemptedRenderers,
            message = message,
            positionMs = lastPositionMs,
            durationMs = lastDurationMs,
            mediaKind = currentMediaKind.wireValue,
            playbackId = playbackCoordinator.playbackId,
            currentItemId = playbackCoordinator.itemIdAt(playbackCoordinator.index),
            broadcastStatus = { status -> ServerService.broadcastStatus(this, status) },
        )
        if (fallback == null) {
            itemPrepareJob?.cancel()
            itemPrepareJob = null
            lateSubtitleRegisterJob?.cancel()
            lateSubtitleRegisterJob = null
            pageProxyRouter.clearActiveItem()
            controlsViewModel.showPlaybackError(
                if (message == PlaybackStallDetector.MESSAGE) {
                    PlaybackStallDetector.MESSAGE
                } else {
                    sessionCoordinator.current().error ?: getString(R.string.player_failed)
                },
            )
            refreshKeepScreenOn()
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
        currentChapters = emptyList()
        chaptersSessionId = 0L
        controlsViewModel.setChapters(emptyList())
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

    private fun startStallMonitor(sessionId: Long) {
        cancelStallMonitor()
        stallState = "buffering"
        stallPositionMs = 0L
        stallBufferedAheadMs = 0L
        val monitor = object : Runnable {
            override fun run() {
                if (finishingSession || session?.sessionId != sessionId) return
                val firstFrame = sessionCoordinator.current().phase == RendererSessionPhase.PLAYING
                if (stallDetector.sample(
                        nowMs = SystemClock.elapsedRealtime(),
                        buffering = stallState == "buffering",
                        playbackRequested = stallPlaybackRequested,
                        hasFirstFrame = firstFrame,
                        positionMs = stallPositionMs,
                        bufferedAheadMs = stallBufferedAheadMs,
                        seeking = pendingSeekTracker.isPending(sessionId),
                    )) {
                    // Exo's own recovery may win first; session fencing and cancellation keep
                    // both paths from reporting or switching twice. Keep the 60s open backstop.
                    handleRendererFailure(
                        message = PlaybackStallDetector.MESSAGE,
                        severity = if (firstFrame) ExoPlaybackErrorPolicy.EscalationSeverity.TERMINAL
                            else ExoPlaybackErrorPolicy.EscalationSeverity.STARTUP_ENGINE_FAILOVER,
                        hadFirstFrame = firstFrame,
                    )
                    return
                }
                mainHandler.postDelayed(this, 1_000L)
            }
        }
        stallMonitor = monitor
        mainHandler.postDelayed(monitor, 1_000L)
    }

    private fun cancelStallMonitor() {
        stallMonitor?.let(mainHandler::removeCallbacks)
        stallMonitor = null
        stallDetector.reset()
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
        runCatchingLogged(TAG, "Pre-play pause failed", ::logPlayerFailure) {
            renderer.pause(sessionId)
        }
        hostPlaying = false
        stallPlaybackRequested = false
        pendingPlayingState = false
        controlsViewModel.setPlaying(false)
        // Resuming from a saved place: pre-play offers "Resume from ..." and "Start over".
        prePlayResumeMs?.takeIf { it > 0L }?.let { position ->
            controlsViewModel.setPrePlayResume(
                PrePlayResume(position, prePlayResumeDurationMs ?: lastDurationMs.takeIf { it > 0L }),
            )
        }
        prePlayCountdownJob = lifecycleScope.launch {
            controlsViewModel.setPrePlayLaunching(true)
            for (seconds in com.playbridge.player.preplay.PREPLAY_COUNTDOWN_SECONDS downTo 1) {
                if (session?.sessionId != sessionId) return@launch
                controlsViewModel.setPrePlayCountdown(seconds)
                delay(1_000L)
            }
            if (session?.sessionId != sessionId) return@launch
            controlsViewModel.setPrePlayCountdown(0)
            controlsViewModel.setPrePlay(null, clearOnlineSubs = false)
            showTransition(R.string.player_starting, force = true)
            stallPlaybackRequested = true
            pendingPlayingState = true
            runCatchingLogged(TAG, "Pre-play countdown play failed", ::logPlayerFailure) {
                rendererService?.play(sessionId)
            }
        }
    }

    private fun startPrePlayNow() {
        val currentSession = session ?: return
        cancelPrePlayCountdown()
        controlsViewModel.setPrePlayCountdown(0)
        controlsViewModel.setPrePlay(null, clearOnlineSubs = false)
        showTransition(R.string.player_starting, force = true)
        stallPlaybackRequested = true
        pendingPlayingState = true
        runCatchingLogged(TAG, "Pre-play skip play failed", ::logPlayerFailure) {
            rendererService?.play(currentSession.sessionId)
        }
    }

    /** Pre-play "Start over": drop the saved place, begin at 0:00 and play. */
    private fun startPrePlayOver() {
        val currentSession = session ?: return
        cancelPrePlayCountdown()
        prePlayResumeMs = null
        controlsViewModel.setPrePlayResume(null)
        seekForSession(0L, currentSession.sessionId)
        startPrePlayNow()
    }

    private fun cancelPrePlayCountdown() {
        prePlayCountdownJob?.cancel()
        prePlayCountdownJob = null
    }

    /** Paused UI: full controls normally; with [quiet] (media key, nothing else on screen) only a badge. */
    private fun showPausedUi(quiet: Boolean, phone: PhoneOrigin? = null) {
        if (phone != null) {
            // A phone paused: a confirmation only. The controls stay as they are and nothing takes focus.
            controlsViewModel.showPhonePaused(phone.name)
        } else if (quiet) {
            controlsViewModel.setPlaying(false)
            controlsViewModel.showTransportBadge(TransportBadge.PAUSED)
        } else {
            controlsViewModel.showControls(full = true, playing = false)
        }
    }

    /** [phone] needs nothing extra: playing clears the phone's "Paused from ..." pill. */
    private fun showPlayingUi(quiet: Boolean, phone: PhoneOrigin? = null) {
        controlsViewModel.setPlaying(true)
        if (quiet) controlsViewModel.showTransportBadge(TransportBadge.PLAYING)
    }

    private fun handleControl(
        command: String?,
        subtitleResource: SubtitleResource? = null,
        subtitleRequestId: String? = null,
        subtitleConnectionId: Long = 0L,
        fromParty: Boolean = false,
        quiet: Boolean = false,
        phone: PhoneOrigin? = null,
    ) {
        // Mode switches must also finish a host still waiting for its renderer to bind.
        if (command == "stop") {
            finishPlaybackSession()
            return
        }
        if (currentMediaKind == MediaKind.IMAGE) {
            if (command == "add_subtitle") {
                ServerService.completeLateSubtitleCommand(
                    subtitleConnectionId, subtitleRequestId, false, "unsupported",
                )
                return
            }
            val currentSession = session ?: return
            when (command) {
                "play" -> {
                    if (lastDurationMs > 0L) {
                        imageTimerRunning = true
                        hostPlaying = true
                        showPlayingUi(quiet, phone)
                        stillWatchingController.onPlayingChanged(true)
                        startImageTimer(currentSession.sessionId)
                    }
                }
                "pause" -> {
                    imageTimerRunning = false
                    hostPlaying = false
                    imageTimerJob?.cancel()
                    stillWatchingController.onPlayingChanged(false)
                    showPausedUi(quiet, phone)
                }
                "toggle" -> handleControl(if (imageTimerRunning) "pause" else "play", quiet = quiet, phone = phone)
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
        // In a party, play, pause and seek from this TV go to everyone and come back
        // through applyPartyCommand.
        if (partyItem != null && !fromParty && forwardToParty(command)) return

        val currentSession = session ?: run {
            if (command == "add_subtitle") ServerService.completeLateSubtitleCommand(
                subtitleConnectionId, subtitleRequestId, false, "no_active_playback",
            )
            return
        }
        val renderer = rendererService ?: run {
            if (command == "add_subtitle") ServerService.completeLateSubtitleCommand(
                subtitleConnectionId, subtitleRequestId, false, "no_active_playback",
            )
            return
        }
        when {
            command == "play" -> {
                hostPlaying = true
                stallPlaybackRequested = true
                pendingPlayingState = true
                showPlayingUi(quiet, phone)
                runCatchingLogged(TAG, "Control play failed", ::logPlayerFailure) {
                    renderer.play(currentSession.sessionId)
                }
            }
            command == "pause" -> {
                hostPlaying = false
                stallPlaybackRequested = false
                pendingPlayingState = false
                runCatchingLogged(TAG, "Control pause failed", ::logPlayerFailure) {
                    renderer.pause(currentSession.sessionId)
                }
                showPausedUi(quiet, phone)
                progressManager.saveProgress()
            }
            command == "toggle" -> {
                if (hostPlaying) {
                    hostPlaying = false
                    stallPlaybackRequested = false
                    pendingPlayingState = false
                    runCatchingLogged(TAG, "Control toggle pause failed", ::logPlayerFailure) {
                        renderer.pause(currentSession.sessionId)
                    }
                    showPausedUi(quiet, phone)
                    progressManager.saveProgress()
                } else {
                    hostPlaying = true
                    stallPlaybackRequested = true
                    pendingPlayingState = true
                    showPlayingUi(quiet, phone)
                    runCatchingLogged(TAG, "Control toggle play failed", ::logPlayerFailure) {
                        renderer.play(currentSession.sessionId)
                    }
                }
            }
            command?.startsWith("seek_to:") == true -> command
                .removePrefix("seek_to:")
                .toLongOrNull()
                ?.let { position ->
                    seekForSession(position, currentSession.sessionId)
                    // A phone's seek shows the strip with its target, never the controls.
                    if (phone != null) controlsViewModel.showPhoneSeek(phone.name, position) else controlsViewModel.showSeekUI()
                }
            command?.startsWith("audio_track:") == true -> {
                val trackId = command.removePrefix("audio_track:")
                runCatchingLogged(TAG, "Remote setAudioTrack failed", ::logPlayerFailure) {
                    renderer.setAudioTrack(trackId, currentSession.sessionId)
                }
                phone?.let {
                    val name = controlsViewModel.controlsState.value.audioTracks.firstOrNull { t -> t.id == trackId }?.name
                    controlsViewModel.showPhoneCard(phoneChangeCard(PhoneChangeKind.AUDIO, audioChangeTitle(name), it.name))
                }
            }
            command?.startsWith("sub_track:") == true -> {
                val trackId = command.removePrefix("sub_track:")
                if (trackId in externalSubtitleUrls) {
                    selectExternalSubtitle(trackId, renderer, currentSession.sessionId)
                } else {
                    selectRendererSubtitle(trackId, renderer, currentSession.sessionId)
                }
                phone?.let { announceSubtitleChange(it, trackId) }
            }
            command?.startsWith("add_subtitle:") == true -> {
                val url = command.removePrefix("add_subtitle:")
                if (url.isNotBlank()) {
                    selectExternalSubtitle(url, renderer, currentSession.sessionId)
                    phone?.let { announceSubtitleChange(it, url) }
                }
            }
            command == "add_subtitle" -> {
                if (subtitleResource != null) {
                    completeLateSubtitleRequest(subtitleResource.url, false)
                    lateSubtitleResources[subtitleResource.url] = subtitleResource
                    pendingLateSubtitleRequests[subtitleResource.url] = LateSubtitleRequest(
                        subtitleConnectionId, subtitleRequestId,
                    )
                    selectExternalSubtitle(subtitleResource.url, renderer, currentSession.sessionId)
                    phone?.let { announceSubtitleChange(it, subtitleResource.url) }
                } else {
                    ServerService.completeLateSubtitleCommand(
                        subtitleConnectionId, subtitleRequestId, false, "invalid_command",
                    )
                }
            }
            command?.startsWith("speed:") == true -> command
                .removePrefix("speed:")
                .toFloatOrNull()
                ?.let { speed ->
                    controlsViewModel.setPlaybackSpeed(speed)
                    syncPlaybackContext()
                    broadcastPlayerSettings()
                    phone?.let {
                        controlsViewModel.showPhoneCard(phoneChangeCard(PhoneChangeKind.SPEED, speedChangeTitle(speed), it.name))
                    }
                }
            command?.startsWith("video_quality:") == true -> {
                val value = command.removePrefix("video_quality:")
                val maxHeight = value.takeUnless { it.equals("auto", true) }?.toIntOrNull() ?: 0
                controlsViewModel.setVideoQuality(maxHeight)
                runCatchingLogged(TAG, "Remote setVideoQuality failed", ::logPlayerFailure) {
                    renderer.setVideoQuality(maxHeight, currentSession.sessionId)
                }
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
                    phone?.let {
                        val offset = controlsViewModel.controlsState.value.subtitleDelayMs
                        controlsViewModel.showPhoneCard(
                            phoneChangeCard(PhoneChangeKind.SUBTITLES, subtitleOffsetTitle(offset), it.name),
                        )
                    }
                }
            command?.startsWith("scaling:") == true -> {
                val mode = command.removePrefix("scaling:")
                runCatchingLogged(TAG, "Remote setVideoScaling failed", ::logPlayerFailure) {
                    renderer.setVideoScaling(mode, currentSession.sessionId)
                }
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
                runCatchingLogged(TAG, "Remote setLooping failed", ::logPlayerFailure) {
                    renderer.setLooping(enabled, currentSession.sessionId)
                }
                controlsViewModel.setLooping(enabled)
                syncPlaybackContext()
                FileLogger.i(
                    TAG,
                    "Remote loop changed to $enabled and was added to playback context",
                )
                broadcastPlayerSettings()
            }
            command == "seek_back" -> seekRelative(-10_000L, currentSession.sessionId, phone)
            command == "seek_forward" -> seekRelative(10_000L, currentSession.sessionId, phone)
            command == "next" -> lifecycleScope.launch {
                val before = playbackCoordinator.index
                playbackCoordinator.next()
                announceQueueChange(phone, before)
            }
            command == "previous" -> lifecycleScope.launch {
                val before = playbackCoordinator.index
                playbackCoordinator.previous()
                announceQueueChange(phone, before)
            }
        }
        refreshKeepScreenOn()
    }

    private fun seekRelative(deltaMs: Long, sessionId: Long, phone: PhoneOrigin? = null) {
        val target = (lastPositionMs + deltaMs).coerceAtLeast(0L)
        val bounded = if (lastDurationMs > 0L) target.coerceAtMost(lastDurationMs) else target
        seekForSession(bounded, sessionId)
        if (phone != null) controlsViewModel.showPhoneSeek(phone.name, bounded) else controlsViewModel.showSeekUI()
    }

    /** A phone chose subtitle track [trackId] (a renderer track or an external file): name it on the TV. */
    private fun announceSubtitleChange(phone: PhoneOrigin, trackId: String) {
        val off = trackId == "off" || trackId == "none"
        val name = if (off) null else {
            subtitleTracks.firstOrNull { it.id == trackId }?.label
                ?: if (trackId in externalSubtitleUrls) externalSubtitleLabel(trackId) else null
        }
        controlsViewModel.showPhoneCard(
            phoneChangeCard(PhoneChangeKind.SUBTITLES, subtitleChangeTitle(name, off), phone.name),
        )
    }

    override fun getMediaDuration(): Long = lastDurationMs

    override fun getCurrentPosition(): Long = lastPositionMs

    override fun seekTo(position: Long) {
        if (partyItem != null && TvTogether.localSeek(position)) return
        session?.let { seekForSession(position, it.sessionId) }
    }

    private fun beginPartySession(requestIntent: Intent) {
        val item = requestIntent.getStringExtra(ServerService.EXTRA_WATCH_TOGETHER_ITEM)
        partyItem = item
        controlsViewModel.setPartySession(item != null)
        // A new cast: who sent it feeds "From <phone>", and nothing a phone did before applies.
        controlsViewModel.setSenderName(requestIntent.getStringExtra(ServerService.EXTRA_SENDER_NAME))
        controlsViewModel.clearPhoneConfirmations()
        partyLoadedReported = false
        partyReady = false
        partyLive = null
        mainHandler.removeCallbacks(reportPartyLoadedAnyway)
        if (item != null) {
            TvTogether.attach(partyPlayer, item)
        } else {
            TvTogether.detach(partyPlayer)
            TvTogether.onOtherPlayback()
        }
        refreshBookmarkState()
    }

    /**
     * Bookmark button state. It is shown only when this item is already in the stored history,
     * which is exactly when [HistoryStore.toggleFavorite] can act on it: casts sent with
     * skip_history have no [currentHistoryId], and with history disabled no entry is written.
     * It is hidden during a Watch Together party and for photos.
     */
    private fun refreshBookmarkState() {
        val id = currentHistoryId
        val bookmarked = if (id == null || partyItem != null || currentMediaKind == MediaKind.IMAGE) {
            null
        } else {
            historySnapshot[id]
        }
        controlsViewModel.setBookmarkState(
            when (bookmarked) {
                null -> BookmarkState.UNAVAILABLE
                true -> BookmarkState.ON
                false -> BookmarkState.OFF
            },
        )
    }

    private fun toggleBookmark() {
        val id = currentHistoryId ?: return
        if (partyItem != null) return
        val bookmarked = historySnapshot[id] ?: return
        val nowBookmarked = !bookmarked
        lifecycleScope.launch {
            historyStore.toggleFavorite(id)
            controlsViewModel.showNotice(bookmarkNoticeText(nowBookmarked), emphasis = nowBookmarked)
        }
    }

    /**
     * Tells the party this TV is ready, with the video's length so the party stops at
     * its end. A live stream has no end, so it sends none; when the renderer never says
     * whether it's live, [force] sends none either.
     */
    private fun reportPartyLoaded(force: Boolean) {
        val item = partyItem ?: return
        if (partyLoadedReported || !partyReady) return
        // Not told in time whether it's live: a video with a known length isn't.
        val live = partyLive ?: if (force) lastDurationMs <= 0 else return
        partyLoadedReported = true
        mainHandler.removeCallbacks(reportPartyLoadedAnyway)
        TvTogether.onLoaded(item, if (live) 0L else lastDurationMs)
    }

    /** "Leave party" on the controls: the video keeps playing here as an ordinary video. */
    private fun leaveParty() {
        TvTogether.leave()
        TvTogether.detach(partyPlayer)
        partyItem = null
        controlsViewModel.setPartySession(false)
        refreshBookmarkState()
    }

    private fun forwardToParty(command: String?): Boolean = when {
        command == "play" -> TvTogether.localPlay()
        command == "pause" -> TvTogether.localPause()
        command == "toggle" -> if (hostPlaying) TvTogether.localPause() else TvTogether.localPlay()
        command?.startsWith("seek_to:") == true ->
            command.removePrefix("seek_to:").toLongOrNull()?.let(TvTogether::localSeek) == true
        command == "seek_back" -> TvTogether.localSeek(lastPositionMs - 10_000L)
        command == "seek_forward" -> TvTogether.localSeek(lastPositionMs + 10_000L)
        else -> false
    }

    private fun applyPartyCommand(command: TogetherPlayerCommand) {
        if (partyItem == null) return
        val currentSession = session ?: return
        when (command) {
            TogetherPlayerCommand.Play -> handleControl("play", fromParty = true)
            TogetherPlayerCommand.Pause -> handleControl("pause", fromParty = true)
            // Playing a live stream again starts at the live point, not where it paused.
            TogetherPlayerCommand.GoLive -> {
                runCatchingLogged(TAG, "Party live seek failed", ::logPlayerFailure) {
                    rendererService?.seekToLive(currentSession.sessionId)
                }
                handleControl("play", fromParty = true)
            }
            is TogetherPlayerCommand.Seek -> seekForSession(command.positionMs, currentSession.sessionId)
            // Speed nudges stay out of the controls and saved settings.
            is TogetherPlayerCommand.Rate -> runCatchingLogged(TAG, "Party speed failed", ::logPlayerFailure) {
                rendererService?.setPlaybackSpeed(command.rate, currentSession.sessionId)
            }
            // TvTogether starts a new video through ServerService.
            is TogetherPlayerCommand.Load -> Unit
        }
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
        if (key == "home") {
            leavePlayerToReceiverIdle()
            return
        }
        if (key != "back" &&
            controlsViewModel.controlsState.value.playbackTransitionMessage != null
        ) return
        when (key) {
            "back" -> {
                handleBackPressed()
            }
            // The phone's on-screen remote behaves exactly like the TV remote: the same key
            // goes through the same dispatch (Compose focus first, then onKeyDown).
            "dpad_center" -> injectRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER)
            "dpad_left" -> injectRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT)
            "dpad_right" -> injectRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            "dpad_up" -> injectRemoteKey(KeyEvent.KEYCODE_DPAD_UP)
            "dpad_down" -> injectRemoteKey(KeyEvent.KEYCODE_DPAD_DOWN)
        }
    }

    private fun injectRemoteKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        // Through the decor view, which hands the event to this activity's own
        // key dispatch exactly as a physical key press would arrive.
        val decor = window.decorView
        decor.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        decor.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun jumpChapter(forward: Boolean): Boolean {
        val currentSession = session ?: return false
        val state = controlsViewModel.controlsState.value
        if (!canJumpChapters(
                state = state,
                isStillWatchingPrompting = stillWatchingController.state.value.isPrompting,
                isImageMedia = currentMediaKind == MediaKind.IMAGE,
            )
        ) {
            return false
        }
        val target = chapterJumpTarget(state.chapters, lastPositionMs, forward = forward)
        if (target == null) {
            if (forward) {
                controlsViewModel.showSeekUI()
                controlsViewModel.showChapterOsd(LAST_CHAPTER_OSD)
                return true
            }
            return false
        }
        seekToChapterTarget(target, currentSession.sessionId)
        return true
    }

    private fun jumpToChapterIndex(index: Int) {
        val currentSession = session ?: return
        val state = controlsViewModel.controlsState.value
        val chapter = state.chapters.getOrNull(index) ?: return
        val target = ChapterTarget(index + 1, chapter)
        seekForSession(target.chapter.startMs, currentSession.sessionId)
        controlsViewModel.hideOverlay()
        controlsViewModel.showChapterOsd(chapterLabel(target))
    }

    private fun seekToChapterTarget(target: ChapterTarget, sessionId: Long) {
        seekForSession(target.chapter.startMs, sessionId)
        controlsViewModel.showSeekUI()
        controlsViewModel.showChapterOsd(chapterLabel(target))
    }

    /**
     * Applies the player key map (ui/player/PlayerKeyMap.kt, from ControlsRemoteMap.dc.html) to a
     * key Compose did not use. Returns true when the key was handled here.
     */
    private fun handlePlayerKey(key: RemoteKey, repeatCount: Int = 0): Boolean {
        val state = controlsViewModel.controlsState.value
        if (state.playbackTransitionMessage != null || state.playbackError != null) return true
        val stillWatchingPrompting = stillWatchingController.state.value.isPrompting
        val isMediaKey = key == RemoteKey.PLAY_PAUSE || key == RemoteKey.PLAY || key == RemoteKey.PAUSE
        // Pre-play and the Still Watching prompt own the D-pad; only media keys reach the player.
        if ((state.prePlayMetadata != null || stillWatchingPrompting) && !isMediaKey) return false
        val now = SystemClock.elapsedRealtime()
        val action = resolveKeyAction(
            key,
            KeyContext(
                surface = state.controlsSurface(),
                repeatCount = repeatCount,
                backArmed = now < backArmedUntilMs,
                confirmBack = confirmBackToStop(state, stillWatchingPrompting),
                isImage = currentMediaKind == MediaKind.IMAGE,
                quietAllowed = state.prePlayMetadata == null && !stillWatchingPrompting,
            ),
        )
        return applyKeyAction(action, state)
    }

    /** Back on a bare video or music needs a second press; pre-play, prompts, photos and loading leave at once. */
    private fun confirmBackToStop(state: com.playbridge.player.ui.player.PlayerControlsState, stillWatchingPrompting: Boolean) =
        state.prePlayMetadata == null &&
            !stillWatchingPrompting &&
            state.playbackTransitionMessage == null &&
            state.playbackError == null &&
            !state.isBuffering &&
            currentMediaKind != MediaKind.IMAGE

    private fun applyKeyAction(action: KeyAction, state: com.playbridge.player.ui.player.PlayerControlsState): Boolean {
        when (action) {
            is KeyAction.Seek -> if (session != null) controlsViewModel.handleScrubbing(action.deltaMs)
            is KeyAction.StepItem -> stepPhoto(action.delta, state)
            KeyAction.PauseAndOpenControls ->
                if (state.isPlaying) handleControl("pause") else controlsViewModel.showControls(full = true)
            KeyAction.OpenControls -> controlsViewModel.showControls(full = true)
            is KeyAction.Transport -> handleControl(action.command, quiet = action.quiet)
            KeyAction.HideSeekBar, KeyAction.HideControls -> controlsViewModel.hideControls()
            KeyAction.ClosePanel -> controlsViewModel.hideOverlay()
            KeyAction.ArmBack -> {
                backArmedUntilMs = SystemClock.elapsedRealtime() + BACK_CONFIRM_WINDOW_MS
                controlsViewModel.showNotice("Press Back again to stop", durationMs = BACK_CONFIRM_WINDOW_MS)
            }
            KeyAction.ExitPlayer -> {
                logPlaybackContextCheckpoint("Back finishing player")
                if (partyItem != null) TvTogether.leave()
                finishingSession = true
                finish()
            }
            KeyAction.Ignore -> Unit
            KeyAction.PassThrough -> return false
        }
        return true
    }

    /** Photos: Left and Right go to the previous or next photo in the queue, never past either end. */
    private fun stepPhoto(delta: Int, state: com.playbridge.player.ui.player.PlayerControlsState) {
        val target = state.playlistIndex + delta
        if (target < 0 || target >= state.playlistItems.size) return
        lifecycleScope.launch {
            if (delta > 0) playbackCoordinator.next() else playbackCoordinator.previous()
        }
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

    /** Remote Home stops this activity and shows the receiver idle screen. System Home is not available here. */
    private fun leavePlayerToReceiverIdle() {
        if (finishingSession) return
        logPlaybackContextCheckpoint("Home finishing player")
        finishingSession = true
        runCatching {
            startActivity(
                Intent(this, com.playbridge.player.MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                },
            )
        }
        finish()
    }

    private fun handleBackPressed() {
        // Back is decided by the key map: close a panel, then hide the controls, then (on a bare
        // video) ask for a second press within BACK_CONFIRM_WINDOW_MS before leaving.
        val state = controlsViewModel.controlsState.value
        val stillWatchingPrompting = stillWatchingController.state.value.isPrompting
        val action = resolveKeyAction(
            RemoteKey.BACK,
            KeyContext(
                surface = state.controlsSurface(),
                backArmed = SystemClock.elapsedRealtime() < backArmedUntilMs,
                confirmBack = confirmBackToStop(state, stillWatchingPrompting),
            ),
        )
        applyKeyAction(action, state)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        stillWatchingController.onUserActivity()
        if (keyCode != KeyEvent.KEYCODE_BACK && backArmedUntilMs != 0L) {
            // Any other key cancels a pending "Press Back again to stop".
            backArmedUntilMs = 0L
            controlsViewModel.clearNotice()
        }
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
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                handlePlayerKey(RemoteKey.PLAY_PAUSE)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                handlePlayerKey(RemoteKey.PLAY)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                handlePlayerKey(RemoteKey.PAUSE)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (jumpChapter(forward = true)) return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (jumpChapter(forward = false)) return true
            }
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                val state = controlsViewModel.controlsState.value
                when (
                    resolveChapterKeyAction(
                        keyCode = keyCode,
                        state = state,
                        isStillWatchingPrompting = stillWatchingController.state.value.isPrompting,
                        isImageMedia = currentMediaKind == MediaKind.IMAGE,
                    )
                ) {
                    ChapterKeyAction.NEXT_CHAPTER -> {
                        jumpChapter(forward = true)
                        return true
                    }
                    ChapterKeyAction.PREV_CHAPTER -> {
                        jumpChapter(forward = false)
                        return true
                    }
                    ChapterKeyAction.SEEK_FORWARD_FALLBACK -> {
                        session?.let { seekRelative(10_000L, it.sessionId) }
                        return true
                    }
                    ChapterKeyAction.SEEK_BACKWARD_FALLBACK -> {
                        session?.let { seekRelative(-10_000L, it.sessionId) }
                        return true
                    }
                    ChapterKeyAction.NONE -> Unit
                }
            }
            // OK, Up, Down, Left, Right: the key map decides. When controls or a panel are open
            // Compose has already used the key (focus move, button press); what reaches here is
            // only what it did not use.
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> if (handlePlayerKey(RemoteKey.OK)) return true
            KeyEvent.KEYCODE_DPAD_UP -> if (handlePlayerKey(RemoteKey.UP)) return true
            KeyEvent.KEYCODE_DPAD_DOWN -> if (handlePlayerKey(RemoteKey.DOWN)) return true
            KeyEvent.KEYCODE_DPAD_LEFT -> if (handlePlayerKey(RemoteKey.LEFT, event.repeatCount)) return true
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (handlePlayerKey(RemoteKey.RIGHT, event.repeatCount)) return true
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
                runCatchingLogged(TAG, "Renderer detach on surface destroy failed", ::logPlayerFailure) {
                    rendererService?.detachSurface(currentSession.sessionId)
                }
            }
        }
    }

    private fun newRendererSurfaceView(): SurfaceView = SurfaceView(this).also { view ->
        view.holder.addCallback(surfaceCallback)
    }

    private fun showTransition(@StringRes message: Int, force: Boolean = false) {
        if (force || controlsViewModel.controlsState.value.prePlayMetadata == null) {
            val text = if (message == R.string.player_failed) sessionCoordinator.current().error else null
            controlsViewModel.showPlaybackTransition(text ?: getString(message))
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
            runCatchingLogged(TAG, "Could not reset surface frame rate", ::logPlayerFailure) {
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
        /** How long a ready party video waits to hear whether it's live. */
        private const val PARTY_LIVE_WAIT_MS = 2_000L
        private const val IMAGE_LOAD_TIMEOUT_MS = 20_000L
        private const val FAILURE_VISIBLE_MS = 600L
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        private val activeHostCount = java.util.concurrent.atomic.AtomicInteger(0)

        internal fun isActive(): Boolean = activeHostCount.get() > 0
    }
}

private val RendererKind.engineId: String
    get() = name.lowercase()
