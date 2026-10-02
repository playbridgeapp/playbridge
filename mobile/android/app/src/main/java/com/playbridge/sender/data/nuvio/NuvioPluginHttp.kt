package com.playbridge.sender.data.nuvio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun interface NuvioHostResolver {
    fun resolve(host: String): List<InetAddress>
}

internal class NuvioHostGate(
    approvedHosts: Set<String>,
    private val onBlocked: (String) -> Unit,
) {
    private val approved = approvedHosts.map { NuvioDestinationPolicy.normalizeHost(it) }.toSet()
    private val blocked = LinkedHashSet<String>()
    private val lock = Any()

    fun allow(host: String): Boolean {
        val normalized = NuvioDestinationPolicy.normalizeHost(host)
        if (normalized in approved) return true
        val added = synchronized(lock) {
            if (blocked.size >= NuvioLimits.MAX_BLOCKED_HOSTS) false else blocked.add(normalized)
        }
        if (added) onBlocked(normalized)
        return false
    }
}

internal class NuvioRequestBudget(
    private val maxRequests: Int,
    private val slots: Semaphore,
) {
    private val used = AtomicInteger(0)

    suspend fun <T> use(block: suspend () -> T): T? {
        if (used.incrementAndGet() > maxRequests) {
            used.decrementAndGet()
            return null
        }
        return slots.withPermit { block() }
    }
}

internal data class NuvioTextFetch(
    val body: String? = null,
    val error: String? = null,
)

/**
 * Redirect decisions used by [NuvioPluginHttp]. Cross-origin includes a different
 * port. HTTPS to HTTP is rejected. Cross-origin hops drop the body and every
 * header except a short allowlist so custom credentials cannot follow.
 */
internal object NuvioRedirectPolicy {
    private val SAFE_HEADERS = setOf("accept", "accept-language", "user-agent")

    data class Hop(
        val headers: Map<String, String>,
        val body: String?,
        val forceGet: Boolean,
    )

    fun sameOrigin(left: HttpUrl, right: HttpUrl): Boolean =
        left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            left.port == right.port

    fun isDowngrade(from: HttpUrl, to: HttpUrl): Boolean =
        from.scheme.equals("https", ignoreCase = true) && to.scheme.equals("http", ignoreCase = true)

    fun prepare(
        from: HttpUrl,
        to: HttpUrl,
        headers: Map<String, String>,
        body: String?,
        install: Boolean,
        initial: HttpUrl,
    ): Hop? {
        if (to.username.isNotEmpty() || to.password.isNotEmpty()) return null
        if (to.scheme != "http" && to.scheme != "https") return null
        if (isDowngrade(from, to)) return null
        if (install && !sameOrigin(initial, to)) return null
        if (sameOrigin(from, to)) return Hop(headers, body, forceGet = false)
        return Hop(
            headers = headers.filterKeys { it.lowercase() in SAFE_HEADERS },
            body = null,
            forceGet = true,
        )
    }
}

/**
 * Dedicated plugin client. It drops the caller's interceptors, cache, cookie jar,
 * proxy, and authenticator. DNS answers are checked and pinned to the socket.
 */
