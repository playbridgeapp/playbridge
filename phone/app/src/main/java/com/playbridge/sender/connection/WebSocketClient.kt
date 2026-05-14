package com.playbridge.sender.connection

import android.util.Log
import com.playbridge.shared.protocol.createAuthJson
import com.playbridge.shared.protocol.createPairingRequestJson
import com.playbridge.shared.protocol.createPingJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

private const val TAG = "WebSocketClient"

/**
 * OkHttp-based WebSocket client for connecting to TV
 */
class WebSocketClient {
    
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    
    @Volatile
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _messages = MutableSharedFlow<String>(replay = 0)
    val messages = _messages.asSharedFlow()

    private val _newToken = MutableSharedFlow<String>(replay = 0)
    val newToken = _newToken.asSharedFlow()
    
    private var retryCount = 0
    private val MAX_RETRIES = com.playbridge.shared.protocol.Config.MAX_RETRIES // 5 minutes
    private val RETRY_DELAY_MS = com.playbridge.shared.protocol.Config.RETRY_DELAY_MS
    private var targetConnection: TvConnectionInfo? = null
    private var isUserDisconnect = false
    
    // Mouse delta accumulation — collapses rapid pointer events into one packet per flush
    // interval so we're not flooding the TV with a packet per display frame (especially at 120Hz).
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
        if (dx != 0f || dy != 0f) {
            send(com.playbridge.shared.protocol.MousePacket.pack("move", dx, dy))
        }
    }

    private data class TvConnectionInfo(
        val ip: String,
        val port: Int,
        val token: String,
        val serverName: String,
        val deviceName: String,
        val deviceUUID: String,
    )

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data class Connected(val serverName: String) : ConnectionState()
        // Pairing request sent — waiting for the TV user to tap Allow.
        data class WaitingForApproval(val serverName: String) : ConnectionState()
        // TV user tapped Deny, or the 30s timeout elapsed.
        data class PairingDenied(val serverName: String) : ConnectionState()
        data class Retrying(val attempt: Int, val maxAttempts: Int, val nextRetrySeconds: Int) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
        // Stale token rejected by TV (e.g. after TV reinstall). Distinct from Error so the
        // ViewModel can wipe the token and prompt re-pairing rather than showing a generic error.
        data object AuthFailed : ConnectionState()
    }
    
    fun connect(ip: String, port: Int, token: String, serverName: String, deviceName: String, deviceUUID: String) {
        retryCount = 0
        isUserDisconnect = false  // Reset so retries are allowed for genuine connectivity failures
        targetConnection = TvConnectionInfo(ip, port, token, serverName, deviceName, deviceUUID)
        attemptConnection(ip, port, serverName)
    }

    private fun attemptConnection(ip: String, port: Int, serverName: String) {
        // Update state first to prevent race where UI thinks we are connected but socket is null
        _connectionState.value = ConnectionState.Connecting
        
        if (webSocket != null) {
            try { webSocket?.close(1000, "Reconnecting") } catch(e: Exception) {}
            webSocket = null
        }
        
        val url = "ws://$ip:$port/"
        if (retryCount == 0) Log.i(TAG, "Connecting to $url") else Log.d(TAG, "Retrying $url (attempt $retryCount)")
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== this@WebSocketClient.webSocket) {
                    Log.d(TAG, "Ignoring onOpen for stale socket")
                    return
                }
                Log.i(TAG, "Socket opened to $serverName")
                retryCount = 0

                scope.launch {
                    try {
                        val conn = targetConnection
                        if (conn?.token.isNullOrEmpty()) {
                            // First-time pairing — send identity and wait for TV user to approve.
                            val json = createPairingRequestJson(
                                deviceName = conn?.deviceName ?: "Android Phone",
                                deviceUUID = conn?.deviceUUID ?: ""
                            )
                            Log.d(TAG, "Sending pairing_request: $json")
                            webSocket.send(json)
                            _connectionState.value = ConnectionState.WaitingForApproval(serverName)
                        } else {
                            // Reconnect with saved token.
                            val authJson = createAuthJson(conn!!.token)
                            Log.d(TAG, "Sending auth: $authJson")
                            webSocket.send(authJson)
                            delay(500)
                            sendPing()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send pairing/auth message", e)
                        webSocket.close(1000, "Send failed")
                    }
                }
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== this@WebSocketClient.webSocket) {
                    Log.d(TAG, "Ignoring onMessage for stale socket")
                    return
                }
                Log.d(TAG, "Received: $text")
                
                // Handle pairing and auth responses before forwarding to command flow.
                if (text.contains("pairing_approved")) {
                    try {
                        val json = kotlinx.serialization.json.Json.parseToJsonElement(text)
                        if (json is JsonObject && json["type"]?.toString()?.replace("\"", "") == "pairing_approved") {
                            val token = json["token"]?.toString()?.replace("\"", "")
                            Log.i(TAG, "Pairing approved by $serverName")
                            if (!token.isNullOrEmpty() && token != "null") {
                                // Update in-memory connection info so retries after a network
                                // blip send `auth` (not another `pairing_request`).
                                targetConnection = targetConnection?.copy(token = token)
                                scope.launch { _newToken.emit(token) }
                            }
                            _connectionState.value = ConnectionState.Connected(serverName)
                            return
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing pairing_approved", e)
                    }
                }

                if (text.contains("pairing_denied")) {
                    try {
                        val json = kotlinx.serialization.json.Json.parseToJsonElement(text)
                        if (json is JsonObject && json["type"]?.toString()?.replace("\"", "") == "pairing_denied") {
                            Log.i(TAG, "Pairing denied by $serverName")
                            isUserDisconnect = true
                            _connectionState.value = ConnectionState.PairingDenied(serverName)
                            webSocket.close(1000, "Pairing denied")
                            return
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing pairing_denied", e)
                    }
                }

                if (text.contains("auth_response")) {
                    try {
                        val json = kotlinx.serialization.json.Json.parseToJsonElement(text)
                        if (json is JsonObject) {
                            val type = json["type"].toString().replace("\"", "")
                            if (type == "auth_response") {
                                val success = json["success"].toString() == "true"
                                if (success) {
                                    Log.i(TAG, "Authentication successful")
                                    _connectionState.value = ConnectionState.Connected(serverName)
                                    val token = json["token"]?.toString()?.replace("\"", "")
                                    if (!token.isNullOrEmpty() && token != "null") {
                                        targetConnection = targetConnection?.copy(token = token)
                                        scope.launch { _newToken.emit(token) }
                                    }
                                } else {
                                    Log.e(TAG, "Authentication failed — stale token")
                                    // Set flag before close so onClosed doesn't overwrite AuthFailed
                                    // with Disconnected, and so onFailure won't schedule retries.
                                    isUserDisconnect = true
                                    _connectionState.value = ConnectionState.AuthFailed
                                    webSocket.close(1000, "Auth failed")
                                }
                                return
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing auth response", e)
                    }
                }

                scope.launch {
                    _messages.emit(text)
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Connection closing: $reason")
                webSocket.close(1000, null)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Connection closed: $reason")
                if (webSocket === this@WebSocketClient.webSocket) {
                    this@WebSocketClient.webSocket = null
                    // Don't overwrite AuthFailed — the UI needs that state to show a re-pair prompt.
                    if (_connectionState.value !is ConnectionState.AuthFailed) {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                } else {
                     Log.d(TAG, "Ignoring onClosed for stale socket")
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (retryCount == 0) {
                    Log.e(TAG, "Connection failed: ${t.message}", t)
                } else {
                    Log.d(TAG, "Connection attempt $retryCount failed: ${t.message}")
                }

                if (webSocket === this@WebSocketClient.webSocket) {
                    this@WebSocketClient.webSocket = null

                    if (!isUserDisconnect && retryCount < MAX_RETRIES) {
                        retryCount++
                        Log.d(TAG, "Retrying ($retryCount/$MAX_RETRIES) in ${RETRY_DELAY_MS}ms")
                        _connectionState.value = ConnectionState.Retrying(retryCount, MAX_RETRIES, (RETRY_DELAY_MS/1000).toInt())
                        
                        scope.launch {
                            try {
                                delay(RETRY_DELAY_MS)
                                targetConnection?.let {
                                    if (!isUserDisconnect) {
                                        attemptConnection(it.ip, it.port, it.serverName)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error during retry attempt", e)
                            }
                        }
                    } else {
                        _connectionState.value = ConnectionState.Error(t.message ?: "Unknown error")
                    }
                } else {
                    Log.d(TAG, "Ignoring onFailure for stale socket")
                }
            }
        })
    }
    
    fun send(message: String): Boolean {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "Cannot send, webSocket is null. State: ${_connectionState.value}")
            return false
        }
        return ws.send(message)
    }

    fun send(bytes: ByteArray): Boolean {
        val ws = webSocket
        if (ws == null) return false
        return ws.send(bytes.toByteString())
    }
    
    fun sendPing(): Boolean {
        return send(createPingJson())
    }

    /**
     * Sends a mouse command, with automatic batching/throttling for high-frequency "move" events.
     */
    fun sendMouseCommand(event: String, dx: Float = 0f, dy: Float = 0f) {
        if (event == "move") {
            pendingDx += dx
            pendingDy += dy
            if (!mouseFlushScheduled) {
                mouseFlushScheduled = true
                mainHandler.postDelayed(mouseFlushRunnable, 16L) // ~60Hz
            }
            return
        }
        // Immediate send for clicks, scrolls, and up/down events
        send(com.playbridge.shared.protocol.MousePacket.pack(event, dx, dy))
    }
    
    fun disconnect() {
        mainHandler.removeCallbacks(mouseFlushRunnable)
        mouseFlushScheduled = false
        pendingDx = 0f
        pendingDy = 0f
        isUserDisconnect = true
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }
    
    fun isConnected(): Boolean {
        return _connectionState.value is ConnectionState.Connected
    }
    
    fun destroy() {
        disconnect()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }
}
