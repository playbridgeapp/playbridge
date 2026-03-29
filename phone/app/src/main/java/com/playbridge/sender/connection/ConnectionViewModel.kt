package com.playbridge.sender.connection

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.playbridge.sender.data.history.DatabaseProvider
import kotlinx.coroutines.Dispatchers
import com.playbridge.sender.data.history.CommandHistoryEntity
import com.playbridge.sender.model.TvDevice
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
        devices.map { TvDevice(ip = it.ip, port = it.port, name = it.name, token = "", uuid = it.uuid) }
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
                    webSocketClient.connect(device.ip, device.port, device.token, device.name)
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
                    if (matchedDevice != null && (matchedDevice.ip != savedDevice.ip || matchedDevice.port != savedDevice.port)) {
                        Log.d(TAG, "NSD discovered saved TV at new IP: ${matchedDevice.ip}:${matchedDevice.port}. Updating and reconnecting.")
                        val updatedDevice = savedDevice.copy(
                            ip = matchedDevice.ip,
                            port = matchedDevice.port,
                            name = matchedDevice.name
                        )
                        connectionStore.saveTvDevice(updatedDevice)
                        connectionStore.addToHistory(updatedDevice)
                        webSocketClient.connect(updatedDevice.ip, updatedDevice.port, updatedDevice.token, updatedDevice.name)
                    }
                }
            }
        }

        // Listen for new auth tokens
        viewModelScope.launch {
            webSocketClient.newToken.collect { token ->
                val currentDevice = connectionStore.tvDevice.first()
                if (currentDevice != null) {
                    Log.i(TAG, "Updating stored token for ${currentDevice.ip}")
                    val updatedDevice = currentDevice.copy(token = token)
                    connectionStore.saveTvDevice(updatedDevice)
                    connectionStore.addToHistory(updatedDevice)
                }
            }
        }

        // On auth failure, wipe the stale token from storage so the next tap on this device
        // shows the PIN dialog instead of silently retrying the wrong token (e.g. after TV reinstall).
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state is WebSocketClient.ConnectionState.AuthFailed) {
                    val currentDevice = connectionStore.tvDevice.first()
                    if (currentDevice != null && currentDevice.token.isNotEmpty()) {
                        Log.i(TAG, "Auth failed — clearing stale token for ${currentDevice.ip}")
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
                    webSocketClient.connect(device.ip, device.port, device.token, device.name)
                }
            }
        }
    }

    fun connect(device: TvDevice) {
        viewModelScope.launch {
            Log.d(TAG, "Connecting to: ${device.name} at ${device.ip}:${device.port}")
            hasAttemptedInitialConnect = true
            connectionStore.saveTvDevice(device)
            connectionStore.addToHistory(device)
            webSocketClient.connect(device.ip, device.port, device.token, device.name)
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
