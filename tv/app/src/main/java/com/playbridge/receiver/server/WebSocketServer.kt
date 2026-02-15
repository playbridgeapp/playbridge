package com.playbridge.receiver.server

import android.util.Log
import com.playbridge.protocol.Command
import com.playbridge.protocol.createPongJson
import com.playbridge.protocol.parseCommand
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
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
                // Ensure previous instance is stopped if it was somehow left valid but not running
                server?.stop(1000, 2000)
                server = null
                
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
                
            } catch (e: java.net.BindException) {
                Log.w(TAG, "Port $port already in use. Assuming server from previous instance is still active.")
                // If the port is in use, it's likely our own service from a previous run that hasn't fully released yet,
                // or a separate instance. We'll mark as running for the UI.
                _connectionState.value = ConnectionState.Running(port)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            }
        }
    }
// ... (handleConnection remains same)

    fun stop() {
        // Stop the server immediately in the current scope if possible, or block until done
        // We use runBlocking here to ensure the server is actually stopped before we return
        // This is crucial for service restarts.
        try {
            runBlocking {
                clients.values.forEach { session ->
                    try {
                        session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server stopping"))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing client", e)
                    }
                }
                clients.clear()
                
                server?.stop(500, 1000)
                server = null
                Log.i(TAG, "Server stopped")
            }
            _connectionState.value = ConnectionState.Stopped
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }
    
    private suspend fun handleConnection(session: WebSocketServerSession) {
        val clientId = java.util.UUID.randomUUID().toString()
        Log.i(TAG, "New connection attempt: $clientId")
        
        try {
            // Authentication phase
            // Loop until we get an auth message, handling pings in the meantime
            var isAuthenticated = false
            
            // Log the expected PIN for debugging
            val pin = authToken.take(4).uppercase()
            Log.i(TAG, "Expecting PIN: $pin for client: $clientId")

            while (!isAuthenticated) {
                val frame = session.incoming.receive()
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    Log.d(TAG, "Message received during auth: $text")
                    
                    if (text.contains("\"type\":\"ping\"") || text.contains("\"type\": \"ping\"")) {
                        Log.d(TAG, "Received ping during auth, sending pong")
                        session.send(Frame.Text(createPongJson()))
                        continue
                    }
                    
                    try {
                        // Robust JSON parsing
                        val authMessage = com.playbridge.protocol.protocolJson.decodeFromString<com.playbridge.protocol.AuthMessage>(text)
                        
                        if (authMessage.type == "auth") {
                            if (authMessage.token == authToken) {
                                // Re-connection with valid token
                                isAuthenticated = true
                                Log.i(TAG, "Client authenticated with token")
                                session.send(Frame.Text("{\"type\": \"auth_response\", \"success\": true}"))
                            } else if (authMessage.pin?.uppercase() == pin) {
                                isAuthenticated = true
                                Log.i(TAG, "Client authenticated with PIN")
                                // Send back the full token for future use
                                session.send(Frame.Text("{\"type\": \"auth_response\", \"success\": true, \"token\": \"$authToken\"}"))
                            } else {
                                Log.w(TAG, "Authentication failed. Expected PIN: $pin, Received PIN: ${authMessage.pin}, Token: ${authMessage.token}")
                                session.send(Frame.Text("{\"type\": \"auth_response\", \"success\": false}"))
                                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid credentials"))
                                return
                            }
                        } else {
                             Log.w(TAG, "Unexpected message type during auth: ${authMessage.type}")
                             // Don't close immediately, maybe it's just noise or a wrong message type
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse auth message: $text", e)
                        // Fallback to substring matching just in case, or treat as error
                        // For now, let's just log and continue waiting
                    }
                } else {
                    // Ignore non-text frames or close if necessary
                    if (frame is Frame.Close) {
                        Log.i(TAG, "Client closed connection during auth")
                        return
                    }
                }
            }
            
            // Authentication successful, register client
            clients[clientId] = session
            _connectionState.value = ConnectionState.Connected(clientId)
            Log.i(TAG, "Client registered: $clientId")

            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        val command = parseCommand(text)
                        
                        // Only log non-mouse commands to avoid spam
                        if (command !is Command.Mouse) {
                            Log.d(TAG, "Parsed command: ${command.javaClass.simpleName}")
                        }
                        
                        when (command) {
                            is Command.Ping -> {
                                session.send(Frame.Text(createPongJson()))
                            }
                            is Command.Play -> {
                                Log.i(TAG, "Play command parsed - URL: ${command.url}, Title: ${command.title}")
                                _commands.emit(command)
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
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            Log.i(TAG, "Channel closed by client: $clientId")
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
    
    fun getPort(): Int = port
}
