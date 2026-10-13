package com.playbridge.sender.cast.atv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** ABI of `pb_atv_*`. Independent of the Google Cast session ABI. */
internal const val ATV_REMOTE_ABI = 1

internal const val ATV_CLIENT_NAME = "PlayBridge"

internal class AtvCommandException(val kind: String, message: String) : Exception(message)

internal data class AtvDiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val addresses: List<String>,
    val txt: List<Pair<String, String>>,
) {
    val bluetoothMac: String?
        get() = txt.firstOrNull { (key, _) -> key.equals("bt", ignoreCase = true) }?.second
}

internal data class AtvIdentity(val certPem: String, val keyPem: String, val fingerprint: String)

internal data class AtvPairingStarted(val serverName: String?, val fingerprint: String, val mac: String?)

/** Server certificate DER stays on the native side. The pin is the fingerprint. */
internal data class AtvPaired(val fingerprint: String, val mac: String?, val serverName: String?)

internal data class AtvTelevisionState(
    val isOn: Boolean,
    val volumeLevel: Int?,
    val volumeMax: Int?,
    val volumeMuted: Boolean,
    val currentApp: String?,
    val model: String?,
    val vendor: String?,
)

internal interface AtvNativeBoundary {
    fun abiVersion(): Int
    fun start(): Long
    fun submitJson(handle: Long, commandJson: String): Boolean
    fun nextEvent(handle: Long, waitMs: Long): String?
    fun cancel(handle: Long)
    fun free(handle: Long)
}

internal class RustAtvBoundary : AtvNativeBoundary {
    override fun abiVersion(): Int = RustAtvNative.abiVersion()
    override fun start(): Long = RustAtvNative.start()
    override fun submitJson(handle: Long, commandJson: String): Boolean = RustAtvNative.submitJson(handle, commandJson)
    override fun nextEvent(handle: Long, waitMs: Long): String? = RustAtvNative.nextEvent(handle, waitMs)
    override fun cancel(handle: Long) = RustAtvNative.cancel(handle)
    override fun free(handle: Long) = RustAtvNative.free(handle)
}

/** One process-wide handle. Pairing and control share it; callers must not log command JSON. */
internal interface AtvControlSession {
    val television: StateFlow<AtvTelevisionState?>
    suspend fun discover(timeoutMs: Long): List<AtvDiscoveredDevice>
    suspend fun generateIdentity(clientName: String): AtvIdentity
    suspend fun startPairing(host: String, certPem: String, keyPem: String, clientName: String): AtvPairingStarted
    suspend fun finishPairing(code: String): AtvPaired
    suspend fun cancelPairing()
    suspend fun connect(host: String, certPem: String, keyPem: String, pinSha256Hex: String)
    suspend fun awaitClosed()
    suspend fun key(name: String?, code: Int?, direction: String)
    suspend fun volume(action: String)
    suspend fun text(text: String)
    suspend fun launchApp(uriOrPackage: String)
    suspend fun power()
    suspend fun disconnect()
    suspend fun close()
}

