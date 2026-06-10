package com.playbridge.sender.cast

import android.content.Context
import android.util.Log
import com.playbridge.sender.cast.dlna.AvTransportClient
import com.playbridge.sender.cast.dlna.DlnaCastTarget
import com.playbridge.sender.cast.dlna.DlnaProxyHolder
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the active cast session — the single seam every screen sends
 * playback through, regardless of transport.
 *
 * Responsibilities:
 *  - Holds the **active target**: the selected DLNA renderer ([DlnaCastTarget]) or, when
 *    none is selected, the connected native receiver ([NativeCastTarget]). One at a time.
 *  - Owns the DLNA target lifecycle (moved out of ConnectionViewModel so a cast survives
 *    Activity/ViewModel death).
 *  - Starts/stops [CastSessionService] (foreground service) while a session is live, so
 *    the WebSocket session, [com.playbridge.sender.connection.TvQueueCoordinator] episode
 *    top-ups, and the DLNA local proxy all survive screen-off and app backgrounding.
 *
 * A session is considered active while a DLNA renderer is selected, or while the native
 * receiver is in the "player" context with a live (or in-flight) connection.
 */
class CastSessionManager(
    private val context: Context,
    private val webSocketClient: WebSocketClient,
    private val connectionCoordinator: ConnectionCoordinator,
    private val scope: CoroutineScope,
) {
    private val TAG = "CastSessionManager"

    // --- DLNA target (third-party renderer; no WS session) ---
    private val _activeDlnaTarget = MutableStateFlow<TvDevice?>(null)
    val activeDlnaTarget: StateFlow<TvDevice?> = _activeDlnaTarget.asStateFlow()

    private val _dlnaStatus = MutableStateFlow<PlaybackStatus?>(null)
    val dlnaStatus: StateFlow<PlaybackStatus?> = _dlnaStatus.asStateFlow()

    private val _dlnaMediaTitle = MutableStateFlow<String?>(null)
    val dlnaMediaTitle: StateFlow<String?> = _dlnaMediaTitle.asStateFlow()

    /** Library identity of what's loaded on the DLNA target (null = untracked content).
     *  Consumed by PlaybackProgressTracker so DLNA plays update the watchlist too. */
    private val _dlnaNowPlayingMeta = MutableStateFlow<playbridge.VisualMetadata?>(null)
    val dlnaNowPlayingMeta: StateFlow<playbridge.VisualMetadata?> = _dlnaNowPlayingMeta.asStateFlow()

    private val _dlnaCast = MutableStateFlow<DlnaCastTarget?>(null)
    private var dlnaStatusJob: Job? = null

    /**
     * Fires whenever the user interrupts DLNA playback — an explicit stop, or a new
     * user-initiated cast replacing what's playing. The DLNA episode queue
     * ([com.playbridge.sender.connection.DlnaQueueCoordinator]) listens and abandons
     * its plan so it never auto-advances over content the user chose.
     */
    private val _dlnaInterrupts = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val dlnaInterrupts: SharedFlow<Unit> = _dlnaInterrupts.asSharedFlow()

    // --- Native target (exists while the WS session is authenticated) ---
    private val _nativeTarget = MutableStateFlow<NativeCastTarget?>(null)

    /**
     * The transport behind "Cast": the selected DLNA renderer if any, else the connected
     * native receiver, else null. UI gates features on [CastTarget.capabilities].
     */
    val activeTarget: StateFlow<CastTarget?> =
        combine(_dlnaCast, _nativeTarget) { dlna, native -> dlna ?: native }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val isDlnaActive: Boolean get() = _dlnaCast.value != null

    /** True while a cast session should keep the process alive (drives the FGS). */
    val hasActiveSession: StateFlow<Boolean> = combine(
        _activeDlnaTarget,
        webSocketClient.connectionState,
        connectionCoordinator.tvActiveContext,
    ) { dlna, state, ctx ->
        dlna != null || (
            ctx == "player" && (
                state is WebSocketClient.ConnectionState.Connected ||
                    state is WebSocketClient.ConnectionState.Connecting
                )
            )
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** What the session notification shows. */
    data class SessionInfo(val deviceName: String, val title: String?)

    val sessionInfo: StateFlow<SessionInfo> = combine(
        _activeDlnaTarget,
        webSocketClient.connectionState,
        connectionCoordinator.tvPlayback,
        _dlnaMediaTitle,
    ) { dlna, state, playback, dlnaTitle ->
        val device = dlna?.name
            ?: (state as? WebSocketClient.ConnectionState.Connected)?.serverName
            ?: "TV"
        SessionInfo(deviceName = device, title = if (dlna != null) dlnaTitle else playback?.title)
    }.stateIn(scope, SharingStarted.Eagerly, SessionInfo("TV", null))

    init {
        // Mirror the WS session into a NativeCastTarget.
        scope.launch {
            webSocketClient.connectionState.collect { state ->
                val current = _nativeTarget.value
                when (state) {
                    is WebSocketClient.ConnectionState.Connected -> {
                        if (current == null || current.name != state.serverName) {
                            _nativeTarget.value = NativeCastTarget(
                                id = state.serverName,
                                name = state.serverName,
                                webSocketClient = webSocketClient,
                                coordinator = connectionCoordinator,
                            )
                        }
                    }
                    // Keep the target through Connecting/Retrying/WaitingForApproval so the
                    // active target doesn't flap mid-session; drop it on terminal states.
                    is WebSocketClient.ConnectionState.Disconnected,
                    is WebSocketClient.ConnectionState.Error,
                    is WebSocketClient.ConnectionState.AuthFailed,
                    is WebSocketClient.ConnectionState.PairingDenied,
                    is WebSocketClient.ConnectionState.PinMismatch,
                    -> _nativeTarget.value = null
                    else -> Unit
                }
            }
        }

        // Drive the foreground service from the session state. collectLatest: a session
        // re-appearing within the grace window cancels the pending stop, so transient
        // disconnects (and the Retrying dance) don't bounce the service.
        scope.launch {
            hasActiveSession.collectLatest { active ->
                if (active) {
                    // From the background this can throw (FGS start restrictions); sessions
                    // begin from a user action in the foreground, so this is belt-and-braces.
                    runCatching { CastSessionService.start(context) }
                        .onFailure { Log.w(TAG, "Could not start cast session service: ${it.message}") }
                } else {
                    delay(STOP_GRACE_MS)
                    CastSessionService.stop(context)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // DLNA target lifecycle (moved from ConnectionViewModel)
    // ------------------------------------------------------------------

    /** Select a DLNA renderer as the active cast target (drops any native session). */
    fun selectDlnaTarget(device: TvDevice) {
        val controlUrl = device.controlUrl ?: return
        webSocketClient.disconnect() // a single target is active at a time
        dlnaStatusJob?.cancel()
        _dlnaCast.value?.release()
        val target = DlnaCastTarget(
            id = device.uuid,
            name = device.name,
            avTransport = AvTransportClient(controlUrl, DlnaProxyHolder.httpClient),
            proxy = DlnaProxyHolder.proxy(context),
        )
        _dlnaCast.value = target
        _activeDlnaTarget.value = device
        dlnaStatusJob = scope.launch { target.status().collect { _dlnaStatus.value = it } }
        Log.d(TAG, "Active DLNA target: ${device.name} ($controlUrl)")
    }

    fun clearDlnaTarget() {
        dlnaStatusJob?.cancel()
        dlnaStatusJob = null
        _dlnaCast.value?.release()
        _dlnaCast.value = null
        _dlnaStatus.value = null
        _dlnaMediaTitle.value = null
        _dlnaNowPlayingMeta.value = null
        _activeDlnaTarget.value = null
    }

    /** Cast a media item to the active DLNA target (user-initiated). No-op if none selected. */
    fun playOnDlna(media: MediaItem) {
        _dlnaInterrupts.tryEmit(Unit) // a user cast supersedes any episode-queue plan
        loadOnDlna(media)
    }

    /** Episode-queue advance — same load, but does NOT interrupt the queue plan. */
    internal fun playOnDlnaFromQueue(media: MediaItem) = loadOnDlna(media)

    private fun loadOnDlna(media: MediaItem) {
        val target = _dlnaCast.value ?: return
        _dlnaMediaTitle.value = media.title
        _dlnaNowPlayingMeta.value = media.visualMetadata
        scope.launch { runCatching { target.load(media) }.onFailure { Log.w(TAG, "DLNA load failed: ${it.message}") } }
    }

    fun dlnaPlay() {
        _dlnaCast.value?.let { t -> scope.launch { runCatching { t.play() } } }
    }

    fun dlnaPause() {
        _dlnaCast.value?.let { t -> scope.launch { runCatching { t.pause() } } }
    }

    fun dlnaStop() {
        _dlnaInterrupts.tryEmit(Unit) // explicit stop ends any episode-queue plan
        _dlnaCast.value?.let { t -> scope.launch { runCatching { t.stop() } } }
    }

    fun dlnaSeek(positionMs: Long) {
        _dlnaCast.value?.let { t -> scope.launch { runCatching { t.seekTo(positionMs) } } }
    }

    // ------------------------------------------------------------------
    // Session control
    // ------------------------------------------------------------------

    /**
     * Mark the native receiver as in the "player" context. The TV only reports context
     * when queried, so every path that sends a play command directly must flip this
     * locally — it drives the session FGS and the NowPlayingBar.
     *
     * Also clears the now-playing identity: callers of this entry point (phone files)
     * have no library identity, and a stale tmdbId would let the progress tracker
     * attribute this content to the previously played title.
     */
    fun notifyNativePlaybackStarted() {
        connectionCoordinator.startLocalPlaybackSession(null, null, null)
    }

    /** Stop playback on the active target and end the session (notification Stop action). */
    fun endSession() {
        val dlna = _dlnaCast.value
        if (dlna != null) {
            scope.launch { runCatching { dlna.stop() } }
            clearDlnaTarget()
            return
        }
        val native = _nativeTarget.value
        if (native != null) {
            scope.launch { runCatching { native.stop() } } // also flips tvActiveContext → "idle"
        } else {
            connectionCoordinator.tvActiveContext.value = "idle"
        }
    }

    companion object {
        /** How long a session may be "inactive" before the FGS is torn down. */
        private const val STOP_GRACE_MS = 3_000L
    }
}
