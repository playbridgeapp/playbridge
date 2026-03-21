package com.playbridge.receiver.server

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.playbridge.protocol.BluetoothConstants
import com.playbridge.protocol.Command
import com.playbridge.protocol.parseCommand
import com.playbridge.receiver.logging.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.IOException
import java.util.UUID

class BluetoothServer(
    private val context: Context,
    private val onCommandReceived: (Command) -> Unit
) {
    private val TAG = "BluetoothServer"
    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    @SuppressLint("MissingPermission") // Permissions are checked before starting
    fun start() {
        if (isRunning) return

        scope.launch {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                FileLogger.e(TAG, "Bluetooth is not supported or disabled")
                return@launch
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    FileLogger.e(TAG, "Missing BLUETOOTH_CONNECT permission, cannot start server")
                    return@launch
                }
            }

            try {
                val uuid = UUID.fromString(BluetoothConstants.SERVICE_UUID_STRING)
                // Use insecure RFCOMM so the client doesn't have to negotiate
                // link-level encryption — this avoids a second auth failure path
                // on clients that connect without a bonded-secure session.
                serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(
                    BluetoothConstants.SERVICE_NAME,
                    uuid
                )
                isRunning = true
                FileLogger.i(TAG, "Bluetooth Server started, listening for connections on $uuid")

                acceptConnections()
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to start Bluetooth server", e)
            }
        }
    }

    private suspend fun acceptConnections() {
        while (isRunning && serverSocket != null) {
            val socket: BluetoothSocket? = try {
                serverSocket?.accept()
            } catch (e: IOException) {
                if (isRunning) {
                    FileLogger.e(TAG, "Socket accept failed", e)
                }
                null
            }

            socket?.let {
                FileLogger.i(TAG, "Bluetooth client connected: ${it.remoteDevice.address}")
                manageConnectedSocket(it)
            }
        }
    }

    private fun manageConnectedSocket(socket: BluetoothSocket) {
        // If there's already an active connection, close it (only allow one remote at a time)
        activeSocket?.let {
            try {
                it.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        activeSocket = socket

        scope.launch {
            val inputStream: InputStream = try {
                socket.inputStream
            } catch (e: IOException) {
                FileLogger.e(TAG, "Error occurred when creating input stream", e)
                return@launch
            }

            val buffer = ByteArray(1024)
            var bytes: Int

            val reader = inputStream.bufferedReader()

            while (isActive && isRunning) {
                try {
                    val message = reader.readLine()
                    if (message != null && message.isNotBlank()) {
                        try {
                            val command = parseCommand(message)
                            onCommandReceived(command)
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "Failed to parse command from Bluetooth: $message", e)
                        }
                    } else if (message == null) {
                        // EOF reached
                        FileLogger.d(TAG, "Input stream was disconnected")
                        break
                    }
                } catch (e: IOException) {
                    FileLogger.e(TAG, "Error reading from input stream", e)
                    break
                }
            }

            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            FileLogger.e(TAG, "Could not close the connect socket", e)
        }
        serverSocket = null

        try {
            activeSocket?.close()
        } catch (e: IOException) {
            FileLogger.e(TAG, "Could not close active socket", e)
        }
        activeSocket = null
    }
}