internal class NuvioPluginHttp(
    client: OkHttpClient = OkHttpClient(),
    private val resolver: NuvioHostResolver = NuvioHostResolver { host ->
        InetAddress.getAllByName(host).toList()
    },
    private val addressBlocked: (InetAddress) -> Boolean = NuvioDestinationPolicy::isBlockedAddress,
    private val hostnameBlocked: (String) -> Boolean = NuvioDestinationPolicy::isBlockedHostname,
    private val allowCleartextInstall: Boolean = false,
    val requestSlots: Semaphore = Semaphore(NuvioLimits.MAX_CONCURRENT_REQUESTS),
    private val maxPluginResponseBytes: Int = NuvioLimits.MAX_RESPONSE_BYTES,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val baseClient = isolated(client)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchText(
        url: String,
        budget: NuvioRequestBudget,
        maxBytes: Int,
    ): NuvioTextFetch = withContext(Dispatchers.IO) {
        val parsed = NuvioDestinationPolicy.parseInstallUrl(url, allowCleartextInstall)
            ?: return@withContext NuvioTextFetch(error = "repository url rejected")
        val result = budget.use {
            exchange(parsed, install = true, method = "GET", headers = emptyMap(), body = null, gate = null, maxBytes = maxBytes)
        } ?: return@withContext NuvioTextFetch(error = "request limit reached")
        when {
            result.error != null -> NuvioTextFetch(error = result.error)
            result.status !in 200..299 -> NuvioTextFetch(error = "repository request failed")
            else -> NuvioTextFetch(body = result.body)
        }
    }

    suspend fun pluginRequest(
        requestJson: String,
        gate: NuvioHostGate,
        budget: NuvioRequestBudget,
    ): String = withContext(Dispatchers.IO) {
        if (utf8Size(requestJson) > NuvioLimits.MAX_REQUEST_JSON_BYTES) return@withContext errorResponse("request too large")
        budget.use { executePlugin(requestJson, gate) } ?: errorResponse("request limit reached")
    }

    private suspend fun executePlugin(requestJson: String, gate: NuvioHostGate): String {
        coroutineContext.ensureActive()
        val req = try {
            json.parseToJsonElement(requestJson).jsonObject
        } catch (_: Exception) {
            return errorResponse("invalid request")
        }
        val rawUrl = req["url"]?.jsonPrimitive?.contentOrNull ?: return errorResponse("missing url")
        val url = rawUrl.toHttpUrlOrNull() ?: return errorResponse("invalid url")
        if (url.scheme != "http" && url.scheme != "https") return errorResponse("unsupported scheme")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return errorResponse("unsupported url")
        val method = (req["method"]?.jsonPrimitive?.contentOrNull ?: "GET").uppercase()
        if (method !in ALLOWED_METHODS) return errorResponse("unsupported method")
        val headers = linkedMapOf<String, String>()
        req["headers"]?.jsonObject?.forEach { (name, value) ->
            if (headers.size >= 32) return@forEach
            val headerValue = value.jsonPrimitive.contentOrNull ?: return@forEach
            if (!safeHeader(name, headerValue) || name.equals("host", ignoreCase = true)) return@forEach
            headers[name] = headerValue
        }
        val body = req["body"]?.jsonPrimitive?.contentOrNull
        if (body != null && utf8Size(body) > NuvioLimits.MAX_REQUEST_BODY_BYTES) return errorResponse("request too large")
        val result = exchange(url, install = false, method = method, headers = headers, body = body, gate = gate, maxBytes = maxPluginResponseBytes)
        if (result.blockedHost != null) return errorResponse("host not approved", result.blockedHost)
        if (result.error != null) return errorResponse(result.error)
        return buildJsonObject {
            put("ok", result.status in 200..299)
            put("status", result.status)
            put("statusText", result.statusText)
            put("url", result.finalUrl)
            put("headers", buildJsonObject {
                result.responseHeaders.forEach { (name, value) -> put(name, value) }
            })
            put("body", result.body)
        }.toString()
    }

    private suspend fun exchange(
        initial: HttpUrl,
        install: Boolean,
        method: String,
        headers: Map<String, String>,
        body: String?,
        gate: NuvioHostGate?,
        maxBytes: Int,
    ): Exchange {
        var current = initial
        var hopMethod = method
        var hopBody = body
        var hopHeaders = headers
        repeat(NuvioLimits.MAX_REDIRECTS + 1) { hop ->
            coroutineContext.ensureActive()
            val host = NuvioDestinationPolicy.normalizeHost(current.host)
            if (install) {
                val allowedScheme = current.scheme == "https" || (allowCleartextInstall && current.scheme == "http")
                if (!allowedScheme || !NuvioRedirectPolicy.sameOrigin(initial, current)) {
                    return Exchange(error = "repository redirect rejected")
                }
            } else if (gate?.allow(host) == false) {
                return Exchange(error = "host not approved", blockedHost = host)
            }
            if (hostnameBlocked(host)) return Exchange(error = "destination blocked")
            val addresses = resolvePinned(host) ?: return Exchange(error = "destination blocked")
            val request = buildRequest(current, hopMethod, hopHeaders, hopBody)
            val response = try {
                executePinned(request, host, addresses, maxBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                return Exchange(error = "request failed")
            }
            if (response.error != null) return response
            if (!response.isRedirect) return response
            if (hop == NuvioLimits.MAX_REDIRECTS) return Exchange(error = "too many redirects")
            val next = response.location?.let { current.resolve(it) } ?: return Exchange(error = "invalid redirect")
            val prepared = NuvioRedirectPolicy.prepare(current, next, hopHeaders, hopBody, install, initial)
                ?: return Exchange(error = "redirect rejected")
            current = next
            hopHeaders = prepared.headers
            hopBody = prepared.body
            if (prepared.forceGet || response.status == 303 || ((response.status == 301 || response.status == 302) && hopMethod != "GET" && hopMethod != "HEAD")) {
                hopMethod = "GET"
                hopBody = null
            }
        }
        return Exchange(error = "too many redirects")
    }

    private suspend fun resolvePinned(host: String): List<InetAddress>? {
        if (hostnameBlocked(host)) return null
        val literal = NuvioDestinationPolicy.literalAddress(host)
        val addresses = if (literal != null) {
            listOf(literal)
        } else {
            lookupWithDeadline(host) ?: return null
        }
        if (addresses.isEmpty() || addresses.any(addressBlocked)) return null
        return addresses
    }

    private suspend fun lookupWithDeadline(host: String): List<InetAddress>? {
        coroutineContext.ensureActive()
        return withTimeoutOrNull(NuvioLimits.DNS_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val resumed = AtomicBoolean(false)
                fun resumeOnce(value: List<InetAddress>?) {
                    if (cont.isActive && resumed.compareAndSet(false, true)) cont.resume(value)
                }
                val future = try {
                    CompletableFuture.supplyAsync({
                        resolver.resolve(host)
                    }, dnsExecutor)
                } catch (_: RejectedExecutionException) {
                    resumeOnce(null)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation { future.cancel(true) }
                future.whenComplete { value, error ->
                    if (error != null) resumeOnce(null) else resumeOnce(value)
                }
            }
        }
    }

    private suspend fun executePinned(
        request: Request,
        host: String,
        addresses: List<InetAddress>,
        maxBytes: Int,
    ): Exchange {
        val pinned = isolated(baseClient)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (!hostname.equals(host, ignoreCase = true)) throw UnknownHostException("unpinned host")
                    return addresses
                }
            })
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .build()
        val call = pinned.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val copied = copyBounded(response, maxBytes)
                        if (cont.isActive) cont.resume(copied)
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    } finally {
                        response.close()
                    }
                }
            })
        }
    }

    private fun copyBounded(response: Response, maxBytes: Int): Exchange {
        val headers = response.headers.toMultimap()
            .mapKeys { it.key.lowercase() }
            .mapValues { it.value.lastOrNull().orEmpty().take(2048) }
            .filterKeys { safeHeader(it, "x") }
            .entries.take(32).associate { it.toPair() }
        val location = response.header("Location")
        if (response.code in REDIRECT_CODES && !location.isNullOrBlank()) {
            return Exchange(
                status = response.code,
                statusText = response.message.take(80),
                finalUrl = response.request.url.toString(),
                responseHeaders = headers,
                location = location,
            )
        }
        val length = response.body?.contentLength() ?: -1
        if (length > maxBytes) return Exchange(error = "response too large")
        val source = response.body?.source()
            ?: return Exchange(
                status = response.code,
                statusText = response.message.take(80),
                finalUrl = response.request.url.toString(),
                responseHeaders = headers,
            )
        val filled = source.request(maxBytes.toLong() + 1)
        if (filled && source.buffer.size > maxBytes) return Exchange(error = "response too large")
        val text = source.buffer.readUtf8()
        if (utf8Size(text) > maxBytes) return Exchange(error = "response too large")
        return Exchange(
            status = response.code,
            statusText = response.message.take(80),
            body = text,
            finalUrl = response.request.url.toString(),
            responseHeaders = headers,
        )
    }

    private fun buildRequest(url: HttpUrl, method: String, headers: Map<String, String>, body: String?): Request {
        val builder = Request.Builder().url(url)
        var hasUserAgent = false
        headers.forEach { (name, value) ->
            if (!safeHeader(name, value)) return@forEach
            if (name.equals("user-agent", true)) hasUserAgent = true
            builder.header(name, value)
        }
        if (!hasUserAgent) builder.header("User-Agent", DEFAULT_USER_AGENT)
        if (method == "GET" || method == "HEAD") {
            builder.method(method, null)
        } else if (body == null) {
            builder.method(method, ByteArray(0).toRequestBody(null))
        } else {
            val contentType = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: "application/json"
            builder.method(method, body.toRequestBody(contentType.toMediaTypeOrNull()))
        }
        return builder.build()
    }

    private fun safeHeader(name: String, value: String): Boolean {
        if (!HEADER_NAME.matches(name)) return false
        if (utf8Size(value) > NuvioLimits.MAX_HEADER_VALUE_BYTES) return false
        if (value.any { char ->
            val code = char.code
            code == 0x7f || (code < 0x20 && char != '\t')
        }) {
            return false
        }
        return true
    }

    private fun errorResponse(message: String, blockedHost: String? = null): String = buildJsonObject {
        put("ok", false)
        put("status", 0)
        put("statusText", message)
        put("url", "")
        put("headers", buildJsonObject {})
        put("body", "")
        put("error", message)
        if (blockedHost != null) put("blockedHost", blockedHost)
    }.toString()

    private data class Exchange(
        val status: Int = 0,
        val statusText: String = "",
        val body: String = "",
        val finalUrl: String = "",
        val responseHeaders: Map<String, String> = emptyMap(),
        val error: String? = null,
        val blockedHost: String? = null,
        val location: String? = null,
    ) {
        val isRedirect: Boolean get() = status in REDIRECT_CODES && !location.isNullOrBlank()
    }

    companion object {
        private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        private val HEADER_NAME = Regex("^[!#\\$%&'*+\\-.^_`|~0-9A-Za-z]{1,64}\$")
        private val dnsExecutor = ThreadPoolExecutor(
            2,
            2,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(4),
            { runnable -> Thread(runnable, "nuvio-plugin-dns").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        private fun isolated(client: OkHttpClient): OkHttpClient.Builder =
            client.newBuilder().apply {
                interceptors().clear()
                networkInterceptors().clear()
            }
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .proxy(Proxy.NO_PROXY)
                .protocols(listOf(Protocol.HTTP_1_1))
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)

        private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size
    }
}
