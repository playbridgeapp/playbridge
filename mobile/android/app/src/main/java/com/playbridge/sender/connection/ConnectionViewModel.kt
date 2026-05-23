package com.playbridge.sender.connection

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playbridge.sender.data.history.DatabaseProvider
import kotlinx.coroutines.Dispatchers
import com.playbridge.sender.data.history.CommandHistoryEntity
import com.playbridge.sender.model.TvDevice
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "ConnectionViewModel"

    val webSocketClient = WebSocketClient()
    val bluetoothClient = BluetoothClient(application)
    private val connectionStore = ConnectionStore(application)
    private val nsdHelper = NsdHelper(application)
    private val prefs = application.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
    private val commandHistoryDb = DatabaseProvider.getDatabase(application)

    // Exposed Flows
    val connectionState: StateFlow<WebSocketClient.ConnectionState> = webSocketClient.connectionState
    val tvDevice: Flow<TvDevice?> = connectionStore.tvDevice

    // Map NsdHelper.DiscoveredDevice to TvDevice for simpler UI handling
    val discoveredDevices: StateFlow<List<TvDevice>> = nsdHelper.discoveredDevices.map { devices ->
        devices.map { TvDevice(ip = it.ip, port = it.port, name = it.name, token = "", uuid = it.uuid, wssPort = it.wssPort) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun getSavedBluetoothMacForTv(tvUuid: String?): String? {
        if (tvUuid.isNullOrEmpty()) return null
        return prefs.getString("tv_bt_mac_$tvUuid", null)
    }

    fun saveBluetoothMacForTv(tvUuid: String?, macAddress: String) {
        if (tvUuid.isNullOrEmpty()) return
        prefs.edit().putString("tv_bt_mac_$tvUuid", macAddress).apply()
    }

    val deviceHistory: Flow<List<TvDevice>> = connectionStore.deviceHistory

    private val _autoConnectEnabled = MutableStateFlow(prefs.getBoolean("auto_connect_tv", true))
    val autoConnectEnabled: StateFlow<Boolean> = _autoConnectEnabled.asStateFlow()

    // Stable identity sent to receivers during pairing so the TV can display a friendly name.
    private val phoneDeviceName: String = Build.MODEL
    private val phoneDeviceUUID: String = prefs.getString("pb_phone_uuid", null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString("pb_phone_uuid", it).apply() }

    private var hasAttemptedInitialConnect = false

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

        // Manage background NSD discovery
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state !is WebSocketClient.ConnectionState.Connected &&
                    state !is WebSocketClient.ConnectionState.Connecting) {
                    nsdHelper.startDiscovery()
                } else {
                    nsdHelper.stopDiscovery()
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
                val currentDevice = connectionStore.tvDevice.first()
                if (currentDevice != null) {
                    Log.i(TAG, "Updating stored token/pin for ${currentDevice.ip}")
                    val updatedDevice = currentDevice.copy(
                        token = creds.token,
                        certFingerprint = creds.certFingerprint ?: currentDevice.certFingerprint
                    )
                    connectionStore.saveTvDevice(updatedDevice)
                    connectionStore.addToHistory(updatedDevice)
                }
            }
        }

        // On auth failure or pairing denial, wipe the token so the next tap triggers
        // a fresh pairing_request instead of silently retrying the wrong token.
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state is WebSocketClient.ConnectionState.AuthFailed ||
                    state is WebSocketClient.ConnectionState.PairingDenied) {
                    val currentDevice = connectionStore.tvDevice.first()
                    if (currentDevice != null && currentDevice.token.isNotEmpty()) {
                        Log.i(TAG, "Auth/pairing failed — clearing token for ${currentDevice.ip}")
                        val clearedDevice = currentDevice.copy(token = "")
                        connectionStore.saveTvDevice(clearedDevice)
                        connectionStore.addToHistory(clearedDevice)
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
        viewModelScope.launch {
            // wss_port is a live property of the receiver; a saved/history entry may
            // predate TLS, so prefer the port from current discovery (matched by uuid,
            // falling back to ip/port). Keeps the device's token + certFingerprint.
            val discovered = discoveredDevices.value.let { list ->
                (if (device.uuid.isNotEmpty()) list.find { it.uuid == device.uuid } else null)
                    ?: list.find { it.ip == device.ip && it.port == device.port }
            }
            val merged = device.copy(wssPort = discovered?.wssPort ?: device.wssPort)
            Log.d(TAG, "Connecting to: ${merged.name} at ${merged.ip}:${merged.port} (wss=${merged.wssPort})")
            hasAttemptedInitialConnect = true
            connectionStore.saveTvDevice(merged)
            connectionStore.addToHistory(merged)
            webSocketClient.connect(merged.ip, merged.port, merged.token, merged.name, phoneDeviceName, phoneDeviceUUID, merged.wssPort, merged.certFingerprint)
        }
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
    }

    fun stopDiscovery() {
        nsdHelper.stopDiscovery()
    }

    override fun onCleared() {
        super.onCleared()
        webSocketClient.destroy()
        bluetoothClient.destroy()
        nsdHelper.stopDiscovery()
    }
}
