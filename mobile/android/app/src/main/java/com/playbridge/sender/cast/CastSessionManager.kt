package com.playbridge.sender.cast

import android.content.Context
import android.util.Log
import com.playbridge.sender.cast.browser.BrowserCastTarget
import com.playbridge.sender.cast.dlna.AvTransportClient
import com.playbridge.sender.cast.dlna.DlnaCastTarget
import com.playbridge.sender.cast.dlna.DlnaProxyHolder
import com.playbridge.sender.cast.dlna.RenderingControlClient
import com.playbridge.sender.cast.googlecast.GoogleCastTarget
import com.playbridge.sender.cast.proxy.StreamProxySettingsStore
import com.playbridge.sender.cast.proxy.StreamRouteMode
import com.playbridge.sender.cast.roku.RokuCastTarget
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.connection.ReceiverDiscoveryRepository
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.logging.DebugNetworkLogger
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import com.playbridge.sender.util.ProcessUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the active cast session — the single seam every screen sends
 * playback through, regardless of transport.
 *
 * Responsibilities:
 *  - Holds exactly one **active target**: a selected DLNA, Roku, or Google Cast receiver,
 *    or, when none is selected, the connected native receiver.
 *  - Owns external-target lifecycles so a cast survives Activity/ViewModel death.
 *  - Starts/stops [CastSessionService] (foreground service) while a session is live, so
 *    the WebSocket session, [com.playbridge.sender.connection.TvQueueCoordinator] episode
 *    top-ups, and the DLNA local proxy all survive screen-off and app backgrounding.
 *  - Idle background stand-down (default): after a grace period with nothing casting,
 *    soft-close the native socket and drop the Connected FGS to save battery unless the
 *    user opts into [SettingsRepository.keepTvConnectionInBackground].
 *
 * A session keeps the foreground service alive while external media is loaded, or while a native
 * receiver link/reconnect is live. Merely selecting an idle third-party receiver stays lightweight.
 */
