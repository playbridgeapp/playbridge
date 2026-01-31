package com.playbridge.receiver.server

import android.util.Log
import com.playbridge.receiver.model.Command
import com.playbridge.receiver.model.createPongJson
import com.playbridge.receiver.model.parseCommand
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private const val TAG = "WebSocketServer"

/**
 * WebSocket server for receiving commands from the phone app
 */
class WebSocketServer(
    private val port: Int = 8765,
    private val authToken: String
) {
    private var server: EmbeddedServer<*, *>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Connected clients
    private val clients = ConcurrentHashMap<String, WebSocketSession>()
    
    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // Command flow for UI to observe
    private val _commands = MutableSharedFlow<Command>(replay = 0)
    val commands = _commands.asSharedFlow()
    
    sealed class ConnectionState {
        data object Stopped : ConnectionState()
        data object Starting : ConnectionState()
        data class Running(val port: Int) : ConnectionState()
        data class Connected(val clientId: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    
    fun start() {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return
        }
        
        _connectionState.value = ConnectionState.Starting
        
        scope.launch {
            try {
                server = embeddedServer(Netty, port = port) {
                    install(WebSockets) {
                        pingPeriod = 15.seconds
                        timeout = 15.seconds
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }
                    
                    routing {
                        webSocket("/") {
                            handleConnection(this)
                        }
                    }
                }.start(wait = false)
                
                _connectionState.value = ConnectionState.Running(port)
                Log.i(TAG, "WebSocket server started on port $port")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    private suspend fun handleConnection(session: WebSocketServerSession) {
        val clientId = java.util.UUID.randomUUID().toString()
        
        // TODO: Implement token validation from first message
        // For now, accept all connections
        
        clients[clientId] = session
        _connectionState.value = ConnectionState.Connected(clientId)
        Log.i(TAG, "Client connected: $clientId")
        
        try {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        Log.d(TAG, "Received: $text")
                        
                        val command = parseCommand(text)
                        
                        when (command) {
                            is Command.Ping -> {
                                session.send(Frame.Text(createPongJson()))
                            }
                            else -> {
                                _commands.emit(command)
                            }
                        }
                    }
                    is Frame.Close -> {
                        Log.i(TAG, "Client requested close: $clientId")
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error for client $clientId", e)
        } finally {
            clients.remove(clientId)
            if (clients.isEmpty()) {
                _connectionState.value = ConnectionState.Running(port)
            }
            Log.i(TAG, "Client disconnected: $clientId")
        }
    }
    
    /**
     * Broadcast status update to all connected clients
     */
    suspend fun broadcastStatus(statusJson: String) {
        clients.values.forEach { session ->
            try {
                session.send(Frame.Text(statusJson))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send status", e)
            }
        }
    }
    
    fun stop() {
        scope.launch {
            clients.values.forEach { session ->
                try {
                    session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server stopping"))
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing client", e)
                }
            }
            clients.clear()
            
            server?.stop(1000, 2000)
            server = null
            _connectionState.value = ConnectionState.Stopped
            Log.i(TAG, "Server stopped")
        }
    }
    
    fun getPort(): Int = port
}
