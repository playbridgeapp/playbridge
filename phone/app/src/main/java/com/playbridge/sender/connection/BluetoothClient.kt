package com.playbridge.sender.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.playbridge.protocol.BluetoothConstants
import com.playbridge.protocol.createMouseCommandJson
import com.playbridge.protocol.createPingJson
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
    private var keepaliveJob: Job? = null

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
    fun getBondedDevices(): List<BluetoothDevice> {
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
            // Cancel discovery before any connection attempt — active discovery interferes with RFCOMM.
            @SuppressLint("MissingPermission")
            bluetoothAdapter.cancelDiscovery()

            val connectedSocket = attemptConnect(targetDevice, bluetoothAdapter, uuid)

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

    /**
     * Tries all connection strategies in order:
     * 1. Secure RFCOMM via SDP
     * 2. Insecure RFCOMM via SDP
     * 3. Force fresh SDP fetch (fixes stale Android cache returning channel -1), then retry 1+2
     */
    @SuppressLint("MissingPermission")
    private suspend fun attemptConnect(device: BluetoothDevice, adapter: BluetoothAdapter, uuid: UUID): BluetoothSocket? {
        // Attempt 1: Secure RFCOMM via SDP
        tryRfcommSocket(device, uuid, secure = true)?.let {
            Log.i(TAG, "Connected securely via SDP to ${device.name}")
            return it
        }

        // Attempt 2: Insecure RFCOMM via SDP
        tryRfcommSocket(device, uuid, secure = false)?.let {
            Log.i(TAG, "Connected insecurely via SDP to ${device.name}")
            return it
        }

        // Both SDP-based attempts failed (channel typically comes back as -1, meaning the
        // Android Bluetooth stack has a stale/empty SDP cache for this device).
        // Force a fresh SDP fetch from the remote device and wait up to 3s for the result.
        Log.d(TAG, "SDP returned channel -1 for ${device.name}; forcing fresh SDP fetch...")
        val sdpRefreshed = fetchUuidsWithSdp(device)
        if (sdpRefreshed) {
            Log.d(TAG, "SDP refreshed for ${device.name}, retrying RFCOMM connection...")
            tryRfcommSocket(device, uuid, secure = true)?.let {
                Log.i(TAG, "Connected securely after SDP refresh to ${device.name}")
                return it
            }
            tryRfcommSocket(device, uuid, secure = false)?.let {
                Log.i(TAG, "Connected insecurely after SDP refresh to ${device.name}")
                return it
            }
        }

        Log.e(TAG, "All connection attempts exhausted for ${device.name}")
        return null
    }

    @SuppressLint("MissingPermission")
    private fun tryRfcommSocket(device: BluetoothDevice, uuid: UUID, secure: Boolean): BluetoothSocket? {
        var s: BluetoothSocket? = null
        return try {
            s = if (secure) device.createRfcommSocketToServiceRecord(uuid)
                else device.createInsecureRfcommSocketToServiceRecord(uuid)
            s.connect()
            s
        } catch (e: IOException) {
            try { s?.close() } catch (_: IOException) {}
            null
        }
    }

    /**
     * Forces Android to do a fresh SDP query against the remote device instead of
     * using its cached (possibly stale) service records. Returns true if the remote
     * device responded before the timeout.
     */
    @SuppressLint("MissingPermission")
    private suspend fun fetchUuidsWithSdp(device: BluetoothDevice): Boolean {
        val deferred = CompletableDeferred<Boolean>()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_UUID) return
                @Suppress("DEPRECATION")
                val remoteDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (remoteDevice?.address == device.address) {
                    deferred.complete(true)
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_UUID))

        return try {
            device.fetchUuidsWithSdp()
            withTimeout(3_000L) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "SDP fetch timed out for ${device.name}")
            false
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
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

        // Immediately send a ping so the RFCOMM link has data flowing right away.
        // Some Android TV Bluetooth stacks drop the link after a few seconds of silence
        // (radio-level supervision timeout); this prevents that from happening before the
        // user's first interaction.
        sendChannel.trySend(createPingJson())

        // Keep the link alive with a periodic ping every 2 seconds whenever the channel
        // would otherwise be idle. The TV silently discards Command.Ping.
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(2_000L)
                if (_connectionState.value == ConnectionState.Connected) {
                    sendChannel.trySend(createPingJson())
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
        keepaliveJob?.cancel()
        keepaliveJob = null
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
