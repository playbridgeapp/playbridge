package com.playbridge.player.server

import android.util.Log
import com.playbridge.shared.protocol.IncomingMessage
import com.playbridge.shared.protocol.createAuthResponseJson
import com.playbridge.shared.protocol.createPairingApprovedJson
import com.playbridge.shared.protocol.createPairingDeniedJson
import com.playbridge.shared.protocol.createPairingChallengeJson
import com.playbridge.shared.protocol.createPongJson
import com.playbridge.shared.protocol.parseIncomingMessage
import kotlinx.coroutines.CompletableDeferred
import com.playbridge.player.logging.FileLogger
import java.util.Base64
import com.playbridge.shared.crypto.SasCrypto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "WebSocketServer"

/**
 * WebSocket server for receiving commands from the phone app
 */
class WebSocketServer(
    private val port: Int = com.playbridge.shared.protocol.Config.DEFAULT_PORT,
    private val isTokenAuthorized: suspend (String) -> Boolean,
    private val onPairingApproved: suspend (deviceName: String, deviceUUID: String) -> String,
    // App-private directory for the persisted TLS identity (PKCS12). wss:// is
    // disabled if null.
    private val tlsDir: File? = null,
    // Invoked after the wss bind attempt with the bound port (null if it failed),
    // so the caller advertises wss_port over NSD only when it's actually up.
    private val onWssReady: ((Int?) -> Unit)? = null,
    // Players/browsers this receiver supports, re-evaluated per auth so a plugin installed
    // after start-up is picked up on the next (re)connect. Reported to the phone at auth.
    private val capabilities: () -> TvCapabilities = { TvCapabilities(emptyList(), emptyList()) },
) {
    data class PairingRequest(
        val deviceName: String,
        val deviceUUID: String,
        val sasCode: String,
        internal val approval: CompletableDeferred<Boolean>
    )

    private class ConnectionHandshake(
        val deviceName: String,
        val deviceUUID: String,
        val commit: String,
        val tvEphPriv: ByteArray,
        val tvEphPub: ByteArray,
        val nonceT: ByteArray,
        var senderEphPub: ByteArray? = null,
        var nonceS: ByteArray? = null,
        var sharedSecret: ByteArray? = null,
        var sasCode: String? = null,
        var attemptsLeft: Int = 3
    )

    private val inProgressHandshakes = ConcurrentHashMap<org.java_websocket.WebSocket, ConnectionHandshake>()
    private val failedAttemptsMap = ConcurrentHashMap<String, Int>()
    private val lockoutMap = ConcurrentHashMap<String, Long>()

    private var server: EmbeddedServer<*, *>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

                val bindHost = "0.0.0.0"
                server = embeddedServer(CIO, host = bindHost, port = port + 1) {
                    routing {
                        // HTTP endpoint: download log files
                        get("/logs") {
                            if (!FileLogger.isEnabled()) {
                                call.respondText(
                                    "Logging is disabled on the TV.",
                                    ContentType.Text.Plain,
                                    HttpStatusCode.Forbidden
                                )
                                return@get
                            }
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
                    }
                }.start(wait = false)

                _connectionState.value = ConnectionState.Running(port)
                FileLogger.i(TAG, "http log server on $bindHost:${port + 1}")
                startWssTransport()
                onWssReady?.invoke(boundWssPort)

            } catch (e: java.net.BindException) {
                FileLogger.w(TAG, "Port $port already in use. Assuming server from previous instance is still active.")
                // If the port is in use, it's likely our own service from a previous run that hasn't fully released yet,
                // or a separate instance. We'll mark as running for the UI.
                _connectionState.value = ConnectionState.Running(port)
                // The prior instance holds the ports and is serving wss on port; keep the
                // service advertised (registerNsdService no-ops if already registered). We
                // don't re-attempt the wss bind here — that'd just log a spurious failure.
                onWssReady?.invoke(port)
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

    /**
     * Broadcast status update to all connected clients
     */
    suspend fun broadcastStatus(statusJson: String) {
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
            val wssPort = port
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
    private fun signalUnreachableIfSecureOnly() {
        _connectionState.value = ConnectionState.Error(
            "Secure server failed to start"
        )
    }

    // Shared pairing approval: shows the prompt and awaits the user's Allow/Deny
    // (auto-deny after 30s). Used by the wss transport; the CIO path inlines its own.
    private suspend fun awaitPairingApproval(deviceName: String, deviceUUID: String, sasCode: String): Boolean {
        val approval = CompletableDeferred<Boolean>()
        _pendingPairingRequest.value = PairingRequest(deviceName, deviceUUID, sasCode, approval)
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
            val ip = conn.remoteSocketAddress?.address?.hostAddress ?: ""
            val lockoutUntil = lockoutMap[ip]
            if (lockoutUntil != null && System.currentTimeMillis() < lockoutUntil) {
                FileLogger.w(TAG, "IP $ip is locked out from pairing")
                conn.close()
            }
        }

        override fun onClose(conn: org.java_websocket.WebSocket, code: Int, reason: String?, remote: Boolean) {
            authed.remove(conn)
            val handshake = inProgressHandshakes.remove(conn)
            if (handshake != null) {
                val pending = _pendingPairingRequest.value
                if (pending != null && pending.deviceUUID == handshake.deviceUUID) {
                    pending.approval.complete(false)
                }
                scope.launch {
                    if (_connectionState.value is ConnectionState.Stopped) return@launch
                    _connectionState.value = ConnectionState.Error("Incorrect code or connection lost")
                    delay(3000)
                    if (_connectionState.value is ConnectionState.Stopped) return@launch
                    refreshCount()
                }
            }
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

            val ip = conn.remoteSocketAddress?.address?.hostAddress ?: ""

            if (text.contains("\"type\":\"pairing_commit\"")) {
                val lockoutUntil = lockoutMap[ip]
                if (lockoutUntil != null && System.currentTimeMillis() < lockoutUntil) {
                    FileLogger.w(TAG, "IP $ip is locked out from pairing")
                    conn.send(createPairingDeniedJson())
                    conn.close()
                    return
                }

                if (_pendingPairingRequest.value != null) {
                    conn.send(createPairingDeniedJson()); conn.close(); return
                }
                val msg = (parseIncomingMessage(text) as? IncomingMessage.PairingCommit)?.msg
                if (msg == null) {
                    conn.send(createPairingDeniedJson()); conn.close(); return
                }

                // Generate TV X25519 keypair and nonce
                val tvKey = SasCrypto.generateX25519KeyPair()
                val nonceT = SasCrypto.generateNonce(16)

                val handshake = ConnectionHandshake(
                    deviceName = msg.device_name,
                    deviceUUID = msg.device_uuid,
                    commit = msg.commit,
                    tvEphPriv = tvKey.privateKey,
                    tvEphPub = tvKey.publicKey,
                    nonceT = nonceT
                )
                inProgressHandshakes[conn] = handshake

                val tvEphPubB64 = Base64.getEncoder().encodeToString(tvKey.publicKey)
                val nonceTB64 = Base64.getEncoder().encodeToString(nonceT)
                conn.send(createPairingChallengeJson(tvEphPub = tvEphPubB64, nonceT = nonceTB64))
                return
            }

            if (text.contains("\"type\":\"pairing_reveal\"")) {
                val handshake = inProgressHandshakes[conn]
                if (handshake == null) {
                    conn.send(createPairingDeniedJson()); conn.close(); return
                }
                val msg = (parseIncomingMessage(text) as? IncomingMessage.PairingReveal)?.msg
                if (msg == null) {
                    conn.send(createPairingDeniedJson()); conn.close(); return
                }

                val senderEphPubBytes = Base64.getDecoder().decode(msg.sender_eph_pub)
                val nonceSBytes = Base64.getDecoder().decode(msg.nonce_s)

                // Verify commitment: commit == SHA256(senderEphPub || nonceS)
                val calculatedCommitBytes = SasCrypto.sha256(senderEphPubBytes + nonceSBytes)
                val calculatedCommit = Base64.getEncoder().encodeToString(calculatedCommitBytes)
                if (calculatedCommit != handshake.commit) {
                    FileLogger.w(TAG, "Commitment mismatch on connection ${conn.remoteSocketAddress}")
                    conn.send(createPairingDeniedJson())
                    conn.close()
                    inProgressHandshakes.remove(conn)
                    recordPairingFailure(ip)
                    return
                }

                handshake.senderEphPub = senderEphPubBytes
                handshake.nonceS = nonceSBytes

                // Compute ECDH shared secret and SAS
                val sharedSecret = SasCrypto.calculateECDH(handshake.tvEphPriv, senderEphPubBytes)
                handshake.sharedSecret = sharedSecret

                val commitBytes = Base64.getDecoder().decode(handshake.commit)
                val transcript = commitBytes + handshake.tvEphPub + handshake.nonceT + senderEphPubBytes + nonceSBytes
                val sas = SasCrypto.generateSAS(sharedSecret, transcript)
                handshake.sasCode = sas

                // Show the pairing display on the TV
                scope.launch {
                    try {
                        val approved = awaitPairingApproval(handshake.deviceName, handshake.deviceUUID, sas)
                        if (approved) {
                            failedAttemptsMap.remove(ip)
                            val token = onPairingApproved(handshake.deviceName, handshake.deviceUUID)
                            val caps = capabilities()
                            if (conn.isOpen) {
                                conn.send(createPairingApprovedJson(token, certFingerprint, caps.players, caps.browsers))
                            }
                            registerAuthed(conn)
                            inProgressHandshakes.remove(conn)
                        } else {
                            if (conn.isOpen) {
                                conn.send(createPairingDeniedJson())
                                conn.close()
                            }
                            recordPairingFailure(ip)
                        }
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "Error in pairing approval coroutine", e)
                    }
                }
                return
            }

            if (text.contains("\"type\":\"pairing_confirmation\"")) {
                val handshake = inProgressHandshakes[conn]
                if (handshake == null) {
                    conn.send(createPairingDeniedJson()); conn.close(); return
                }
                val msg = (parseIncomingMessage(text) as? IncomingMessage.PairingConfirmation)?.msg
                if (msg == null) {
                    conn.send(createPairingDeniedJson()); conn.close(); return
                }

                val commitBytes = Base64.getDecoder().decode(handshake.commit)
                val transcript = commitBytes + handshake.tvEphPub + handshake.nonceT + handshake.senderEphPub!! + handshake.nonceS!!

                // Derive confirmation key and expected MAC
                val prk = SasCrypto.hkdfExtract(salt = null, ikm = handshake.sharedSecret!!)
                val confirmationKey = SasCrypto.hkdfExpand(prk, info = "confirmationKey".toByteArray(), length = 32)
                val expectedMacBytes = SasCrypto.hmacSha256(confirmationKey, transcript)
                val expectedMac = Base64.getEncoder().encodeToString(expectedMacBytes)

                if (msg.mac == expectedMac) {
                    _pendingPairingRequest.value?.approval?.complete(true)
                } else {
                    FileLogger.w(TAG, "Confirmation MAC mismatch on connection ${conn.remoteSocketAddress}")
                    _pendingPairingRequest.value?.approval?.complete(false)
                }
                return
            }

            if (text.contains("\"type\":\"auth\"")) {
                val token = (parseIncomingMessage(text) as? IncomingMessage.Auth)?.msg?.token
                scope.launch {
                    try {
                        if (!token.isNullOrEmpty() && isTokenAuthorized(token)) {
                            val caps = capabilities()
                            if (conn.isOpen) {
                                conn.send(createAuthResponseJson(
                                    success = true, certFingerprint = certFingerprint,
                                    players = caps.players, browsers = caps.browsers
                                ))
                            }
                            registerAuthed(conn)
                        } else {
                            if (conn.isOpen) {
                                conn.send(createAuthResponseJson(success = false))
                                conn.close()
                            }
                        }
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "Error in auth coroutine", e)
                    }
                }
            }
        }

        private fun recordPairingFailure(ip: String) {
            val attempts = (failedAttemptsMap[ip] ?: 0) + 1
            failedAttemptsMap[ip] = attempts
            if (attempts >= 3) {
                lockoutMap[ip] = System.currentTimeMillis() + 60_000
                failedAttemptsMap.remove(ip)
                FileLogger.w(TAG, "IP $ip locked out for 60s due to 3 failed pairing attempts")
            }
        }

        private fun registerAuthed(conn: org.java_websocket.WebSocket) {
            authed.add(conn)
            wssClients.add(conn)
            refreshCount()
            _connectionState.value = ConnectionState.Connected(conn.remoteSocketAddress?.toString() ?: "wss")
        }

        private fun refreshCount() {
            if (_connectionState.value is ConnectionState.Stopped) return
            val clients = wssClients.toList()
            _connectedClientCount.value = clients.size
            if (clients.isEmpty()) {
                _connectionState.value = ConnectionState.Running(port)
            } else {
                if (_connectionState.value !is ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Connected(clients.first().remoteSocketAddress?.toString() ?: "wss")
                }
            }
        }
    }
}
