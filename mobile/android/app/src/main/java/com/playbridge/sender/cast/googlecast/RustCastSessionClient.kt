package com.playbridge.sender.cast.googlecast

import android.os.SystemClock
import android.util.Log
import com.playbridge.sender.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal object RustCastSessionNative {
    init {
        try {
            System.loadLibrary("playbridge_cast_core_ffi")
            if (BuildConfig.DEBUG) Log.d(TAG, "Loaded playbridge_cast_core_ffi")
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "Native Cast Core unavailable: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
        }
    }

    external fun abiVersion(): Int
    external fun start(targetJson: String, timeoutMs: Long): Long
    external fun submitJson(handle: Long, commandJson: String): Boolean
    external fun nextEvent(handle: Long, waitMs: Long): String?
    external fun cancel(handle: Long)
    external fun free(handle: Long)

    private const val TAG = "RustCastSessionNative"
}

internal data class RustCastPlaybackStatus(
    val state: String,
    val positionSeconds: Double,
    val durationSeconds: Double,
)

internal sealed class GoogleCastSessionInvalidException(message: String) :
    IllegalStateException(message)

internal class GoogleCastReceiverEndedException : GoogleCastSessionInvalidException(
    "Google Cast receiver application is no longer active",
)

internal class GoogleCastSessionUnresponsiveException : GoogleCastSessionInvalidException(
    "Google Cast receiver application stopped responding",
)

internal class GoogleCastConnectionLostException : GoogleCastSessionInvalidException(
    "Google Cast receiver connection was lost",
)

internal class GoogleCastLocalNetworkUnavailableException(message: String) :
    IllegalStateException(message)

/**
 * Thin coroutine adapter over Cast Core's single-owner native session worker.
 *
 * The native worker owns all CastV2 reads, heartbeat replies and request
 * correlation. Kotlin only submits JSON commands and consumes correlated
 * events, so polling and UI actions cannot race on the Cast socket.
 */
