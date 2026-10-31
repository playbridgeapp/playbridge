package com.playbridge.player.proxy

import android.util.Log
import com.playbridge.shared.network.MediaNetworkPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal data class RegisteredMedia(
    val id: String,
    val url: String,
    val encryptedUrl: String? = null,
)

/**
 * Loopback-only host for the embedded Rust stream proxy on Android TV.
 *
 * Started lazily on the first page-controlled playback item (`page_cast` / `linked_page`)
 * and kept alive for the active playback session.
 */
internal class ReceiverStreamProxy private constructor(
    private val handle: Long,
) {
    private val nextRequestId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, Continuation<JSONObject>>()
    private val closed = AtomicLong(0)
    private val leaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val leases = PlaybackLeaseRegistry(leaseScope, ::renew) { id ->
        revoke(id)
    }

    @Volatile
    var proxyPort: Int = 0
        private set

    val isAvailable: Boolean
        get() = handle != 0L && closed.get() == 0L && proxyPort in 1..65535

    val trustedOrigin: String?
        get() = proxyPort.takeIf { isAvailable }?.let { "http://$LOOPBACK_HOST:$it" }

    fun ownsUrl(url: String): Boolean =
        MediaNetworkPolicy.isTrustedLocalProxyUrl(url, trustedOrigin)

    fun retainId(id: String): AutoCloseable? =
        if (!isAvailable) null else leases.retain(id)

    fun retainMedia(url: String): AutoCloseable? {
        if (!ownsUrl(url)) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val segments = uri.path.split('/').filter { it.isNotBlank() }
        if (segments.size != 3 || segments[0] != "s") return null
        return leases.retain(segments[1])
    }

    private suspend fun renew(id: String): Boolean = withContext(Dispatchers.IO) {
        submit("proxy_renew", mapOf("id" to id)).optBoolean("renewed", false)
    }

    suspend fun registerUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String? = null,
        // Every registration on the receiver is page media: exact origins only, never DNS-derived trust.
        allowedPrivateOrigins: List<String> = emptyList(),
        remoteOrigin: Boolean = true,
    ): RegisteredMedia = withContext(Dispatchers.IO) {
        val headersJson = JSONObject()
        headers.forEach { (k, v) -> headersJson.put(k, v) }
        val fields = mutableMapOf<String, Any?>(
            "host" to LOOPBACK_HOST,
            "url" to url,
            "headers" to headersJson,
            "remote_origin" to remoteOrigin,
        )
        if (!contentType.isNullOrBlank()) fields["content_type"] = contentType
        fields["allowed_private_origins"] = JSONArray(allowedPrivateOrigins)
        val data = submit(
            command = "proxy_register_url",
            fields = fields,
        )
        val id = data.getString("id")
        leases.register(id)
        RegisteredMedia(
            id = id,
            url = data.getString("url"),
            encryptedUrl = data.optString("encrypted_url").takeIf { it.isNotEmpty() },
        )
    }

    suspend fun revoke(id: String): Boolean = withContext(Dispatchers.IO) {
        leases.forget(id)
        val data = submit(command = "proxy_revoke", fields = mapOf("id" to id))
        data.optBoolean("revoked", false)
    }

    fun shutdown() {
        if (!closed.compareAndSet(0, 1)) return
        leaseScope.cancel()
        leases.clear()
        pending.forEach { (_, cont) ->
            runCatching {
                cont.resumeWithException(IllegalStateException("Receiver proxy shut down"))
            }
        }
        pending.clear()
        if (handle != 0L) {
            runCatching { ReceiverProxyNative.cancel(handle) }
            runCatching { ReceiverProxyNative.free(handle) }
        }
    }

    private suspend fun submit(command: String, fields: Map<String, Any?>): JSONObject {
        if (handle == 0L || closed.get() != 0L) {
            error("Embedded receiver stream proxy is not available")
        }
        val requestId = nextRequestId.getAndIncrement().toString()
        val payload = JSONObject()
        payload.put("command", command)
        payload.put("request_id", requestId)
        fields.forEach { (k, v) -> payload.put(k, v ?: JSONObject.NULL) }

        return withTimeout(OPERATION_TIMEOUT_MS) {
            suspendCoroutine { cont ->
                pending[requestId] = cont
                val ok = try {
                    ReceiverProxyNative.submitJson(handle, payload.toString())
                } catch (e: Throwable) {
                    pending.remove(requestId)
                    cont.resumeWithException(e)
                    return@suspendCoroutine
                }
                if (!ok) {
                    pending.remove(requestId)
                    cont.resumeWithException(
                        IllegalStateException("Native receiver proxy rejected $command"),
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
                    logWarn("Receiver proxy error ($operation): $message")
                }
            }
        }
    }

    private fun pollLoop() {
        while (closed.get() == 0L && handle != 0L) {
            val json = try {
                ReceiverProxyNative.nextEvent(handle, 200L)
            } catch (e: Throwable) {
                logWarn("nextEvent failed: ${e.message}")
                break
            }
            if (json.isNullOrEmpty()) continue
            handleEvent(json)
        }
    }

    companion object {
        private const val TAG = "ReceiverStreamProxy"
        const val LOOPBACK_HOST = "127.0.0.1"
        private const val OPERATION_TIMEOUT_MS = 15_000L
        private const val STARTUP_POLL_STEPS = 250
        private const val STARTUP_POLL_INTERVAL_MS = 20L

        private val mutex = Mutex()
        @Volatile
        private var instance: ReceiverStreamProxy? = null

        fun isSupported(): Boolean = ReceiverProxyNative.isReady

        fun currentTrustedOrigin(): String? = instance?.trustedOrigin

        fun ownsUrlIfRunning(url: String): Boolean = instance?.ownsUrl(url) == true

        fun retainIdIfRunning(id: String): AutoCloseable? = instance?.retainId(id)

        /**
         * Returns the process-wide loopback proxy instance, starting the native worker if needed.
         * Returns null when the native library, upstream callbacks, or loopback bind are unavailable.
         */
        suspend fun get(): ReceiverStreamProxy? = mutex.withLock {
            instance?.takeIf { it.isAvailable }?.let { return it }
            instance?.shutdown()
            instance = null
            if (!ReceiverProxyNative.isReady) return null
            val handle = runCatching { ReceiverProxyNative.start() }.getOrDefault(0L)
            if (handle == 0L) {
                logWarn("Failed to start receiver stream-proxy worker")
                return null
            }
            val proxy = ReceiverStreamProxy(handle)
            Thread({ proxy.pollLoop() }, "playbridge-tv-proxy-poll")
                .apply { isDaemon = true }
                .start()
            var waited = 0
            while (proxy.proxyPort == 0 && proxy.closed.get() == 0L && waited < STARTUP_POLL_STEPS) {
                delay(STARTUP_POLL_INTERVAL_MS)
                waited++
            }
            if (!proxy.isAvailable) {
                logWarn("Receiver stream-proxy worker did not report a loopback port")
                proxy.shutdown()
                return null
            }
            instance = proxy
            proxy
        }

        fun shutdownIfRunning() {
            val running = instance
            instance = null
            running?.shutdown()
        }

        /**
         * Rewrites a registered `/s/<id>/<filename>` URL to `/s/<id>/manifest.edl` for `mpv` DASH,
         * matching Desktop `StreamProxyServer.mpvDashUrl`.
         */
        fun mpvDashUrl(manifestUrl: String): String {
            val uri = runCatching { URI(manifestUrl) }.getOrNull() ?: return manifestUrl
            val segments = uri.path.split('/').filter { it.isNotBlank() }
            if (segments.size < 2 || segments[0] != "s") return manifestUrl
            val edlPath = "/" + (segments.dropLast(1) + "manifest.edl").joinToString("/")
            return URI(uri.scheme, null, uri.host, uri.port, edlPath, null, null).toString()
        }

        private fun logWarn(message: String) {
            try {
                Log.w(TAG, message)
            } catch (_: Throwable) {
                // Pure JVM unit tests do not mock android.util.Log.
            }
        }
    }
}
