package com.playbridge.shared.player

import com.playbridge.shared.logging.logger
import com.playbridge.shared.network.SharedHttpClient
import com.playbridge.shared.protocol.PlayPayload
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
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

object M3uParser {
    private val http: HttpClient = SharedHttpClient.client
    private val REGEX_RESOLUTION = Regex("""RESOLUTION=(\d+x\d+)""")
    private val REGEX_BANDWIDTH = Regex("""BANDWIDTH=(\d+)""")
    private val REGEX_CODECS = Regex("""CODECS="([^"]+)"""")

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
                                contentType = null,
                                detectedBy = currentDetectedBy,
                                headers = inputHeaders?.toMap()
                            )                    )
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
