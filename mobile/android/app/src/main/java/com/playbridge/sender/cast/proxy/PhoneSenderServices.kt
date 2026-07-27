package com.playbridge.sender.cast.proxy

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

data class RegisteredMedia(
    val id: String,
    val url: String,
    val encryptedUrl: String? = null,
)

/**
 * Process-wide façade over Rust [SenderServices]: one embedded stream proxy for
 * [StreamRouteMode.VIA_PHONE]. Browser-host commands exist on the native side
 * but are not exposed here yet.
 */
class PhoneSenderServices private constructor(
    private val handle: Long,
) {
    private val nextRequestId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, Continuation<JSONObject>>()
    private val closed = AtomicLong(0)

    @Volatile
    var proxyPort: Int = 0
        private set

    val isAvailable: Boolean get() = handle != 0L && closed.get() == 0L

    suspend fun registerUrl(
        host: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): RegisteredMedia = withContext(Dispatchers.IO) {
        val headersJson = JSONObject()
        headers.forEach { (k, v) -> headersJson.put(k, v) }
        val data = submit(
            command = "proxy_register_url",
            fields = mapOf(
                "host" to host,
                "url" to url,
                "headers" to headersJson,
            ),
        )
        RegisteredMedia(
            id = data.getString("id"),
            url = data.getString("url"),
            encryptedUrl = data.optString("encrypted_url").takeIf { it.isNotEmpty() },
        )
    }

    suspend fun registerFile(
        host: String,
        path: String,
        contentType: String? = null,
        ttlMs: Long = 6L * 60L * 60L * 1000L,
    ): RegisteredMedia = withContext(Dispatchers.IO) {
        val fields = mutableMapOf<String, Any?>(
            "host" to host,
            "path" to path,
            "ttl_ms" to ttlMs,
        )
        if (contentType != null) fields["content_type"] = contentType
        val data = submit(command = "proxy_register_file", fields = fields)
        RegisteredMedia(
            id = data.getString("id"),
            url = data.getString("url"),
            encryptedUrl = data.optString("encrypted_url").takeIf { it.isNotEmpty() },
        )
    }

    suspend fun revoke(id: String): Boolean = withContext(Dispatchers.IO) {
        val data = submit(command = "proxy_revoke", fields = mapOf("id" to id))
        data.optBoolean("revoked", false)
    }

    fun shutdown() {
        if (!closed.compareAndSet(0, 1)) return
        pending.forEach { (_, cont) ->
            runCatching {
                cont.resumeWithException(IllegalStateException("Sender services shut down"))
            }
        }
        pending.clear()
        if (handle != 0L) {
            runCatching { SenderServicesNative.cancel(handle) }
            runCatching { SenderServicesNative.free(handle) }
        }
    }

    private suspend fun submit(command: String, fields: Map<String, Any?>): JSONObject {
        if (!isAvailable) error("Embedded stream proxy is not available")
        val requestId = nextRequestId.getAndIncrement().toString()
        val payload = JSONObject()
        payload.put("command", command)
        payload.put("request_id", requestId)
        fields.forEach { (k, v) -> payload.put(k, v ?: JSONObject.NULL) }

        return withTimeout(OPERATION_TIMEOUT_MS) {
            suspendCoroutine { cont ->
                pending[requestId] = cont
                val ok = try {
                    SenderServicesNative.submitJson(handle, payload.toString())
                } catch (e: Throwable) {
                    pending.remove(requestId)
                    cont.resumeWithException(e)
                    return@suspendCoroutine
                }
                if (!ok) {
                    pending.remove(requestId)
                    cont.resumeWithException(
                        IllegalStateException("Native queue rejected $command"),
                    )
                }
            }
        }
    }

    private fun handleEvent(json: String) {
        val event = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (event.optString("event")) {
            "started" -> {
                proxyPort = event.optInt("proxyPort", 0)
            }
            "operation" -> {
                val requestId = event.opt("requestId")?.toString() ?: return
                val cont = pending.remove(requestId) ?: return
                val data = event.optJSONObject("data") ?: JSONObject()
                cont.resume(data)
            }
            "error" -> {
                val requestId = event.opt("requestId")?.toString()
                val message = event.optString("message", "unknown error")
                val operation = event.optString("operation", "operation")
                if (requestId != null) {
                    val cont = pending.remove(requestId)
                    cont?.resumeWithException(IllegalStateException("$operation: $message"))
                } else {
                    Log.w(TAG, "Sender services error ($operation): $message")
                }
            }
        }
    }

    private fun pollLoop() {
        while (closed.get() == 0L && handle != 0L) {
            val json = try {
                SenderServicesNative.nextEvent(handle, 200L)
            } catch (e: Throwable) {
                Log.w(TAG, "nextEvent failed: ${e.message}")
                break
            }
            if (json.isNullOrEmpty()) continue
            handleEvent(json)
        }
    }

    companion object {
        private const val TAG = "PhoneSenderServices"
        private const val OPERATION_TIMEOUT_MS = 15_000L
        private const val EXPECTED_ABI = 1

        private val mutex = Mutex()
        @Volatile private var instance: PhoneSenderServices? = null

        /**
         * Returns the process-wide instance, starting the native worker if needed.
         * Returns null when the native library / ABI is unavailable.
         */
        suspend fun get(): PhoneSenderServices? = mutex.withLock {
            instance?.takeIf { it.isAvailable }?.let { return it }
            if (!SenderServicesNative.libraryLoaded) return null
            val abi = runCatching { SenderServicesNative.abiVersion() }.getOrDefault(0)
            if (abi != EXPECTED_ABI) {
                Log.w(TAG, "Sender services ABI mismatch: got $abi expected $EXPECTED_ABI")
                return null
            }
            val handle = runCatching { SenderServicesNative.start() }.getOrDefault(0L)
            if (handle == 0L) {
                Log.w(TAG, "Failed to start sender services worker")
                return null
            }
            val services = PhoneSenderServices(handle)
            Thread({ services.pollLoop() }, "playbridge-sender-services-poll")
                .apply { isDaemon = true }
                .start()
            // Wait briefly for the "started" event so proxyPort is known.
            var waited = 0
            while (services.proxyPort == 0 && waited < 50) {
                kotlinx.coroutines.delay(20)
                waited++
            }
            instance = services
            services
        }

        fun shutdownIfRunning() {
            instance?.shutdown()
            instance = null
        }
    }
}