internal class AtvRemoteClient(
    private val native: AtvNativeBoundary,
    private val scope: CoroutineScope,
) : AtvControlSession {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val nativeLock = Mutex()
    private val closeLock = Mutex()
    private val televisionState = MutableStateFlow<AtvTelevisionState?>(null)
    private data class Connection(val requestId: String, val closed: CompletableDeferred<String> = CompletableDeferred())
    @Volatile private var connection: Connection? = null
    @Volatile private var handle = 0L
    private var closed = false
    private var pump: kotlinx.coroutines.Job? = null

    override val television: StateFlow<AtvTelevisionState?> = televisionState.asStateFlow()

    override suspend fun discover(timeoutMs: Long): List<AtvDiscoveredDevice> {
        val event = roundTrip(command("discover") { put("timeout_ms", timeoutMs.coerceIn(0L, 60_000L)) }, timeoutMs + 5_000L)
        return parseDevices(event)
    }

    override suspend fun generateIdentity(clientName: String): AtvIdentity {
        val event = roundTrip(command("generate_identity") { put("client_name", clientName) }, 30_000L)
        return parseIdentity(event)
    }

    override suspend fun startPairing(host: String, certPem: String, keyPem: String, clientName: String): AtvPairingStarted {
        val event = roundTrip(command("start_pairing") {
            put("host", host)
            put("port", JSONObject.NULL)
            put("cert_pem", certPem)
            put("key_pem", keyPem)
            put("client_name", clientName)
        }, 30_000L)
        return parsePairingStarted(event)
    }

    override suspend fun finishPairing(code: String): AtvPaired {
        val event = roundTrip(command("finish_pairing") { put("code", code) }, 30_000L)
        return parsePaired(event)
    }

    override suspend fun cancelPairing() {
        roundTrip(command("cancel_pairing"), 10_000L)
    }

    override suspend fun connect(host: String, certPem: String, keyPem: String, pinSha256Hex: String) {
        roundTrip(command("connect") {
            put("host", host)
            put("port", JSONObject.NULL)
            put("cert_pem", certPem)
            put("key_pem", keyPem)
            put("pin_sha256_hex", pinSha256Hex)
        }, 30_000L)
    }

    override suspend fun awaitClosed() {
        val current = connection ?: throw closedException()
        val kind = current.closed.await()
        throw AtvCommandException(kind, fixedMessage(kind))
    }

    override suspend fun key(name: String?, code: Int?, direction: String) {
        roundTrip(command("key") {
            if (code == null) put("code", JSONObject.NULL) else put("code", code)
            if (name == null) put("name", JSONObject.NULL) else put("name", name)
            put("direction", direction)
        })
    }

    override suspend fun volume(action: String) {
        roundTrip(command("volume") { put("action", action) })
    }

    override suspend fun text(text: String) {
        roundTrip(command("text") { put("text", text) })
    }

    override suspend fun launchApp(uriOrPackage: String) {
        roundTrip(command("launch_app") { put("uri_or_package", uriOrPackage) })
    }

    override suspend fun power() {
        roundTrip(command("power"))
    }

    override suspend fun disconnect() {
        roundTrip(command("disconnect"), 10_000L)
    }

    override suspend fun close() = withContext(NonCancellable) {
        closeLock.withLock {
            val (current, job) = nativeLock.withLock {
                closed = true
                val current = handle
                handle = 0L
                retireConnection()
                if (current != 0L) native.cancel(current)
                (current to pump).also { pump = null }
            }
            job?.cancelAndJoin()
            nativeLock.withLock {
                if (current != 0L) native.free(current)
            }
            failPending(closedException())
        }
    }

    private fun command(name: String, fill: JSONObject.() -> Unit = {}): JSONObject =
        JSONObject().put("command", name).apply(fill)

    private suspend fun roundTrip(body: JSONObject, timeoutMs: Long = 15_000L): JSONObject {
        val id = "atv-${ids.incrementAndGet()}"
        body.put("request_id", id)
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val accepted = try {
            nativeLock.withLock {
                ensureStarted()
                when (body.optString("command")) {
                    "connect" -> {
                        retireConnection()
                        connection = Connection(id)
                    }
                    "disconnect" -> retireConnection()
                }
                native.submitJson(handle, body.toString())
            }
        } catch (error: Throwable) {
            pending.remove(id)
            throw error
        }
        if (!accepted) {
            pending.remove(id)
            throw AtvCommandException("protocol", "Android TV remote command was not accepted")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Must hold nativeLock: start/submit/close are serialized; close joins polling before free. */
    private fun ensureStarted() {
        if (closed) throw closedException()
        if (handle != 0L) return
        val abi = native.abiVersion()
        if (abi != ATV_REMOTE_ABI) {
            throw AtvCommandException("protocol", "Android TV remote command was not accepted")
        }
        val started = native.start()
        if (started == 0L) throw AtvCommandException("protocol", "Android TV remote command was not accepted")
        handle = started
        pump = scope.launch { pumpEvents(started) }
    }

    private suspend fun pumpEvents(nativeHandle: Long) {
        while (currentCoroutineContext().isActive && handle == nativeHandle) {
            val raw = try {
                runInterruptible(Dispatchers.IO) { native.nextEvent(nativeHandle, EVENT_WAIT_MS) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: continue
            nativeLock.withLock { if (handle == nativeHandle) deliver(raw) }
        }
    }

    private fun deliver(raw: String) {
        val event = try {
            parseAtvEvent(raw)
        } catch (error: AtvCommandException) {
            failPending(error)
            return
        }
        when (event) {
            is AtvEvent.State -> if (event.requestId == connection?.requestId && connection?.closed?.isCompleted == false) {
                televisionState.value = event.state
            }
            is AtvEvent.Closed -> {
                connection?.takeIf { it.requestId == event.requestId }?.let {
                    televisionState.value = null
                    it.closed.complete(event.kind)
                }
                completeExceptionally(event.requestId, AtvCommandException(event.kind, fixedMessage(event.kind)))
            }
            is AtvEvent.Error -> completeExceptionally(event.requestId, AtvCommandException(event.kind, event.message))
            is AtvEvent.Devices -> complete(event.requestId, event.raw)
            is AtvEvent.Identity -> complete(event.requestId, event.raw)
            is AtvEvent.PairingStarted -> complete(event.requestId, event.raw)
            is AtvEvent.Paired -> complete(event.requestId, event.raw)
            is AtvEvent.Operation -> complete(event.requestId, event.raw)
            is AtvEvent.Connected -> complete(event.requestId, event.raw)
        }
    }

    private fun complete(requestId: String, event: JSONObject) {
        if (requestId.isBlank()) return
        pending[requestId]?.complete(event)
    }

    private fun completeExceptionally(requestId: String, error: AtvCommandException) {
        if (requestId.isBlank()) return
        pending[requestId]?.completeExceptionally(error)
    }

    private fun failPending(error: AtvCommandException) {
        pending.values.forEach { it.completeExceptionally(error) }
    }

    private fun retireConnection() {
        connection?.closed?.complete("closed")
        connection = null
        televisionState.value = null
    }

    private fun closedException() = AtvCommandException("closed", fixedMessage("closed"))

    private companion object {
        const val EVENT_WAIT_MS = 250L
    }
}

internal sealed class AtvEvent {
    abstract val requestId: String
    data class Devices(override val requestId: String, val raw: JSONObject) : AtvEvent()
    data class Identity(override val requestId: String, val raw: JSONObject) : AtvEvent()
    data class PairingStarted(override val requestId: String, val raw: JSONObject) : AtvEvent()
    data class Paired(override val requestId: String, val raw: JSONObject) : AtvEvent()
    data class Operation(override val requestId: String, val raw: JSONObject) : AtvEvent()
    data class Connected(override val requestId: String, val raw: JSONObject) : AtvEvent()
    data class State(override val requestId: String, val state: AtvTelevisionState) : AtvEvent()
    data class Closed(override val requestId: String, val kind: String) : AtvEvent()
    data class Error(override val requestId: String, val kind: String, val message: String) : AtvEvent()
}

/** Parses one FFI event. Failures never include the body, which can quote a code or key. */
internal fun parseAtvEvent(raw: String): AtvEvent {
    val json = try {
        JSONObject(raw)
    } catch (_: Exception) {
        throw AtvCommandException("protocol", "invalid Android TV remote event")
    }
    val requestId = json.optString("request_id")
    return when (json.optString("event")) {
        "devices" -> AtvEvent.Devices(requestId, json)
        "identity" -> AtvEvent.Identity(requestId, json)
        "pairing_started" -> AtvEvent.PairingStarted(requestId, json)
        "paired" -> AtvEvent.Paired(requestId, json)
        "operation" -> AtvEvent.Operation(requestId, json)
        "connected" -> AtvEvent.Connected(requestId, json)
        "state" -> AtvEvent.State(requestId, parseTelevision(json.optJSONObject("state")))
        "closed" -> AtvEvent.Closed(requestId, json.optString("kind").ifBlank { "closed" })
        "error" -> {
            val kind = json.optString("kind").ifBlank { "protocol" }
            val message = json.optString("message").ifBlank { fixedMessage(kind) }
            AtvEvent.Error(requestId, kind, message)
        }
        else -> throw AtvCommandException("protocol", "invalid Android TV remote event")
    }
}

internal fun parseDevices(event: JSONObject): List<AtvDiscoveredDevice> {
    val array = event.optJSONArray("devices") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val device = array.optJSONObject(index) ?: continue
            add(
                AtvDiscoveredDevice(
                    name = device.optString("name"),
                    host = device.optString("host"),
                    port = device.optInt("port"),
                    addresses = jsonStrings(device.optJSONArray("addresses")),
                    txt = jsonPairs(device.optJSONArray("txt")),
                ),
            )
        }
    }
}

internal fun parseIdentity(event: JSONObject): AtvIdentity {
    val cert = event.optString("cert_pem")
    val key = event.optString("key_pem")
    val fingerprint = event.optString("fingerprint").lowercase()
    if (cert.isBlank() || key.isBlank() || !isFingerprint(fingerprint)) {
        throw AtvCommandException("protocol", "invalid Android TV remote event")
    }
    return AtvIdentity(cert, key, fingerprint)
}

internal fun parsePairingStarted(event: JSONObject): AtvPairingStarted = AtvPairingStarted(
    serverName = event.optString("server_name").takeIf { it.isNotBlank() },
    fingerprint = event.optString("fingerprint").lowercase(),
    mac = event.optString("mac").takeIf { it.isNotBlank() },
)

internal fun parsePaired(event: JSONObject): AtvPaired {
    val fingerprint = event.optString("fingerprint").lowercase()
    if (!isFingerprint(fingerprint)) throw AtvCommandException("protocol", "invalid Android TV remote event")
    return AtvPaired(
        fingerprint = fingerprint,
        mac = event.optString("mac").takeIf { it.isNotBlank() },
        serverName = event.optString("server_name").takeIf { it.isNotBlank() },
    )
}

internal fun parseTelevision(state: JSONObject?): AtvTelevisionState {
    if (state == null) throw AtvCommandException("protocol", "invalid Android TV remote event")
    return AtvTelevisionState(
        isOn = state.optBoolean("is_on", true),
        volumeLevel = state.optionalInt("volume_level"),
        volumeMax = state.optionalInt("volume_max"),
        volumeMuted = state.optBoolean("volume_muted", false),
        currentApp = state.optString("current_app").takeIf { it.isNotBlank() },
        model = state.optString("model").takeIf { it.isNotBlank() },
        vendor = state.optString("vendor").takeIf { it.isNotBlank() },
    )
}

internal fun fixedMessage(kind: String): String = when (kind) {
    "unreachable" -> "Android TV remote is unreachable"
    "tls_handshake" -> "Android TV TLS handshake failed"
    "not_trusted" -> "Android TV does not trust this client; pair again"
    "pin_mismatch" -> "Android TV certificate does not match the stored pin"
    "bad_code" -> "pairing code was rejected"
    "timeout" -> "Android TV remote timed out"
    "closed" -> "Android TV remote connection closed"
    else -> "invalid Android TV remote command or protocol message"
}

internal fun isFingerprint(value: String): Boolean = value.length == 64 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

private fun JSONObject.optionalInt(name: String): Int? = if (!has(name) || isNull(name)) null else optInt(name)

private fun jsonStrings(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) add(array.optString(index))
    }
}

private fun jsonPairs(array: JSONArray?): List<Pair<String, String>> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val pair = array.optJSONArray(index) ?: continue
            if (pair.length() >= 2) add(pair.optString(0) to pair.optString(1))
        }
    }
}

