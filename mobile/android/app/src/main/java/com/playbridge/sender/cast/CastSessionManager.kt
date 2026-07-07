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
        stopReconnectSupervisor()
    }

    /**
     * Stand the reconnect supervisor down. Runs inside the manager's single-threaded
     * scope: callers are on arbitrary threads (UI picks, DLNA selection), while the
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
                        _reconnectStatus.value = null
                        cancelReconnectGaveUpNotification()
                    }
                    is WebSocketClient.ConnectionState.Error,
                    is WebSocketClient.ConnectionState.Disconnected -> {
                        // A deliberate disconnect (Disconnect button / route change) must NOT
                        // trigger the reconnect cycle — only an *unexpected* drop does. Without
                        // this guard, hitting Disconnect immediately popped a "Reconnecting…"
                        // dialog and fought the user's action.
                        if (_route.value is Route.NativeTv &&
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
        stopReconnectSupervisor()
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
            connectionCoordinator.markIdle()
        }
    }

    companion object {
        /** How long a session may be "inactive" before the FGS is torn down. */
        private const val STOP_GRACE_MS = 3_000L

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
