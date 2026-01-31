package com.playbridge.sender.connection

import android.util.Log
import com.playbridge.sender.model.createPingJson
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
    
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _messages = MutableSharedFlow<String>(replay = 0)
    val messages = _messages.asSharedFlow()
    
    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data class Connected(val serverName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    fun connect(ip: String, port: Int, token: String, serverName: String) {
        if (webSocket != null) {
            Log.w(TAG, "Already connected or connecting")
            return
        }
        
        _connectionState.value = ConnectionState.Connecting
        
        val url = "ws://$ip:$port/"
        Log.i(TAG, "Connecting to $url")
        
        val request = Request.Builder()
            .url(url)
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to $serverName")
                _connectionState.value = ConnectionState.Connected(serverName)
                
                // Send initial ping to verify connection
                scope.launch {
                    delay(500)
                    sendPing()
                }
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
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
                this@WebSocketClient.webSocket = null
                _connectionState.value = ConnectionState.Disconnected
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failed", t)
                this@WebSocketClient.webSocket = null
                _connectionState.value = ConnectionState.Error(t.message ?: "Unknown error")
            }
        })
    }
    
    fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false.also {
            Log.w(TAG, "Cannot send, not connected")
        }
    }
    
    fun sendPing(): Boolean {
        return send(createPingJson())
    }
    
    fun disconnect() {
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
