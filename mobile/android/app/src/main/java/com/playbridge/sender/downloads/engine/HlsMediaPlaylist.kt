package com.playbridge.sender.downloads.engine

import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI

/**
 * A parsed HLS *media* playlist (the per-variant one with #EXTINF segments), as opposed
 * to the master playlist `HlsParser` handles. Only what the downloader needs.
 */
data class HlsMediaPlaylist(
    val segments: List<String>,          // absolute segment URLs, in order
    val initSegmentUrl: String?,         // #EXT-X-MAP (fMP4), absolute or null
    val key: EncryptionKey?,             // #EXT-X-KEY (AES-128) or null
    val isLive: Boolean,                 // no #EXT-X-ENDLIST
    val hasByteRanges: Boolean,          // #EXT-X-BYTERANGE present (unsupported in v1)
) {
    data class EncryptionKey(
        val method: String,              // e.g. "AES-128"
        val uri: String,                 // absolute key URL
        val iv: String?,                 // hex IV string incl. 0x, or null
    )
}

/**
 * Fetches and parses an HLS media playlist. Kept in the engine package (own OkHttp fetch)
 * so the download path doesn't depend on `HlsParser`'s HttpURLConnection fetch.
 */
object HlsMediaPlaylistParser {

    suspend fun fetch(
        client: OkHttpClient,
        variantUrl: String,
        headers: Map<String, String>,
    ): HlsMediaPlaylist {
        val request = Request.Builder().url(variantUrl).headers(headers.toHeaders()).build()
        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} fetching media playlist")
            resp.body?.string() ?: throw IOException("Empty media playlist body")
        }
        return parse(body, variantUrl)
    }

    fun parse(content: String, baseUrl: String): HlsMediaPlaylist {
        val segments = mutableListOf<String>()
        var initSegment: String? = null
        var key: HlsMediaPlaylist.EncryptionKey? = null
        var isLive = true
        var hasByteRanges = false

        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-ENDLIST") -> isLive = false
                line.startsWith("#EXT-X-BYTERANGE") -> hasByteRanges = true
                line.startsWith("#EXT-X-MAP:") -> {
                    uriAttr(line)?.let { initSegment = resolve(baseUrl, it) }
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attr(line, "METHOD") ?: "NONE"
                    if (!method.equals("NONE", true)) {
                        val uri = uriAttr(line)
                        if (uri != null) {
                            key = HlsMediaPlaylist.EncryptionKey(
                                method = method,
                                uri = resolve(baseUrl, uri),
                                iv = attr(line, "IV"),
                            )
                        }
                    }
                }
                line.isNotEmpty() && !line.startsWith("#") -> segments.add(resolve(baseUrl, line))
            }
        }
        return HlsMediaPlaylist(segments, initSegment, key, isLive, hasByteRanges)
    }

    // METHOD=AES-128,URI="...",IV=0x... — attribute helpers
    private fun attr(line: String, name: String): String? =
        Regex("$name=([^,\"]+)").find(line)?.groupValues?.getOrNull(1)?.trim()

    private fun uriAttr(line: String): String? =
        Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.getOrNull(1)

    private fun resolve(base: String, rel: String): String =
        runCatching { URI(base).resolve(rel).toString() }.getOrDefault(rel)
}
