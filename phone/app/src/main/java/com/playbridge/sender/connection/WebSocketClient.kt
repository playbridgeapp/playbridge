package com.playbridge.sender.connection

import android.util.Log
import com.playbridge.protocol.createPingJson
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
    private val MAX_RETRIES = com.playbridge.protocol.Config.MAX_RETRIES // 5 minutes
    private val RETRY_DELAY_MS = com.playbridge.protocol.Config.RETRY_DELAY_MS
    private var targetConnection: TvConnectionInfo? = null
    private var isUserDisconnect = false

    private data class TvConnectionInfo(val ip: String, val port: Int, val token: String, val serverName: String)

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data class Connected(val serverName: String) : ConnectionState()
        data class Retrying(val attempt: Int, val maxAttempts: Int, val nextRetrySeconds: Int) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    fun connect(ip: String, port: Int, token: String, serverName: String) {
        retryCount = 0
        isUserDisconnect = false
        targetConnection = TvConnectionInfo(ip, port, token, serverName)
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
                // _connectionState.value = ConnectionState.Connected(serverName) // Wait for auth
                retryCount = 0
                
                // Send auth message immediately
                scope.launch {
                    try {
                        val authJson = com.playbridge.protocol.createAuthJson(targetConnection?.token ?: "")
                        Log.d(TAG, "Sending auth: $authJson")
                        webSocket.send(authJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send auth", e)
                        webSocket.close(1000, "Auth failed")
                    }
                    
                    // Send initial ping to verify connection (or keep alive)
                    delay(500)
                    sendPing()
                }
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== this@WebSocketClient.webSocket) {
                    Log.d(TAG, "Ignoring onMessage for stale socket")
                    return
                }
                Log.d(TAG, "Received: $text")
                
                // Check for auth response
                if (text.contains("auth_response")) {
                    try {
                        val json = kotlinx.serialization.json.Json.parseToJsonElement(text)
                        if (json is kotlinx.serialization.json.JsonObject) {
                            val type = json["type"].toString().replace("\"", "")
                            if (type == "auth_response") {
                                val success = json["success"].toString() == "true"
                                if (success) {
                                    Log.i(TAG, "Authentication successful")
                                    _connectionState.value = ConnectionState.Connected(serverName)
                                    // Helper to update token if returned
                                    val token = json["token"]?.toString()?.replace("\"", "")
                                    if (!token.isNullOrEmpty() && token != "null") {
                                        scope.launch {
                                            _newToken.emit(token)
                                        }
                                    }
                                } else {
                                    Log.e(TAG, "Authentication failed")
                                    _connectionState.value = ConnectionState.Error("Authentication failed")
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
                    _connectionState.value = ConnectionState.Disconnected
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
    
    fun sendPing(): Boolean {
        return send(createPingJson())
    }
    
    fun disconnect() {
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
