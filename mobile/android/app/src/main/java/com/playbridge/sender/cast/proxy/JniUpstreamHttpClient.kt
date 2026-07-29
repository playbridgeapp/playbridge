package com.playbridge.sender.cast.proxy

import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Host-side origin fetch for stream-proxy-rust `upstream-jni`.
 *
 * Uses [HttpURLConnection] — the same stack Media3 [DefaultHttpDataSource] uses —
 * so live CDNs that accept ExoPlayer also accept Via-phone proxy origin pulls.
 *
 * Methods are `@JvmStatic` so Rust trampolines can call them without an instance.
 * Contract mirrors `pb_proxy_upstream_*` open/read/close.
 */
internal object JniUpstreamHttpClient {
    private const val TAG = "JniUpstreamHttp"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 45_000

    /** Match LocalProxyServer / PhoneExoPlayerFactory default UA. */
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val nextHandle = AtomicLong(1)
    private val openHandles = ConcurrentHashMap<Long, OpenHandle>()

    private data class OpenHandle(
        val connection: HttpURLConnection,
        val input: InputStream?,
    )

    /**
     * Open [url] with JSON object request headers.
     *
     * @return JSON: `{"ok":true,"handle":N,"status":200,"headers":{...}}`
     *         or `{"ok":false,"error":"..."}`.
     */
    @JvmStatic
    fun open(url: String, requestHeadersJson: String): String {
        return try {
            val headers = parseHeadersJson(requestHeadersJson)
            openWithRetry(url, headers)
        } catch (e: Throwable) {
            warn("open failed: ${e.message}")
            errorJson(e.message ?: "open failed")
        }
    }

    /**
     * Read up to [maxLen] bytes.
     * @return non-empty data, empty array on EOF, or null on error.
     */
    @JvmStatic
    fun read(handle: Long, maxLen: Int): ByteArray? {
        if (maxLen <= 0) return ByteArray(0)
        val open = openHandles[handle] ?: return null
        val input = open.input ?: return ByteArray(0)
        return try {
            val buf = ByteArray(maxLen.coerceAtMost(256 * 1024))
            val n = input.read(buf)
            when {
                n < 0 -> ByteArray(0)
                n == 0 -> ByteArray(0)
                n == buf.size -> buf
                else -> buf.copyOf(n)
            }
        } catch (e: Throwable) {
            warn("read failed handle=$handle: ${e.message}")
            null
        }
    }

    @JvmStatic
    fun close(handle: Long) {
        val open = openHandles.remove(handle) ?: return
        runCatching { open.input?.close() }
        runCatching { open.connection.disconnect() }
    }

    private fun openWithRetry(url: String, headers: Map<String, String>): String {
        val filtered = filterHeaders(headers)
        var lastError: String? = null

        // First try full headers; on 401/403/429 retry with minimal set (LocalProxy parity).
        val attempts = listOf(filtered, minimalHeaders(filtered))
        for ((index, attemptHeaders) in attempts.withIndex()) {
            val result = connectOnce(url, attemptHeaders)
            when {
                result.ok -> return result.json
                result.status in listOf(401, 403, 429, 500, 502, 503) && index == 0 -> {
                    lastError = result.error
                    warn("upstream HTTP ${result.status}; retrying with minimal headers")
                    continue
                }
                else -> return if (result.ok) result.json else errorJson(result.error ?: "HTTP ${result.status}")
            }
        }
        return errorJson(lastError ?: "upstream open failed")
    }

    private data class ConnectOutcome(
        val ok: Boolean,
        val json: String = "",
        val status: Int = 0,
        val error: String? = null,
    )

    private fun connectOnce(url: String, headers: Map<String, String>): ConnectOutcome {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            useCaches = false
            doInput = true
            headers.forEach { (k, v) ->
                runCatching { setRequestProperty(k, v) }
            }
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                setRequestProperty("User-Agent", DEFAULT_UA)
            }
            if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
                setRequestProperty(
                    "Accept",
                    "application/vnd.apple.mpegurl, application/x-mpegURL, application/dash+xml, */*;q=0.8",
                )
            }
        }

        val code = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return ConnectOutcome(ok = false, error = e.message ?: "connect failed")
        }

        val stream = try {
            if (code in 200..299 || code == 206) conn.inputStream else conn.errorStream
        } catch (_: Exception) {
            null
        }

        // Accept success and 206; still open body for odd HLS-ish 2xx already covered.
        if (code !in 200..299 && code != 206) {
            // Drain/close error stream; caller may retry.
            runCatching { stream?.close() }
            conn.disconnect()
            return ConnectOutcome(
                ok = false,
                status = code,
                error = "HTTP $code ${conn.responseMessage ?: ""}".trim(),
            )
        }

        val handle = nextHandle.getAndIncrement()
        openHandles[handle] = OpenHandle(connection = conn, input = stream)

        val respHeaders = JSONObject()
        conn.contentType?.let { respHeaders.put("content-type", it) }
        val cl = conn.contentLengthLong
        if (cl >= 0) respHeaders.put("content-length", cl.toString())
        conn.getHeaderField("Content-Range")?.let { respHeaders.put("content-range", it) }
        conn.getHeaderField("Accept-Ranges")?.let { respHeaders.put("accept-ranges", it) }
        // Cache policy for stream-proxy segment cache (no-store / max-age / Vary).
        conn.getHeaderField("Cache-Control")?.let { respHeaders.put("cache-control", it) }
        conn.getHeaderField("Vary")?.let { respHeaders.put("vary", it) }
        conn.getHeaderField("Expires")?.let { respHeaders.put("expires", it) }
        conn.getHeaderField("Age")?.let { respHeaders.put("age", it) }
        conn.getHeaderField("Date")?.let { respHeaders.put("date", it) }

        val out = JSONObject()
            .put("ok", true)
            .put("handle", handle)
            .put("status", code)
            .put("headers", respHeaders)
        return ConnectOutcome(ok = true, json = out.toString(), status = code)
    }

    /** Drop hop-by-hop / browser-context headers (same policy as LocalProxyServer). */
    internal fun filterHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { k ->
            val lk = k.lowercase()
            !lk.startsWith("sec-fetch") &&
                !lk.startsWith("sec-ch") &&
                lk != "host" &&
                lk != "accept-encoding" &&
                lk != "connection" &&
                lk != "origin" &&
                lk != "content-length" &&
                lk != "content-type" &&
                lk != "transfer-encoding" &&
                lk != "te" &&
                lk != "upgrade" &&
                lk != "http2-settings"
        }

    private fun minimalHeaders(headers: Map<String, String>): Map<String, String> {
        val out = headers.filterKeys { k ->
            val lk = k.lowercase()
            lk == "user-agent" || lk == "referer" || lk == "cookie" || lk == "authorization" ||
                lk == "range"
        }.toMutableMap()
        if (out.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            out["User-Agent"] = DEFAULT_UA
        }
        return out
    }

    private fun parseHeadersJson(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        val obj = JSONObject(json)
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optString(k, "")
        }
        return out
    }

    private fun errorJson(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    /** android.util.Log is not mocked in pure JVM unit tests. */
    private fun warn(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: RuntimeException) {
            // unit tests
        }
    }
}