internal class RustCastSessionClient(
    private val scope: CoroutineScope,
    private val attemptId: Int,
) {
    private val requestIds = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val operationMutex = Mutex()
    private var connected = CompletableDeferred<Unit>()
    @Volatile
    private var handle = 0L
    private var eventJob: Job? = null

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var volume: Float = 0.5f
        private set

    suspend fun connect(
        host: String,
        port: Int,
        applicationId: String = BuildConfig.GOOGLE_CAST_APPLICATION_ID,
        forceRelaunch: Boolean = false,
        networkHandle: Long? = null,
    ) = operationMutex.withLock {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            trace(
                "connect begin endpoint=$host:$port appId=$applicationId " +
                    "launchPolicy=${if (forceRelaunch) "force_relaunch" else "reuse_or_launch"} " +
                    "localNetwork=${if (networkHandle == null) "default" else "bound"}",
            )
            close()
            val abiVersion = RustCastSessionNative.abiVersion()
            trace("native ABI=$abiVersion expected=$EXPECTED_ABI_VERSION")
            check(abiVersion == EXPECTED_ABI_VERSION) {
                "Packaged Cast Core ABI does not support ready-state sessions"
            }
            connected = CompletableDeferred()
            val target = JSONObject()
                .put("protocol", "google_cast")
                .put("addresses", org.json.JSONArray().put(host))
                .put("port", port)
                .put("application_id", applicationId)
                .put("launch_policy", if (forceRelaunch) "force_relaunch" else "reuse_or_launch")
                .putIfNotNull("network_handle", networkHandle)
            val nativeHandle = RustCastSessionNative.start(target.toString(), CONNECT_TIMEOUT_MS)
            trace(
                "native session start returned handle=${handleLabel(nativeHandle)} " +
                    "after ${elapsedSince(startedAt)}ms",
            )
            check(nativeHandle != 0L) { "Native Google Cast session could not start" }
            handle = nativeHandle
            eventJob = scope.launch(Dispatchers.IO) { pumpEvents(nativeHandle) }
            check(
                withTimeoutOrNull(CONNECT_TIMEOUT_MS + EVENT_GRACE_MS) {
                    connected.await()
                    true
                } == true,
            ) {
                "Timed out waiting for the Google Cast receiver"
            }
            trace("connect ready after ${elapsedSince(startedAt)}ms")
        } catch (error: Throwable) {
            // A failed attempt must not leave its worker, native handle, or socket available
            // for the next attempt. The next connect() always starts from a clean handle.
            withContext(NonCancellable) { close() }
            if (error is CancellationException) {
                trace("connect cancelled after ${elapsedSince(startedAt)}ms")
            } else {
                Log.w(
                    TAG,
                    "$tracePrefix connect failed after ${elapsedSince(startedAt)}ms: " +
                        "${error.javaClass.simpleName}: ${error.message}",
                    error,
                )
            }
            throw error
        }
    }

    suspend fun load(
        contentUrl: String,
        contentType: String?,
        title: String?,
        artUrl: String?,
        startSeconds: Double,
    ) {
        submit(
            "load",
            JSONObject()
                .put("url", contentUrl)
                .putIfNotNull("content_type", contentType)
                .putIfNotNull("title", title)
                .putIfNotNull("art_url", artUrl)
                .put("start_seconds", startSeconds.coerceAtLeast(0.0)),
        )
    }

    suspend fun play() {
        submit("play")
    }

    suspend fun pause() {
        submit("pause")
    }

    suspend fun stop() {
        submit("stop")
    }

    suspend fun seek(positionSeconds: Double) {
        submit("seek", JSONObject().put("position_seconds", positionSeconds.coerceAtLeast(0.0)))
    }

    suspend fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        submit("set_volume", JSONObject().put("level", clamped))
        volume = clamped
    }

    suspend fun status(): RustCastPlaybackStatus {
        val event = submit("status")
        val status = event.getJSONObject("status")
        return RustCastPlaybackStatus(
            state = status.optString("state", "unknown"),
            positionSeconds = status.optDouble("position_seconds", 0.0),
            durationSeconds = status.optDouble("duration_seconds", 0.0),
        )
    }

    suspend fun disconnect() {
        operationMutex.withLock {
            if (handle != 0L && isReady) {
                runCatching { submitLocked("disconnect") }
            }
            close()
        }
    }

    /** Drop the current worker/socket without sending a receiver command. */
    suspend fun reset() {
        operationMutex.withLock { close() }
    }

    private suspend fun submit(
        command: String,
        fields: JSONObject = JSONObject(),
    ): JSONObject = operationMutex.withLock {
        submitLocked(command, fields)
    }

    private suspend fun submitLocked(
        command: String,
        fields: JSONObject = JSONObject(),
    ): JSONObject {
        val nativeHandle = handle
        check(nativeHandle != 0L && isReady) { "Google Cast receiver is not ready" }
        val requestId = requestIds.getAndIncrement().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[requestId] = deferred
        val request = JSONObject(fields.toString())
            .put("command", command)
            .put("request_id", requestId)
        val startedAt = SystemClock.elapsedRealtime()
        logCommand(command, requestId, "submit")
        try {
            check(RustCastSessionNative.submitJson(nativeHandle, request.toString())) {
                "Native Google Cast command queue rejected $command"
            }
            return withTimeout(OPERATION_TIMEOUT_MS) { deferred.await() }.also {
                logCommand(command, requestId, "complete elapsed=${elapsedSince(startedAt)}ms")
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                trace(
                    "command=$command requestId=$requestId cancelled " +
                        "after ${elapsedSince(startedAt)}ms",
                )
            } else {
                Log.w(
                    TAG,
                    "$tracePrefix command=$command requestId=$requestId failed " +
                        "after ${elapsedSince(startedAt)}ms: " +
                        "${error.javaClass.simpleName}: ${error.message}",
                    error,
                )
            }
            throw error
        } finally {
            pending.remove(requestId)
        }
    }

    private fun pumpEvents(nativeHandle: Long) {
        trace("event pump begin handle=${handleLabel(nativeHandle)}")
        try {
            while (scope.isActive && handle == nativeHandle) {
                val source = RustCastSessionNative.nextEvent(nativeHandle, EVENT_WAIT_MS) ?: continue
                val event = JSONObject(source)
                val requestId = event.opt("request_id")
                    .takeUnless { it == null || it === JSONObject.NULL }
                    ?.toString()
                when (event.optString("event")) {
                    "connected" -> {
                        trace(
                            "event=connected protocol=${event.optString("protocol", "unknown")} " +
                                "receiverAppId=${event.optString("receiver_application_id", "none")}",
                        )
                        isReady = true
                        connected.complete(Unit)
                    }
                    "operation", "status" -> {
                        val eventType = event.optString("event")
                        val operation = event.optString("operation", eventType)
                        if (eventType == "status") {
                            Log.v(
                                TAG,
                                "$tracePrefix event=status requestId=${requestId ?: "none"} " +
                                    "state=${event.optJSONObject("status")?.optString("state", "unknown")}",
                            )
                        } else {
                            trace("event=operation operation=$operation requestId=${requestId ?: "none"}")
                        }
                        if (requestId != null) pending.remove(requestId)?.complete(event)
                    }
                    "error" -> {
                        val reason = eventReason(event)
                        val error = eventException(event, reason)
                        Log.w(
                            TAG,
                            "$tracePrefix event=error operation=${event.optString("operation", "unknown")} " +
                                "requestId=${requestId ?: "none"} " +
                                "reason=${reason ?: "none"} " +
                                "message=${event.optString("message", "unknown")}",
                        )
                        if (requestId != null) {
                            pending.remove(requestId)?.completeExceptionally(error)
                        } else if (googleCastSessionErrorEndsSession(reason)) {
                            isReady = false
                            connected.completeExceptionally(error)
                            failPending(error)
                        } else {
                            trace("non-terminal maintenance error; keeping session ready")
                        }
                    }
                    "finished" -> {
                        isReady = false
                        val error = eventException(event)
                        Log.w(
                            TAG,
                            "$tracePrefix event=finished reason=${event.optString("reason", "unknown")} " +
                                "message=${event.optString("message", "none")} pending=${pending.size}",
                        )
                        connected.completeExceptionally(error)
                        failPending(error)
                        return
                    }
                    else -> Log.w(
                        TAG,
                        "$tracePrefix ignored native event type=" +
                            event.optString("event", "missing"),
                    )
                }
            }
        } catch (error: Throwable) {
            isReady = false
            Log.e(
                TAG,
                "$tracePrefix event pump failed for handle=${handleLabel(nativeHandle)}: " +
                    "${error.javaClass.simpleName}: ${error.message}",
                error,
            )
            connected.completeExceptionally(error)
            failPending(error)
        } finally {
            trace(
                "event pump end handle=${handleLabel(nativeHandle)} " +
                    "scopeActive=${scope.isActive} currentHandle=${handleLabel(handle)}",
            )
        }
    }

    private suspend fun close() {
        val nativeHandle = handle
        if (nativeHandle != 0L || eventJob != null || pending.isNotEmpty()) {
            trace(
                "close begin handle=${handleLabel(nativeHandle)} ready=$isReady " +
                    "pending=${pending.size}",
            )
        }
        handle = 0L
        isReady = false
        if (nativeHandle != 0L) {
            runCatching { RustCastSessionNative.cancel(nativeHandle) }
        }
        eventJob?.cancelAndJoin()
        eventJob = null
        if (nativeHandle != 0L) {
            runCatching { RustCastSessionNative.free(nativeHandle) }
        }
        failPending(IllegalStateException("Google Cast session closed"))
        if (nativeHandle != 0L) trace("close complete handle=${handleLabel(nativeHandle)}")
    }

    private fun failPending(error: Throwable) {
        if (pending.isNotEmpty()) {
            Log.w(
                TAG,
                "$tracePrefix failing ${pending.size} pending command(s): " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
        }
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private fun logCommand(command: String, requestId: String, detail: String) {
        val message = "$tracePrefix command=$command requestId=$requestId $detail"
        if (command == "status") Log.v(TAG, message) else Log.d(TAG, message)
    }

    private fun trace(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$tracePrefix $message")
    }

    private fun handleLabel(value: Long): String =
        if (value == 0L) "none" else "0x${java.lang.Long.toHexString(value)}"

    private fun elapsedSince(startedAt: Long): Long =
        SystemClock.elapsedRealtime() - startedAt

    private val tracePrefix: String
        get() = "[attempt=$attemptId handle=${handleLabel(handle)}]"

    private fun eventException(
        event: JSONObject,
        reason: String? = eventReason(event),
    ): IllegalStateException =
        when (reason) {
            RECEIVER_ENDED_REASON -> GoogleCastReceiverEndedException()
            SESSION_UNRESPONSIVE_REASON -> GoogleCastSessionUnresponsiveException()
            CONNECTION_LOST_REASON -> GoogleCastConnectionLostException()
            LOCAL_NETWORK_UNREACHABLE_REASON -> GoogleCastLocalNetworkUnavailableException(
                event.optString(
                    "message",
                    "Google Cast cannot reach the receiver over the local network",
                ),
            )
            else -> IllegalStateException(
                event.optString(
                    "message",
                    "Google Cast session ended: ${event.optString("reason", "unknown")}",
                ),
            )
        }

    private fun eventReason(event: JSONObject): String? =
        event.opt("reason")
            .takeUnless { it == null || it === JSONObject.NULL }
            ?.toString()
            ?.takeIf { it.isNotBlank() }

    private fun JSONObject.putIfNotNull(name: String, value: Any?): JSONObject {
        if (value != null) put(name, value)
        return this
    }

    private companion object {
        const val EXPECTED_ABI_VERSION = 2
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val OPERATION_TIMEOUT_MS = 16_000L
        const val EVENT_GRACE_MS = 1_000L
        const val EVENT_WAIT_MS = 200L
        const val RECEIVER_ENDED_REASON = "receiver_ended"
        const val SESSION_UNRESPONSIVE_REASON = "session_unresponsive"
        const val CONNECTION_LOST_REASON = "connection_lost"
        const val LOCAL_NETWORK_UNREACHABLE_REASON = "local_network_unreachable"
        const val TAG = "RustCastSession"
    }
}

internal fun googleCastSessionErrorEndsSession(reason: String?): Boolean =
    !reason.isNullOrBlank()

internal fun googleCastStatusErrorEndsSession(error: Throwable): Boolean =
    error is GoogleCastSessionInvalidException

internal fun googleCastStatusFailuresRequireFreshSession(consecutiveFailures: Int): Boolean =
    consecutiveFailures >= 3
