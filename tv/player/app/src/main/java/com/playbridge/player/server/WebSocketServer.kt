package com.playbridge.player.server

import android.util.Log
import com.playbridge.shared.protocol.IncomingMessage
import com.playbridge.shared.protocol.createPairingApprovedJson
import com.playbridge.shared.protocol.createPairingDeniedJson
import com.playbridge.shared.protocol.createPongJson
import com.playbridge.shared.protocol.parseIncomingMessage
import kotlinx.coroutines.CompletableDeferred
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
    private val port: Int = com.playbridge.shared.protocol.Config.DEFAULT_PORT,
    private val isTokenAuthorized: suspend (String) -> Boolean,
    private val onPairingApproved: suspend (deviceName: String, deviceUUID: String) -> String,
    private val subtitleDir: File? = null
) {
    data class PairingRequest(
        val deviceName: String,
        val deviceUUID: String,
        internal val approval: CompletableDeferred<Boolean>
    )
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

    // Incoming message flow for UI to observe
    private val _commands = MutableSharedFlow<IncomingMessage>(replay = 0)
    val commands = _commands.asSharedFlow()

    // Fires when a new device sends pairing_request; ServerService observes this to bring
    // the app to the foreground so the user can tap Allow/Deny.
    private val _connectionAttemptFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val connectionAttemptFlow = _connectionAttemptFlow.asSharedFlow()

    // Non-null while a device is waiting for the user to tap Allow or Deny.
    private val _pendingPairingRequest = MutableStateFlow<PairingRequest?>(null)
    val pendingPairingRequest: StateFlow<PairingRequest?> = _pendingPairingRequest.asStateFlow()

    fun approvePairing() {
        _pendingPairingRequest.value?.approval?.complete(true)
    }

    fun denyPairing() {
        _pendingPairingRequest.value?.approval?.complete(false)
    }

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
        // NOTE: connectionAttemptFlow is only emitted when request_pairing is received below,
        // NOT unconditionally here. Emitting on every connection would re-open PairingScreen
        // whenever a paired phone reconnects with its saved token.

        try {
            // Localhost connections (e.g. PlayBridge Browser app on the same device) skip auth
            val remoteHost = session.call.request.local.remoteHost
            val isLocalhost = remoteHost == "127.0.0.1" || remoteHost == "::1"

            // Authentication phase — loop until auth resolves or connection closes.
            var isAuthenticated = isLocalhost
            if (isLocalhost) FileLogger.i(TAG, "Localhost connection — skipping auth: $clientId")

            while (!isAuthenticated) {
                val frame = session.incoming.receive()
                if (frame is Frame.Text) {
                    val text = frame.readText()

                    if (text.contains("\"type\":\"ping\"") || text.contains("\"type\": \"ping\"")) {
                        session.send(Frame.Text(createPongJson()))
                        continue
                    }

                    if (text.contains("\"type\":\"pairing_request\"")) {
                        // Another device already waiting — deny immediately.
                        if (_pendingPairingRequest.value != null) {
                            FileLogger.w(TAG, "Pairing request ignored — another request already pending")
                            session.send(Frame.Text(createPairingDeniedJson()))
                            session.close(CloseReason(CloseReason.Codes.NORMAL, "busy"))
                            return
                        }

                        val parsed = parseIncomingMessage(text)
                        val msg = (parsed as? IncomingMessage.PairingRequest)?.msg
                        if (msg == null) {
                            FileLogger.w(TAG, "Failed to parse pairing_request: $parsed")
                            session.send(Frame.Text(createPairingDeniedJson()))
                            session.close(CloseReason(CloseReason.Codes.NORMAL, "parse_error"))
                            return
                        }

                        FileLogger.i(TAG, "pairing_request from ${msg.device_name} (${msg.device_uuid})")
                        val approval = CompletableDeferred<Boolean>()
                        val request = PairingRequest(msg.device_name, msg.device_uuid, approval)
                        _pendingPairingRequest.value = request
                        _connectionAttemptFlow.tryEmit(Unit)

                        // Auto-deny after 30 seconds if the user doesn't respond.
                        val timeoutJob = scope.launch {
                            delay(30_000)
                            FileLogger.i(TAG, "Pairing request timed out for ${msg.device_name}")
                            approval.complete(false)
                        }

                        val approved = approval.await()
                        timeoutJob.cancel()
                        _pendingPairingRequest.value = null

                        if (approved) {
                            val token = onPairingApproved(msg.device_name, msg.device_uuid)
                            FileLogger.i(TAG, "Pairing approved for ${msg.device_name} — token issued")
                            session.send(Frame.Text(createPairingApprovedJson(token)))
                            isAuthenticated = true
                        } else {
                            FileLogger.i(TAG, "Pairing denied for ${msg.device_name}")
                            session.send(Frame.Text(createPairingDeniedJson()))
                            session.close(CloseReason(CloseReason.Codes.NORMAL, "denied"))
                            return
                        }
                        continue
                    }

                    // Reconnect with saved token.
                    if (text.contains("\"type\":\"auth\"")) {
                        try {
                            val authMessage = (parseIncomingMessage(text) as? IncomingMessage.Auth)?.msg
                            val token = authMessage?.token
                            if (!token.isNullOrEmpty() && isTokenAuthorized(token)) {
                                isAuthenticated = true
                                FileLogger.i(TAG, "Client reconnected with saved token: $clientId")
                                session.send(Frame.Text("{\"type\":\"auth_response\",\"success\":true}"))
                            } else {
                                FileLogger.w(TAG, "Auth rejected — unknown or revoked token: $clientId")
                                session.send(Frame.Text("{\"type\":\"auth_response\",\"success\":false}"))
                                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid token"))
                                return
                            }
                        } catch (e: Exception) {
                            FileLogger.w(TAG, "Failed to parse auth message for $clientId", e)
                        }
                    }
                } else {
                    if (frame is Frame.Close) {
                        FileLogger.i(TAG, "Client closed connection during auth: $clientId")
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
                        val msg = parseIncomingMessage(text)

                        // Only log non-mouse messages to avoid spam
                        if (msg !is IncomingMessage.Mouse) {
                            Log.d(TAG, "Parsed message: ${msg.javaClass.simpleName}")
                        }

                        when (msg) {
                            is IncomingMessage.Ping -> {
                                session.send(Frame.Text(createPongJson()))
                            }
                            is IncomingMessage.Play -> {
                                FileLogger.i(TAG, "Play command parsed - URL: ${msg.payload.url}, Title: ${msg.payload.title}")
                                FileLogger.i(TAG, "Raw JSON body: $text")
                                _commands.emit(msg)
                            }
                            else -> {
                                _commands.emit(msg)
                            }
                        }
                    }
                    is Frame.Binary -> {
                        val bytes = frame.readBytes()
                        if (bytes.size == 9) {
                            val unpacked = com.playbridge.shared.protocol.MousePacket.unpack(bytes)
                            if (unpacked != null) {
                                _commands.emit(
                                    IncomingMessage.Mouse(
                                        playbridge.MousePayload(
                                            event = unpacked.event,
                                            dx = unpacked.dx,
                                            dy = unpacked.dy,
                                        )
                                    )
                                )
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
