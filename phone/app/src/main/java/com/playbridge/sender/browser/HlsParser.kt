package com.playbridge.sender.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

import kotlinx.serialization.Serializable

/**
 * Represents a video quality variant extracted from an HLS master playlist.
 */
@Serializable
data class VideoQuality(
    val resolution: String,
    val bandwidth: Long, // in bits per second
    val url: String,
    val codecs: String? = null
)

/**
 * Parser for HTTP Live Streaming (HLS) playlists (.m3u8).
 * Extracts available video variants/qualities from master playlists.
 */
object HlsParser {

    /**
     * Parses the given M3U8 URL and returns a list of available video qualities.
     * Returns an empty list if parsing fails or no variants are found.
     */
    suspend fun parse(masterPlaylistUrl: String): List<VideoQuality> = withContext(Dispatchers.IO) {
        val qualities = mutableListOf<VideoQuality>()
        try {
            val content = fetchUrlContent(masterPlaylistUrl)
            
            // Basic check for M3U8 format
            if (!content.startsWith("#EXTM3U")) {
                return@withContext emptyList()
            }

            // Split by lines and process
            val lines = content.lines()
            var currentBandwidth: Long? = null
            var currentResolution: String? = null
            var currentCodecs: String? = null

            for (i in lines.indices) {
                val line = lines[i].trim()
                
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    // unexpected format: #EXT-X-STREAM-INF:BANDWIDTH=...,RESOLUTION=...
                    val attributes = line.substringAfter(":")
                    
                    // Parse BANDWIDTH
                    val bandwidthMatch = Regex("BANDWIDTH=(\\d+)").find(attributes)
                    currentBandwidth = bandwidthMatch?.groupValues?.get(1)?.toLongOrNull()
                    
                    // Parse RESOLUTION
                    val resolutionMatch = Regex("RESOLUTION=(\\d+x\\d+)").find(attributes)
                    currentResolution = resolutionMatch?.groupValues?.get(1)
                    
                    // Parse CODECS
                    val codecsMatch = Regex("CODECS=\"([^\"]+)\"").find(attributes)
                    currentCodecs = codecsMatch?.groupValues?.get(1)
                    
                } else if (!line.startsWith("#") && line.isNotEmpty()) {
                    // This is a URI line following a stream info tag
                    if (currentBandwidth != null) {
                        val variantUrl = resolveUrl(masterPlaylistUrl, line)
                        
                        // Create quality label (e.g., "1080p", "720p", or just resolution "1920x1080")
                        val resolutionLabel = currentResolution?.let { res ->
                            val height = res.substringAfter("x")
                            "${height}p"
                        } ?: "Unknown"

                        qualities.add(VideoQuality(
                            resolution = resolutionLabel,
                            bandwidth = currentBandwidth,
                            url = variantUrl,
                            codecs = currentCodecs
                        ))
                        
                        // Reset for next entry
                        currentBandwidth = null
                        currentResolution = null
                        currentCodecs = null
                    }
                }
            }
            
            // Sort by bandwidth descending (highest quality first)
            qualities.sortByDescending { it.bandwidth }
            qualities
            
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun fetchUrlContent(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.instanceFollowRedirects = true
        
        // Some servers require User-Agent
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")

        return connection.inputStream.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }
    }

    /**
     * Resolves a relative URL against a base URL.
     */
    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            val baseUri = URI(baseUrl)
            baseUri.resolve(relativeUrl).toString()
        } catch (e: Exception) {
            relativeUrl // Fallback to original string if resolution fails
        }
    }
}
