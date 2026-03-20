package com.playbridge.sender.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.playbridge.protocol.BluetoothConstants
import com.playbridge.protocol.Command
import com.playbridge.protocol.createMouseCommandJson
import com.playbridge.protocol.createRemoteCommandJson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class BluetoothClient(private val context: Context) {
    private val TAG = "BluetoothClient"

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun connect() {
        if (_connectionState.value is ConnectionState.Connected || _connectionState.value is ConnectionState.Connecting) {
            return
        }

        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    _connectionState.value = ConnectionState.Error("Missing BLUETOOTH_CONNECT or BLUETOOTH_SCAN permission")
                    return@launch
                }
            }

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                _connectionState.value = ConnectionState.Error("Bluetooth is not supported or disabled")
                return@launch
            }

            @SuppressLint("MissingPermission")
            val pairedDevices = bluetoothAdapter.bondedDevices
            if (pairedDevices.isNullOrEmpty()) {
                _connectionState.value = ConnectionState.Error("No paired devices found")
                return@launch
            }

            val uuid = UUID.fromString(BluetoothConstants.SERVICE_UUID_STRING)
            var connectedSocket: BluetoothSocket? = null

            // Try to connect to each paired device with the specific UUID
            @SuppressLint("MissingPermission")
            for (device in pairedDevices) {
                var tempSocket: BluetoothSocket? = null
                try {
                    tempSocket = device.createRfcommSocketToServiceRecord(uuid)

                    // Cancel discovery as it slows down the connection
                    bluetoothAdapter.cancelDiscovery()

                    tempSocket.connect()
                    connectedSocket = tempSocket
                    Log.i(TAG, "Connected securely to Bluetooth device: ${device.name}")
                    break
                } catch (e: IOException) {
                    Log.d(TAG, "Failed secure connection to ${device.name}, trying insecure fallback...", e)
                    try {
                        tempSocket?.close()
                    } catch (closeException: IOException) {
                        Log.e(TAG, "Could not close the client socket", closeException)
                    }

                    // Fallback to insecure socket
                    try {
                        tempSocket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                        tempSocket.connect()
                        connectedSocket = tempSocket
                        Log.i(TAG, "Connected insecurely to Bluetooth device: ${device.name}")
                        break
                    } catch (fallbackException: IOException) {
                        Log.d(TAG, "Failed insecure connection to ${device.name} as well", fallbackException)
                        try {
                            tempSocket?.close()
                        } catch (closeException: IOException) {
                            Log.e(TAG, "Could not close the client socket", closeException)
                        }
                    }
                }
            }

            if (connectedSocket != null) {
                socket = connectedSocket
                try {
                    outputStream = socket?.outputStream
                    _connectionState.value = ConnectionState.Connected
                } catch (e: IOException) {
                    Log.e(TAG, "Error creating output stream", e)
                    disconnect()
                    _connectionState.value = ConnectionState.Error("Failed to open data stream")
                }
            } else {
                _connectionState.value = ConnectionState.Error("Could not connect to PlayBridge TV service on any paired device")
            }
        }
    }

    fun sendRemoteCommand(key: String) {
        val json = createRemoteCommandJson(key)
        send(json)
    }

    fun sendMouseCommand(event: String, dx: Float, dy: Float) {
        val json = createMouseCommandJson(event, dx, dy)
        send(json)
    }

    fun send(jsonCommand: String) {
        scope.launch {
            if (_connectionState.value != ConnectionState.Connected) {
                Log.w(TAG, "Cannot send command: not connected to Bluetooth")
                return@launch
            }

            try {
                // Add a newline to separate commands in the stream
                val message = "$jsonCommand\n"
                outputStream?.write(message.toByteArray())
                outputStream?.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Failed to send command over Bluetooth", e)
                disconnect()
            }
        }
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing Bluetooth socket", e)
        }
        socket = null
        outputStream = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
