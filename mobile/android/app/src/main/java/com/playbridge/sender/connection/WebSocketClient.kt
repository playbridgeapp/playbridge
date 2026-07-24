package com.playbridge.sender.connection

import android.util.Log
import com.playbridge.shared.protocol.createAuthJson
import com.playbridge.shared.protocol.createContextQueryJson
import com.playbridge.shared.protocol.createPairingCommitJson
import com.playbridge.shared.protocol.createPairingChallengeJson
import com.playbridge.shared.protocol.createPairingRevealJson
import com.playbridge.shared.protocol.createPairingConfirmationJson
import com.playbridge.shared.protocol.createPingJson
import java.util.Base64
import com.playbridge.shared.crypto.SasCrypto
import com.playbridge.shared.protocol.IncomingMessage
import com.playbridge.shared.protocol.parseIncomingMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

private const val TAG = "WebSocketClient"

/** Number of SAS code entries the user gets before the handshake is torn down. */
private const val MAX_PAIR_ATTEMPTS = 3

/**
 * OkHttp-based WebSocket client for connecting to TV
 */
class WebSocketClient {
    
    private val client = OkHttpClient.Builder()
        .dns(LinkLocalDns())
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    
    @Volatile
    private var webSocket: WebSocket? = null

    // SPKI pin the server presented during the current TLS handshake (captured by
    // the pinning trust manager), and whether it failed to match the expected pin.
    @Volatile private var capturedServerPin: String? = null
    @Volatile private var pinMismatch: Boolean = false
    // Whether the active connection is wss (true) vs plaintext ws (false).
    @Volatile private var isSecure: Boolean = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _messages = MutableSharedFlow<String>(replay = 0)
    val messages = _messages.asSharedFlow()

    private val _newCredentials = MutableSharedFlow<IssuedCredentials>(replay = 0)
    val newCredentials = _newCredentials.asSharedFlow()

    // Players/browsers the TV reports at auth. Emitted on *every* successful auth
    // (incl. reconnect with an existing token, where no new credentials are issued),
    // so it's a separate channel from [newCredentials].
    private val _tvCapabilities = MutableSharedFlow<TvCapabilities>(replay = 0)
    val tvCapabilities = _tvCapabilities.asSharedFlow()

    private var targetConnection: TvConnectionInfo? = null
    private var isUserDisconnect = false

    /**
     * True when the last disconnect was an explicit user action (Disconnect button /
     * route change), false after an unexpected drop. Lets callers (foreground-return
     * hook, auto-connect re-arm) avoid re-establishing a link the user chose to close.
     */
    val wasUserDisconnect: Boolean get() = isUserDisconnect

