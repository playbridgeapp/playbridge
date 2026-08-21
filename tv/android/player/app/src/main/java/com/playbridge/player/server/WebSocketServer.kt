package com.playbridge.player.server

import android.util.Log
import com.playbridge.shared.protocol.IncomingMessage
import com.playbridge.shared.protocol.createAuthResponseJson
import com.playbridge.shared.protocol.createProtectedPairingApprovedJson
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
import java.net.BindException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

private const val TAG = "WebSocketServer"
private const val WSS_START_TIMEOUT_MS = 10_000L

internal fun receiverPortCandidates(
    requestedPort: Int,
    defaultPort: Int = com.playbridge.shared.protocol.Config.DEFAULT_PORT,
    maxAttempts: Int = 32,
): List<Int> {
    if (maxAttempts <= 0) return emptyList()
    val firstPort = requestedPort.takeIf { it in 1..65535 }
        ?: defaultPort.takeIf { it in 1..65535 }
        ?: return emptyList()
    val attemptCount = minOf(maxAttempts, 65535 - firstPort + 1)
    return List(attemptCount) { offset -> firstPort + offset }
}

internal fun Throwable.isAddressAlreadyInUse(): Boolean =
    generateSequence(this) { it.cause }
        .any { cause ->
            cause is BindException && (
                cause.message?.contains("address already in use", ignoreCase = true) == true ||
                    cause.message?.contains("EADDRINUSE", ignoreCase = true) == true
                )
        }

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
    // Invoked from Java-WebSocket's onStart path with the actual bound port. It is
    // never invoked for a failed bind, so callers cannot advertise a dead endpoint.
    private val onWssReady: ((wssPort: Int, logsPort: Int?) -> Unit)? = null,
    // Players/browsers this receiver supports, re-evaluated per auth so a plugin installed
    // after start-up is picked up on the next (re)connect. Reported to the phone at auth.
    private val capabilities: () -> TvCapabilities = {
        TvCapabilities(emptyList(), emptyList(), screenMirrorWebRtc = false)
    },
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
        var sasCode: String? = null
    )

    private val inProgressHandshakes = ConcurrentHashMap<org.java_websocket.WebSocket, ConnectionHandshake>()
    private val failedAttemptsMap = ConcurrentHashMap<String, Int>()
    private val lockoutMap = ConcurrentHashMap<String, Long>()

    private var diagnosticsServer: EmbeddedServer<*, *>? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var startJob: Job? = null

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

    // NOTE: there is no manual "approve" entry point. Approval is driven exclusively by
    // the SAS confirmation MAC (see handlePreAuth → pairing_confirmation): the phone proves
    // it holds the shared secret, and only a matching MAC completes the approval. A manual
    // Allow button would bypass key confirmation, so it was removed (Phase 1).
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
        if (startJob?.isActive == true || wssServer != null) {
            FileLogger.w(TAG, "Server already running")
            return
        }

        _connectionState.value = ConnectionState.Starting

        startJob = scope.launch {
            try {
                val selectedPort = startWssTransport()
                val logsPort = startDiagnosticsServer(selectedPort)
                _connectionState.value = ConnectionState.Running(selectedPort)
                onWssReady?.invoke(selectedPort, logsPort)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to start server", e)
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
            } finally {
                startJob = null
            }
        }
    }
