package com.playbridge.sender.browser

import com.playbridge.sender.cast.proxy.PhoneSenderServices
import com.playbridge.sender.cast.proxy.SenderServicesNative

class PageMediaProxyException(message: String) : Exception(message)

/** Registers one URL with the phone's checked proxy and owns its playback lease. */
internal interface PageMediaRegistrar {
    suspend fun register(
        host: String,
        url: String,
        headers: Map<String, String>,
        contentType: String?,
        allowedPrivateOrigins: List<String>,
    ): String

    fun retain(proxyUrl: String): AutoCloseable?
}

/** Leases for one website session's registrations; released together when it ends. */
internal class PageMediaLeases : AutoCloseable {
    private val leases = mutableListOf<AutoCloseable>()
    private var closed = false

    fun add(lease: AutoCloseable) {
        val late = synchronized(this) { if (closed) true else { leases += lease; false } }
        if (late) lease.close()
    }

    override fun close() {
        val released = synchronized(this) { closed = true; leases.toList().also { leases.clear() } }
        released.forEach { runCatching { it.close() } }
    }
}

/**
 * Website media that this phone fetches (This phone) or hands to a device that is not a
 * PlayBridge receiver (DLNA, Google Cast, Roku, TV browser) goes through the phone's checked
 * proxy: public addresses plus the exact local origins the user approved, checked on every
 * connection, redirect and playlist child. PlayBridge receivers enforce this themselves (#480).
 */
internal object PageMediaProxy {
    /** This phone's player reaches the proxy without leaving the device. */
    const val LOCAL_HOST = "127.0.0.1"

    suspend fun route(
        item: LinkedPageCastItem,
        host: String,
        leases: PageMediaLeases,
        registrar: PageMediaRegistrar = PhonePageMediaRegistrar,
    ): LinkedPageCastItem {
        val payload = item.payload
        suspend fun proxied(url: String, headers: Map<String, String>, contentType: String?): String {
            val proxyUrl = registrar.register(host, url, headers, contentType, payload.allowed_private_origins)
            leases.add(registrar.retain(proxyUrl) ?: throw PageMediaProxyException("Proxy lease unavailable"))
            return proxyUrl
        }
        val url = proxied(payload.url, payload.headers, payload.content_type)
        val subtitles = payload.subtitles.map { proxied(it, emptyMap(), null) }
        val resources = payload.subtitle_resources.map {
            it.copy(url = proxied(it.url, it.headers, null), headers = emptyMap())
        }
        // The proxy now carries each resource's credentials; the player sends none.
        return item.copy(
            payload = payload.copy(url = url, headers = emptyMap(), subtitles = subtitles, subtitle_resources = resources),
        )
    }
}

private object PhonePageMediaRegistrar : PageMediaRegistrar {
    override suspend fun register(
        host: String,
        url: String,
        headers: Map<String, String>,
        contentType: String?,
        allowedPrivateOrigins: List<String>,
    ): String {
        // No fallback: without checked native networking, website media doesn't play.
        val ready = SenderServicesNative.jniUpstreamReady &&
            runCatching { SenderServicesNative.upstreamCallbacksRegistered() }.getOrDefault(false)
        if (!ready) throw PageMediaProxyException("Checked phone networking is unavailable")
        val services = PhoneSenderServices.get() ?: throw PageMediaProxyException("Phone proxy is unavailable")
        return services.registerUrl(
            host = host,
            url = url,
            headers = headers,
            contentType = contentType,
            allowedPrivateOrigins = allowedPrivateOrigins,
        ).url
    }

    override fun retain(proxyUrl: String): AutoCloseable? = PhoneSenderServices.retainMediaIfRunning(proxyUrl)
}
