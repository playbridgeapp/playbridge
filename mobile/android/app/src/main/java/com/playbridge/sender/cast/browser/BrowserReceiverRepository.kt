package com.playbridge.sender.cast.browser

import android.content.Context
import android.util.Log
import com.playbridge.sender.cast.proxy.PhoneSenderServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Process-wide owner of the phone browser-receiver host: start/stop, pairing,
 * and approved sessions. Lives as long as the process; host lifecycle is also
 * mirrored by [BrowserReceiverHostService] for a foreground notification.
 */
class BrowserReceiverRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val TAG = "BrowserReceiverRepo"
    private val mutex = Mutex()
    private var eventJob: Job? = null

    private val _state = MutableStateFlow(BrowserHostState())
    val state: StateFlow<BrowserHostState> = _state.asStateFlow()

    /** Session id the user just approved — activate as NOW on the next `connected` event. */
    @Volatile
    private var sessionToActivate: String? = null

    private val _activateSession = MutableStateFlow<BrowserReadySession?>(null)
    /**
     * Emits the ready session that should become the cast destination after a
     * successful approve (or auto-activate of the sole browser). Consumed by UI/VM.
     */
    val activateSession: StateFlow<BrowserReadySession?> = _activateSession.asStateFlow()

    fun clearActivateSession() {
        _activateSession.value = null
    }

    suspend fun startHost(preferredPort: Int = 8770): Result<Unit> = mutex.withLock {
        _state.update { it.copy(busy = true, lastError = null) }
        val services = PhoneSenderServices.get()
        if (services == null) {
            val msg = "Browser host unavailable on this device"
            _state.update { it.copy(busy = false, lastError = msg) }
            return Result.failure(IllegalStateException(msg))
        }
        return try {
            ensureEventListener(services)
            val info = services.startBrowser(preferredPort = preferredPort)
            _state.update {
                it.copy(
                    running = true,
                    urls = info.urls,
                    port = info.port,
                    busy = false,
                    lastError = null,
                )
            }
            BrowserReceiverHostService.start(context, info.urls.firstOrNull(), info.port)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "startHost failed: ${e.message}")
            val msg = humanizeStartError(e.message)
            _state.update { it.copy(busy = false, lastError = msg, running = false) }
            Result.failure(e)
        }
    }

    /** Stop the host without tying teardown to a caller coroutine (FGS timeout / Stop action). */
    fun stopHostAsync() {
        scope.launch { stopHost() }
    }

    suspend fun stopHost(): Result<Unit> = mutex.withLock {
        _state.update { it.copy(busy = true, lastError = null) }
        return try {
            val services = PhoneSenderServices.get()
            services?.stopBrowser()
            sessionToActivate = null
            _activateSession.value = null
            _state.value = BrowserHostState()
            BrowserReceiverHostService.stop(context)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "stopHost failed: ${e.message}")
            _state.update {
                it.copy(
                    busy = false,
                    lastError = e.message ?: "Couldn't stop browser host",
                )
            }
            Result.failure(e)
        }
    }

    /**
     * Approve a pending pairing code. On success the host emits `connected` and
     * this repository surfaces the session via [activateSession].
     */
    suspend fun approve(sessionId: String, code: String): Result<Unit> {
        val services = PhoneSenderServices.get()
            ?: return Result.failure(IllegalStateException("Browser host unavailable"))
        if (!_state.value.running) {
            return Result.failure(IllegalStateException("Browser host is not running"))
        }
        sessionToActivate = sessionId
        _state.update { it.copy(lastError = null) }
        return try {
            services.approveBrowser(sessionId = sessionId, code = code.trim())
            Result.success(Unit)
        } catch (e: Exception) {
            if (sessionToActivate == sessionId) sessionToActivate = null
            Log.w(TAG, "approve failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun forget(receiverId: String): Result<Unit> {
        if (receiverId.isBlank()) return Result.success(Unit)
        val services = PhoneSenderServices.get()
            ?: return Result.failure(IllegalStateException("Browser host unavailable"))
        return try {
            services.forgetBrowserReceiver(receiverId)
            _state.update { state ->
                state.copy(
                    pending = state.pending.filterNot { it.receiverId == receiverId },
                    ready = state.ready.filterNot { it.receiverId == receiverId },
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "forget failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun disconnect(sessionId: String): Result<Unit> {
        val services = PhoneSenderServices.get()
            ?: return Result.failure(IllegalStateException("Browser host unavailable"))
        return try {
            services.disconnectBrowser(sessionId)
            _state.update { state ->
                state.copy(ready = state.ready.filterNot { it.sessionId == sessionId })
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "disconnect failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun lanHostIp(): String {
        val urls = _state.value.urls
        for (url in urls) {
            val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
            if (host.isNotEmpty() && host != "127.0.0.1" && host != "localhost" && host != "::1") {
                return host
            }
        }
        return ""
    }

    private fun ensureEventListener(services: PhoneSenderServices) {
        if (eventJob?.isActive == true) return
        eventJob = scope.launch {
            services.events.collect { event -> handleServicesEvent(event) }
        }
    }

    private fun handleServicesEvent(event: JSONObject) {
        val kind = event.optString("event")
        when (kind) {
            "pairing_requested" -> {
                val session = event.optJSONObject("session") ?: return
                val sessionId = session.optString("sessionId").takeIf { it.isNotEmpty() } ?: return
                val name = session.optString("name").ifBlank { "Browser" }
                val receiverId = session.optString("receiverId")
                val expiresInMs = event.optLong("expires_in_ms", 120_000L)
                val request = BrowserPairingRequest(
                    sessionId = sessionId,
                    receiverId = receiverId,
                    name = name,
                    expiresAtMs = System.currentTimeMillis() + expiresInMs,
                )
                _state.update { state ->
                    val pending = state.pending
                        .filterNot {
                            it.sessionId == sessionId ||
                                (receiverId.isNotEmpty() && it.receiverId == receiverId)
                        } + request
                    state.copy(pending = pending)
                }
            }
            "connected", "capabilities", "status" -> {
                val session = event.optJSONObject("session") ?: return
                val sessionId = session.optString("sessionId").takeIf { it.isNotEmpty() } ?: return
                val name = session.optString("name").ifBlank { "Browser" }
                val receiverId = session.optString("receiverId")
                val ready = BrowserReadySession(
                    sessionId = sessionId,
                    receiverId = receiverId,
                    name = name,
                )
                _state.update { state ->
                    state.copy(
                        pending = state.pending.filterNot {
                            it.sessionId == sessionId ||
                                (receiverId.isNotEmpty() && it.receiverId == receiverId)
                        },
                        ready = state.ready
                            .filterNot {
                                it.sessionId == sessionId ||
                                    (receiverId.isNotEmpty() && it.receiverId == receiverId)
                            } + ready,
                    )
                }
                // Activate only on connected to avoid re-binding on status chatter.
                if (kind == "connected") {
                    val shouldActivate = sessionToActivate == sessionId ||
                        _state.value.ready.size == 1
                    if (shouldActivate) {
                        sessionToActivate = null
                        _activateSession.value = ready
                    }
                }
            }
            "disconnected" -> {
                val sessionId = event.optString("session_id").ifEmpty {
                    event.optString("sessionId")
                }
                if (sessionId.isEmpty()) return
                if (sessionToActivate == sessionId) sessionToActivate = null
                _state.update { state ->
                    state.copy(
                        pending = state.pending.filterNot { it.sessionId == sessionId },
                        ready = state.ready.filterNot { it.sessionId == sessionId },
                    )
                }
            }
            "error" -> {
                // Operation errors are already delivered via submit() exceptions.
                // Session playback errors may arrive with a nested session.
                val message = event.optString("message").takeIf { it.isNotEmpty() }
                if (message != null && event.has("session")) {
                    _state.update {
                        it.copy(lastError = "TV browser couldn’t play this stream")
                    }
                }
            }
        }
    }

    private fun humanizeStartError(raw: String?): String {
        val msg = raw.orEmpty()
        return when {
            msg.contains("port", ignoreCase = true) ||
                msg.contains("address already in use", ignoreCase = true) ||
                msg.contains("bind", ignoreCase = true) ->
                "Couldn't start browser host (ports 8770–8779 busy)"
            msg.isBlank() -> "Couldn't start browser host"
            else -> msg
        }
    }
}
