package com.playbridge.sender.connection

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playbridge.sender.data.history.DatabaseProvider
import kotlinx.coroutines.Dispatchers
import com.playbridge.sender.data.history.CommandHistoryEntity
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.dlna.AvTransportClient
import com.playbridge.sender.cast.dlna.DeviceDescription
import com.playbridge.sender.cast.dlna.DlnaCastTarget
import com.playbridge.sender.cast.dlna.DlnaDiscovery
import com.playbridge.sender.cast.dlna.DlnaProxyHolder
import com.playbridge.sender.cast.dlna.DlnaProxyService
import com.playbridge.shared.protocol.createSingleVideoCommandJson
import playbridge.PlayPayload
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

class ConnectionViewModel(
    application: Application,
    val webSocketClient: WebSocketClient = WebSocketClient(),
    private val connectionStore: ConnectionStore = ConnectionStore(application),
    private val nsdHelper: NsdHelper = NsdHelper(application),
    private val commandHistoryDb: com.playbridge.sender.data.history.HistoryDatabase = DatabaseProvider.getDatabase(application)
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

    private val _autoConnectEnabled = MutableStateFlow(prefs.getBoolean("auto_connect_tv", true))
    val autoConnectEnabled: StateFlow<Boolean> = _autoConnectEnabled.asStateFlow()

    // --- DLNA cast target (third-party renderer; no WS session) ---
    private val _activeDlnaTarget = MutableStateFlow<TvDevice?>(null)
    val activeDlnaTarget: StateFlow<TvDevice?> = _activeDlnaTarget.asStateFlow()
    private val _dlnaStatus = MutableStateFlow<PlaybackStatus?>(null)
    val dlnaStatus: StateFlow<PlaybackStatus?> = _dlnaStatus.asStateFlow()
    private var dlnaCastTarget: DlnaCastTarget? = null
    private var dlnaStatusJob: Job? = null

    // Stable identity sent to receivers during pairing so the TV can display a friendly name.
    private val phoneDeviceName: String = Build.MODEL
    private val phoneDeviceUUID: String = prefs.getString("pb_phone_uuid", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("pb_phone_uuid", it).apply() }

    private var hasAttemptedInitialConnect = false

    // Serialises read-modify-write updates to the saved TvDevice. The credentials and
    // capabilities collectors both fire on auth and each does tvDevice.first() → copy() →
    // save; without this lock a stale read lets one clobber the other's fields (e.g. a token
    // refresh wiping the just-stored players list, which is why receivers that echo the token
    // on every reconnect showed "TV Default" only).
    private val deviceUpdateMutex = Mutex()

    private suspend fun updateSavedDevice(transform: (TvDevice) -> TvDevice) {
        deviceUpdateMutex.withLock {
            val current = connectionStore.tvDevice.first() ?: return@withLock
            val updated = transform(current)
            if (updated == current) return@withLock
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
                if (!hasAttemptedInitialConnect &&
                    _autoConnectEnabled.value &&
                    device != null &&
                    state is WebSocketClient.ConnectionState.Disconnected) {
                    hasAttemptedInitialConnect = true
                    Log.d(TAG, "Auto-connecting to saved TV: ${device.name} at ${device.ip}:${device.port}")
                    webSocketClient.connect(device.ip, device.port, device.token, device.name, phoneDeviceName, phoneDeviceUUID, device.wssPort, device.certFingerprint)
                }
            }
        }

        // Manage background discovery (mDNS + DLNA SSDP)
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state !is WebSocketClient.ConnectionState.Connected &&
                    state !is WebSocketClient.ConnectionState.Connecting) {
                    nsdHelper.startDiscovery()
                    dlnaDiscovery.start(viewModelScope)
                } else {
                    nsdHelper.stopDiscovery()
                    dlnaDiscovery.stop()
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
                        Log.d(TAG, "NSD discovered saved TV at new IP/port: ${matchedDevice.ip}:${matchedDevice.port} (wss=${matchedDevice.wssPort}). Updating and reconnecting.")
                        val updatedDevice = savedDevice.copy(
                            ip = matchedDevice.ip,
                            port = matchedDevice.port,
                            name = matchedDevice.name,
                            wssPort = matchedDevice.wssPort
                        )
                        connectionStore.saveTvDevice(updatedDevice)
                        connectionStore.addToHistory(updatedDevice)
                        webSocketClient.connect(updatedDevice.ip, updatedDevice.port, updatedDevice.token, updatedDevice.name, phoneDeviceName, phoneDeviceUUID, updatedDevice.wssPort, updatedDevice.certFingerprint)
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

        // On auth failure or pairing denial, wipe the token so the next tap triggers
        // a fresh pairing_request instead of silently retrying the wrong token.
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state is WebSocketClient.ConnectionState.AuthFailed ||
                    state is WebSocketClient.ConnectionState.PairingDenied) {
                    updateSavedDevice { device ->
                        if (device.token.isNotEmpty()) device.copy(token = "") else device
                    }
                }
            }
        }
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        _autoConnectEnabled.value = enabled
        prefs.edit().putBoolean("auto_connect_tv", enabled).apply()
        // If enabling auto-connect and disconnected, try connecting immediately
        if (enabled && connectionState.value is WebSocketClient.ConnectionState.Disconnected) {
            viewModelScope.launch {
                val device = tvDevice.first()
                if (device != null) {
                    webSocketClient.connect(device.ip, device.port, device.token, device.name, phoneDeviceName, phoneDeviceUUID, device.wssPort, device.certFingerprint)
                }
            }
        }
    }

    fun connect(device: TvDevice) {
        // Connecting natively supersedes any DLNA target.
        clearDlnaTarget()
        viewModelScope.launch {
            // wss_port is a live property of the receiver; a saved/history entry may
            // predate TLS, so prefer the port from current discovery.
            val merged = ConnectionMerge.withDiscoveredWssPort(device, discoveredDevices.value)
            Log.d(TAG, "Connecting to: ${merged.name} at ${merged.ip}:${merged.port} (wss=${merged.wssPort})")
            hasAttemptedInitialConnect = true
            connectionStore.saveTvDevice(merged)
            connectionStore.addToHistory(merged)
            webSocketClient.connect(merged.ip, merged.port, merged.token, merged.name, phoneDeviceName, phoneDeviceUUID, merged.wssPort, merged.certFingerprint)
        }
    }

    /** Select a DLNA renderer as the active cast target (drops any native session). */
    fun selectDlnaTarget(device: TvDevice) {
        val controlUrl = device.controlUrl ?: return
        webSocketClient.disconnect() // a single target is active at a time
        dlnaStatusJob?.cancel()
        dlnaCastTarget?.release()
        val target = DlnaCastTarget(
            id = device.uuid,
            name = device.name,
            avTransport = AvTransportClient(controlUrl, DlnaProxyHolder.httpClient),
            proxy = DlnaProxyHolder.proxy(getApplication<Application>()),
        )
        dlnaCastTarget = target
        _activeDlnaTarget.value = device
        DlnaProxyService.start(getApplication<Application>()) // keep the proxy alive on screen-off
        dlnaStatusJob = viewModelScope.launch { target.status().collect { _dlnaStatus.value = it } }
        Log.d(TAG, "Active DLNA target: ${device.name} ($controlUrl)")
    }

    fun clearDlnaTarget() {
        dlnaStatusJob?.cancel()
        dlnaStatusJob = null
        dlnaCastTarget?.release()
        dlnaCastTarget = null
        _dlnaStatus.value = null
        _activeDlnaTarget.value = null
        DlnaProxyService.stop(getApplication<Application>())
    }

    /** Cast a media item to the active DLNA target. No-op if none selected. */
    fun playOnDlna(media: MediaItem) {
        val target = dlnaCastTarget ?: return
        viewModelScope.launch { target.load(media) }
    }

    fun dlnaPlay() { dlnaCastTarget?.let { t -> viewModelScope.launch { t.play() } } }
    fun dlnaPause() { dlnaCastTarget?.let { t -> viewModelScope.launch { t.pause() } } }
    fun dlnaStop() { dlnaCastTarget?.let { t -> viewModelScope.launch { t.stop() } } }
    fun dlnaSeek(positionMs: Long) { dlnaCastTarget?.let { t -> viewModelScope.launch { t.seekTo(positionMs) } } }

    /**
     * Cast an on-device file (content:// URI) to the active target. Prefers an active
     * DLNA renderer; otherwise a connected native receiver (served via the proxy so
     * the TV can fetch it). Returns false if no target is available.
     */
    fun castLocalFile(uriString: String, mime: String?, title: String?): Boolean {
        val dlna = dlnaCastTarget
        if (dlna != null) {
            viewModelScope.launch { dlna.load(MediaItem(url = uriString, mimeType = mime, title = title)) }
            return true
        }
        if (connectionState.value is WebSocketClient.ConnectionState.Connected) {
            viewModelScope.launch {
                val proxyUrl = DlnaProxyHolder.proxy(getApplication<Application>())
                    .publishLocal(Uri.parse(uriString), mime)
                val cmd = createSingleVideoCommandJson(
                    PlayPayload(url = proxyUrl, title = title ?: "Phone file", content_type = mime),
                )
                sendCommandAndRecord(cmd, "play", proxyUrl, title)
            }
            return true
        }
        return false
    }

    fun disconnect() {
        webSocketClient.disconnect()
        // Also disable auto-connect so it doesn't immediately reconnect
        setAutoConnectEnabled(false)
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
    fun startDiscovery() {
        nsdHelper.startDiscovery()
        dlnaDiscovery.start(viewModelScope)
    }

    fun stopDiscovery() {
        nsdHelper.stopDiscovery()
        dlnaDiscovery.stop()
    }

    override fun onCleared() {
        super.onCleared()
        // WebSocketClient is a process-wide singleton reused by the next Activity/ViewModel, so
        // only close the socket here — destroy() shuts down its OkHttp executor permanently, which
        // would make every later reconnect fail with "executor rejected".
        webSocketClient.disconnect()
        nsdHelper.stopDiscovery()
        dlnaDiscovery.stop()
        DlnaProxyService.stop(getApplication<Application>())
    }
}
