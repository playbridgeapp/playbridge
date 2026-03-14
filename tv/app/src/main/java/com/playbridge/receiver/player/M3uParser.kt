package com.playbridge.receiver.player

import com.playbridge.protocol.PlayPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI

private const val TAG = "M3uParser"

data class HlsVariant(
    val url: String,
    val resolution: String?,
    val bandwidth: Int?,
    val codecs: String?,
    val audioUrl: String? = null
)

object M3uParser {

    suspend fun parseMasterPlaylist(url: String, headers: Map<String, String>?): List<HlsVariant>? = withContext(Dispatchers.IO) {
        try {
            val sniffer = ContentSniffer()
            val client = sniffer.getUnsafeOkHttpClient(headers)
            val requestBuilder = okhttp3.Request.Builder().url(url)

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch master playlist: HTTP ${response.code}")
                response.close()
                return@withContext null
            }

            val body = response.body
            if (body == null) {
                response.close()
                return@withContext null
            }

            val variants = mutableListOf<HlsVariant>()
            var isFirstLine = true
            var isMasterPlaylist = false

            var currentResolution: String? = null
            var currentBandwidth: Int? = null
            var currentCodecs: String? = null
            var currentAudioGroup: String? = null

            val audioGroups = mutableMapOf<String, String>() // GROUP-ID to URI mapping

            BufferedReader(InputStreamReader(body.byteStream())).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()

                    if (isFirstLine) {
                        isFirstLine = false
                        if (!trimmed.startsWith("#EXTM3U")) {
                            return@use
                        }
                    }

                    if (trimmed.isEmpty()) {
                        line = reader.readLine()
                        continue
                    }

                    if (trimmed.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                        // Parse audio groups
                        isMasterPlaylist = true
                        val groupIdMatch = Regex("""GROUP-ID="([^"]+)"""").find(trimmed)
                        val uriMatch = Regex("""URI="([^"]+)"""").find(trimmed)

                        val groupId = groupIdMatch?.groupValues?.get(1)
                        val uri = uriMatch?.groupValues?.get(1)

                        if (groupId != null && uri != null) {
                            val absoluteUri = try {
                                val parsedUri = URI(uri)
                                if (parsedUri.isAbsolute) uri else URI(url).resolve(parsedUri).toString()
                            } catch (e: Exception) {
                                uri
                            }
                            // Store mapping of group id to the absolute audio stream URL
                            audioGroups[groupId] = absoluteUri
                        }
                    } else if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                        isMasterPlaylist = true

                        // Parse attributes
                        val resMatch = Regex("""RESOLUTION=(\d+x\d+)""").find(trimmed)
                        currentResolution = resMatch?.groupValues?.get(1)

                        val bwMatch = Regex("""BANDWIDTH=(\d+)""").find(trimmed)
                        currentBandwidth = bwMatch?.groupValues?.get(1)?.toIntOrNull()

                        val codecsMatch = Regex("""CODECS="([^"]+)"""").find(trimmed)
                        currentCodecs = codecsMatch?.groupValues?.get(1)

                        val audioMatch = Regex("""AUDIO="([^"]+)"""").find(trimmed)
                        currentAudioGroup = audioMatch?.groupValues?.get(1)

                    } else if (!trimmed.startsWith("#") && currentBandwidth != null) {
                        // It's a URI for the stream inf
                        val streamUrl = try {
                            val uri = URI(trimmed)
                            if (uri.isAbsolute) {
                                trimmed
                            } else {
                                URI(url).resolve(uri).toString()
                            }
                        } catch (e: Exception) {
                            trimmed
                        }

                        val resolvedAudioUrl = currentAudioGroup?.let { audioGroups[it] }

                        variants.add(
                            HlsVariant(
                                url = streamUrl,
                                resolution = currentResolution,
                                bandwidth = currentBandwidth,
                                codecs = currentCodecs,
                                audioUrl = resolvedAudioUrl
                            )
                        )

                        // Reset properties for next stream inf
                        currentResolution = null
                        currentBandwidth = null
                        currentCodecs = null
                        currentAudioGroup = null
                    }
                    line = reader.readLine()
                }
            }

            return@withContext if (isMasterPlaylist && variants.isNotEmpty()) variants else null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing master playlist", e)
            return@withContext null
        }
    }

    suspend fun fetchAndParseM3u(url: String, headers: Map<String, String>?): List<PlayPayload>? = withContext(Dispatchers.IO) {
        try {
            val sniffer = ContentSniffer()
            val client = sniffer.getUnsafeOkHttpClient(headers)
            val requestBuilder = okhttp3.Request.Builder().url(url)

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch M3U playlist: HTTP ${response.code}")
                response.close()
                return@withContext null
            }

            val body = response.body
            if (body == null) {
                response.close()
                return@withContext null
            }

            val items = mutableListOf<PlayPayload>()
            var isFirstLine = true
            var isIptvPlaylist = false
            var currentTitle: String? = null
            var currentDetectedBy = "m3u_parser"

            BufferedReader(InputStreamReader(body.byteStream())).use { reader ->
                var line: String? = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()

                    if (isFirstLine) {
                        isFirstLine = false
                        if (!trimmed.startsWith("#EXTM3U")) {
                            Log.d(TAG, "Not an M3U file")
                            return@use
                        }
                    }

                    if (trimmed.isEmpty()) {
                        line = reader.readLine()
                        continue
                    }

                    // Standard HLS check - abort if we find standard HLS tags because ExoPlayer handles those
                    if (trimmed.startsWith("#EXT-X-STREAM-INF") || trimmed.startsWith("#EXT-X-TARGETDURATION")) {
                        Log.d(TAG, "Detected standard HLS playlist, aborting custom parse")
                        items.clear()
                        return@use
                    }

                    if (trimmed.startsWith("#EXTINF:")) {
                        isIptvPlaylist = true
                        val commaIndex = trimmed.indexOf(',')
                        if (commaIndex != -1 && commaIndex + 1 < trimmed.length) {
                            currentTitle = trimmed.substring(commaIndex + 1).trim()
                        }
                    } else if (!trimmed.startsWith("#")) {
                        // It's a URL
                        val streamUrl = try {
                            val uri = URI(trimmed)
                            if (uri.isAbsolute) {
                                trimmed
                            } else {
                                // Resolve relative URL properly
                                URI(url).resolve(uri).toString()
                            }
                        } catch (e: Exception) {
                            trimmed // fallback to original string if URI parsing fails
                        }

                        items.add(
                            PlayPayload(
                                url = streamUrl,
                                title = currentTitle ?: "Channel ${items.size + 1}",
                                contentType = null,
                                detectedBy = currentDetectedBy,
                                headers = headers?.toMap()
                            )
                        )
                        currentTitle = null // Reset for next item
                    }
                    line = reader.readLine()
                }
            }

            // Return items only if we detected an IPTV playlist structure
            return@withContext if (isIptvPlaylist && items.isNotEmpty()) items else null

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing M3U", e)
            return@withContext null
        }
    }
}
