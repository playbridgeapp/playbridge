package com.playbridge.sender.history

import com.playbridge.shared.network.MediaNetworkPolicy
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class LinkCheckResult { Works, Expired, CouldNotCheck }

/**
 * Pure decision: HTTP [status] (+ optional first bytes of an error body, lower-cased text only)
 * to a result. Returns null when the caller should retry without a Range header
 * (the server rejected the range request itself).
 */
fun decideLinkCheck(status: Int, bodySnippet: String? = null, rangeRequested: Boolean = true): LinkCheckResult? =
    when {
        status in 200..299 -> LinkCheckResult.Works
        // The resource exists but is empty or the range was unsatisfiable.
        status == 416 -> LinkCheckResult.Works
        status in EXPIRED_STATUSES -> LinkCheckResult.Expired
        // Some signed-URL stores answer an expired signature with 400 and say so in the body.
        status == 400 && bodySnippet?.contains("expired", ignoreCase = true) == true -> LinkCheckResult.Expired
        rangeRequested && (status == 400 || status == 405 || status == 501) -> null
        else -> LinkCheckResult.CouldNotCheck // 3xx left over, 429, other 4xx, 5xx
    }

private const val CONNECT_TIMEOUT_S = 5L
private const val READ_TIMEOUT_S = 5L
private const val CALL_TIMEOUT_S = 10L
private const val MAX_REDIRECTS = 5
private const val SNIPPET_BYTES = 256L
private val DROPPED_HEADERS = setOf("range", "host", "content-length", "connection", "accept-encoding")
private val CROSS_ORIGIN_KEPT_HEADERS = setOf("user-agent", "accept", "accept-language")

/**
 * One small ranged GET for a saved cast; at most a few bytes are read. Reuses the app's shared
 * [client] (derived with short timeouts, no automatic redirects) and enforces
 * [MediaNetworkPolicy] on every hop and every DNS answer, so private/loopback hosts and
 * redirects into them are refused unless the origin is in [allowedPrivateOrigins].
 * Saved [headers] go to the original origin only. Never logs URLs or header values.
 */
suspend fun checkLink(
    client: OkHttpClient,
    url: String,
    headers: Map<String, String> = emptyMap(),
    allowedPrivateOrigins: Collection<String> = emptyList(),
): LinkCheckResult = withContext(Dispatchers.IO) {
    runCatching {
        val checker = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val answers = Dns.SYSTEM.lookup(hostname)
                    if (!MediaNetworkPolicy.areAllowedAddressesForHost(hostname, answers, allowedPrivateOrigins)) {
                        throw java.net.UnknownHostException("blocked")
                    }
                    return answers
                }
            })
            .build()
        val first = request(checker, url, url, headers, allowedPrivateOrigins, ranged = true)
        if (first != null) first
        else request(checker, url, url, headers, allowedPrivateOrigins, ranged = false) ?: LinkCheckResult.CouldNotCheck
    }.getOrElse { e ->
        if (e is kotlinx.coroutines.CancellationException) throw e
        LinkCheckResult.CouldNotCheck
    }
}

/** Follows redirects by hand; null means "retry without Range". */
private suspend fun request(
    client: OkHttpClient,
    original: String,
    start: String,
    headers: Map<String, String>,
    grants: Collection<String>,
    ranged: Boolean,
): LinkCheckResult? {
    var current = start
    repeat(MAX_REDIRECTS + 1) {
        if (!MediaNetworkPolicy.isAllowedRegistrationUrlSyntax(current, grants)) {
            return LinkCheckResult.CouldNotCheck
        }
        val sameOrigin = MediaNetworkPolicy.sameOrigin(original, current)
        val builder = Request.Builder().url(current).get()
        for ((name, value) in headers) {
            val lower = name.lowercase()
            if (lower in DROPPED_HEADERS) continue
            if (!sameOrigin && lower !in CROSS_ORIGIN_KEPT_HEADERS) continue
            runCatching { builder.header(name, value) }
        }
        if (ranged) builder.header("Range", "bytes=0-0")
        val call = client.newCall(builder.build())
        run {
            call.await().use { response ->
                val code = response.code
                if (code in 301..303 || code == 307 || code == 308) {
                    val next = response.header("Location")?.let { response.request.url.resolve(it) }
                        ?: return LinkCheckResult.CouldNotCheck
                    current = next.toString()
                    return@use
                }
                val snippet = if (code == 400) {
                    runCatching { response.peekBody(SNIPPET_BYTES).string() }.getOrNull()
                } else null
                return decideLinkCheck(code, snippet, ranged)
            }
        }
    }
    return LinkCheckResult.CouldNotCheck
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
