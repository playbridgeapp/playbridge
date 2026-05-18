package com.playbridge.sender.browser

import java.util.concurrent.ConcurrentHashMap

/**
 * Global registry mapping CDN host → browser session headers (cookies, user-agent, referer).
 *
 * When an HLS download is enqueued, DownloadUtils registers the headers here.
 * HeaderInjectingDataSourceFactory then applies them to every DataSource.open() call
 * whose URI host matches a registered entry.
 *
 * Keyed by host (not exact URL) so that segment requests on the same CDN domain
 * automatically receive the same headers as the master playlist request.
 */
object DownloadHeadersStore {

    private val store = ConcurrentHashMap<String, Map<String, String>>()

    fun register(downloadUrl: String, headers: Map<String, String>) {
        if (headers.isEmpty()) return
        val host = hostOf(downloadUrl) ?: return
        store[host] = headers
    }

    fun headersForUrl(url: String): Map<String, String> {
        val host = hostOf(url) ?: return emptyMap()
        return store[host] ?: emptyMap()
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host?.lowercase() }
            .getOrNull()?.takeIf { it.isNotBlank() }
}
