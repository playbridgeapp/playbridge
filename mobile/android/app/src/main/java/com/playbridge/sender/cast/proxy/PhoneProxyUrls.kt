package com.playbridge.sender.cast.proxy

import java.net.URI

/**
 * Helpers for LAN proxy URLs produced by Via phone packaging.
 *
 * - Embedded Rust stream-proxy (primary remote path):  
 *   `http://192.168.x.x:port/s/{session}/playlist.m3u8` (JNI HttpURLConnection upstream)
 * This is deliberately only used to validate URLs returned by our embedded Rust service.
 * Route selection must use explicit route metadata, never URL shape: an origin server on a
 * private network can legitimately expose the same path structure.
 */
object PhoneProxyUrls {
    fun isPrivateLanHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true) || host == "127.0.0.1") return true
        if (host.startsWith("10.")) return true
        if (host.startsWith("192.168.")) return true
        if (host.matches(Regex("""^172\.(1[6-9]|2\d|3[0-1])\..*"""))) return true
        return false
    }

    private fun parse(url: String): Pair<String, String>? = runCatching {
        val uri = URI(url)
        val host = uri.host ?: return@runCatching null
        val path = uri.path ?: ""
        host to path
    }.getOrNull()

    /** Validate an embedded Rust `/s/...` or `/media/...` URL returned by sender services. */
    fun isRustEmbeddedProxyUrl(url: String): Boolean {
        val (host, path) = parse(url) ?: return false
        if (!isPrivateLanHost(host)) return false
        return path.startsWith("/s/") || path.startsWith("/media/")
    }
}
