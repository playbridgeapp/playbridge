package com.playbridge.player.server

import android.util.Log
import com.playbridge.shared.protocol.IncomingMessage
import com.playbridge.shared.protocol.createAuthResponseJson
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
    private val subtitleDir: File? = null,
    // App-private directory for the persisted TLS identity (PKCS12). wss:// is
    // disabled if null.
    private val tlsDir: File? = null,
    // When false (default) external clients must use wss://; ws:// is bound to
    // loopback only (for the same-device in-app browser). When true, ws:// also
    // binds externally for legacy senders.
    private val allowInsecure: Boolean = false,
    // Invoked after the wss bind attempt with the bound port (null if it failed),
    // so the caller advertises wss_port over NSD only when it's actually up.
    private val onWssReady: ((Int?) -> Unit)? = null,
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

    // wss:// (Java-WebSocket) transport + its authenticated connections.
    private var wssServer: WssTransport? = null
    private val wssClients = ConcurrentHashMap.newKeySet<org.java_websocket.WebSocket>()

    // SPKI pin of our TLS cert, sent to senders at pairing. Set when wss starts.
    @Volatile var certFingerprint: String? = null
        private set

    // Bound wss port (advertised over NSD), or null if TLS didn't start.
    @Volatile private var boundWssPort: Int? = null
    fun getWssPort(): Int? = boundWssPort

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

                val bindHost = if (allowInsecure) "0.0.0.0" else "127.0.0.1"
                server = embeddedServer(CIO, host = bindHost, port = port) {
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
                FileLogger.i(TAG, "ws server on $bindHost:$port (insecure=$allowInsecure)")
                startWssTransport()
                onWssReady?.invoke(boundWssPort)

            } catch (e: java.net.BindException) {
                FileLogger.w(TAG, "Port $port already in use. Assuming server from previous instance is still active.")
                // If the port is in use, it's likely our own service from a previous run that hasn't fully released yet,
                // or a separate instance. We'll mark as running for the UI.
                _connectionState.value = ConnectionState.Running(port)
                // The prior instance holds the ports and is serving wss on port+1; keep the
                // service advertised (registerNsdService no-ops if already registered). We
                // don't re-attempt the wss bind here — that'd just log a spurious failure.
                onWssReady?.invoke(port + 1)
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
                try { wssServer?.stop(500) } catch (e: Exception) { FileLogger.e(TAG, "Error stopping wss", e) }
                wssServer = null
                wssClients.clear()
                boundWssPort = null
                certFingerprint = null
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
        wssClients.forEach { conn ->
            try {
                conn.send(statusJson)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to send status (wss)", e)
            }
        }
    }

    fun getPort(): Int = port

    private fun startWssTransport() {
        val dir = tlsDir
        if (dir == null) {
            FileLogger.w(TAG, "No tlsDir provided — wss:// disabled")
            signalUnreachableIfSecureOnly()
            return
        }
        try {
            val tls = TlsIdentity.loadOrCreate(dir)
            certFingerprint = tls.fingerprint
            val wssPort = port + 1
            WssTransport(wssPort, tls.sslContext).also {
                it.start()
                wssServer = it
                boundWssPort = wssPort
            }
            FileLogger.i(TAG, "wss server started on $wssPort (pin ${tls.fingerprint})")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start wss transport", e)
            signalUnreachableIfSecureOnly()
        }
    }

    // wss failed and ws:// is loopback-only ⇒ unreachable by external senders.
    // Surface a hint so the user knows to enable "Allow insecure" in Settings.
    private fun signalUnreachableIfSecureOnly() {
        if (!allowInsecure) {
            _connectionState.value = ConnectionState.Error(
                "Secure server failed — enable \"Allow insecure\" in Settings"
            )
        }
    }

    // Shared pairing approval: shows the prompt and awaits the user's Allow/Deny
    // (auto-deny after 30s). Used by the wss transport; the CIO path inlines its own.
    private suspend fun awaitPairingApproval(deviceName: String, deviceUUID: String): Boolean {
        val approval = CompletableDeferred<Boolean>()
        _pendingPairingRequest.value = PairingRequest(deviceName, deviceUUID, approval)
        _connectionAttemptFlow.tryEmit(Unit)
        val timeoutJob = scope.launch {
            delay(30_000)
            approval.complete(false)
        }
        val approved = approval.await()
        timeoutJob.cancel()
        _pendingPairingRequest.value = null
        return approved
    }

    /** TLS-terminating wss:// transport — Ktor CIO can't terminate TLS. */
    inner class WssTransport(
        private val wssPort: Int,
        sslContext: javax.net.ssl.SSLContext,
    ) : org.java_websocket.server.WebSocketServer(java.net.InetSocketAddress(wssPort)) {

        private val authed = ConcurrentHashMap.newKeySet<org.java_websocket.WebSocket>()

        init {
            setWebSocketFactory(org.java_websocket.server.DefaultSSLWebSocketServerFactory(sslContext))
            isReuseAddr = true
            connectionLostTimeout = 20
        }

        override fun onStart() {
            FileLogger.i(TAG, "wss transport listening on $wssPort")
        }

        override fun onOpen(conn: org.java_websocket.WebSocket, handshake: org.java_websocket.handshake.ClientHandshake) {
            FileLogger.i(TAG, "wss connection: ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: org.java_websocket.WebSocket, code: Int, reason: String?, remote: Boolean) {
            authed.remove(conn)
            if (wssClients.remove(conn)) refreshCount()
        }

        override fun onError(conn: org.java_websocket.WebSocket?, ex: Exception) {
            FileLogger.e(TAG, "wss error", ex)
        }

        override fun onMessage(conn: org.java_websocket.WebSocket, message: String) {
            if (authed.contains(conn)) {
                try {
                    when (val msg = parseIncomingMessage(message)) {
                        is IncomingMessage.Ping -> conn.send(createPongJson())
                        else -> scope.launch { _commands.emit(msg) }
                    }
                } catch (e: Exception) {
                    FileLogger.e(TAG, "wss message error", e)
                }
                return
            }
            handlePreAuth(conn, message)
        }

        override fun onMessage(conn: org.java_websocket.WebSocket, message: java.nio.ByteBuffer) {
            if (!authed.contains(conn)) return
            val bytes = ByteArray(message.remaining()).also { message.get(it) }
            if (bytes.size == 9) {
                val unpacked = com.playbridge.shared.protocol.MousePacket.unpack(bytes) ?: return
                scope.launch {
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

        private fun handlePreAuth(conn: org.java_websocket.WebSocket, text: String) {
            if (text.contains("\"type\":\"ping\"") || text.contains("\"type\": \"ping\"")) {
                conn.send(createPongJson())
                return
            }
            if (text.contains("\"type\":\"pairing_request\"")) {
                if (_pendingPairingRequest.value != null) {
                    conn.send(createPairingDeniedJson()); conn.close(); return
                }
                val msg = (parseIncomingMessage(text) as? IncomingMessage.PairingRequest)?.msg
                if (msg == null) {
                    conn.send(createPairingDeniedJson()); conn.close(); return
                }
                scope.launch {
                    val approved = awaitPairingApproval(msg.device_name, msg.device_uuid)
                    if (approved) {
                        val token = onPairingApproved(msg.device_name, msg.device_uuid)
                        conn.send(createPairingApprovedJson(token, certFingerprint))
                        registerAuthed(conn)
                    } else {
                        conn.send(createPairingDeniedJson()); conn.close()
                    }
                }
                return
            }
            if (text.contains("\"type\":\"auth\"")) {
                val token = (parseIncomingMessage(text) as? IncomingMessage.Auth)?.msg?.token
                scope.launch {
                    if (!token.isNullOrEmpty() && isTokenAuthorized(token)) {
                        conn.send(createAuthResponseJson(success = true, certFingerprint = certFingerprint))
                        registerAuthed(conn)
                    } else {
                        conn.send(createAuthResponseJson(success = false)); conn.close()
                    }
                }
            }
        }

        private fun registerAuthed(conn: org.java_websocket.WebSocket) {
            authed.add(conn)
            wssClients.add(conn)
            refreshCount()
            _connectionState.value = ConnectionState.Connected(conn.remoteSocketAddress?.toString() ?: "wss")
        }

        private fun refreshCount() {
            _connectedClientCount.value = clients.size + wssClients.size
            if (clients.isEmpty() && wssClients.isEmpty()) {
                _connectionState.value = ConnectionState.Running(port)
            }
        }
    }
}
