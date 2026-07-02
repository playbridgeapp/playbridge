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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val connectionStore: com.playbridge.sender.connection.ConnectionStore,
    private val nsdHelper: com.playbridge.sender.connection.NsdHelper,
) {
    private val TAG = "CastSessionManager"

    // ------------------------------------------------------------------
    // Routing intent (authoritative) — see CONNECTION_ROUTING_PLAN.md
    //
    // The single source of truth for *where playback should go*, set ONLY by explicit
    // user action in the device picker. It is deliberately decoupled from the live
    // connection state: connecting to a TV must never change the route, and choosing
    // "This Device" must never be implemented as a disconnect. Screens read [route]
    // instead of inferring a destination from connectionState / the old `watch_on_tv`
    // SharedPreference.
    // ------------------------------------------------------------------
    sealed interface Route {
        /** Play on the phone (in-app player). */
        data object ThisDevice : Route
        /** Cast to the saved native (WebSocket) TV receiver. */
        data object NativeTv : Route
        /** Cast to a third-party DLNA renderer. */
        data class Dlna(val deviceId: String, val name: String) : Route
    }

    private val routePrefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    private val ROUTE_KEY = "cast_route" // persisted base route: "this" | "native"

    // DLNA routes are live (driven by activeDlnaTarget), so only the base (this/native)
    // is persisted; on restart a DLNA selection collapses back to its base route.
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

    /** True when the active routing intent targets a TV/renderer (native or DLNA). */
    val routeTargetsTv: StateFlow<Boolean> =
        _route.map { it !is Route.ThisDevice }.stateIn(scope, SharingStarted.Eagerly, false)

    private fun persistBaseRoute(value: String) {
        // Keep the legacy `watch_on_tv` mirror in lockstep so every screen that still reads
        // it (CastSheet, PhoneFiles, …) agrees with the authoritative route.
        routePrefs.edit()
            .putString(ROUTE_KEY, value)
            .putBoolean("watch_on_tv", value == "native")
            .apply()
    }

    /** User picked "This Device": route phone-local. Does NOT tear down any connection. */
    fun selectThisDevice() {
        _route.value = Route.ThisDevice
        persistBaseRoute("this")
        // Leaving the TV route: stop trying to keep the native link alive.
        hasConnectedThisSession = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        _reconnecting.value = false
    }

    /** User picked the native TV receiver. Connecting is a separate concern (caller/Stage B). */
    fun selectNativeRoute() {
        _route.value = Route.NativeTv
        persistBaseRoute("native")
    }

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

    /**
     * True while a cast session should keep the process alive (drives the FGS).
     *
     * Stage B: keep the process alive whenever a DLNA renderer is selected, OR the routing
     * intent is the native TV and the socket is up/connecting — regardless of whether
     * something is actively playing. This makes an *idle* native link survive screen-off /
     * backgrounding so it doesn't silently die, and gives the reconnect supervisor a live
     * process to run in. (Previously this required tvActiveContext == "player".)
     */
    val hasActiveSession: StateFlow<Boolean> = combine(
        _activeDlnaTarget,
        webSocketClient.connectionState,
        connectionCoordinator.tvActiveContext,
        _route,
        _reconnecting,
    ) { dlna, state, ctx, route, reconnecting ->
        val connectedOrConnecting = state is WebSocketClient.ConnectionState.Connected ||
            state is WebSocketClient.ConnectionState.Connecting
        // Always keep alive while actually playing on the native TV (preserves the original
        // behaviour regardless of how routing intent was set).
        val nativePlaying = ctx == "player" && connectedOrConnecting
        // Stage B: also keep an *idle* native link alive when that's the routing intent…
        val nativeIdleLinked = route is Route.NativeTv && connectedOrConnecting
        // …and across the reconnect backoff so the process survives the retry window.
        val nativeReconnecting = route is Route.NativeTv && reconnecting
        dlna != null || nativePlaying || nativeIdleLinked || nativeReconnecting
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * True while the phone is actually playing/proxying bytes (vs merely linked-but-idle).
     * Drives the FGS *type* (mediaPlayback vs connectedDevice) and the CPU wake lock in
     * [CastSessionService]: DLNA with media loaded (proxy serving), or the native TV in the
     * "player" context.
     */
    val isActivelyPlaying: StateFlow<Boolean> = combine(
        _activeDlnaTarget,
        _dlnaMediaTitle,
        connectionCoordinator.tvActiveContext,
    ) { dlna, dlnaTitle, ctx ->
        (dlna != null && dlnaTitle != null) || ctx == "player"
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
        // Reattach a dropped link whenever the app returns to the foreground (change 1).
        registerForegroundObserver()
        // Reattach as soon as Wi-Fi/Ethernet comes (back) up (change 5).
        registerNetworkCallback()

        // Background discovery while the reconnect supervisor is retrying (change 3): the
        // saved TV may have moved (router restart → new DHCP lease), in which case the
        // backoff attempts would hammer a dead IP forever. Scan while retrying; when the
        // saved UUID re-announces, heal the stored record and reconnect immediately instead
        // of waiting out the backoff. NsdHelper is owner-refcounted, so this never fights
        // the UI's foreground scan window.
        scope.launch {
            _reconnecting.collectLatest { active ->
                if (!active) return@collectLatest
                val saved = runCatching { connectionStore.tvDevice.first() }.getOrNull()
                    ?: return@collectLatest
                if (saved.uuid.isEmpty()) return@collectLatest
                nsdHelper.startDiscovery(com.playbridge.sender.connection.NsdHelper.OWNER_RECONNECT)
                try {
                    var current: TvDevice = saved
                    nsdHelper.discoveredDevices.collect { devices ->
                        val found = devices.find { it.uuid == current.uuid } ?: return@collect
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
                    nsdHelper.stopDiscovery(com.playbridge.sender.connection.NsdHelper.OWNER_RECONNECT)
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
                    runCatching { CastSessionService.start(context) }
                        .onFailure { Log.w(TAG, "Could not start cast session service: ${it.message}") }
                    maybeRequestBatteryExemption()
                } else {
                    delay(STOP_GRACE_MS)
                    CastSessionService.stop(context)
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
                    _route.value !is Route.Dlna &&
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
                        reconnectAttempt = 0
                        reconnectJob?.cancel()
                        _reconnecting.value = false
                    }
                    is WebSocketClient.ConnectionState.Error,
                    is WebSocketClient.ConnectionState.Disconnected -> {
                        if (_route.value is Route.NativeTv && hasConnectedThisSession) {
                            scheduleReconnect()
                        }
                    }
                    is WebSocketClient.ConnectionState.AuthFailed,
                    is WebSocketClient.ConnectionState.PairingDenied,
                    is WebSocketClient.ConnectionState.PinMismatch -> {
                        hasConnectedThisSession = false
                        reconnectJob?.cancel()
                        _reconnecting.value = false
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

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        // Bound the effort: after RECONNECT_GIVE_UP attempts, stand down (release the FGS so
        // we don't pin the process / show a misleading notification while the TV is gone).
        // The user can reconnect manually, which resets the counter.
        if (reconnectAttempt >= RECONNECT_GIVE_UP) {
            Log.i(TAG, "Reconnect: giving up after $reconnectAttempt attempts")
            _reconnecting.value = false
            return
        }
        _reconnecting.value = true
        reconnectJob = scope.launch {
            val attempt = reconnectAttempt
            reconnectAttempt += 1
            val step = attempt.coerceAtMost(RECONNECT_MAX_STEP)
            val backoff = (RECONNECT_BASE_MS shl step).coerceAtMost(RECONNECT_MAX_MS)
            val jitter = (0L..RECONNECT_JITTER_MS).random()
            delay(backoff + jitter)
            // Re-check: the user may have switched route or a connection may have come up.
            if (_route.value !is Route.NativeTv) {
                _reconnecting.value = false
                return@launch
            }
            val s = webSocketClient.connectionState.value
            if (s is WebSocketClient.ConnectionState.Connected ||
                s is WebSocketClient.ConnectionState.Connecting
            ) return@launch
            Log.d(TAG, "Reconnect attempt $attempt after ${backoff + jitter}ms")
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

    private fun registerForegroundObserver() {
        // ProcessLifecycleOwner must be touched on the main thread; Koin may build this
        // singleton off it.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                        onAppForegrounded()
                    }
                }
            )
        }
    }

    private fun onAppForegrounded() = attemptRecovery("app foregrounded")

    /**
     * Try to re-establish a dropped link right now, from the saved device record.
     * Triggered by foreground return and by Wi-Fi/Ethernet becoming available — both
     * moments where an immediate attempt is far more likely to succeed than the next
     * scheduled backoff tick (if any is even pending).
     */
    private fun attemptRecovery(reason: String) {
        // Fresh retry budget: conditions changed, so a TV that took longer than the
        // backoff window to come back (e.g. router reboot) gets retried.
        reconnectAttempt = 0
        if (!routePrefs.getBoolean("auto_connect_tv", true)) return
        val state = webSocketClient.connectionState.value
        val eligible = state is WebSocketClient.ConnectionState.Disconnected ||
            state is WebSocketClient.ConnectionState.Error
        if (!eligible) return
        Log.d(TAG, "attemptRecovery($reason)")
        scope.launch {
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
        // Selecting a renderer is an explicit routing choice; it also supersedes any native
        // link, so stop the native reconnect supervisor.
        _route.value = Route.Dlna(device.uuid, device.name)
        // DLNA is a cast target → mirror "watch on TV" for legacy readers.
        routePrefs.edit().putBoolean("watch_on_tv", true).apply()
        hasConnectedThisSession = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        _reconnecting.value = false
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
        // Collapse the live DLNA route back to its persisted base (this/native).
        if (_route.value is Route.Dlna) {
            _route.value = when (routePrefs.getString(ROUTE_KEY, "this")) {
                "native" -> Route.NativeTv
                else -> Route.ThisDevice
            }
        }
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
        // Treat an explicit Stop as ending the now-playing session: clear the title/meta and
        // status so the cast bar drops to idle and the Remote no longer shows stale media.
        // The renderer stays selected (activeDlnaTarget), so the user can cast to it again.
        _dlnaMediaTitle.value = null
        _dlnaNowPlayingMeta.value = null
        _dlnaStatus.value = null
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

    /**
     * Ask (once, on the first cast session) to be exempted from battery optimization.
     * Casting phone files means this app IS the media server: Doze and OEM "app sleep"
     * managers freeze a backgrounded app's network — and eventually the process — even
     * with the cast FGS running, killing the local proxy mid-stream. Prompted here
     * rather than at app launch so a fresh install isn't greeted by a stack of system
     * dialogs; a session always starts from a foreground user action, so launching
     * the settings dialog is permitted.
     */
    private fun maybeRequestBatteryExemption() {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
        val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_exemption_prompted", false)) return
        prefs.edit().putBoolean("battery_exemption_prompted", true).apply()
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "Battery-optimization exemption request failed", it) }
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

        // Reconnect backoff: 1s, 2s, 4s, 8s, then capped at 10s, each with up to 0.5s jitter.
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS = 10_000L
        private const val RECONNECT_JITTER_MS = 500L
        private const val RECONNECT_MAX_STEP = 4
        // Stop retrying after this many consecutive failed attempts (~1 minute of backoff).
        private const val RECONNECT_GIVE_UP = 8
    }
}
