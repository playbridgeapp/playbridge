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
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.dlna.DeviceDescription
import com.playbridge.sender.cast.dlna.DlnaDiscovery
import com.playbridge.sender.cast.dlna.DlnaProxyHolder
import com.playbridge.shared.protocol.createSingleVideoCommandJson
import playbridge.PlayPayload
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.net.URI
import java.net.Socket
import java.net.InetSocketAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.util.UUID
import java.util.concurrent.TimeUnit

class ConnectionViewModel(
    application: Application,
    val webSocketClient: WebSocketClient = WebSocketClient(),
    private val connectionStore: ConnectionStore = ConnectionStore(application),
    private val nsdHelper: NsdHelper = NsdHelper(application),
    private val commandHistoryDb: com.playbridge.sender.data.history.HistoryDatabase = DatabaseProvider.getDatabase(application),
    private val castSessionManager: CastSessionManager
) : AndroidViewModel(application) {

    private val TAG = "ConnectionViewModel"
    private val prefs = application.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)

    // Exposed Flows
    val connectionState: StateFlow<WebSocketClient.ConnectionState> = webSocketClient.connectionState
    val tvDevice: Flow<TvDevice?> = connectionStore.tvDevice

    // OkHttp + continuous SSDP discovery for third-party DLNA renderers, run alongside mDNS.
    private val dlnaHttp = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
    private val dlnaDiscovery = DlnaDiscovery(application, dlnaHttp)

    // Native (mDNS) + DLNA (SSDP) discovery merged into one list for the UI.
    val discoveredDevices: StateFlow<List<TvDevice>> = combine(
        nsdHelper.discoveredDevices,
        dlnaDiscovery.renderers,
    ) { native, renderers ->
        val nativeTv = native.map {
            TvDevice(ip = it.ip, port = it.port, name = it.name, token = "", uuid = it.uuid, wssPort = it.wssPort)
        }
        ConnectionMerge.mergeDiscovered(nativeTv, renderers.map { it.toDlnaTvDevice() })
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private fun DeviceDescription.Renderer.toDlnaTvDevice(): TvDevice {
        val uri = avTransportControlUrl?.let { runCatching { URI(it) }.getOrNull() }
        return TvDevice(
            ip = uri?.host.orEmpty(),
            port = (uri?.port ?: -1).takeIf { it > 0 } ?: 0,
            token = "",
            name = friendlyName,
            uuid = udn ?: location,
            isDlna = true,
            controlUrl = avTransportControlUrl,
        )
    }

    val deviceHistory: Flow<List<TvDevice>> = connectionStore.deviceHistory

    private val _savedDevicesOnlineStatus = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val savedDevicesOnlineStatus: StateFlow<Map<String, Boolean>> = _savedDevicesOnlineStatus.asStateFlow()

    fun pingSavedDevices(devices: List<TvDevice>) {
        viewModelScope.launch(Dispatchers.IO) {
            val results = devices.map { device ->
                async {
                    val online = try {
                        Socket().use { socket ->
                            val portToPing = device.wssPort ?: device.port
                            socket.connect(InetSocketAddress(device.ip, portToPing), 600)
                            true
                        }
                    } catch (_: Exception) {
                        false
                    }
                    device.uuid to online
                }
            }.awaitAll().toMap()
            _savedDevicesOnlineStatus.value = results
        }
    }

    private val _autoConnectEnabled = MutableStateFlow(prefs.getBoolean("auto_connect_tv", true))
    val autoConnectEnabled: StateFlow<Boolean> = _autoConnectEnabled.asStateFlow()

    // --- Routing intent (authoritative; owned by CastSessionManager) ---
    // Screens read this to decide where playback goes instead of inferring from
    // connectionState / the old `watch_on_tv` pref. See CONNECTION_ROUTING_PLAN.md.
    val route: StateFlow<CastSessionManager.Route> = castSessionManager.route
    val routeTargetsTv: StateFlow<Boolean> = castSessionManager.routeTargetsTv

    /** User picked "This Device": route phone-local without forcing a disconnect. */
    fun selectThisDevice() = castSessionManager.selectThisDevice()

    /** User picked the saved native TV as the routing target. */
    fun selectNativeRoute() = castSessionManager.selectNativeRoute()

    // --- DLNA cast target (owned by CastSessionManager so a cast survives this VM) ---
    val activeDlnaTarget: StateFlow<TvDevice?> = castSessionManager.activeDlnaTarget
    val dlnaStatus: StateFlow<PlaybackStatus?> = castSessionManager.dlnaStatus
    val dlnaMediaTitle: StateFlow<String?> = castSessionManager.dlnaMediaTitle

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

        // Manage discovery (mDNS + DLNA SSDP). Stop discovery once we're connected — but
        // NOT while merely Connecting: a failing (re)connect flaps through Connecting
        // repeatedly, and killing the scan there would suppress the very UUID heal that
        // fixes a stale IP after the TV moved (router restart).
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state is WebSocketClient.ConnectionState.Connected) {
                    stopDiscovery()
                }
            }
        }

        // Handle NSD discovery for saved TV
        viewModelScope.launch {
            discoveredDevices.combine(tvDevice) { devices, savedDevice ->
                Pair(devices, savedDevice)
            }.collect { (devices, savedDevice) ->
                if (_autoConnectEnabled.value && savedDevice != null && savedDevice.uuid.isNotEmpty()) {
                    val matchedDevice = devices.find { it.uuid == savedDevice.uuid }
                    if (matchedDevice != null && (matchedDevice.ip != savedDevice.ip || matchedDevice.port != savedDevice.port || matchedDevice.wssPort != savedDevice.wssPort)) {
                        val updatedDevice = savedDevice.copy(
                            ip = matchedDevice.ip,
                            port = matchedDevice.port,
                            name = matchedDevice.name,
                            wssPort = matchedDevice.wssPort
                        )
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
                            Log.i(TAG, "Saved TV moved (${savedDevice.ip} → ${matchedDevice.ip}); re-arming auto-connect")
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
        // Connecting natively supersedes any DLNA target.
        clearDlnaTarget()
        // A deliberate connect is an explicit "watch on this TV" intent — record it as the
        // authoritative route so playback routing and the foreground-service / reconnect
        // supervisor agree (see CONNECTION_ROUTING_PLAN.md). Cold-start auto-connect goes
        // through webSocketClient.connect() directly and intentionally does NOT set this.
        castSessionManager.selectNativeRoute()
        // A deliberate connect (user picked or cast to a device) re-enables auto-connect, which a
        // prior manual disconnect turned off. Set the flag directly rather than via
        // setAutoConnectEnabled() — its enable side-effect would kick off a second connect.
        _autoConnectEnabled.value = true
        prefs.edit { putBoolean("auto_connect_tv", true) }
        viewModelScope.launch {
            // wss_port is a live property of the receiver; a saved/history entry may
            // predate TLS, so prefer the port from current discovery.
            val merged = ConnectionMerge.withDiscoveredWssPort(device, discoveredDevices.value)
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
    fun selectDlnaTarget(device: TvDevice) = castSessionManager.selectDlnaTarget(device)

    fun clearDlnaTarget() = castSessionManager.clearDlnaTarget()

    /** Cast a media item to the active DLNA target. No-op if none selected. */
    fun playOnDlna(media: MediaItem) = castSessionManager.playOnDlna(media)

    fun dlnaPlay() = castSessionManager.dlnaPlay()
    fun dlnaPause() = castSessionManager.dlnaPause()
    fun dlnaStop() = castSessionManager.dlnaStop()
    fun dlnaSeek(positionMs: Long) = castSessionManager.dlnaSeek(positionMs)

    /**
     * Cast an on-device file (content:// URI) to the active target. Prefers an active
     * DLNA renderer; otherwise a connected native receiver (served via the proxy so
     * the TV can fetch it). Returns false if no target is available.
     */
    fun castLocalFile(uriString: String, mime: String?, title: String?, durationMs: Long = 0L): Boolean {
        if (castSessionManager.isDlnaActive) {
            castSessionManager.playOnDlna(
                MediaItem(url = uriString, mimeType = mime, title = title, durationMs = durationMs)
            )
            return true
        }
        if (connectionState.value is WebSocketClient.ConnectionState.Connected) {
            viewModelScope.launch {
                val proxyUrl = DlnaProxyHolder.proxy(getApplication<Application>())
                    .publishLocal(uriString.toUri(), mime)
                val cmd = createSingleVideoCommandJson(
                    PlayPayload(url = proxyUrl, title = title ?: "Phone file", content_type = mime),
                )
                sendCommandAndRecord(cmd, "play", proxyUrl, title)
                // The TV only reports context when queried — flip it locally like every
                // other play path, so the session FGS + NowPlayingBar engage.
                castSessionManager.notifyNativePlaybackStarted()
            }
            return true
        }
        return false
    }

    /**
     * Cast a web stream (e.g. an IPTV channel) with optional request [headers] to the active
     * target. Prefers an active DLNA renderer (proxy injects headers); otherwise a connected
     * native receiver. Returns false if no target is available.
     */
    fun castWebStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        title: String? = null,
        mime: String? = null,
    ): Boolean {
        if (castSessionManager.isDlnaActive) {
            castSessionManager.playOnDlna(
                MediaItem(url = url, headers = headers, mimeType = mime, title = title)
            )
            return true
        }
        if (connectionState.value is WebSocketClient.ConnectionState.Connected) {
            val cmd = createSingleVideoCommandJson(
                PlayPayload(url = url, title = title ?: "Channel", headers = headers, content_type = mime),
            )
            sendCommandAndRecord(cmd, "play", url, title)
            castSessionManager.notifyNativePlaybackStarted()
            return true
        }
        return false
    }

    fun disconnect() {
        activeConnectingDevice = null
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
            if (currentSaved != null && currentSaved.ip == device.ip && currentSaved.port == device.port) {
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
    fun startDiscovery() {
        scanTimeoutJob?.cancel()
        nsdHelper.startDiscovery()
        dlnaDiscovery.start(viewModelScope)
        _isScanning.value = true
        scanTimeoutJob = viewModelScope.launch {
            delay(SCAN_WINDOW_MS)
            stopDiscovery()
        }
    }

    /** User-triggered re-scan (e.g. the Rescan button); restarts the scan window. */
    fun rescan() = startDiscovery()

    fun stopDiscovery() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        nsdHelper.stopDiscovery()
        dlnaDiscovery.stop()
        _isScanning.value = false
    }

    override fun onCleared() {
        super.onCleared()
        nsdHelper.stopDiscovery()
        dlnaDiscovery.stop()
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
