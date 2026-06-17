package com.playbridge.shared.player

import com.playbridge.shared.logging.logger
import com.playbridge.shared.network.SharedHttpClient
import playbridge.PlayPayload
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.http.fullPath
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "M3uParser"

data class HlsVariant(
    val url: String,
    val resolution: String?,
    val bandwidth: Int?,
    val codecs: String?
)

/**
 * A single channel parsed from an IPTV-style M3U playlist, retaining the metadata the
 * casting path needs ([headers]) and the UI uses ([logo]/[groupTitle]). Convert to a
 * [PlayPayload] via [toPlayPayload] when casting.
 */
data class IptvChannel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val groupTitle: String? = null,
    val tvgId: String? = null,
    val order: Int = 0,
    val headers: Map<String, String> = emptyMap(),
) {
    fun toPlayPayload(): PlayPayload = PlayPayload(
        url = url,
        title = name,
        content_type = null,
        detected_by = "iptv_m3u",
        headers = headers,
    )
}

object M3uParser {
    private val http: HttpClient = SharedHttpClient.client
    private val REGEX_RESOLUTION = Regex("""RESOLUTION=(\d+x\d+)""")
    private val REGEX_BANDWIDTH = Regex("""BANDWIDTH=(\d+)""")
    private val REGEX_CODECS = Regex("""CODECS="([^"]+)"""")
    private val REGEX_TVG_ID = Regex("""tvg-id="([^"]*)"""")
    private val REGEX_TVG_LOGO = Regex("""tvg-logo="([^"]*)"""")
    private val REGEX_TVG_NAME = Regex("""tvg-name="([^"]*)"""")
    private val REGEX_GROUP_TITLE = Regex("""group-title="([^"]*)"""")

    /**
     * Pure parser for an IPTV M3U document already in memory (so a local file's contents can
     * reuse the exact same logic as a fetched URL). Captures tvg-logo/group-title/tvg-id and
     * per-channel #EXTVLCOPT headers, merged over [baseHeaders]. Returns null if the text is
     * not an IPTV playlist (e.g. a single-stream HLS manifest).
     */
    fun parseM3uText(
        text: String,
        baseUrl: String? = null,
        baseHeaders: Map<String, String> = emptyMap(),
    ): List<IptvChannel>? {
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext()) return null

        val channels = mutableListOf<IptvChannel>()
        var isFirstLine = true
        var isIptv = false

        var currentTitle: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null
        var currentTvgId: String? = null
        var pendingGroup: String? = null // from a standalone #EXTGRP line
        val currentHeaders = baseHeaders.toMutableMap()

        fun resetCurrent() {
            currentTitle = null
            currentLogo = null
            currentGroup = null
            currentTvgId = null
            currentHeaders.clear()
            currentHeaders.putAll(baseHeaders)
        }

        while (lines.hasNext()) {
            val trimmed = lines.next().trim()

            if (isFirstLine) {
                isFirstLine = false
                if (!trimmed.startsWith("#EXTM3U")) return null
                continue
            }
            if (trimmed.isEmpty()) continue

            // A real HLS manifest, not an IPTV channel list — bail so it plays directly.
            if (trimmed.startsWith("#EXT-X-STREAM-INF") || trimmed.startsWith("#EXT-X-TARGETDURATION")) {
                return null
            }

            when {
                trimmed.startsWith("#EXTINF:") -> {
                    isIptv = true
                    currentTvgId = REGEX_TVG_ID.find(trimmed)?.groupValues?.get(1)?.ifBlank { null }
                    currentLogo = REGEX_TVG_LOGO.find(trimmed)?.groupValues?.get(1)?.ifBlank { null }
                    currentGroup = REGEX_GROUP_TITLE.find(trimmed)?.groupValues?.get(1)?.ifBlank { null }
                        ?: pendingGroup
                    val commaIndex = trimmed.indexOf(',')
                    currentTitle = if (commaIndex != -1 && commaIndex + 1 < trimmed.length) {
                        trimmed.substring(commaIndex + 1).trim().ifBlank { null }
                    } else null
                    if (currentTitle == null) {
                        currentTitle = REGEX_TVG_NAME.find(trimmed)?.groupValues?.get(1)?.ifBlank { null }
                    }
                }
                trimmed.startsWith("#EXTGRP:") -> {
                    pendingGroup = trimmed.substringAfter(':').trim().ifBlank { null }
                    if (currentGroup == null) currentGroup = pendingGroup
                }
                trimmed.startsWith("#EXTVLCOPT:") -> {
                    val opt = trimmed.substringAfter(':').trim()
                    val key = opt.substringBefore('=', "").trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) when (key) {
                        "http-user-agent" -> currentHeaders["User-Agent"] = value
                        "http-referrer" -> currentHeaders["Referer"] = value
                        "http-origin" -> currentHeaders["Origin"] = value
                    }
                }
                trimmed.startsWith("#") -> { /* ignore other tags */ }
                else -> {
                    val streamUrl = if (baseUrl != null) resolveUrl(baseUrl, trimmed) else trimmed
                    channels.add(
                        IptvChannel(
                            name = currentTitle ?: "Channel ${channels.size + 1}",
                            url = streamUrl,
                            logo = currentLogo,
                            groupTitle = currentGroup,
                            tvgId = currentTvgId,
                            order = channels.size,
                            headers = currentHeaders.toMap(),
                        )
                    )
                    resetCurrent()
                }
            }
        }

        return if (isIptv && channels.isNotEmpty()) channels else null
    }

    /** Fetch [url] and parse it into [IptvChannel]s (URL-sourced playlists). */
    suspend fun fetchChannels(url: String, inputHeaders: Map<String, String>?): List<IptvChannel>? =
        withContext(Dispatchers.Default) {
            try {
                val response: HttpResponse = http.get(url) {
                    inputHeaders?.forEach { (k, v) -> headers.append(k, v) }
                }
                if (!response.status.isSuccess()) {
                    logger.w(TAG, "Failed to fetch IPTV playlist: HTTP ${response.status.value}")
                    return@withContext null
                }
                val body = response.bodyAsText()
                parseM3uText(body, baseUrl = url, baseHeaders = inputHeaders ?: emptyMap())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Error fetching IPTV playlist", e)
                null
            }
        }

    suspend fun parseMasterPlaylist(url: String, inputHeaders: Map<String, String>?): List<HlsVariant>? = withContext(Dispatchers.Default) {
        try {
            val response: HttpResponse = http.get(url) {
                inputHeaders?.forEach { (k, v) -> headers.append(k, v) }
            }

            if (!response.status.isSuccess()) {
                logger.w(TAG, "Failed to fetch master playlist: HTTP ${response.status.value}")
                return@withContext null
            }

            val channel = response.bodyAsChannel()
            val variants = mutableListOf<HlsVariant>()
            var isFirstLine = true
            var isMasterPlaylist = false

            var currentResolution: String? = null
            var currentBandwidth: Int? = null
            var currentCodecs: String? = null

            while (true) {
                val line = channel.readUTF8Line() ?: break
                val trimmed = line.trim()

                if (isFirstLine) {
                    isFirstLine = false
                    if (!trimmed.startsWith("#EXTM3U")) {
                        return@withContext null
                    }
                }

                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                    isMasterPlaylist = true
                    currentResolution = REGEX_RESOLUTION.find(trimmed)?.groupValues?.get(1)
                    currentBandwidth = REGEX_BANDWIDTH.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
                    currentCodecs = REGEX_CODECS.find(trimmed)?.groupValues?.get(1)
                } else if (!trimmed.startsWith("#") && currentBandwidth != null) {
                    val streamUrl = resolveUrl(url, trimmed)
                    variants.add(
                        HlsVariant(
                            url = streamUrl,
                            resolution = currentResolution,
                            bandwidth = currentBandwidth,
                            codecs = currentCodecs
                        )
                    )
                    currentResolution = null
                    currentBandwidth = null
                    currentCodecs = null
                }
            }

            return@withContext if (isMasterPlaylist && variants.isNotEmpty()) variants else null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Error parsing master playlist", e)
            return@withContext null
        }
    }

    suspend fun fetchAndParseM3u(url: String, inputHeaders: Map<String, String>?): List<PlayPayload>? = withContext(Dispatchers.Default) {
        try {
            val response: HttpResponse = http.get(url) {
                inputHeaders?.forEach { (k, v) -> headers.append(k, v) }
            }

            if (!response.status.isSuccess()) {
                logger.w(TAG, "Failed to fetch M3U playlist: HTTP ${response.status.value}")
                return@withContext null
            }

            val channel = response.bodyAsChannel()
            val items = mutableListOf<PlayPayload>()
            var isFirstLine = true
            var isIptvPlaylist = false
            var currentTitle: String? = null
            var currentDetectedBy = "m3u_parser"

            while (true) {
                val line = channel.readUTF8Line() ?: break
                val trimmed = line.trim()

                if (isFirstLine) {
                    isFirstLine = false
                    if (!trimmed.startsWith("#EXTM3U")) {
                        logger.d(TAG, "Not an M3U file")
                        return@withContext null
                    }
                }

                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("#EXT-X-STREAM-INF") || trimmed.startsWith("#EXT-X-TARGETDURATION")) {
                    logger.d(TAG, "Detected standard HLS playlist, aborting custom parse")
                    return@withContext null
                }

                if (trimmed.startsWith("#EXTINF:")) {
                    isIptvPlaylist = true
                    val commaIndex = trimmed.indexOf(',')
                    if (commaIndex != -1 && commaIndex + 1 < trimmed.length) {
                        currentTitle = trimmed.substring(commaIndex + 1).trim()
                    }
                } else if (!trimmed.startsWith("#")) {
                    val streamUrl = resolveUrl(url, trimmed)
                    items.add(
                        PlayPayload(
                            url = streamUrl,
                            title = currentTitle ?: "Channel ${items.size + 1}",
                            content_type = null,
                            detected_by = currentDetectedBy,
                            headers = inputHeaders?.toMap() ?: emptyMap()
                        )
                    )
                    currentTitle = null
                }
            }

            return@withContext if (isIptvPlaylist && items.isNotEmpty()) items else null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Error parsing M3U", e)
            return@withContext null
        }
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            val base = Url(baseUrl)
            val relative = Url(relativeUrl)
            if (relative.protocol.name == "http" || relative.protocol.name == "https") {
                relativeUrl
            } else {
                // Manual resolution for relative paths since Ktor Url doesn't have a simple 'resolve'
                if (relativeUrl.startsWith("/")) {
                    "${base.protocol.name}://${base.hostWithPort}${relativeUrl}"
                } else {
                    val lastSlash = base.fullPath.lastIndexOf('/')
                    val path = if (lastSlash != -1) base.fullPath.substring(0, lastSlash + 1) else "/"
                    "${base.protocol.name}://${base.hostWithPort}${path}${relativeUrl}"
                }
            }
        } catch (e: Exception) {
            relativeUrl
        }
    }

    // Helper for adding headers to Ktor request
    private fun header(builder: io.ktor.client.request.HttpRequestBuilder, key: String, value: String) {
        builder.headers.append(key, value)
    }

    private val Url.hostWithPort: String
        get() = if (port == protocol.defaultPort) host else "$host:$port"
}