    @Volatile private var senderKeyPair: SasCrypto.KeyPair? = null
    @Volatile private var nonceS: ByteArray? = null
    @Volatile private var commitStr: String? = null
    @Volatile private var tvEphPub: ByteArray? = null
    @Volatile private var nonceT: ByteArray? = null
    @Volatile private var sharedSecret: ByteArray? = null
    @Volatile private var calculatedSas: String? = null
    // SAS entries remaining for the current handshake; reset when a fresh challenge arrives.
    @Volatile private var pairingAttemptsLeft: Int = MAX_PAIR_ATTEMPTS
    
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
        val wssPort: Int? = null,
        val pin: String? = null,
        /** The receiver's uuid (from discovery/saved record) — used to match the saved
         *  device on reconnect. Names collide (two TVs of the same model announce the
         *  same Build.MODEL); uuids don't. Empty when unknown. */
        val tvUuid: String = "",
    )

    /** Token + SPKI pin issued by the receiver, persisted together by the ViewModel. */
    data class IssuedCredentials(val token: String, val certFingerprint: String?)

    /** Players/browsers (player_mode / browser_mode ids) the TV reported it supports. */
    data class TvCapabilities(val players: List<String>, val browsers: List<String>)

        sealed class ConnectionState {
            data object Disconnected : ConnectionState()
            /** [serverName] = the receiver being dialled, so UI can say WHICH TV. */
            data class Connecting(val serverName: String = "TV") : ConnectionState()
            data class Connected(val serverName: String, val secure: Boolean = false) : ConnectionState()
            // New SAS Handshake states.
            // [attemptsLeft] counts down as the user mistypes the 6-digit code; [lastCodeWrong]
            // is true when this state was re-emitted after an incorrect entry, so the UI can
            // show an inline "incorrect code — N left" hint and clear the field.
            data class WaitingForCodeInput(
                val serverName: String,
                val attemptsLeft: Int = MAX_PAIR_ATTEMPTS,
                val lastCodeWrong: Boolean = false,
            ) : ConnectionState()
            data class VerifyingCode(val serverName: String) : ConnectionState()
            // Pairing request sent — waiting for the TV user to tap Allow.
            data class WaitingForApproval(val serverName: String) : ConnectionState()
            // TV user tapped Deny, or the 30s timeout elapsed.
            data class PairingDenied(val serverName: String) : ConnectionState()
        // Kept for UI exhaustiveness, but no longer emitted — automatic retries were
        // removed (failures go straight to Error; reconnects are on-demand).
        data class Retrying(val attempt: Int, val maxAttempts: Int, val nextRetrySeconds: Int) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
        // Stale token rejected by TV (e.g. after TV reinstall). Distinct from Error so the
        // ViewModel can wipe the token and prompt re-pairing rather than showing a generic error.
        data object AuthFailed : ConnectionState()
        // The receiver's TLS cert didn't match the pinned fingerprint — possible MITM.
        // We refuse to connect and surface a re-pair prompt rather than retrying.
        data class PinMismatch(val serverName: String) : ConnectionState()
    }
    
    fun connect(
        ip: String,
        port: Int,
        token: String,
        serverName: String,
        deviceName: String,
        deviceUUID: String,
        wssPort: Int? = null,
        certFingerprint: String? = null,
        tvUuid: String = "",
    ) {
        isUserDisconnect = false
        targetConnection = TvConnectionInfo(
            ip, port, token, serverName, deviceName, deviceUUID, wssPort, certFingerprint, tvUuid
        )
        attemptConnection(ip, port, serverName)
    }

    private fun attemptConnection(ip: String, port: Int, serverName: String) {
        // Update state first to prevent race where UI thinks we are connected but socket is null
        _connectionState.value = ConnectionState.Connecting(serverName)
        
        if (webSocket != null) {
            try { webSocket?.close(1000, "Reconnecting") } catch(e: Exception) {}
            webSocket = null
        }

        capturedServerPin = null
        pinMismatch = false

        val conn = targetConnection
        val wssPort = conn?.wssPort ?: port
        isSecure = true
        val formattedHost = LinkLocalDns.encodeIpv6ToHost(ip)
        val url = "wss://$formattedHost:$wssPort/"
        val httpClient = buildPinningClient(conn?.pin)
        Log.i(TAG, "Connecting to $url")

        val request = try {
            Request.Builder()
                .url(url)
                .build()
        } catch (e: Exception) {
            // A malformed host (e.g. an un-bracketed IPv6 literal) must never crash the app.
            Log.e(TAG, "Invalid connection URL '$url'", e)
            _connectionState.value = ConnectionState.Error("Invalid address: ${e.message}")
            return
        }

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== this@WebSocketClient.webSocket) {
                    Log.d(TAG, "Ignoring onOpen for stale socket")
                    return
                }
                Log.i(TAG, "Socket opened to $serverName")

                scope.launch {
                    try {
                        val conn = targetConnection
                        if (conn?.token.isNullOrEmpty()) {
                            // SAS Handshake Step 1: Generate keys & commitment, send pairing_commit
                            val pair = SasCrypto.generateX25519KeyPair()
                            val nonce = SasCrypto.generateNonce(16)
                            senderKeyPair = pair
                            nonceS = nonce

                            val commitBytes = SasCrypto.sha256(pair.publicKey + nonce)
                            val commit = Base64.getEncoder().encodeToString(commitBytes)
                            commitStr = commit

                            val json = createPairingCommitJson(
                                commit = commit,
                                deviceName = conn?.deviceName ?: "Android Phone",
                                deviceUUID = conn?.deviceUUID ?: ""
                            )
                            Log.d(TAG, "Sending pairing_commit: $json")
                            webSocket.send(json)
                        } else {
                            // Reconnect with saved token.
                            val authJson = createAuthJson(conn!!.token)
                            Log.d(TAG, "Sending auth credentials")
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

                // Route on the exact envelope `type` rather than substring matching, so a
                // value that merely contains "pairing_approved" etc. can't be misrouted.
                val type = runCatching {
                    (Json.parseToJsonElement(text) as? JsonObject)
                        ?.get("type")?.jsonPrimitive?.contentOrNull
                }.getOrNull()

                // Handle pairing_challenge message from TV
                if (type == "pairing_challenge") {
                    try {
                        val parsed = parseIncomingMessage(text) as? IncomingMessage.PairingChallenge
                        val msg = parsed?.msg
                        if (msg != null) {
                            val tvEphPubBytes = Base64.getDecoder().decode(msg.tv_eph_pub)
                            val nonceTBytes = Base64.getDecoder().decode(msg.nonce_t)

                            tvEphPub = tvEphPubBytes
                            nonceT = nonceTBytes

                            // Calculate ECDH shared secret
                            val sharedSecret = SasCrypto.calculateECDH(senderKeyPair!!.privateKey, tvEphPubBytes)
                            this@WebSocketClient.sharedSecret = sharedSecret

                            // Calculate transcript and local SAS code
                            val commitBytes = Base64.getDecoder().decode(commitStr!!)
                            val transcript = commitBytes + tvEphPubBytes + nonceTBytes + senderKeyPair!!.publicKey + nonceS!!
                            val sas = SasCrypto.generateSAS(sharedSecret, transcript)
                            calculatedSas = sas

                            Log.d(TAG, "Calculated SAS: $sas. Sending reveal and prompting user...")

                            val senderEphPubB64 = Base64.getEncoder().encodeToString(senderKeyPair!!.publicKey)
                            val nonceSB64 = Base64.getEncoder().encodeToString(nonceS!!)
                            val revealJson = createPairingRevealJson(senderEphPub = senderEphPubB64, nonceS = nonceSB64)
                            webSocket.send(revealJson)

                            // Fresh handshake → reset the retry budget for code entry.
                            pairingAttemptsLeft = MAX_PAIR_ATTEMPTS
                            _connectionState.value = ConnectionState.WaitingForCodeInput(serverName)
                            return
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error handling pairing_challenge", e)
                        webSocket.close(1000, "Challenge failed")
                    }
                }
                
                // Handle pairing and auth responses before forwarding to command flow.
                if (type == "pairing_approved") {
                    try {
                        val json = kotlinx.serialization.json.Json.parseToJsonElement(text)
                        if (json is JsonObject && json["type"]?.toString()?.replace("\"", "") == "pairing_approved") {
                            val shared = sharedSecret ?: error("Missing pairing shared secret")
                            val commit = commitStr ?: error("Missing pairing commitment")
                            val tvPub = tvEphPub ?: error("Missing receiver public key")
                            val receiverNonce = nonceT ?: error("Missing receiver nonce")
                            val keypair = senderKeyPair ?: error("Missing sender keypair")
                            val senderNonce = nonceS ?: error("Missing sender nonce")
                            val transcript = Base64.getDecoder().decode(commit) + tvPub + receiverNonce +
                                keypair.publicKey + senderNonce
                            val transcriptHash = SasCrypto.sha256(transcript)
                            val prk = SasCrypto.hkdfExtract(salt = null, ikm = shared)
                            val credentialKey = SasCrypto.hkdfExpand(
                                prk,
                                info = "playbridgeCredentialKey-v1".toByteArray(),
                                length = 32,
                            )
                            val nonce = Base64.getDecoder().decode(
                                json["nonce"]?.jsonPrimitive?.contentOrNull ?: error("Missing credential nonce")
                            )
                            val ciphertext = Base64.getDecoder().decode(
                                json["ciphertext"]?.jsonPrimitive?.contentOrNull ?: error("Missing credentials")
                            )
                            val credentialsJson = Json.parseToJsonElement(
                                SasCrypto.aesGcmDecrypt(
                                    credentialKey, nonce, ciphertext, transcriptHash
                                ).decodeToString()
                            ) as? JsonObject ?: error("Invalid credential payload")
                            val token = credentialsJson["token"]?.jsonPrimitive?.contentOrNull
                            val certFp = credentialsJson["certFingerprint"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotEmpty() }
                            Log.i(TAG, "Pairing approved by $serverName")
                            // Bind the delivered pin to the cert actually served this handshake.
                            val served = capturedServerPin
                            if (certFp != null && served != null && certFp != served) {
                                Log.e(TAG, "pairing_approved pin ($certFp) != served cert ($served) — refusing")
                                pinMismatch = true
                                isUserDisconnect = true
                                _connectionState.value = ConnectionState.PinMismatch(serverName)
                                webSocket.close(1000, "pin mismatch")
                                return
                            }
                            if (!token.isNullOrEmpty() && token != "null") {
                                // Update in-memory connection info so retries after a network
                                // blip send `auth` (not another `pairing_request`).
                                val pin = certFp ?: served
                                targetConnection = targetConnection?.copy(token = token, pin = pin)
                                scope.launch { _newCredentials.emit(IssuedCredentials(token, pin)) }
                            }
                            emitCapabilities(credentialsJson)
                            clearPairingSecrets() // handshake done — drop key material
                            _connectionState.value = ConnectionState.Connected(serverName, isSecure)
                            // Resync: the TV only broadcasts context on its own activity
                            // transitions, so a client (re)connecting mid-playback would
                            // otherwise show "idle" until the next transition.
                            webSocket.send(createContextQueryJson())
                            return
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid or unauthenticated pairing credentials", e)
                        isUserDisconnect = true
                        _connectionState.value = ConnectionState.Error("Pairing security verification failed")
                        webSocket.close(1008, "Invalid pairing credentials")
                        return
                    }
                }

                if (type == "pairing_denied") {
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

                if (type == "auth_response") {
                    try {
                        val json = kotlinx.serialization.json.Json.parseToJsonElement(text)
                        if (json is JsonObject) {
                            val type = json["type"].toString().replace("\"", "")
                            if (type == "auth_response") {
                                val success = json["success"].toString() == "true"
                                if (success) {
                                    Log.i(TAG, "Authentication successful")
                                    emitCapabilities(json)
                                    _connectionState.value = ConnectionState.Connected(serverName, isSecure)
                                    // Resync context after every (re)connect — see the
                                    // pairing_approved path for rationale.
                                    webSocket.send(createContextQueryJson())
                                    // SEC-005: legacy echoed tokens in auth_response are ignored.
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
                Log.e(TAG, "Connection failed: ${t.message}", t)

                if (webSocket === this@WebSocketClient.webSocket) {
                    this@WebSocketClient.webSocket = null

                    if (pinMismatch) {
                        Log.e(TAG, "TLS pin mismatch — refusing to connect (possible MITM)")
                        isUserDisconnect = true
                        _connectionState.value = ConnectionState.PinMismatch(serverName)
                        return
                    }

                    // No automatic retries — a failed/dropped connection goes straight to
                    // Error. Reconnects happen on demand: the "ensure connected before
                    // sending" paths and the startup auto-connect cover recovery, without
                    // a background retry loop flapping the connection UI.
                    _connectionState.value = ConnectionState.Error(t.message ?: "Unknown error")
                } else {
                    Log.d(TAG, "Ignoring onFailure for stale socket")
                }
            }
        })
    }

    /**
     * Submit the 6-digit code typed by the user to verify the TV and complete pairing.
     * Returns true if the code matched and reveal/confirmation messages were sent, false otherwise.
     */
    fun submitPairingCode(code: String): Boolean {
        val ws = webSocket ?: return false
        val keypair = senderKeyPair ?: return false
        val nonce = nonceS ?: return false
        val commit = commitStr ?: return false
        val tvPub = tvEphPub ?: return false
        val nonceTv = nonceT ?: return false
        val shared = sharedSecret ?: return false
        val expectedSas = calculatedSas ?: return false

        // Check if code matches
        val cleanCode = code.replace(" ", "")
        val serverName = targetConnection?.serverName ?: "TV"
        if (cleanCode != expectedSas) {
            pairingAttemptsLeft -= 1
            if (pairingAttemptsLeft <= 0) {
                Log.w(TAG, "SAS retries exhausted — tearing down handshake")
                ws.close(1000, "Incorrect code")
                _connectionState.value = ConnectionState.PairingDenied(serverName)
                return false
            }
            // Keep the socket + handshake alive and re-prompt; the TV is still awaiting our
            // confirmation MAC, so the user can simply retype the code.
            Log.w(TAG, "Incorrect SAS code — $pairingAttemptsLeft attempt(s) left")
            _connectionState.value = ConnectionState.WaitingForCodeInput(
                serverName, pairingAttemptsLeft, lastCodeWrong = true
            )
            return false
        }

        // Code matches! Send confirmation
        _connectionState.value = ConnectionState.VerifyingCode(targetConnection?.serverName ?: "TV")
        scope.launch {
            try {
                // Calculate confirmation MAC
                val commitBytes = Base64.getDecoder().decode(commit)
                val transcript = commitBytes + tvPub + nonceTv + keypair.publicKey + nonce
                val prk = SasCrypto.hkdfExtract(salt = null, ikm = shared)
                val confirmationKey = SasCrypto.hkdfExpand(prk, info = "confirmationKey".toByteArray(), length = 32)
                val macBytes = SasCrypto.hmacSha256(confirmationKey, transcript)
                val mac = Base64.getEncoder().encodeToString(macBytes)

                val confirmationJson = createPairingConfirmationJson(mac)
                Log.d(TAG, "Sending pairing_confirmation: $confirmationJson")
                ws.send(confirmationJson)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send confirmation", e)
                ws.close(1000, "Handshake fail")
            }
        }
        return true
    }

    /**
     * Drop the ephemeral SAS-handshake material (keys, nonces, shared secret, code).
     * Called once a session is established and on teardown — there's no reason to keep
     * key material in memory for the life of the process.
     */
    private fun clearPairingSecrets() {
        senderKeyPair = null
        nonceS = null
        commitStr = null
        tvEphPub = null
        nonceT = null
        sharedSecret = null
        calculatedSas = null
    }

    /** Parse players/browsers from an auth/pairing response and publish them (if any). */
    private fun emitCapabilities(json: JsonObject) {
        val players = parseStringArray(json, "players")
        val browsers = parseStringArray(json, "browsers")
        if (players.isNotEmpty() || browsers.isNotEmpty()) {
            scope.launch { _tvCapabilities.emit(TvCapabilities(players, browsers)) }
        }
    }

    private fun parseStringArray(json: JsonObject, key: String): List<String> =
        (json[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    /**
     * OkHttp client for wss:// that trusts the receiver's self-signed cert by SPKI
     * pin. Captures the presented pin (for pairing-time verification) and rejects a
     * mismatch against [expectedPin] (possible MITM). When [expectedPin] is null
     * (first pairing) it accepts the cert trust-on-first-use.
     */
    private fun buildPinningClient(expectedPin: String?): OkHttpClient {
        val trustManager = PinningTrustManager(expectedPin) { presented ->
            capturedServerPin = presented
            if (expectedPin != null && presented != expectedPin) pinMismatch = true
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        return client.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // Pinning replaces hostname/CA validation; the cert is bound by its SPKI.
            .hostnameVerifier { _, _ -> true }
            .build()
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
    
    /**
     * Re-establish the last connection using the retained [targetConnection]. Used by the
     * cast-session reconnect supervisor when a live native link drops unexpectedly, and by
     * the foreground-return hook. No-op if the user explicitly disconnected, if there's no
     * prior target, or if a connection is already in progress / established. Reuses the
     * saved token (no re-pairing).
     *
     * [freshDevice] is the saved TV record (kept up to date by the UUID-matched discovery
     * healer in ConnectionViewModel). When it refers to the same receiver as
     * [targetConnection], its endpoint + credentials replace the cached ones — so a
     * reconnect after a DHCP lease change (router restart) targets the TV's *new* IP
     * instead of retrying the dead one.
     */
    fun reconnect(freshDevice: com.playbridge.sender.model.TvDevice? = null) {
        var conn = targetConnection
        if (conn == null) {
            Log.d(TAG, "reconnect(): no prior target — ignoring")
            return
        }
        if (isUserDisconnect) {
            Log.d(TAG, "reconnect(): last disconnect was user-initiated — ignoring")
            return
        }
        val state = _connectionState.value
        if (state is ConnectionState.Connected || state is ConnectionState.Connecting) return

        // Refresh the endpoint from the saved record if it's the same receiver. Match by
        // uuid when both sides know it (names collide: two TVs of the same model announce
        // the same Build.MODEL); fall back to the announced name only when the uuid is
        // unavailable. The token/pin are refreshed too: receivers may rotate the token on
        // every auth, and the store is authoritative.
        val sameReceiver = freshDevice != null &&
            (if (conn.tvUuid.isNotEmpty() && freshDevice.uuid.isNotEmpty()) {
                freshDevice.uuid == conn.tvUuid
            } else {
                freshDevice.name == conn.serverName
            })
        if (freshDevice != null &&
            freshDevice.resolvedProtocol == com.playbridge.sender.model.CastProtocol.PLAYBRIDGE &&
            freshDevice.token.isNotEmpty() && sameReceiver &&
            (freshDevice.ip != conn.ip || freshDevice.port != conn.port ||
                freshDevice.wssPort != conn.wssPort || freshDevice.token != conn.token ||
                (freshDevice.certFingerprint ?: conn.pin) != conn.pin)
        ) {
            Log.i(TAG, "reconnect(): endpoint refreshed from saved record " +
                "(${conn.ip}:${conn.port} → ${freshDevice.ip}:${freshDevice.port})")
            conn = conn.copy(
                ip = freshDevice.ip,
                port = freshDevice.port,
                wssPort = freshDevice.wssPort ?: conn.wssPort,
                token = freshDevice.token,
                pin = freshDevice.certFingerprint ?: conn.pin,
            )
            targetConnection = conn
        }

        Log.i(TAG, "reconnect(): re-attempting ${conn.serverName} at ${conn.ip}:${conn.port}")
        attemptConnection(conn.ip, conn.port, conn.serverName)
    }

    fun disconnect() {
        // Stack trace helps attribute unexpected "User disconnect" (DevicePicker, notif
        // action, pairing dialog, etc.) without guessing from close reason alone.
        Log.i(TAG, "disconnect() (user-initiated)", Throwable("disconnect caller"))
        mainHandler.removeCallbacks(mouseFlushRunnable)
        mouseFlushScheduled = false
        pendingDx = 0f
        pendingDy = 0f
        isUserDisconnect = true
        clearPairingSecrets()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Close the socket without treating it as a user disconnect. Used for idle
     * background stand-down: the link is dropped to save battery, but
     * [wasUserDisconnect] stays false so foreground-return / auto-connect can
     * re-open it. Pairing material is kept so the next auth is a normal reconnect.
     */
    fun softDisconnect(reason: String = "background idle stand-down") {
        Log.i(TAG, "softDisconnect($reason)")
        mainHandler.removeCallbacks(mouseFlushRunnable)
        mouseFlushScheduled = false
        pendingDx = 0f
        pendingDy = 0f
        // Deliberately do NOT set isUserDisconnect or clearPairingSecrets.
        try {
            webSocket?.close(1000, reason)
        } catch (_: Exception) {
        }
        webSocket = null
        if (_connectionState.value !is ConnectionState.Disconnected &&
            _connectionState.value !is ConnectionState.Error
        ) {
            _connectionState.value = ConnectionState.Disconnected
        }
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

internal class LinkLocalDns : Dns {
    override fun lookup(hostname: String): List<java.net.InetAddress> {
        if (hostname.endsWith(".local-ipv6")) {
            try {
                val decodedIp = decodeHostToIpv6String(hostname)
                return listOf(java.net.InetAddress.getByName(decodedIp))
            } catch (e: Exception) {
                // fallback to default resolution
            }
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    companion object {
        fun encodeIpv6ToHost(ip: String): String {
            if (!ip.contains(":")) return ip
            val cleanIp = ip.removePrefix("[").removeSuffix("]")
            val rawIp = cleanIp.substringBefore("%")
            val scope = cleanIp.substringAfter("%", "")
            val hex = ipv6ToHex(rawIp)
            if (hex != null) {
                return if (scope.isNotEmpty()) "$hex-$scope.local-ipv6" else "$hex.local-ipv6"
            }
            // Could not normalize — return a *valid* URL host rather than the raw literal, so
            // we never build "wss://fe80::..%zone:port/" (which okhttp rejects, crashing the app).
            // Bracket the literal and percent-encode the zone delimiter ('%' -> '%25') per RFC 6874.
            val zoneSuffix = if (scope.isNotEmpty()) "%25$scope" else ""
            return "[$rawIp$zoneSuffix]"
        }

        /**
         * Expands an IPv6 literal (with optional "::" compression) into 32 lowercase hex chars,
         * or null if it isn't parseable. Pure string math with no name resolution, so — unlike
         * InetAddress.getByName — it can't throw on a scoped/link-local literal.
         */
        private fun ipv6ToHex(addr: String): String? {
            if (!addr.contains(":")) return null
            return try {
                val groups: List<String> = if (addr.contains("::")) {
                    val halves = addr.split("::")
                    if (halves.size != 2) return null
                    val head = if (halves[0].isEmpty()) emptyList() else halves[0].split(":")
                    val tail = if (halves[1].isEmpty()) emptyList() else halves[1].split(":")
                    val missing = 8 - head.size - tail.size
                    if (missing < 0) return null
                    head + List(missing) { "0" } + tail
                } else {
                    addr.split(":")
                }
                if (groups.size != 8) return null
                buildString {
                    for (g in groups) {
                        if (g.isEmpty() || g.length > 4) return null
                        val v = g.toInt(16) // throws for non-hex groups (e.g. embedded IPv4)
                        if (v < 0 || v > 0xFFFF) return null
                        append("%04x".format(v))
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        fun decodeHostToIpv6String(hostname: String): String {
            if (!hostname.endsWith(".local-ipv6")) return hostname
            val parts = hostname.removeSuffix(".local-ipv6")
            val hexPart = parts.substringBefore("-")
            if (hexPart.length != 32) return hostname
            val scopePart = parts.substringAfter("-", "")
            val ipStringBuilder = StringBuilder()
            for (i in 0 until 8) {
                if (i > 0) ipStringBuilder.append(":")
                ipStringBuilder.append(hexPart.substring(i * 4, i * 4 + 4))
            }
            val ipStr = ipStringBuilder.toString()
            return if (scopePart.isNotEmpty() && scopePart != parts) "$ipStr%$scopePart" else ipStr
        }
    }
}