// ... (handleConnection remains same)

    fun stop() {
        // Detach references and flip state synchronously; do the (blocking) engine
        // teardown off the caller's thread. This is invoked from ServerService.onDestroy
        // on the main thread — the previous runBlocking teardown could stall it for
        // 1.5s+ (ANR territory).
        val ktor = diagnosticsServer
        val wss = wssServer
        startJob?.cancel()
        startJob = null
        diagnosticsServer = null
        wssServer = null
        wssClients.clear()
        boundWssPort = null
        certFingerprint = null
        _connectionState.value = ConnectionState.Stopped
        scope.launch {
            try {
                ktor?.stop(500, 1000)
                try { wss?.stop(500) } catch (e: Exception) { FileLogger.e(TAG, "Error stopping wss", e) }
                FileLogger.i(TAG, "Server stopped")
            } catch (e: Exception) {
                FileLogger.e(TAG, "Error stopping server", e)
            }
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

    fun getPort(): Int = boundWssPort ?: port

    private suspend fun startWssTransport(): Int {
        val dir = tlsDir
        if (dir == null) {
            throw IllegalStateException("No TLS identity directory configured")
        }

        val tls = TlsIdentity.loadOrCreate(dir)
        val candidates = receiverPortCandidates(port)
        var lastBindFailure: Throwable? = null
        for (candidate in candidates) {
            val transport = WssTransport(candidate, tls.sslContext)
            wssServer = transport
            val startupFailure = try {
                transport.start()
                transport.awaitStartup()
            } catch (e: Exception) {
                e
            }

            if (startupFailure == null) {
                certFingerprint = tls.fingerprint
                boundWssPort = candidate
                FileLogger.i(TAG, "wss server started on $candidate (pin ${tls.fingerprint})")
                return candidate
            }

            if (wssServer === transport) wssServer = null
            try {
                transport.stop(500)
            } catch (_: Exception) {
                // A transport that failed before binding may already be fully stopped.
            }

            if (!startupFailure.isAddressAlreadyInUse()) throw startupFailure
            lastBindFailure = startupFailure
            FileLogger.w(TAG, "Port $candidate is in use; trying the next receiver port")
        }

        throw IllegalStateException(
            "No available receiver port in ${candidates.firstOrNull()}..${candidates.lastOrNull()}",
            lastBindFailure,
        )
    }

    private suspend fun startDiagnosticsServer(selectedPort: Int): Int? {
        val preferredPort = if (selectedPort < 65535) selectedPort + 1 else 0
        val started = try {
            startDiagnosticsServerOn(preferredPort)
        } catch (e: CancellationException) {
            throw e
        } catch (preferredFailure: Exception) {
            FileLogger.w(TAG, "Diagnostics server failed on port $preferredPort; trying an OS-assigned port")
            if (preferredPort == 0) {
                FileLogger.e(TAG, "Diagnostics server unavailable", preferredFailure)
                return null
            }
            try {
                startDiagnosticsServerOn(0)
            } catch (e: CancellationException) {
                throw e
            } catch (fallbackFailure: Exception) {
                FileLogger.e(TAG, "Diagnostics server unavailable", fallbackFailure)
                return null
            }
        }
        diagnosticsServer = started
        val actualPort = try {
            started.engine.resolvedConnectors().firstOrNull()?.port
        } catch (e: Exception) {
            FileLogger.w(TAG, "Could not resolve diagnostics server port", e)
            null
        }?.takeIf { it in 1..65535 }
        FileLogger.i(TAG, "http log server on 0.0.0.0:${actualPort ?: "OS-assigned"}")
        return actualPort
    }

    private fun startDiagnosticsServerOn(diagnosticsPort: Int): EmbeddedServer<*, *> =
        embeddedServer(CIO, host = "0.0.0.0", port = diagnosticsPort) {
            routing {
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

                delete("/logs") {
                    if (!FileLogger.isEnabled()) {
                        call.respondText(
                            "Logging is disabled on the TV.",
                            ContentType.Text.Plain,
                            HttpStatusCode.Forbidden
                        )
                        return@delete
                    }
                    FileLogger.clearLogs()
                    call.respondText("Logs cleared.", ContentType.Text.Plain)
                }
            }
        }.start(wait = false)

    // Shared pairing approval: displays the SAS code and awaits the phone's confirmation MAC
    // (auto-deny after 60s). The window matches the desktop receiver and gives the user room
    // to re-enter the code (the phone allows a few retries) before the handshake expires.
    private suspend fun awaitPairingApproval(deviceName: String, deviceUUID: String, sasCode: String): Boolean {
        val approval = CompletableDeferred<Boolean>()
        _pendingPairingRequest.value = PairingRequest(deviceName, deviceUUID, sasCode, approval)
        _connectionAttemptFlow.tryEmit(Unit)
        val timeoutJob = scope.launch {
            delay(60_000)
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
        private val startup = CompletableDeferred<Throwable?>()

        init {
            setWebSocketFactory(org.java_websocket.server.DefaultSSLWebSocketServerFactory(sslContext))
            isReuseAddr = true
            connectionLostTimeout = 20
        }

        override fun onStart() {
            FileLogger.i(TAG, "wss transport listening on $wssPort")
            startup.complete(null)
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
            if (conn == null) startup.complete(ex)
        }

        suspend fun awaitStartup(): Throwable? = try {
            withTimeout(WSS_START_TIMEOUT_MS) { startup.await() }
        } catch (e: TimeoutCancellationException) {
            IllegalStateException("Timed out waiting for port $wssPort to bind", e)
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

        // Routes pre-auth frames on the parsed envelope type (not substring matching), so a
        // payload that merely contains a type literal can't be misrouted. Every pre-auth
        // message is a variant of the shared [IncomingMessage] sealed type.
        private fun handlePreAuth(conn: org.java_websocket.WebSocket, text: String) {
            when (val msg = parseIncomingMessage(text)) {
                is IncomingMessage.Ping -> conn.send(createPongJson())
                is IncomingMessage.PairingCommit -> handleCommit(conn, msg.msg)
                is IncomingMessage.PairingReveal -> handleReveal(conn, msg.msg)
                is IncomingMessage.PairingConfirmation -> handleConfirmation(conn, msg.msg)
                is IncomingMessage.Auth -> handleAuth(conn, msg.msg)
                else -> { /* Ignore anything else from an unauthenticated peer. */ }
            }
        }

        private fun handleCommit(conn: org.java_websocket.WebSocket, msg: playbridge.PairingCommitMessage) {
            val ip = conn.remoteSocketAddress?.address?.hostAddress ?: ""
            val lockoutUntil = lockoutMap[ip]
            if (lockoutUntil != null && System.currentTimeMillis() < lockoutUntil) {
                FileLogger.w(TAG, "IP $ip is locked out from pairing")
                conn.send(createPairingDeniedJson()); conn.close(); return
            }
            if (_pendingPairingRequest.value != null) {
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
        }

        private fun handleReveal(conn: org.java_websocket.WebSocket, msg: playbridge.PairingRevealMessage) {
            val ip = conn.remoteSocketAddress?.address?.hostAddress ?: ""
            val handshake = inProgressHandshakes[conn]
            if (handshake == null) {
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
                            val transcriptHash = SasCrypto.sha256(transcript)
                            val prk = SasCrypto.hkdfExtract(salt = null, ikm = sharedSecret)
                            val credentialKey = SasCrypto.hkdfExpand(
                                prk, info = "playbridgeCredentialKey-v1".toByteArray(), length = 32
                            )
                            val credentialNonce = SasCrypto.generateNonce(12)
                            val plaintext = buildJsonObject {
                                put("token", token)
                                certFingerprint?.let { put("certFingerprint", it) }
                                put("players", buildJsonArray { caps.players.forEach { add(it) } })
                                put("browsers", buildJsonArray { caps.browsers.forEach { add(it) } })
                                put("mediaKinds", buildJsonArray { caps.mediaKinds.forEach { add(it) } })
                                if (caps.screenMirrorWebRtc) put("screenMirrorWebRtc", true)
                            }.toString().toByteArray()
                            val ciphertext = SasCrypto.aesGcmEncrypt(
                                credentialKey, credentialNonce, plaintext, transcriptHash
                            )
                            conn.send(createProtectedPairingApprovedJson(
                                Base64.getEncoder().encodeToString(credentialNonce),
                                Base64.getEncoder().encodeToString(ciphertext),
                            ))
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
        }

        private fun handleConfirmation(conn: org.java_websocket.WebSocket, msg: playbridge.PairingConfirmationMessage) {
            val handshake = inProgressHandshakes[conn]
            if (handshake == null) {
                conn.send(createPairingDeniedJson()); conn.close(); return
            }

            val commitBytes = Base64.getDecoder().decode(handshake.commit)
            val transcript = commitBytes + handshake.tvEphPub + handshake.nonceT + handshake.senderEphPub!! + handshake.nonceS!!

            // Derive confirmation key and expected MAC
            val prk = SasCrypto.hkdfExtract(salt = null, ikm = handshake.sharedSecret!!)
            val confirmationKey = SasCrypto.hkdfExpand(prk, info = "confirmationKey".toByteArray(), length = 32)
            val expectedMacBytes = SasCrypto.hmacSha256(confirmationKey, transcript)
            val expectedMac = Base64.getEncoder().encodeToString(expectedMacBytes)

            // This MAC is the *only* signal that completes approval with success — the phone
            // proves it derived the same shared secret. There is no manual Allow bypass.
            if (msg.mac == expectedMac) {
                _pendingPairingRequest.value?.approval?.complete(true)
            } else {
                FileLogger.w(TAG, "Confirmation MAC mismatch on connection ${conn.remoteSocketAddress}")
                _pendingPairingRequest.value?.approval?.complete(false)
            }
        }

        private fun handleAuth(conn: org.java_websocket.WebSocket, msg: playbridge.AuthMessage) {
            val token = msg.token
            scope.launch {
                try {
                    if (!token.isNullOrEmpty() && isTokenAuthorized(token)) {
                        val caps = capabilities()
                        if (conn.isOpen) {
                            conn.send(createAuthResponseJson(
                                success = true, certFingerprint = certFingerprint,
                                players = caps.players,
                                browsers = caps.browsers,
                                mediaKinds = caps.mediaKinds,
                                screenMirrorWebRtc = caps.screenMirrorWebRtc,
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
                _connectionState.value = ConnectionState.Running(getPort())
            } else {
                if (_connectionState.value !is ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Connected(clients.first().remoteSocketAddress?.toString() ?: "wss")
                }
            }
        }
    }
}