class CastSessionManager(
    private val context: Context,
    private val webSocketClient: WebSocketClient,
    private val connectionCoordinator: ConnectionCoordinator,
    private val scope: CoroutineScope,
    private val connectionStore: com.playbridge.sender.connection.ConnectionStore,
    private val discoveryRepository: ReceiverDiscoveryRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val TAG = "CastSessionManager"

    // ------------------------------------------------------------------
    // Routing intent (authoritative)
    //
    // The single source of truth for *where playback should go*, set ONLY by explicit
    // user action in the device picker. It is deliberately decoupled from the live
    // connection state: connecting to a TV must never change the route, and choosing
    // "This Device" must never be implemented as a disconnect. Screens read [route]
    // instead of inferring a destination from connection state.
    // ------------------------------------------------------------------
    sealed interface Route {
        /** Play on the phone (in-app player). */
        data object ThisDevice : Route
        /** Cast to the saved native (WebSocket) TV receiver. */
        data object NativeTv : Route
        /** Cast to one explicitly selected third-party protocol endpoint. */
        data class External(val endpointKey: EndpointKey, val name: String) : Route
    }

    private val routePrefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    private val ROUTE_KEY = "cast_route" // persisted base route: "this" | "native"

    // External routes are live and deliberately not restored without reconnecting. Only the
    // base (this/native) route is persisted; restart collapses to that safe base route.
    private val _route = MutableStateFlow<Route>(
        when (routePrefs.getString(ROUTE_KEY, "this")) {
            "native" -> Route.NativeTv
            else -> Route.ThisDevice
        }
    )
    val route: StateFlow<Route> = _route.asStateFlow()

    // True while the reconnect supervisor is actively retrying a dropped native link. Keeps
    // the FGS (and thus the process + supervisor) alive across the backoff window so a slow
    // reconnect isn't killed mid-attempt. Declared before hasActiveSession (eager combine).
    private val _reconnecting = MutableStateFlow(false)

    /** True when the active routing intent targets any TV/renderer. */
    val routeTargetsTv: StateFlow<Boolean> =
        _route.map { it !is Route.ThisDevice }.stateIn(scope, SharingStarted.Eagerly, false)

    private fun persistBaseRoute(value: String) {
        routePrefs.edit().putString(ROUTE_KEY, value).apply()
    }

    /** Route phone-local. Keeps an idle native link, but stops any external receiver session. */
    fun selectThisDevice() {
        stopAndClearExternalTarget()
        _route.value = Route.ThisDevice
        persistBaseRoute("this")
        // Leaving the TV route: stop trying to keep the native link alive.
        stopReconnectSupervisor()
    }

    /**
     * Stand the reconnect supervisor down. Runs inside the manager's single-threaded
     * scope: callers are on arbitrary threads (UI picks, external selection), while the
     * supervisor's counters/job are otherwise only touched by scope coroutines.
     */
    private fun stopReconnectSupervisor() {
        scope.launch {
            hasConnectedThisSession = false
            reconnectAttempt = 0
            reconnectJob?.cancel()
            _reconnecting.value = false
            _reconnectStatus.value = null
        }
    }

    /** User picked the native TV receiver. Connecting is a separate concern (caller/Stage B). */
    fun selectNativeRoute() {
        stopAndClearExternalTarget()
        _route.value = Route.NativeTv
        persistBaseRoute("native")
    }

    // --- Third-party target (exactly one of DLNA/Roku/Google Cast) ---
    private val _externalTarget = MutableStateFlow<CastTarget?>(null)
    private val externalTargetSlot = CastTargetSlot()
    private val _activeExternalDevice = MutableStateFlow<TvDevice?>(null)
    val activeExternalDevice: StateFlow<TvDevice?> = _activeExternalDevice.asStateFlow()

    private val _externalStatus = MutableStateFlow<PlaybackStatus?>(null)
    val externalStatus: StateFlow<PlaybackStatus?> = _externalStatus.asStateFlow()

    private val _externalMediaTitle = MutableStateFlow<String?>(null)
    val externalMediaTitle: StateFlow<String?> = _externalMediaTitle.asStateFlow()
    /** True after a successful hand-off begins, including while paused. */
    private val _externalMediaLoaded = MutableStateFlow(false)

    /**
     * User-selected stream route for the next cast packaging (cast sheet chips).
     * Browser packaging maps Direct → Via phone and always uses Via phone for local files.
     */
    private val _preferredStreamRoute = MutableStateFlow(
        StreamProxySettingsStore.load(context).initialRouteMode(),
    )
    val preferredStreamRoute: StateFlow<StreamRouteMode> = _preferredStreamRoute.asStateFlow()

    fun setPreferredStreamRoute(mode: StreamRouteMode) {
        _preferredStreamRoute.value = mode
    }

    /** Effective packaging route used on the last external load (for NOW subtitle). */
    private val _lastEffectiveStreamRoute = MutableStateFlow<StreamRouteMode?>(null)
    val lastEffectiveStreamRoute: StateFlow<StreamRouteMode?> = _lastEffectiveStreamRoute.asStateFlow()

    /**
     * One-shot user-visible notices from external packaging (e.g. proxy fallback).
     * UI should collect and clear via [consumeCastNotice].
     */
    private val _castNotices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val castNotices: SharedFlow<String> = _castNotices.asSharedFlow()

    fun noteEffectiveStreamRoute(mode: StreamRouteMode, proxyFallback: Boolean = false) {
        _lastEffectiveStreamRoute.value = mode
        if (proxyFallback) {
            _castNotices.tryEmit("Remote proxy unavailable — using Via phone")
        }
    }

    /** Library identity of what's loaded on an external target (null = untracked content). */
    private val _externalNowPlayingMeta = MutableStateFlow<playbridge.VisualMetadata?>(null)
    val externalNowPlayingMeta: StateFlow<playbridge.VisualMetadata?> =
        _externalNowPlayingMeta.asStateFlow()

    private var externalStatusJob: Job? = null
    private var externalLoadJob: Job? = null

    /**
     * Fires whenever the user interrupts external playback — an explicit stop, or a new
     * user-initiated cast replacing what's playing. The phone-driven episode queue
     * ([com.playbridge.sender.connection.ExternalQueueCoordinator]) listens and abandons
     * its plan so it never auto-advances over content the user chose.
     */
    private val _externalInterrupts = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val externalInterrupts: SharedFlow<Unit> = _externalInterrupts.asSharedFlow()

    // --- Native target (exists while the WS session is authenticated) ---
    private val _nativeTarget = MutableStateFlow<NativeCastTarget?>(null)
    /**
     * The transport behind "Cast": the selected DLNA, Roku, or Google Cast renderer if any,
     * else the connected native receiver, else null. UI gates features on [CastTarget.capabilities].
     */
    val activeTarget: StateFlow<CastTarget?> =
        combine(_externalTarget, _nativeTarget, _route) { external, native, route ->
            when (route) {
                is Route.External -> external
                Route.NativeTv -> native
                Route.ThisDevice -> null
            }
        }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val nativeDevice: StateFlow<TvDevice?> = connectionStore.tvDevice
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun selectRokuTarget(device: TvDevice) {
        selectExternalTarget(device, RokuCastTarget(device, scope, context))
    }

    fun rokuKeypress(key: String) {
        val rokuKey = when (key) {
            "dpad_up" -> "Up"
            "dpad_down" -> "Down"
            "dpad_left" -> "Left"
            "dpad_right" -> "Right"
            "dpad_center", "key_enter" -> "Select"
            "back" -> "Back"
            "home" -> "Home"
            "volume_up" -> "VolumeUp"
            "volume_down" -> "VolumeDown"
            else -> return
        }
        (_externalTarget.value as? RokuCastTarget)?.sendKeypress(rokuKey)
    }

    fun selectGoogleCastTarget(device: TvDevice) {
        selectExternalTarget(device, GoogleCastTarget(device, scope, context))
    }

    /** Select an approved phone-hosted browser session as the active cast target. */
    fun selectBrowserTarget(device: TvDevice) {
        if (device.resolvedProtocol != CastProtocol.WEB_BROWSER) {
            Log.w(TAG, "selectBrowserTarget ignored for non-browser ${device.resolvedProtocol}")
            return
        }
        val sessionId = device.uuid.ifEmpty {
            Log.w(TAG, "selectBrowserTarget requires session uuid")
            return
        }
        selectExternalTarget(
            device,
            BrowserCastTarget(
                context = context,
                scope = scope,
                sessionId = sessionId,
                name = device.name,
                routeMode = { _preferredStreamRoute.value },
                onRouteResolved = { effective, proxyFallback ->
                    noteEffectiveStreamRoute(effective, proxyFallback)
                },
            ),
        )
    }

    private val externalSessionState: StateFlow<CastSessionState> = combine(
        _activeExternalDevice,
        _externalTarget,
        _externalStatus,
        _externalMediaTitle,
    ) { device, target, status, title ->
        if (device == null || target == null) {
            CastSessionState()
        } else {
            val phase = when (status?.state) {
                PlaybackState.BUFFERING -> SessionPhase.CONNECTING
                PlaybackState.PLAYING, PlaybackState.PAUSED -> SessionPhase.PLAYING
                PlaybackState.ERROR -> SessionPhase.FAILED
                PlaybackState.IDLE, PlaybackState.STOPPED, null ->
                    if (title == null) SessionPhase.SELECTED else SessionPhase.CONNECTED
            }
            CastSessionState(
                phase = phase,
                endpointKey = device.endpointKey,
                device = device,
                targetKind = target.kind,
                capabilities = target.capabilities,
                playback = status,
                mediaTitle = title,
                error = if (phase == SessionPhase.FAILED) "Receiver operation failed" else null,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, CastSessionState())

    private val nativePlaybackState = combine(
        connectionCoordinator.tvActiveContext,
        connectionCoordinator.tvPlayback,
    ) { context, playback -> context to playback }

    val sessionState: StateFlow<CastSessionState> = combine(
        externalSessionState,
        webSocketClient.connectionState,
        nativePlaybackState,
        _route,
        nativeDevice,
    ) { external, nativeState, (nativeContext, nativePlayback), route, device ->
        if (route is Route.External) return@combine external
        if (route is Route.ThisDevice) return@combine CastSessionState()
        when (nativeState) {
            is WebSocketClient.ConnectionState.Connected -> CastSessionState(
                phase = if (nativeContext == "player") {
                    SessionPhase.PLAYING
                } else {
                    SessionPhase.CONNECTED
                },
                endpointKey = device?.endpointKey,
                device = device,
                targetKind = TargetKind.NATIVE,
                capabilities = _nativeTarget.value?.capabilities.orEmpty(),
                playback = nativePlayback?.let { playback ->
                    PlaybackStatus(
                        state = if (playback.state.equals("playing", ignoreCase = true)) {
                            PlaybackState.PLAYING
                        } else {
                            PlaybackState.PAUSED
                        },
                        positionMs = playback.positionMs,
                        durationMs = playback.durationMs,
                    )
                },
                mediaTitle = nativePlayback?.title,
            )
            is WebSocketClient.ConnectionState.Connecting,
            is WebSocketClient.ConnectionState.Retrying,
            is WebSocketClient.ConnectionState.WaitingForApproval,
            -> CastSessionState(
                phase = SessionPhase.CONNECTING,
                endpointKey = device?.endpointKey,
                device = device,
                targetKind = TargetKind.NATIVE,
            )
            is WebSocketClient.ConnectionState.Error,
            is WebSocketClient.ConnectionState.AuthFailed,
            is WebSocketClient.ConnectionState.PairingDenied,
            is WebSocketClient.ConnectionState.PinMismatch,
            -> CastSessionState(
                phase = SessionPhase.FAILED,
                endpointKey = device?.endpointKey,
                device = device,
                targetKind = TargetKind.NATIVE,
                error = "Could not connect to PlayBridge receiver",
            )
            else -> CastSessionState()
        }
    }.stateIn(scope, SharingStarted.Eagerly, CastSessionState())

    /**
     * True while a cast session should keep the process alive (drives the FGS).
     *
     * - External target selected
     * - Live native WebSocket (connected/connecting), any non-external route — includes
     *   auto-connect while the persisted route is still "This Device", so a cold start
     *   still surfaces the Connected notification
     * - Reconnecting after an unexpected drop (NativeTv route)
     * - Sticky player: we still believe the TV is playing across a drop, so the FGS is
     *   not torn down before `_reconnecting` arms (Android 12+ blocks restarting FGS
     *   from the background after a stop)
     */
    val hasActiveSession: StateFlow<Boolean> = combine(
        _externalMediaLoaded,
        webSocketClient.connectionState,
        connectionCoordinator.tvActiveContext,
        _route,
        _reconnecting,
    ) { externalMediaLoaded, state, ctx, route, reconnecting ->
        val connectedOrConnecting = state is WebSocketClient.ConnectionState.Connected ||
            state is WebSocketClient.ConnectionState.Connecting
        // Any live native socket — casting or idle-linked. Route.ThisDevice only means
        // "prefer local play", not "hide the link"; Disconnect closes the socket.
        val nativeLive = connectedOrConnecting && route !is Route.External
        val nativeReconnecting = route is Route.NativeTv && reconnecting
        // Keep FGS across a drop while we still think content is on the TV.
        val nativeStickyPlaying = ctx == "player" && route !is Route.External
        externalMediaLoaded || nativeLive || nativeReconnecting || nativeStickyPlaying
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Start (or re-enter) [CastSessionService] when a session is live. Safe to call
     * repeatedly. Needed after "Exit PlayBridge" which stops the FGS without flipping
     * [hasActiveSession] — on reopen the flow never re-emits `true`, so nothing would
     * restart the notification without an explicit ensure.
     */
    fun ensureCastServiceRunning() {
        if (!hasActiveSession.value) return
        if (!ProcessUtil.isMainProcess(context)) {
            Log.w(TAG, "ensureCastServiceRunning ignored in non-main process")
            return
        }
        runCatching { CastSessionService.start(context) }
            .onFailure { Log.w(TAG, "Could not ensure cast session service: ${it.message}") }
    }

    /** Last known native receiver name — used for the FGS title while the socket is down. */
    private var lastNativeDeviceName: String? = null

    /**
     * True while the phone is actually playing/proxying bytes (vs merely linked-but-idle).
     * Drives the FGS *type* (mediaPlayback vs connectedDevice) and the CPU wake lock in
     * [CastSessionService]: DLNA with media loaded (proxy serving), or the native TV in the
     * "player" context.
     */
    val isActivelyPlaying: StateFlow<Boolean> = combine(
        _externalMediaLoaded,
        connectionCoordinator.tvActiveContext,
    ) { externalMediaLoaded, ctx ->
        externalMediaLoaded || ctx == "player"
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** What the session notification shows. */
    data class SessionInfo(val deviceName: String, val title: String?)

    val sessionInfo: StateFlow<SessionInfo> = combine(
        _activeExternalDevice,
        webSocketClient.connectionState,
        connectionCoordinator.tvPlayback,
        _externalMediaTitle,
        connectionCoordinator.tvActiveContext,
    ) { external, state, playback, externalTitle, ctx ->
        if (state is WebSocketClient.ConnectionState.Connected) {
            lastNativeDeviceName = state.serverName
        }
        val device = external?.name
            ?: (state as? WebSocketClient.ConnectionState.Connected)?.serverName
            ?: lastNativeDeviceName
            ?: "TV"
        // Prefer the live title; while reconnecting with sticky player context and a
        // cleared snapshot, still surface "Playing" so the notif doesn't flip to the
        // idle "Ready to cast" copy mid-drop.
        val title = if (external != null) {
            externalTitle
        } else {
            playback?.title ?: if (ctx == "player") "Playing" else null
        }
        SessionInfo(deviceName = device, title = title)
    }.stateIn(scope, SharingStarted.Eagerly, SessionInfo("TV", null))

    init {
        // Reattach a dropped link whenever the app returns to the foreground (change 1).
        registerForegroundObserver()
        // Reattach as soon as Wi-Fi/Ethernet comes (back) up (change 5).
        registerNetworkCallback()

        // Background discovery while the reconnect supervisor is retrying (change 3): the
        // saved TV may have moved (router restart → new DHCP lease), in which case the
        // backoff attempts would hammer a dead IP forever. Scan while retrying; when the
        // saved UUID re-announces, heal the stored record and reconnect immediately instead
        // of waiting out the backoff. The repository is owner-refcounted, so this never fights
        // the UI's foreground scan window.
        scope.launch {
            _reconnecting.collectLatest { active ->
                if (!active) return@collectLatest
                val saved = runCatching { connectionStore.tvDevice.first() }.getOrNull()
                    ?: return@collectLatest
                if (saved.uuid.isEmpty()) return@collectLatest
                discoveryRepository.start(
                    owner = ReceiverDiscoveryRepository.OWNER_RECONNECT,
                    protocols = setOf(CastProtocol.PLAYBRIDGE),
                    timeoutMs = 15_000L,
                )
                try {
                    var current: TvDevice = saved
                    discoveryRepository.devices.collect { devices ->
                        val found = devices.find {
                            it.resolvedProtocol == CastProtocol.PLAYBRIDGE && it.uuid == current.uuid
                        } ?: return@collect
                        val healed = current.copy(
                            ip = found.ip,
                            port = found.port,
                            name = found.name,
                            wssPort = found.wssPort,
                        )
                        if (healed != current) {
                            Log.i(TAG, "Reconnect scan: saved TV re-announced at " +
                                "${found.ip}:${found.port} (was ${current.ip}:${current.port})")
                            runCatching { connectionStore.saveTvDevice(healed) }
                            current = healed
                        }
                        val s = webSocketClient.connectionState.value
                        if (s !is WebSocketClient.ConnectionState.Connected &&
                            s !is WebSocketClient.ConnectionState.Connecting
                        ) {
                            // Skip the rest of the backoff wait — the TV is provably here.
                            reconnectJob?.cancel()
                            webSocketClient.reconnect(healed)
                        }
                    }
                } finally {
                    discoveryRepository.stop(ReceiverDiscoveryRepository.OWNER_RECONNECT)
                }
            }
        }

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
                    Log.d(TAG, "hasActiveSession=true → ensure cast FGS")
                    ensureCastServiceRunning()
                    maybeRequestBatteryExemption()
                } else {
                    Log.d(TAG, "hasActiveSession=false → stop cast FGS after ${STOP_GRACE_MS}ms")
                    delay(STOP_GRACE_MS)
                    CastSessionService.stopAndCancelNotification(context)
                }
            }
        }

        // If TV context becomes "player" while a stand-down is pending (e.g. cold-start
        // connected as idle, then context_query returns player after we backgrounded),
        // cancel the soft-disconnect so we do not kill a live cast session.
        scope.launch {
            isActivelyPlaying.collect { playing ->
                if (playing) {
                    backgroundStandDownJob?.cancel()
                    backgroundStandDownJob = null
                }
            }
        }

        // Capture native-TV routing intent from actual playback. If something starts playing
        // on the native receiver (ctx == "player") while connected, the user clearly intends
        // the TV — record it as the route so idle keep-alive + reconnect engage even when the
        // socket pre-existed (e.g. cold-start auto-connect) and the picker was never used.
        // Safe vs the Stage A bug: this only fires when content is already on the TV, and an
        // explicit "This Device" pick still overrides it.
        scope.launch {
            connectionCoordinator.tvActiveContext.collect { ctx ->
                if (ctx == "player" &&
                    _route.value !is Route.External &&
                    _route.value !is Route.NativeTv &&
                    webSocketClient.connectionState.value is WebSocketClient.ConnectionState.Connected
                ) {
                    _route.value = Route.NativeTv
                    persistBaseRoute("native")
                }
            }
        }

        // Reconnect supervisor (Stage B): keep the native link alive. If a link that the
        // user established drops unexpectedly while they still intend to watch on the TV,
        // reconnect with capped, jittered exponential backoff. User-initiated disconnects
        // are ignored (WebSocketClient.reconnect() checks its isUserDisconnect flag), and
        // terminal auth/pin/pairing states stop the loop until the next explicit action.
        scope.launch {
            webSocketClient.connectionState.collect { state ->
                when (state) {
                    is WebSocketClient.ConnectionState.Connected -> {
                        hasConnectedThisSession = true
                        lastNativeDeviceName = state.serverName
                        reconnectAttempt = 0
                        reconnectJob?.cancel()
                        _reconnecting.value = false
                        _reconnectStatus.value = null
                        cancelReconnectGaveUpNotification()
                        // Cold start / auto-connect / reconnect: always re-assert the FGS.
                        // Also covers "Exit PlayBridge" (service stopped, session still live).
                        ensureCastServiceRunning()
                    }
                    is WebSocketClient.ConnectionState.Error,
                    is WebSocketClient.ConnectionState.Disconnected -> {
                        // A deliberate disconnect (Disconnect button / route change) must NOT
                        // trigger the reconnect cycle — only an *unexpected* drop does. Without
                        // this guard, hitting Disconnect immediately popped a "Reconnecting…"
                        // dialog and fought the user's action.
                        //
                        // Also arm reconnect when we still believe the TV is playing even if
                        // the route never flipped to NativeTv (e.g. cold-start cast before the
                        // route-capture collector ran). Sticky FGS depends on NativeTv, so
                        // promote the route here the same way live playback does.
                        val playingOnNative = connectionCoordinator.tvActiveContext.value == "player" &&
                            _route.value !is Route.External
                        if (playingOnNative && _route.value !is Route.NativeTv) {
                            _route.value = Route.NativeTv
                            persistBaseRoute("native")
                        }
                        // Idle background stand-down uses softDisconnect (not user-initiated).
                        // Do not arm the reconnect supervisor for that — wait for foreground /
                        // network recovery instead of burning battery retrying in the background.
                        if (idleBackgroundStandDown) {
                            idleBackgroundStandDown = false
                            Log.d(TAG, "Ignoring disconnect for reconnect (idle background stand-down)")
                        } else if (_route.value is Route.NativeTv &&
                            hasConnectedThisSession &&
                            !webSocketClient.wasUserDisconnect
                        ) {
                            scheduleReconnect()
                        }
                    }
                    is WebSocketClient.ConnectionState.AuthFailed,
                    is WebSocketClient.ConnectionState.PairingDenied,
                    is WebSocketClient.ConnectionState.PinMismatch -> {
                        hasConnectedThisSession = false
                        reconnectJob?.cancel()
                        _reconnecting.value = false
                        _reconnectStatus.value = null
                    }
                    else -> Unit
                }
            }
        }
    }

    // --- Reconnect supervisor state ---
    private var hasConnectedThisSession = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    /** What the "Reconnecting…" popup shows; null when no retry cycle is running. */
    data class ReconnectStatus(val attempt: Int, val maxAttempts: Int, val deviceName: String)

    private val _reconnectStatus = MutableStateFlow<ReconnectStatus?>(null)
    val reconnectStatus: StateFlow<ReconnectStatus?> = _reconnectStatus.asStateFlow()

    /**
     * User pressed Cancel on the reconnect/connecting popup: stop chasing the TV and
     * route playback to this phone.
     */
    fun cancelReconnect() {
        _reconnectStatus.value = null
        selectThisDevice() // also stands the supervisor down
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        // Budget exhausted: how we stand down depends on whether the user is watching.
        //  - Foreground (interactive): the user was actively using the TV; a route left
        //    pointing at a dead receiver makes every play action fail, so fall back to
        //    this phone and tell them (they can re-pick the TV to try again).
        //  - Background: never silently change the route — a screen-off session (episode
        //    queue topping up) must resume on the TV when it returns. Just stand down and
        //    notify; the foreground-return / Wi-Fi hooks re-arm and reconnect later.
        if (reconnectAttempt >= RECONNECT_GIVE_UP) {
            _reconnecting.value = false
            _reconnectStatus.value = null
            // Drop the sticky "still casting" belief so the FGS/casting notification can
            // tear down. The TV may still be playing at home, but we can't reach it — when
            // the link comes back, context_query re-asserts "player" and the notif returns.
            // Without this, background give-up left route=NativeTv + ctx=player forever and
            // the "Casting to …" notification never went away after leaving the house.
            connectionCoordinator.markIdle()
            if (isForeground) {
                Log.i(TAG, "Reconnect: gave up after $reconnectAttempt attempts (foreground) — routing to This Device")
                selectThisDevice()
                notifyReconnectGaveUp(backgrounded = false)
            } else {
                Log.i(TAG, "Reconnect: gave up after $reconnectAttempt attempts (background) — standing down, route unchanged")
                notifyReconnectGaveUp(backgrounded = true)
            }
            return
        }
        _reconnecting.value = true
        reconnectJob = scope.launch {
            val attempt = reconnectAttempt
            reconnectAttempt += 1
            // Surface the retry cycle so the connecting popup can show progress (1-based).
            val deviceName = runCatching { connectionStore.tvDevice.first() }.getOrNull()
                ?.name ?: "TV"
            _reconnectStatus.value = ReconnectStatus(attempt + 1, RECONNECT_GIVE_UP, deviceName)
            // Linear pacing over a realistic window: real drops (router reboot, TV Wi-Fi
            // waking from standby, AP roaming) take tens of seconds, so retry steadily for
            // ~90s rather than giving up in a few. On a LAN a 3s retry is cheap.
            delay(RECONNECT_DELAY_MS)
            // Re-check: the user may have switched route or a connection may have come up.
            if (_route.value !is Route.NativeTv) {
                _reconnecting.value = false
                _reconnectStatus.value = null
                return@launch
            }
            val s = webSocketClient.connectionState.value
            if (s is WebSocketClient.ConnectionState.Connected ||
                s is WebSocketClient.ConnectionState.Connecting
            ) return@launch
            Log.d(TAG, "Reconnect attempt ${attempt + 1}/$RECONNECT_GIVE_UP")
            // Pass the saved record: if discovery has UUID-matched the TV at a new address
            // (router restart / DHCP change), the attempt targets the fresh IP, not the
            // dead one cached from the previous socket.
            webSocketClient.reconnect(runCatching { connectionStore.tvDevice.first() }.getOrNull())
        }
    }

    // ------------------------------------------------------------------
    // Foreground-return reconnect
    //
    // Without a foreground service (e.g. connected-but-idle on the ThisDevice route) the
    // socket routinely dies while the app is cached/backgrounded, and nothing used to
    // re-establish it: startup auto-connect is once-per-ViewModel and the supervisor only
    // runs for the NativeTv route. This hook reattaches on every return to the foreground.
    // ------------------------------------------------------------------

    /**
     * Whether the app is currently in the foreground. Drives the retry policy's key
     * decision: an *interactive* reconnect (user watching) may auto-fall back to the
     * phone when the TV can't be reached, but a *background* one must never silently
     * change the route — it just stands down and notifies, so a backgrounded session
     * (screen off, episode queue topping up) resumes on the TV when it returns.
     */
    @Volatile private var isForeground = false
    /**
     * Set just before [WebSocketClient.softDisconnect] for idle background stand-down so the
     * connection-state collector does not arm [scheduleReconnect].
     */
    @Volatile private var idleBackgroundStandDown = false
    private var backgroundStandDownJob: Job? = null

    private fun registerForegroundObserver() {
        // ProcessLifecycleOwner must be touched on the main thread; Koin may build this
        // singleton off it.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                        isForeground = true
                        onAppForegrounded()
                    }

                    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                        isForeground = false
                        onAppBackgrounded()
                    }
                }
            )
        }
    }

    private fun onAppForegrounded() {
        // Cancel a pending idle stand-down (quick app-switcher peeks / multitasking).
        backgroundStandDownJob?.cancel()
        backgroundStandDownJob = null
        // Exit PlayBridge / warm process: re-assert FGS if a session is still live.
        ensureCastServiceRunning()
        attemptRecovery("app foregrounded")
    }

    /**
     * When the app leaves the foreground and nothing is casting, optionally soft-close the
     * native TV socket after [IDLE_BACKGROUND_GRACE_MS] so we do not hold a Connected FGS +
     * Wi-Fi lock while the user watches YouTube, etc. Casting always keeps the link.
     * Opt out via Settings → TV → "Keep connection in background".
     */
    private fun onAppBackgrounded() {
        backgroundStandDownJob?.cancel()
        backgroundStandDownJob = scope.launch {
            delay(IDLE_BACKGROUND_GRACE_MS)
            if (isForeground) return@launch
            val keepAlive = runCatching {
                settingsRepository.keepTvConnectionInBackground.first()
            }.getOrDefault(false)
            if (keepAlive) {
                Log.d(TAG, "Background idle stand-down skipped (keep connection in background)")
                return@launch
            }
            // Casting / DLNA media / sticky player context — keep the session.
            if (isActivelyPlaying.value) {
                Log.d(TAG, "Background idle stand-down skipped (actively casting)")
                return@launch
            }
            // Release an idle third-party session in the background. Saved endpoint data remains,
            // and the next explicit pick reconnects it without keeping heartbeats/FGS alive.
            if (_activeExternalDevice.value != null) {
                Log.i(TAG, "Idle background stand-down: releasing external target")
                clearExternalTarget()
                return@launch
            }
            val state = webSocketClient.connectionState.value
            val linked = state is WebSocketClient.ConnectionState.Connected ||
                state is WebSocketClient.ConnectionState.Connecting
            if (!linked) return@launch
            Log.i(TAG, "Idle background stand-down: soft-closing TV socket after grace")
            idleBackgroundStandDown = true
            webSocketClient.softDisconnect()
            // hasActiveSession drops → CastSessionService tears down via its collector.
        }
    }

    /**
     * Try to re-establish a dropped link right now, from the saved device record.
     * Triggered by foreground return and by Wi-Fi/Ethernet becoming available — both
     * moments where an immediate attempt is far more likely to succeed than the next
     * scheduled backoff tick (if any is even pending).
     */
    private fun attemptRecovery(reason: String) {
        // Callers arrive on arbitrary threads (main-thread lifecycle observer,
        // ConnectivityManager binder thread) — hop into the manager's single-threaded
        // scope before touching supervisor state so nothing races the reconnect loop.
        scope.launch {
            if (!routePrefs.getBoolean("auto_connect_tv", true)) return@launch
            val state = webSocketClient.connectionState.value
            val eligible = state is WebSocketClient.ConnectionState.Disconnected ||
                state is WebSocketClient.ConnectionState.Error
            if (!eligible) return@launch
            // Fresh retry budget: conditions changed, so a TV that took longer than the
            // backoff window to come back (e.g. router reboot) gets retried.
            reconnectAttempt = 0
            Log.d(TAG, "attemptRecovery($reason)")
            // reconnect() internally no-ops when there was no prior link this process
            // (cold start — ConnectionViewModel's auto-connect owns that) or when the
            // user disconnected deliberately.
            webSocketClient.reconnect(runCatching { connectionStore.tvDevice.first() }.getOrNull())
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        // Note: onAvailable also fires once at registration when a matching network is
        // already up; attemptRecovery() is a cheap no-op in that case (no prior target).
        runCatching {
            cm.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    attemptRecovery("network available")
                }
            })
        }.onFailure { Log.w(TAG, "Could not register network callback: ${it.message}") }
    }

    // ------------------------------------------------------------------
    // Third-party target lifecycle
    // ------------------------------------------------------------------

    private fun selectExternalTarget(device: TvDevice, target: CastTarget) {
        if (_externalTarget.value != null) _externalInterrupts.tryEmit(Unit)
        detachExternalTarget(stopFirst = true)
        externalTargetSlot.replace(target)
        _externalTarget.value = externalTargetSlot.target
        _activeExternalDevice.value = device
        _externalStatus.value = PlaybackStatus(PlaybackState.IDLE)
        _externalMediaTitle.value = null
        _externalMediaLoaded.value = false
        _externalNowPlayingMeta.value = null
        _lastEffectiveStreamRoute.value = null
        webSocketClient.disconnect()
        stopReconnectSupervisor()
        // External routes cannot be restored without reconnecting, so their restart/clear
        // fallback is local playback rather than a stale native-TV route.
        persistBaseRoute("this")
        _route.value = Route.External(device.endpointKey, device.name)
        externalStatusJob = scope.launch {
            target.status().collect { status ->
                if (_externalTarget.value !== target) return@collect
                _externalStatus.value = status
                if (_externalMediaLoaded.value && status.state in TERMINAL_EXTERNAL_STATES) {
                    _externalMediaLoaded.value = false
                    _externalMediaTitle.value = null
                    _externalNowPlayingMeta.value = null
                }
            }
        }
        Log.d(TAG, "Selected ${target.kind} target: ${device.name}")
    }

    fun clearExternalTarget() = detachExternalTarget(stopFirst = false)

    /** Stop media before releasing transports that disconnect on release (notably CastV2). */
    fun stopAndClearExternalTarget() = detachExternalTarget(stopFirst = true)

    private fun detachExternalTarget(stopFirst: Boolean) {
        externalLoadJob?.cancel()
        externalLoadJob = null
        externalStatusJob?.cancel()
        externalStatusJob = null
        val detached = externalTargetSlot.take()
        _externalTarget.value = null
        _activeExternalDevice.value = null
        _externalStatus.value = null
        _externalMediaTitle.value = null
        _externalMediaLoaded.value = false
        _externalNowPlayingMeta.value = null
        _lastEffectiveStreamRoute.value = null
        if (_route.value is Route.External) {
            _route.value = when (routePrefs.getString(ROUTE_KEY, "this")) {
                "native" -> Route.NativeTv
                else -> Route.ThisDevice
            }
        }
        if (detached != null) {
            if (stopFirst) {
                scope.launch {
                    try {
                        detached.stop()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(TAG, "${detached.kind} stop before release failed: ${error.message}")
                    } finally {
                        // Leave browser host up; only drop this session's WS link.
                        if (detached is BrowserCastTarget) {
                            try {
                                com.playbridge.sender.cast.proxy.PhoneSenderServices.get()
                                    ?.disconnectBrowser(detached.sessionId)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Log.w(TAG, "browser disconnect failed: ${error.message}")
                            }
                        }
                        detached.release()
                    }
                }
            } else {
                detached.release()
            }
        }
    }

    /** Select a DLNA renderer as the active cast target (drops any native session). */
    fun selectDlnaTarget(device: TvDevice): Boolean {
        val controlUrl = device.controlUrl ?: return false
        val target = DlnaCastTarget(
            id = device.uuid,
            name = device.name,
            avTransport = AvTransportClient(controlUrl, DlnaProxyHolder.httpClient),
            renderingControl = device.renderingControlUrl?.let {
                RenderingControlClient(it, DlnaProxyHolder.httpClient)
            },
            proxy = DlnaProxyHolder.proxy(context),
        )
        selectExternalTarget(device, target)
        return true
    }

    fun load(media: MediaItem, userInitiated: Boolean = true): Boolean {
        val target = _externalTarget.value ?: return false
        DebugNetworkLogger.urlAndHeaders(
            TAG,
            "External ${target.kind} cast input",
            media.url,
            media.headers,
        )
        if (userInitiated) _externalInterrupts.tryEmit(Unit)
        _externalMediaTitle.value = media.title ?: "Casting media"
        _externalMediaLoaded.value = true
        _externalNowPlayingMeta.value = media.visualMetadata
        _externalStatus.value = PlaybackStatus(PlaybackState.BUFFERING)
        externalLoadJob?.cancel()
        externalLoadJob = scope.launch {
            runCatching { target.load(media) }
                .onSuccess {
                    if (_externalTarget.value === target && target is BrowserCastTarget) {
                        target.lastEffectiveRoute?.let { noteEffectiveStreamRoute(it, target.lastProxyFallback) }
                    }
                }
                .onFailure {
                    if (it is CancellationException) return@onFailure
                    if (_externalTarget.value === target) {
                        _externalStatus.value = PlaybackStatus(PlaybackState.ERROR)
                        _externalMediaLoaded.value = false
                        Log.w(TAG, "${target.kind} load failed: ${it.message}")
                        if (target is BrowserCastTarget) {
                            _castNotices.tryEmit("TV browser couldn’t play this stream")
                        }
                    }
                }
        }
        return true
    }

    fun play() = controlExternal("play") { it.play() }
    fun pause() = controlExternal("pause") { it.pause() }
    fun seekTo(positionMs: Long) = controlExternal("seek") { it.seekTo(positionMs) }
    fun setVolume(percent: Int) = controlExternal("volume") { it.setVolume(percent) }

    fun adjustVolume(up: Boolean) {
        when (val target = _externalTarget.value) {
            is GoogleCastTarget -> scope.launch {
                runCatching { target.adjustVolume(if (up) 0.05f else -0.05f) }
            }
            is RokuCastTarget -> target.sendKeypress(if (up) "VolumeUp" else "VolumeDown")
            is DlnaCastTarget -> scope.launch {
                runCatching { target.adjustVolume(if (up) 5 else -5) }
                    .onFailure { Log.w(TAG, "DLNA volume adjustment failed: ${it.message}") }
            }
            is BrowserCastTarget -> scope.launch {
                runCatching { target.adjustVolume(if (up) 0.05 else -0.05) }
                    .onFailure { Log.w(TAG, "Browser volume adjustment failed: ${it.message}") }
            }
            else -> Unit
        }
    }

    fun stop() {
        _externalInterrupts.tryEmit(Unit)
        externalLoadJob?.cancel()
        externalLoadJob = null
        controlExternal("stop") { it.stop() }
        _externalMediaTitle.value = null
        _externalMediaLoaded.value = false
        _externalNowPlayingMeta.value = null
        _externalStatus.value = PlaybackStatus(PlaybackState.STOPPED)
    }

    private fun controlExternal(operation: String, block: suspend (CastTarget) -> Unit) {
        val target = _externalTarget.value ?: return
        scope.launch {
            runCatching { block(target) }
                .onFailure { Log.w(TAG, "${target.kind} $operation failed: ${it.message}") }
        }
    }

    /** Episode-queue advance — same load, but does NOT interrupt the queue plan. */
    internal fun playOnExternalFromQueue(media: MediaItem) {
        load(media, userInitiated = false)
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

    /**
     * Guide the user (once, on the first cast session) to whitelist the app from
     * battery optimization. Casting phone files means this app IS the media server:
     * Doze and OEM "app sleep" managers freeze a backgrounded app's network — and
     * eventually the process — even with the cast FGS running, killing the local
     * proxy mid-stream.
     *
     * Deliberately does NOT use ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: the
     * direct-exemption dialog requires the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * permission, which Google Play restricts to a narrow allowlist. Instead we open
     * the system battery-optimization settings page (no permission needed) with an
     * explanatory toast, and the user flips the switch manually. Prompted here rather
     * than at app launch so a fresh install isn't greeted by a stack of system
     * screens; a session always starts from a foreground user action, so launching
     * the settings screen is permitted.
     */
    private fun maybeRequestBatteryExemption() {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
        val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_exemption_prompted", false)) return
        runCatching {
            android.widget.Toast.makeText(
                context,
                "To keep casting stable with the screen off, find PlayBridge in this list " +
                    "and set battery usage to “Unrestricted” / “Don’t optimize”.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onSuccess {
            // Only burn the one-shot AFTER the settings screen actually launched — if the
            // intent fails (odd OEM builds), the next cast session gets another chance.
            prefs.edit().putBoolean("battery_exemption_prompted", true).apply()
        }.onFailure { Log.w(TAG, "Couldn't open battery-optimization settings", it) }
    }

    /**
     * One-shot "Lost connection to your TV" notification when the reconnect supervisor
     * exhausts its retry budget. Best-effort: wrapped so a missing POST_NOTIFICATIONS
     * grant (API 33+) or OEM quirk can never break the supervisor.
     *
     * [backgrounded] tailors the message: a background stand-down left the route on the
     * TV (it'll resume when reachable), while a foreground give-up switched to the phone.
     */
    private fun notifyReconnectGaveUp(backgrounded: Boolean) {
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                mgr.getNotificationChannel(RECONNECT_CHANNEL_ID) == null
            ) {
                mgr.createNotificationChannel(
                    android.app.NotificationChannel(
                        RECONNECT_CHANNEL_ID,
                        "TV connection",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
            val contentPi = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.let { launch ->
                    android.app.PendingIntent.getActivity(
                        context, 0, launch,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                            android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                }
            val message = if (backgrounded) {
                "Couldn't reach your TV. It'll reconnect when you reopen the app."
            } else {
                "Playback switched to this phone. Pick the TV again to reconnect."
            }
            val notif = androidx.core.app.NotificationCompat.Builder(context, RECONNECT_CHANNEL_ID)
                .setSmallIcon(com.playbridge.sender.R.drawable.ic_launcher_foreground)
                .setContentTitle("Lost connection to your TV")
                .setContentText(message)
                .setAutoCancel(true)
                .apply { contentPi?.let { setContentIntent(it) } }
                .build()
            mgr.notify(RECONNECT_NOTIF_ID, notif)
        }.onFailure { Log.w(TAG, "Could not post reconnect notification: ${it.message}") }
    }

    private fun cancelReconnectGaveUpNotification() {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(RECONNECT_NOTIF_ID)
        }
    }

    /**
     * Stop what's playing (notification **Stop** while casting).
     *
     * Native: marks context idle immediately (so the FGS morphs to **Connected** +
     * **Disconnect** without waiting on the socket) and sends `stop` to the TV. The
     * socket and Native TV route stay so a new cast is one tap away.
     *
     * External receiver: stops playback and clears the target — there is no separate
     * restorable linked-idle session.
     */
    fun endCastSession() {
        val external = _externalTarget.value
        if (external != null) {
            stopAndClearExternalTarget()
            return
        }
        // Idle first so isActivelyPlaying flips before the WS round-trip; the notif
        // action then becomes Disconnect while we still hold the link.
        connectionCoordinator.markIdle()
        if (_nativeTarget.value != null) {
            scope.launch {
                runCatching {
                    webSocketClient.send(
                        com.playbridge.shared.protocol.createControlCommandJson("stop")
                    )
                }
            }
        }
    }

    /**
     * Drop the link entirely (notification **Disconnect** while connected/idle).
     *
     * User-initiated: tears down the WebSocket (or external target), routes to This Device,
     * and stops the reconnect supervisor so the casting FGS does not come back.
     */
    fun disconnectSession() {
        stopEpisodeQueues()
        connectionCoordinator.markIdle()
        val external = _externalTarget.value
        if (external != null) {
            stopAndClearExternalTarget()
            return
        }
        // Flag as user disconnect before close so the reconnect supervisor does not re-arm.
        webSocketClient.disconnect()
        selectThisDevice()
    }

    /**
     * Full app quit (Dashboard **Exit PlayBridge**): stop binge queues, drop the link,
     * tear down the cast FGS and cancel its notification immediately. Callers should
     * then [android.app.Activity.finishAndRemoveTask] and kill the process so Koin
     * singletons cannot keep `queue_add`-ing after the UI is gone.
     */
    fun shutdownForAppExit(context: Context) {
        Log.i(TAG, "shutdownForAppExit")
        backgroundStandDownJob?.cancel()
        backgroundStandDownJob = null
        stopEpisodeQueues()
        connectionCoordinator.markIdle()
        val external = _externalTarget.value
        if (external != null) {
            clearExternalTarget()
        }
        webSocketClient.disconnect()
        selectThisDevice()
        // Cancel notifs even if stopService is async or the service was already dying —
        // otherwise finishAndRemoveTask can leave a sticky FGS row while the process lingers.
        CastSessionService.stopAndCancelNotification(context)
        cancelReconnectGaveUpNotification()
    }

    /** Best-effort stop of phone-side series queues (native + external). Lazy Koin to avoid cycles. */
    private fun stopEpisodeQueues() {
        runCatching {
            org.koin.core.context.GlobalContext.get()
                .get<com.playbridge.sender.connection.TvQueueCoordinator>()
                .stop()
        }.onFailure { Log.w(TAG, "TvQueueCoordinator.stop failed: ${it.message}") }
        runCatching {
            org.koin.core.context.GlobalContext.get()
                .get<com.playbridge.sender.connection.ExternalQueueCoordinator>()
                .stop()
        }.onFailure { Log.w(TAG, "ExternalQueueCoordinator.stop failed: ${it.message}") }
    }

    /** @deprecated Prefer [endCastSession] / [disconnectSession]; kept for any external call sites. */
    fun endSession() = endCastSession()

    companion object {
        private val TERMINAL_EXTERNAL_STATES = setOf(
            PlaybackState.IDLE,
            PlaybackState.STOPPED,
            PlaybackState.ERROR,
        )

        /** How long a session may be "inactive" before the FGS is torn down. */
        private const val STOP_GRACE_MS = 3_000L

        /**
         * How long the app may stay backgrounded while idle-linked before soft-closing the
         * TV socket (unless "Keep connection in background" is on). Long enough to survive
         * app-switcher peeks / permission dialogs; short enough to free battery when the
         * user moves on to YouTube etc.
         */
        private const val IDLE_BACKGROUND_GRACE_MS = 45_000L

        // Linear retry pacing: a fixed pause before each attempt (no exponential backoff).
        private const val RECONNECT_DELAY_MS = 3_000L
        // ~90s window (30 × 3s): covers router reboots / TV Wi-Fi wake / AP roaming, which
        // a few-second budget missed entirely. Exhaustion then stands down (see scheduleReconnect).
        private const val RECONNECT_GIVE_UP = 30

        // "Reconnect gave up" user notification.
        private const val RECONNECT_CHANNEL_ID = "tv_connection_channel"
        private const val RECONNECT_NOTIF_ID = 4713
    }
}
