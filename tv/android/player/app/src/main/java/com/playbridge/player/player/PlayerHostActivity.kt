package com.playbridge.player.player

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.playbridge.player.R
import com.playbridge.player.data.HistoryStore
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.server.ServerService
import com.playbridge.player.ui.player.ActiveOverlay
import com.playbridge.player.ui.player.PlayerControlsOverlay
import com.playbridge.player.ui.player.PlayerControlsViewModel
import com.playbridge.player.ui.player.SettingsTab
import com.playbridge.player.ui.player.UnifiedTrack
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import com.playbridge.shared.protocol.createStatusJson
import com.playbridge.shared.protocol.encodePlayPayloadListJson
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import playbridge.PlayPayload

private data class RendererTrack(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
)

/**
 * Permanent host shell for renderer-process playback.
 *
 * Owns the stable playback shell and swaps isolated renderer services behind it. Legacy
 * player activity source remains temporarily for comparison during stress testing, but those
 * activities are no longer registered runtime entry points.
 */
class PlayerHostActivity : ComponentActivity(), PlaybackProgressSource {
    private lateinit var playerRoot: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var composeView: ComposeView
    private lateinit var transitionView: TextView
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
    private var audioTracks: List<RendererTrack> = emptyList()
    private var subtitleTracks: List<RendererTrack> = emptyList()
    private var currentExternalSubtitleUrl: String? = null
    private val controlsAdapter = object : PlayerEngineAdapter {
        override val isPlaying: Boolean get() = hostPlaying
        override val currentPosition: Long get() = lastPositionMs
        override val duration: Long get() = lastDurationMs
        override val bufferedPosition: Long get() = lastPositionMs
        override val streamInfo: String? get() = null
        override val frameRate: Float get() = 0f
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
        composeView = ComposeView(this)
        transitionView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setText(R.string.player_preparing)
            visibility = View.GONE
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setShadowLayer(dp(6).toFloat(), 0f, dp(2).toFloat(), Color.BLACK)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(210, 18, 18, 18))
            }
            elevation = dp(8).toFloat()
        }
        playerRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
            addView(
                transitionView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.CENTER
                    topMargin = dp(104)
                },
            )
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
        runCatching { unregisterReceiver(controlReceiver) }
        super.onStop()
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
                PlayerControlsOverlay(
                    state = state,
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
                        controlsViewModel.hideOverlay()
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

    private fun selectTrack(track: UnifiedTrack) {
        val currentSession = session ?: return
        val renderer = rendererService ?: return
        when (track.type) {
            "audio" -> runCatching { renderer.setAudioTrack(track.id, currentSession.sessionId) }
            "sub" -> {
                currentExternalSubtitleUrl = null
                controlsViewModel.clearSubtitle()
                runCatching { renderer.setSubtitleTrack(track.id, currentSession.sessionId) }
            }
            "external_sub" -> {
                currentExternalSubtitleUrl = track.id
                runCatching { renderer.setSubtitleTrack("off", currentSession.sessionId) }
                controlsViewModel.loadExternalSubtitle(
                    track.id,
                    playbackCoordinator.playlist.getOrNull(playbackCoordinator.index)?.headers,
                )
                progressManager.updateSelections(externalSubtitleUrl = track.id)
            }
        }
    }

    private fun switchRenderer(target: RendererKind) {
        if (target == rendererKind || target !in setOf(RendererKind.MPV, RendererKind.EXO)) return
        FileLogger.i(TAG, "Switching renderer from $rendererKind to $target")
        cancelStartupWatchdog()
        cancelPrePlayCountdown()
        progressManager.saveProgress()
        val oldKind = rendererKind
        val oldSession = session
        requestedStartPositionMs = lastPositionMs.takeIf { it > 0L }
        pendingSeekTracker.clear()
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
        showTransition(R.string.player_switching)
        bindRenderer()
    }

    override fun onDestroy() {
        finishingSession = true
        cancelStartupWatchdog()
        cancelPrePlayCountdown()
        cancelFailureFinish()
        progressManager.saveProgress()
        val currentSession = session
        if (currentSession != null) {
            runCatching { rendererService?.stop(currentSession.sessionId) }
            runCatching { rendererService?.release(currentSession.sessionId) }
        }
        unbindCurrentRenderer()
        surfaceView.holder.removeCallback(surfaceCallback)
        controlsViewModel.detach()
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
        showTransition(R.string.player_preparing)

        if (targetKind != oldKind) {
            runCatching { oldSession?.let { oldRenderer?.release(it.sessionId) } }
            unbindCurrentRenderer()
            rotateRendererSurface()
            terminateRendererProcess(oldKind)
            bindRenderer()
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
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        currentExternalSubtitleUrl = null
        controlsViewModel.setPlaying(false)
        controlsViewModel.setBuffering(true)
        controlsViewModel.clearSubtitle()
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

    private fun bindRenderer() {
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

        showTransition(if (attemptedRenderers.size > 1) {
            R.string.player_switching
        } else {
            R.string.player_preparing
        })
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
        val rendererSurface = surface ?: run {
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

        val request = Bundle().apply {
            putString(RendererProtocol.KEY_PAYLOAD_JSON, encodePlayPayloadListJson(listOf(payload)))
        }
        preparedSessionId = currentSession.sessionId
        try {
            renderer.prepare(request, currentSession.sessionId)
            renderer.attachSurface(rendererSurface, currentSession.sessionId)
            applyRendererSettings(renderer, currentSession.sessionId)
            renderer.play(currentSession.sessionId)
            // Request resume immediately. MPV retains this until FILE_LOADED, while Exo can
            // accept a seek during preparation; waiting for a rendered-ready event can briefly
            // start long-form library media from zero.
            restoreProgress(currentSession.sessionId)
        } catch (error: RemoteException) {
            preparedSessionId = 0L
            throw error
        }
    }

    private fun applyRendererSettings(renderer: IRendererService, sessionId: Long) {
        val state = controlsViewModel.controlsState.value
        renderer.setPlaybackSpeed(state.playbackSpeed, sessionId)
        renderer.setVideoScaling(state.videoScalingMode, sessionId)
        renderer.setLooping(state.isLooping, sessionId)
        renderer.setAudioBoost(state.isAudioBoostEnabled, sessionId)
        renderer.setSubtitleDelay(state.subtitleDelayMs, sessionId)
    }

    private fun configureProgress(payload: PlayPayload) {
        val items = playbackCoordinator.playlist
        val index = playbackCoordinator.index
        val payloadJson = runCatching { PlayerLauncher.historyPayloadJson(items, index) }
            .getOrDefault("")
        val visualMetadata = payload.visual_metadata
        progressManager.setCurrentMedia(
            url = payload.url,
            title = payload.title,
            contentType = payload.content_type,
            headers = payload.headers,
            payloadJson = payloadJson,
            historyId = PlayerLauncher.historyId(items),
            thumbnailUrl = visualMetadata?.backdrop_url ?: visualMetadata?.poster_url,
            preferredAudioLanguage = payload.preferred_audio_language,
            preferredSubtitleLanguage = payload.preferred_subtitle_language,
            playbackSpeed = controlsViewModel.controlsState.value.playbackSpeed,
        )
        progressManager.recordLanded(requestedStartPositionMs ?: 0L)
        lastSavedPositionMs = 0L
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
        if (shouldShowPrePlay) transitionView.visibility = View.GONE
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
                controlsViewModel.setBuffering(false)
                if (controlsViewModel.controlsState.value.prePlayMetadata != null) {
                    startPrePlayCountdown(currentSession.sessionId)
                }
                if (sessionCoordinator.current().phase != RendererSessionPhase.PLAYING) {
                    scheduleFirstFrameWatchdog(currentSession.sessionId, rendererKind)
                }
            }
            RendererProtocol.EVENT_FIRST_FRAME -> {
                markPlaybackStarted(currentSession.sessionId)
            }
            RendererProtocol.EVENT_STATE -> {
                val state = event.getString(RendererProtocol.KEY_STATE) ?: return
                hostPlaying = state == "playing"
                controlsViewModel.setPlaying(hostPlaying)
                controlsViewModel.setBuffering(state == "buffering")
                if (state == "playing") markPlaybackStarted(currentSession.sessionId)
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
                    lastPositionMs - lastSavedPositionMs >= PROGRESS_SAVE_INTERVAL_MS
                ) {
                    lastSavedPositionMs = lastPositionMs
                    progressManager.saveProgress()
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
                updateVideoSurfaceLayout()
            }
            RendererProtocol.EVENT_TRACKS -> handleTracks(event)
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
        currentExternalSubtitleUrl = null
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
        audioTracks = decode(RendererProtocol.KEY_AUDIO_TRACKS)
        subtitleTracks = decode(RendererProtocol.KEY_SUBTITLE_TRACKS)
        progressManager.updateSelections(
            preferredAudioLanguage = audioTracks.firstOrNull { it.selected }?.language,
            preferredSubtitleLanguage = subtitleTracks.firstOrNull { it.selected }?.language,
        )
        controlsViewModel.updateTracks(
            audio = audioTracks.map { UnifiedTrack(it.id, it.label, it.selected, "audio") },
            subtitles = subtitleTracks.map { UnifiedTrack(it.id, it.label, it.selected, "sub") },
            video = emptyList(),
            currentSubtitleUrl = currentExternalSubtitleUrl,
        )
        broadcastTracks()
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
        val status = org.json.JSONObject().apply {
            put("type", "tracks")
            put("audio", encode(audioTracks, "audio"))
            put("subtitle", encode(subtitleTracks, "sub"))
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
        showTransition(R.string.player_switching)
        bindRenderer()
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
            if (isCurrentSession(sessionId, kind) &&
                sessionCoordinator.current().phase == RendererSessionPhase.PREPARING
            ) {
                handleRendererFailure("$kind renderer did not become ready in time")
            }
        }
        startupWatchdog = watchdog
        mainHandler.postDelayed(watchdog, RENDERER_OPEN_TIMEOUT_MS)
    }

    private fun scheduleFirstFrameWatchdog(sessionId: Long, kind: RendererKind) {
        cancelStartupWatchdog()
        val watchdog = Runnable {
            if (isCurrentSession(sessionId, kind) &&
                sessionCoordinator.current().phase != RendererSessionPhase.PLAYING
            ) {
                handleRendererFailure("$kind renderer did not present a frame in time")
            }
        }
        startupWatchdog = watchdog
        mainHandler.postDelayed(watchdog, RENDERER_FIRST_FRAME_TIMEOUT_MS)
    }

    private fun cancelStartupWatchdog() {
        startupWatchdog?.let(mainHandler::removeCallbacks)
        startupWatchdog = null
    }

    /**
     * Some vendor Surface implementations do not deliver Media3's first-frame callback even
     * though playback is visibly advancing. A renderer "playing" state is therefore also a
     * valid startup confirmation and must disarm the watchdog.
     */
    private fun markPlaybackStarted(sessionId: Long) {
        hostPlaying = true
        controlsViewModel.setPlaying(true)
        sessionCoordinator.markFirstFrame(sessionId)
        if (sessionCoordinator.current().phase == RendererSessionPhase.PLAYING) {
            cancelStartupWatchdog()
            transitionView.visibility = View.GONE
        }
    }

    private fun startPrePlayCountdown(sessionId: Long) {
        if (prePlayCountdownJob?.isActive == true) return
        val renderer = rendererService ?: return
        transitionView.visibility = View.GONE
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
            transitionView.setText(R.string.player_starting)
            transitionView.visibility = View.VISIBLE
            runCatching { rendererService?.play(sessionId) }
        }
    }

    private fun startPrePlayNow() {
        val currentSession = session ?: return
        cancelPrePlayCountdown()
        controlsViewModel.setPrePlayCountdown(0)
        controlsViewModel.setPrePlay(null, clearOnlineSubs = false)
        transitionView.setText(R.string.player_starting)
        transitionView.visibility = View.VISIBLE
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
                runCatching { renderer.setSubtitleTrack(trackId, currentSession.sessionId) }
            }
            command?.startsWith("add_subtitle:") == true -> {
                val url = command.removePrefix("add_subtitle:")
                if (url.isNotBlank()) {
                    currentExternalSubtitleUrl = url
                    runCatching { renderer.setSubtitleTrack("off", currentSession.sessionId) }
                    controlsViewModel.loadExternalSubtitle(
                        url,
                        playbackCoordinator.playlist.getOrNull(playbackCoordinator.index)?.headers,
                    )
                    progressManager.updateSelections(externalSubtitleUrl = url)
                }
            }
            command?.startsWith("speed:") == true -> command
                .removePrefix("speed:")
                .toFloatOrNull()
                ?.let { speed ->
                    controlsViewModel.setPlaybackSpeed(speed)
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
        transitionView.setText(message)
        transitionView.visibility = if (
            force || controlsViewModel.controlsState.value.prePlayMetadata == null
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun resetVideoSurfaceLayout() {
        videoWidth = 0
        videoHeight = 0
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
        private const val RENDERER_FIRST_FRAME_TIMEOUT_MS = 15_000L
        private const val FAILURE_VISIBLE_MS = 600L
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        private val activeHostCount = java.util.concurrent.atomic.AtomicInteger(0)

        internal fun isActive(): Boolean = activeHostCount.get() > 0
    }
}

private val RendererKind.engineId: String
    get() = name.lowercase()
