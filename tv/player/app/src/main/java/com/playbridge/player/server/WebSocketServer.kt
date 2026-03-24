package com.playbridge.player.server

import android.util.Log
import com.playbridge.protocol.Command
import com.playbridge.protocol.createPongJson
import com.playbridge.protocol.parseCommand
import com.playbridge.player.logging.FileLogger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private const val TAG = "WebSocketServer"

/**
 * WebSocket server for receiving commands from the phone app
 */
class WebSocketServer(
    private val port: Int = com.playbridge.protocol.Config.DEFAULT_PORT,
    private val authToken: String,
    private val subtitleDir: File? = null
) {
    private var server: EmbeddedServer<*, *>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Connected clients
    private val clients = ConcurrentHashMap<String, WebSocketSession>()
    
    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Stopped)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // Connected client count
    private val _connectedClientCount = MutableStateFlow(0)
    val connectedClientCount: StateFlow<Int> = _connectedClientCount.asStateFlow()
    
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
            FileLogger.w(TAG, "Server already running")
            return
        }
        
        _connectionState.value = ConnectionState.Starting
        
        scope.launch {
            try {
                // Ensure previous instance is stopped if it was somehow left valid but not running
                server?.stop(1000, 2000)
                server = null
                
                server = embeddedServer(CIO, port = port) {
                    install(WebSockets) {
                        pingPeriod = 15.seconds
                        timeout = 15.seconds
                        maxFrameSize = Long.MAX_VALUE
                        masking = false
                    }
                    
                    routing {
                        // HTTP endpoint: download log files
                        get("/logs") {
                            val logFiles = FileLogger.getLogFiles()
                            if (logFiles.isEmpty()) {
                                call.respondText("No log files found.", ContentType.Text.Plain)
                                return@get
                            }
                            val combined = logFiles.reversed().joinToString("\n") { it.readText() }
                            call.response.header(
                                HttpHeaders.ContentDisposition,
                                ContentDisposition.Attachment.withParameter(
                                    ContentDisposition.Parameters.FileName, "playbridge_tv_logs.txt"
                                ).toString()
                            )
                            call.respondText(combined, ContentType.Text.Plain)
                        }

                        // HTTP endpoint: clear log files
                        delete("/logs") {
                            FileLogger.clearLogs()
                            call.respondText("Logs cleared.", ContentType.Text.Plain)
                        }

                        // HTTP endpoint: serve locally-cached subtitle files to external players (e.g. MPV)
                        get("/subtitle/{filename}") {
                            val filename = call.parameters["filename"]
                                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing filename")
                            val dir = subtitleDir
                                ?: return@get call.respond(HttpStatusCode.NotFound, "No subtitle cache")
                            val file = File(dir, filename)
                            // Guard against path traversal
                            if (!file.canonicalPath.startsWith(dir.canonicalPath)) {
                                call.respond(HttpStatusCode.BadRequest, "Invalid path")
                                return@get
                            }
                            if (!file.exists()) {
                                call.respond(HttpStatusCode.NotFound, "Subtitle not found")
                                return@get
                            }
                            call.respondBytes(file.readBytes(), ContentType.Text.Plain)
                        }

                        webSocket("/") {
                            handleConnection(this)
                        }
                    }
                }.start(wait = false)
                
                _connectionState.value = ConnectionState.Running(port)
                FileLogger.i(TAG, "WebSocket server started on port $port")
                
            } catch (e: java.net.BindException) {
                FileLogger.w(TAG, "Port $port already in use. Assuming server from previous instance is still active.")
                // If the port is in use, it's likely our own service from a previous run that hasn't fully released yet,
                // or a separate instance. We'll mark as running for the UI.
                _connectionState.value = ConnectionState.Running(port)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to start server", e)
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
                        FileLogger.e(TAG, "Error closing client", e)
                    }
                }
                clients.clear()
                
                server?.stop(500, 1000)
                server = null
                FileLogger.i(TAG, "Server stopped")
            }
            _connectionState.value = ConnectionState.Stopped
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error stopping server", e)
        }
    }
    
    private suspend fun handleConnection(session: WebSocketServerSession) {
        val clientId = java.util.UUID.randomUUID().toString()
        FileLogger.i(TAG, "New connection attempt: $clientId")

        try {
            // Localhost connections (e.g. PlayBridge Browser app on the same device) skip auth
            val remoteHost = session.call.request.local.remoteHost
            val isLocalhost = remoteHost == "127.0.0.1" || remoteHost == "::1"

            // Authentication phase
            // Loop until we get an auth message, handling pings in the meantime
            var isAuthenticated = isLocalhost
            if (isLocalhost) FileLogger.i(TAG, "Localhost connection — skipping auth: $clientId")

            // Log the expected PIN for debugging
            val pin = authToken.take(4).uppercase()
            if (!isLocalhost) FileLogger.i(TAG, "Expecting PIN: $pin for client: $clientId")

            while (!isAuthenticated) {
                val frame = session.incoming.receive()
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    FileLogger.d(TAG, "Message received during auth: $text")
                    
                    if (text.contains("\"type\":\"ping\"") || text.contains("\"type\": \"ping\"")) {
                        FileLogger.d(TAG, "Received ping during auth, sending pong")
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
                                FileLogger.i(TAG, "Client authenticated with token")
                                session.send(Frame.Text("{\"type\": \"auth_response\", \"success\": true}"))
                            } else if (authMessage.pin?.uppercase() == pin) {
                                isAuthenticated = true
                                FileLogger.i(TAG, "Client authenticated with PIN")
                                // Send back the full token for future use
                                session.send(Frame.Text("{\"type\": \"auth_response\", \"success\": true, \"token\": \"$authToken\"}"))
                            } else {
                                FileLogger.w(TAG, "Authentication failed. Expected PIN: $pin, Received PIN: ${authMessage.pin}, Token: ${authMessage.token}")
                                session.send(Frame.Text("{\"type\": \"auth_response\", \"success\": false}"))
                                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid credentials"))
                                return
                            }
                        } else {
                              FileLogger.w(TAG, "Unexpected message type during auth: ${authMessage.type}")
                             // Don't close immediately, maybe it's just noise or a wrong message type
                        }
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "Failed to parse auth message: $text", e)
                        // Fallback to substring matching just in case, or treat as error
                        // For now, let's just log and continue waiting
                    }
                } else {
                    // Ignore non-text frames or close if necessary
                    if (frame is Frame.Close) {
                        FileLogger.i(TAG, "Client closed connection during auth")
                        return
                    }
                }
            }
            
            // Authentication successful, register client
            clients[clientId] = session
            _connectedClientCount.value = clients.size
            _connectionState.value = ConnectionState.Connected(clientId)
            FileLogger.i(TAG, "Client registered: $clientId (total: ${clients.size})")

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
                                FileLogger.i(TAG, "Play command parsed - URL: ${command.url}, Title: ${command.title}")
                                _commands.emit(command)
                            }
                            else -> {
                                _commands.emit(command)
                            }
                        }
                    }
                    is Frame.Close -> {
                        FileLogger.i(TAG, "Client requested close: $clientId")
                    }
                    else -> {}
                }
            }
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            FileLogger.i(TAG, "Channel closed by client: $clientId")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Connection error for client $clientId", e)
        } finally {
            clients.remove(clientId)
            _connectedClientCount.value = clients.size
            if (clients.isEmpty()) {
                _connectionState.value = ConnectionState.Running(port)
            }
            FileLogger.i(TAG, "Client disconnected: $clientId (remaining: ${clients.size})")
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
                FileLogger.e(TAG, "Failed to send status", e)
            }
        }
    }
    
    fun getPort(): Int = port
}
