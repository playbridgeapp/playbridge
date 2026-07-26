package com.playbridge.sender.connection
import androidx.core.content.edit

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.net.toUri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playbridge.sender.data.history.DatabaseProvider
import kotlinx.coroutines.Dispatchers
import com.playbridge.sender.data.history.CommandHistoryEntity
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.dlna.DlnaProxyHolder
import com.playbridge.shared.protocol.createSingleVideoCommandJson
import playbridge.PlayPayload
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Socket
import java.net.InetSocketAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.UUID

class ConnectionViewModel(
    application: Application,
    val webSocketClient: WebSocketClient = WebSocketClient(),
    private val connectionStore: ConnectionStore = ConnectionStore(application),
    private val commandHistoryDb: com.playbridge.sender.data.history.HistoryDatabase = DatabaseProvider.getDatabase(application),
    val castSessionManager: CastSessionManager,
    private val discoveryRepository: ReceiverDiscoveryRepository,
) : AndroidViewModel(application) {

    private val TAG = "ConnectionViewModel"
    private val prefs = application.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)

    // Exposed Flows
    val connectionState: StateFlow<WebSocketClient.ConnectionState> = webSocketClient.connectionState
    val tvDevice: Flow<TvDevice?> = connectionStore.tvDevice

    private val _selectedDiscoveryProtocols = MutableStateFlow(CastProtocol.entries.toSet())
    val selectedDiscoveryProtocols: StateFlow<Set<CastProtocol>> =
        _selectedDiscoveryProtocols.asStateFlow()

    val discoveredDevices: StateFlow<List<TvDevice>> = combine(
        discoveryRepository.devices,
        _selectedDiscoveryProtocols,
    ) { devices, protocols -> devices.filter { it.resolvedProtocol in protocols } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val discoveredEndpoints = combine(
        discoveryRepository.endpoints,
        _selectedDiscoveryProtocols,
    ) { endpoints, protocols -> endpoints.filter { it.protocol in protocols } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val deviceHistory: Flow<List<TvDevice>> = connectionStore.deviceHistory

    private val _savedDevicesOnlineStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val savedDevicesOnlineStatus: StateFlow<Map<String, Boolean>> = _savedDevicesOnlineStatus.asStateFlow()

    fun pingSavedDevices(devices: List<TvDevice>) {
        viewModelScope.launch(Dispatchers.IO) {
            val results = devices.map { device ->
                async {
                    val descriptionPort = device.descriptionUrl?.let { url ->
                        runCatching {
                            java.net.URI(url).let { uri ->
                                uri.port.takeIf { it > 0 }
                                    ?: when (uri.scheme?.lowercase()) {
                                        "https" -> 443
                                        "http" -> 80
                                        else -> null
                                    }
                            }
                        }.getOrNull()
                    }
                    val portToPing = device.wssPort ?: device.port.takeIf { it > 0 } ?: descriptionPort
                    val online = portToPing != null && device.addresses.ifEmpty { listOf(device.ip) }
                        .filter(String::isNotBlank)
                        .any { address ->
                            try {
                                Socket().use { socket ->
                                    socket.connect(InetSocketAddress(address, portToPing), 600)
                                    true
                                }
                            } catch (_: Exception) {
                                false
                            }
                        }
                    device.endpointKey.toString() to online
                }
            }.awaitAll().toMap()
            _savedDevicesOnlineStatus.value = results
        }
    }

    private val _autoConnectEnabled = MutableStateFlow(prefs.getBoolean("auto_connect_tv", true))
    val autoConnectEnabled: StateFlow<Boolean> = _autoConnectEnabled.asStateFlow()

    // --- Routing intent (authoritative; owned by CastSessionManager) ---
    // Screens read this to decide where playback goes instead of inferring from
    // connectionState.
    val route: StateFlow<CastSessionManager.Route> = castSessionManager.route
    val routeTargetsTv: StateFlow<Boolean> = castSessionManager.routeTargetsTv

    /** User picked "This Device": route phone-local without forcing a disconnect. */
    fun selectThisDevice() = castSessionManager.selectThisDevice()

    /** User picked the saved native TV as the routing target. */
    fun selectNativeRoute() = castSessionManager.selectNativeRoute()

    // --- Cast target state (owned by CastSessionManager so a cast survives this VM) ---
    val activeExternalDevice: StateFlow<TvDevice?> = castSessionManager.activeExternalDevice
    val externalStatus: StateFlow<PlaybackStatus?> = castSessionManager.externalStatus
    val externalMediaTitle: StateFlow<String?> = castSessionManager.externalMediaTitle
    val castSessionState = castSessionManager.sessionState

    // Foreground scan session. Discovery is time-boxed (SCAN_WINDOW_MS) so the SSDP
    // M-SEARCH loop and mDNS listener don't run forever; the UI binds [isScanning] for
    // the spinner and shows a Rescan affordance once a window elapses. rescan() re-arms it.
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    private var scanTimeoutJob: Job? = null

    // Stable identity sent to receivers during pairing so the TV can display a friendly name.
    private val phoneDeviceName: String = Build.MODEL
    private val phoneDeviceUUID: String = prefs.getString("pb_phone_uuid", null)
        ?: UUID.randomUUID().toString().also { prefs.edit { putString("pb_phone_uuid", it) } }

    private var hasAttemptedInitialConnect = false
    private var activeConnectingDevice: TvDevice? = null

    // Serialises read-modify-write updates to the saved TvDevice. The credentials and
    // capabilities collectors both fire on auth and each does tvDevice.first() → copy() →
    // save; without this lock a stale read lets one clobber the other's fields (e.g. a token
    // refresh wiping the just-stored players list, which is why receivers that echo the token
    // on every reconnect showed "TV Default" only).
    private val deviceUpdateMutex = Mutex()

    private suspend fun updateSavedDevice(transform: (TvDevice) -> TvDevice) {
        deviceUpdateMutex.withLock {
            val active = activeConnectingDevice
            val current = active ?: connectionStore.tvDevice.first()
            if (current == null) return@withLock
            val updated = transform(current)
            if (active != null) {
                activeConnectingDevice = null
            }
            if (updated == current && current != active) return@withLock
            Log.i(TAG, "Saving TV device ${updated.ip} (players=${updated.players})")
            connectionStore.saveTvDevice(updated)
            connectionStore.addToHistory(updated)
        }
    }

    init {
        // Handle Auto-connection
        viewModelScope.launch {
            tvDevice.combine(connectionState) { device, state ->
                Pair(device, state)
            }.collect { (device, state) ->
                // Only auto-connect on startup or initial discovery, not infinitely after disconnection.
                // Never auto-connect after AuthFailed — the token is wrong and we must not retry it.
                // Error is accepted alongside Disconnected so the UUID-healer below can re-arm a
                // failed startup attempt once discovery finds the TV at a fresh address (a failed
                // connect lands in Error, not Disconnected). wasUserDisconnect keeps a deliberate
                // disconnect from being overridden when this ViewModel is recreated.
                if (!hasAttemptedInitialConnect &&
                    _autoConnectEnabled.value &&
                    device != null &&
                    !webSocketClient.wasUserDisconnect &&
                    (state is WebSocketClient.ConnectionState.Disconnected ||
                        state is WebSocketClient.ConnectionState.Error)) {
                    hasAttemptedInitialConnect = true
                    Log.d(TAG, "Auto-connecting to saved TV: ${device.name} at ${device.ip}:${device.port}")
                    webSocketClient.connect(device.ip, device.port, device.token, device.name, phoneDeviceName, phoneDeviceUUID, device.wssPort, device.certFingerprint, device.uuid)
                }
            }
        }

        // Keep a foreground scan independent of the current connection. One television may expose
        // several protocols, and connecting PlayBridge must not hide DLNA/Roku/Google Cast results
        // while the Discover tab's bounded scan window is still active.

        // Handle Rust discovery updates for the saved native TV.
        viewModelScope.launch {
            discoveryRepository.devices.combine(tvDevice) { devices, savedDevice ->
                Pair(devices, savedDevice)
            }.collect { (devices, savedDevice) ->
                if (savedDevice != null && savedDevice.uuid.isNotEmpty()) {
                    val updatedDevice = ConnectionMerge.withDiscoveredEndpoint(savedDevice, devices)
                    if (updatedDevice != savedDevice) {
                        // Keep the saved address fresh for the next connect. Additionally
                        // (change 4): if the startup auto-connect already fired and lost the
                        // race against discovery — it tried the stale IP and failed while the
                        // TV had moved (router restart / DHCP change) — re-arm it, so the
                        // save below re-triggers the auto-connect collector with the healed
                        // address. Gated on a dead link and on the disconnect not being
                        // user-initiated, so this never spontaneously connects when the user
                        // is happily disconnected and merely opens the Cast sheet.
                        val st = connectionState.value
                        if (hasAttemptedInitialConnect &&
                            _autoConnectEnabled.value &&
                            !webSocketClient.wasUserDisconnect &&
                            (st is WebSocketClient.ConnectionState.Disconnected ||
                                st is WebSocketClient.ConnectionState.Error)
                        ) {
                            Log.i(TAG, "Saved TV endpoint changed (${savedDevice.ip}:${savedDevice.port} → ${updatedDevice.ip}:${updatedDevice.port}); re-arming auto-connect")
                            hasAttemptedInitialConnect = false
                        }
                        connectionStore.saveTvDevice(updatedDevice)
                        connectionStore.addToHistory(updatedDevice)
                    }
                }
            }
        }

        // Listen for new auth tokens + cert pins issued by the receiver.
        viewModelScope.launch {
            webSocketClient.newCredentials.collect { creds ->
                updateSavedDevice { device ->
                    device.copy(
                        token = creds.token,
                        certFingerprint = creds.certFingerprint ?: device.certFingerprint
                    )
                }
            }
        }

        // Cache the players/browsers the TV advertises at auth so the phone's pickers
        // reflect what this specific TV can actually drive (see TvCapabilityOptions).
        viewModelScope.launch {
            webSocketClient.tvCapabilities.collect { caps ->
                updateSavedDevice { device -> device.copy(players = caps.players, browsers = caps.browsers) }
            }
        }

        // On auth failure or pairing denial, wipe credentials for the device that
        // actually FAILED — not blindly whatever is in connectionStore.tvDevice.
        // Previously a denied pairing with TV B wiped the token of already-paired
        // TV A (the stored device), so tapping the saved TV A asked for a pairing
        // code again. activeConnectingDevice tells us which TV was being connected;
        // it is null only for the startup auto-connect, where the failing device IS
        // the stored one.
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is WebSocketClient.ConnectionState.AuthFailed,
                    is WebSocketClient.ConnectionState.PairingDenied -> {
                        val failed = activeConnectingDevice
                        activeConnectingDevice = null
                        val saved = connectionStore.tvDevice.first()
                        val (target, action) =
                            ConnectionMerge.resolveAuthFailure(failed, saved) ?: return@collect
                        when (action) {
                            ConnectionMerge.AuthFailureAction.CLEAR_SAVED_DEVICE ->
                                // First-time pairing with the stored device failed — forget it.
                                connectionStore.clearTvDevice()
                            ConnectionMerge.AuthFailureAction.WIPE_SAVED_TOKEN ->
                                // Was paired; wipe the token but keep the device so a tap re-pairs.
                                deviceUpdateMutex.withLock {
                                    val wiped = saved!!.copy(token = "")
                                    connectionStore.saveTvDevice(wiped)
                                    connectionStore.addToHistory(wiped)
                                }
                            ConnectionMerge.AuthFailureAction.WIPE_FAILED_HISTORY_ONLY ->
                                Unit // stored device untouched; history wipe below covers it
                        }
                        // Always invalidate the failing device's own history token so the
                        // next tap goes through pairing instead of retrying a rejected
                        // token. No-op if it was never saved; never touches other TVs.
                        connectionStore.wipeHistoryToken(target)
                    }
                    is WebSocketClient.ConnectionState.Error,
                    is WebSocketClient.ConnectionState.Disconnected -> {
                        // A dead link ends any in-flight connect attempt; drop the
                        // reference so a later failure can't be attributed to a stale
                        // device (and wipe the wrong token).
                        activeConnectingDevice = null
                    }
                    else -> Unit
                }
            }
        }
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        _autoConnectEnabled.value = enabled
        prefs.edit { putBoolean("auto_connect_tv", enabled) }
        // If enabling auto-connect and disconnected, try connecting immediately
        if (enabled && connectionState.value is WebSocketClient.ConnectionState.Disconnected) {
            viewModelScope.launch {
                val device = tvDevice.first()
                if (device != null) {
                    webSocketClient.connect(device.ip, device.port, device.token, device.name, phoneDeviceName, phoneDeviceUUID, device.wssPort, device.certFingerprint, device.uuid)
                }
            }
        }
    }

    fun connect(device: TvDevice) {
        // A deliberate connect is an explicit "watch on this TV" intent — record it as the
        // authoritative route so playback routing and the foreground-service / reconnect
        // supervisor agree. Cold-start auto-connect goes
        // through webSocketClient.connect() directly and intentionally does NOT set this.
        castSessionManager.selectNativeRoute()
        // A deliberate connect (user picked or cast to a device) re-enables auto-connect, which a
        // prior manual disconnect turned off. Set the flag directly rather than via
        // setAutoConnectEnabled() — its enable side-effect would kick off a second connect.
        _autoConnectEnabled.value = true
        prefs.edit { putBoolean("auto_connect_tv", true) }
        viewModelScope.launch {
            // The complete endpoint is live receiver state; a saved/history entry may
            // contain a stale address or predate TLS, so prefer current discovery by UUID.
            val merged = ConnectionMerge.withDiscoveredEndpoint(device, discoveryRepository.devices.value)
            Log.d(TAG, "Connecting to: ${merged.name} at ${merged.ip}:${merged.port} (wss=${merged.wssPort})")
            hasAttemptedInitialConnect = true
            activeConnectingDevice = merged
            webSocketClient.connect(merged.ip, merged.port, merged.token, merged.name, phoneDeviceName, phoneDeviceUUID, merged.wssPort, merged.certFingerprint, merged.uuid)
        }
    }

    fun submitPairingCode(code: String) {
        webSocketClient.submitPairingCode(code)
    }

    /** Select a DLNA renderer as the active cast target (drops any native session). */
    fun selectDlnaTarget(device: TvDevice): Boolean {
        val selected = castSessionManager.selectDlnaTarget(device)
        if (selected) viewModelScope.launch { connectionStore.addToHistory(device) }
        return selected
    }

    /** Select a Roku device as the active cast target. */
    fun selectRokuTarget(device: TvDevice) {
        castSessionManager.selectRokuTarget(device)
        viewModelScope.launch {
            connectionStore.addToHistory(device)
        }
    }

    fun rokuKeypress(key: String) = castSessionManager.rokuKeypress(key)

    /** Select a Google Cast (Chromecast) device as the active cast target. */
    fun selectGoogleCastTarget(device: TvDevice) {
        castSessionManager.selectGoogleCastTarget(device)
        viewModelScope.launch {
            connectionStore.addToHistory(device)
        }
    }

    fun disconnectExternalTarget() = castSessionManager.stopAndClearExternalTarget()

    fun castToSelectedExternal(media: MediaItem): Boolean = castSessionManager.load(media)

    fun externalPlay() = castSessionManager.play()
    fun externalPause() = castSessionManager.pause()
    fun externalStop() = castSessionManager.stop()
    fun externalSeek(positionMs: Long) = castSessionManager.seekTo(positionMs)
    fun externalSetVolume(percent: Int) = castSessionManager.setVolume(percent)
    fun externalAdjustVolume(up: Boolean) = castSessionManager.adjustVolume(up)

    /**
     * Cast an on-device file (content:// URI) to the active target. Prefers an active
     * external receiver; otherwise a connected native receiver (served via the proxy so
     * the TV can fetch it). Returns false if no target is available.
     */
    fun castLocalFile(uriString: String, mime: String?, title: String?, durationMs: Long = 0L): Boolean {
        val media = MediaItem(url = uriString, mimeType = mime, title = title, durationMs = durationMs)
        if (castSessionManager.load(media)) return true
        if (route.value is CastSessionManager.Route.NativeTv &&
            connectionState.value is WebSocketClient.ConnectionState.Connected
        ) {
            viewModelScope.launch {
                try {
                    val app = getApplication<Application>()
                    val packaged = com.playbridge.sender.cast.proxy.StreamRouteService(app)
                        .packageForCast(
                            media = com.playbridge.sender.cast.proxy.CastableMedia(
                                url = uriString,
                                contentType = mime,
                                title = title,
                                localUri = uriString.toUri(),
                            ),
                            mode = com.playbridge.sender.cast.proxy.StreamRouteMode.VIA_PHONE,
                        )
                    val cmd = createSingleVideoCommandJson(
                        PlayPayload(
                            url = packaged.url,
                            title = title ?: "Phone file",
                            content_type = packaged.contentType ?: mime,
                        ),
                    )
                    sendCommandAndRecord(cmd, "play", packaged.url, title)
                    // The TV only reports context when queried — flip it locally like every
                    // other play path, so the session FGS + NowPlayingBar engage.
                    castSessionManager.notifyNativePlaybackStarted()
                } catch (e: Exception) {
                    android.util.Log.e("ConnectionViewModel", "castLocalFile failed: ${e.message}")
                }
            }
            return true
        }
        return false
    }

    /**
     * Cast a web stream (e.g. an IPTV channel) with optional request [headers] to the active
     * target. Prefers an active external receiver; otherwise a connected
     * native receiver. Returns false if no target is available.
     */
    fun castWebStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        title: String? = null,
        mime: String? = null,
    ): Boolean {
        val media = MediaItem(url = url, headers = headers, mimeType = mime, title = title)
        if (castSessionManager.load(media)) return true
        if (route.value is CastSessionManager.Route.NativeTv &&
            connectionState.value is WebSocketClient.ConnectionState.Connected
        ) {
            viewModelScope.launch {
                try {
                    val app = getApplication<Application>()
                    val settings = com.playbridge.sender.cast.proxy.StreamProxySettingsStore.load(app)
                    val mode = settings.initialRouteMode()
                    val packaged = com.playbridge.sender.cast.proxy.StreamRouteService(app)
                        .packageForCast(
                            media = com.playbridge.sender.cast.proxy.CastableMedia(
                                url = url,
                                headers = headers,
                                contentType = mime,
                                title = title,
                            ),
                            mode = mode,
                            settings = settings,
                        )
                    val cmd = createSingleVideoCommandJson(
                        PlayPayload(
                            url = packaged.url,
                            title = title ?: "Channel",
                            headers = packaged.headers ?: emptyMap(),
                            content_type = packaged.contentType ?: mime,
                        ),
                    )
                    sendCommandAndRecord(cmd, "play", packaged.url, title)
                    castSessionManager.notifyNativePlaybackStarted()
                } catch (e: Exception) {
                    android.util.Log.e("ConnectionViewModel", "castWebStream failed: ${e.message}")
                }
            }
            return true
        }
        return false
    }

    fun disconnect() {
        activeConnectingDevice = null
        castSessionManager.selectThisDevice()
        webSocketClient.disconnect()
        // Also disable auto-connect so it doesn't immediately reconnect
        setAutoConnectEnabled(false)
    }

    /** Live retry-cycle info for the "Reconnecting to <TV>" popup (null = no cycle). */
    val reconnectStatus = castSessionManager.reconnectStatus

    /**
     * Cancel on the connecting/reconnecting popup: stop dialling the TV and route
     * playback to this phone, per the popup's promise.
     */
    fun cancelConnectingToThisDevice() {
        disconnect()
        castSessionManager.cancelReconnect() // clears retry state + selects This Device
    }


    fun removeDeviceFromHistory(device: TvDevice) {
        viewModelScope.launch {
            connectionStore.removeFromHistory(device)

            // If the removed device is the currently saved one, clear it
            val currentSaved = connectionStore.tvDevice.first()
            if (currentSaved != null && ConnectionMerge.isSameDevice(currentSaved, device)) {
                connectionStore.clearTvDevice()
            }
        }
    }



    fun sendCommandAndRecord(commandJson: String, type: String, url: String, title: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            commandHistoryDb.commandHistoryDao().insert(
                CommandHistoryEntity(
                    commandType = type,
                    url = url,
                    title = title,
                    payloadJson = commandJson
                )
            )
        }
        Log.d(TAG, "Sending command payload: $commandJson")
        webSocketClient.send(commandJson)
    }
    /**
     * Start a time-boxed scan: run mDNS + DLNA SSDP discovery for [SCAN_WINDOW_MS], then
     * stop automatically. Idempotent for the engines (their start() is a no-op when already
     * running); calling again re-arms the timeout, so this doubles as rescan().
     */
    fun startDiscovery(protocols: Set<CastProtocol> = _selectedDiscoveryProtocols.value) {
        _selectedDiscoveryProtocols.value = protocols.ifEmpty { CastProtocol.entries.toSet() }
        scanTimeoutJob?.cancel()
        discoveryRepository.start(
            owner = ReceiverDiscoveryRepository.OWNER_UI,
            protocols = _selectedDiscoveryProtocols.value,
            timeoutMs = SCAN_WINDOW_MS,
        ) { summary ->
            Log.i(
                TAG,
                "Rust discovery finished: PlayBridge=${summary.playBridgeDevices}, DLNA=${summary.dlnaDevices}, Roku=${summary.rokuDevices}, GoogleCast=${summary.googleCastDevices}, errors=${summary.errors}"
            )
        }
        _isScanning.value = true
        scanTimeoutJob = viewModelScope.launch {
            delay(SCAN_WINDOW_MS)
            stopDiscovery()
        }
    }

    /** User-triggered re-scan (e.g. the Rescan button); restarts the scan window. */
    fun rescan() = startDiscovery()

    fun setDiscoveryProtocols(protocols: Set<CastProtocol>) = startDiscovery(protocols)

    fun stopDiscovery() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        discoveryRepository.stop(ReceiverDiscoveryRepository.OWNER_UI)
        _isScanning.value = false
    }

    override fun onCleared() {
        super.onCleared()
        discoveryRepository.stop(ReceiverDiscoveryRepository.OWNER_UI)
        // While a cast session is active, CastSessionManager + CastSessionService own the
        // socket's lifetime — episode queueing must survive this ViewModel (screen-off /
        // activity death). Otherwise close the socket as before. Never destroy(): the
        // WebSocketClient singleton's OkHttp executor must stay usable for reconnects.
        if (!castSessionManager.hasActiveSession.value) {
            webSocketClient.disconnect()
        }
    }

    companion object {
        // Two-plus DLNA SSDP refresh cycles (6s each) plus headroom for mDNS resolves.
        private const val SCAN_WINDOW_MS = 15_000L
    }
}
