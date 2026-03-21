package com.playbridge.sender.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.playbridge.protocol.BluetoothConstants
import com.playbridge.protocol.createMouseCommandJson
import com.playbridge.protocol.createRemoteCommandJson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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

    // Single writer channel — eliminates per-send coroutine launch overhead.
    // All outgoing messages funnel here and are written sequentially by one coroutine.
    private val sendChannel = Channel<String>(capacity = Channel.UNLIMITED)
    private var writeJob: Job? = null

    // Mouse delta accumulation — collapses rapid pointer events into one packet per flush
    // interval so we're not flooding RFCOMM with a packet per display frame.
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var mouseFlushScheduled = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val mouseFlushRunnable = Runnable {
        mouseFlushScheduled = false
        val dx = pendingDx
        val dy = pendingDy
        pendingDx = 0f
        pendingDy = 0f
        if ((dx != 0f || dy != 0f) && _connectionState.value == ConnectionState.Connected) {
            sendChannel.trySend(createMouseCommandJson("move", dx, dy))
        }
    }

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<android.bluetooth.BluetoothDevice> {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return emptyList()
            }
        }
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun connect(targetMacAddress: String) {
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
            val targetDevice = try {
                bluetoothAdapter.getRemoteDevice(targetMacAddress)
            } catch (e: IllegalArgumentException) {
                _connectionState.value = ConnectionState.Error("Invalid MAC address")
                return@launch
            }

            val uuid = UUID.fromString(BluetoothConstants.SERVICE_UUID_STRING)
            var connectedSocket: BluetoothSocket? = null

            @SuppressLint("MissingPermission")
            var tempSocket: BluetoothSocket? = null
            try {
                tempSocket = targetDevice.createRfcommSocketToServiceRecord(uuid)

                // Cancel discovery as it slows down the connection
                bluetoothAdapter.cancelDiscovery()

                tempSocket.connect()
                connectedSocket = tempSocket
                Log.i(TAG, "Connected securely to Bluetooth device: ${targetDevice.name}")
            } catch (e: IOException) {
                Log.d(TAG, "Failed secure connection to ${targetDevice.name}, trying insecure fallback...", e)
                try {
                    tempSocket?.close()
                } catch (closeException: IOException) {
                    Log.e(TAG, "Could not close the client socket", closeException)
                }

                // Fallback to insecure socket
                try {
                    tempSocket = targetDevice.createInsecureRfcommSocketToServiceRecord(uuid)
                    tempSocket.connect()
                    connectedSocket = tempSocket
                    Log.i(TAG, "Connected insecurely to Bluetooth device: ${targetDevice.name}")
                } catch (fallbackException: IOException) {
                    Log.d(TAG, "Failed insecure connection to ${targetDevice.name} as well", fallbackException)
                    try {
                        tempSocket?.close()
                    } catch (closeException: IOException) {
                        Log.e(TAG, "Could not close the client socket", closeException)
                    }
                }
            }

            if (connectedSocket != null) {
                socket = connectedSocket
                try {
                    outputStream = socket?.outputStream
                    _connectionState.value = ConnectionState.Connected
                    startWriteLoop()
                } catch (e: IOException) {
                    Log.e(TAG, "Error creating output stream", e)
                    disconnect()
                    _connectionState.value = ConnectionState.Error("Failed to open data stream")
                }
            } else {
                _connectionState.value = ConnectionState.Error("Could not connect to PlayBridge TV service on this device")
            }
        }
    }

    // Single writer coroutine — drains sendChannel sequentially on the IO dispatcher.
    // This avoids the per-send coroutine launch overhead that causes jitter at high rates.
    private fun startWriteLoop() {
        writeJob?.cancel()
        writeJob = scope.launch {
            for (message in sendChannel) {
                try {
                    outputStream?.write("$message\n".toByteArray())
                    outputStream?.flush()
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to send command over Bluetooth", e)
                    disconnect()
                    break
                }
            }
        }
    }

    fun sendRemoteCommand(key: String) {
        send(createRemoteCommandJson(key))
    }

    // Mouse moves are accumulated and flushed at most once per 16ms (~60fps).
    // Multiple pointer events between flushes are summed so no movement is lost,
    // but we send far fewer packets — one per frame instead of one per pointer event.
    // Must be called from the main thread (Compose pointer input callbacks satisfy this).
    fun sendMouseCommand(event: String, dx: Float = 0f, dy: Float = 0f) {
        if (event == "move") {
            pendingDx += dx
            pendingDy += dy
            if (!mouseFlushScheduled) {
                mouseFlushScheduled = true
                mainHandler.postDelayed(mouseFlushRunnable, 16L)
            }
            return
        }
        send(createMouseCommandJson(event, dx, dy))
    }

    fun send(jsonCommand: String) {
        if (_connectionState.value != ConnectionState.Connected) {
            Log.w(TAG, "Cannot send command: not connected to Bluetooth")
            return
        }
        sendChannel.trySend(jsonCommand)
    }

    fun disconnect() {
        mainHandler.removeCallbacks(mouseFlushRunnable)
        mouseFlushScheduled = false
        pendingDx = 0f
        pendingDy = 0f
        writeJob?.cancel()
        writeJob = null
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
