package com.playbridge.sender.browser

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data class representing a detected video URL
 */
@Serializable
data class DetectedVideo(
    val url: String,
    val tabId: Int = -1,
    val contentType: String? = null,
    val detectedBy: String = "unknown",
    val originUrl: String? = null,
    val headers: Map<String, String>? = null,
    val timestamp: Long = System.currentTimeMillis(),
    var fileSize: Long? = null,  // Will be fetched asynchronously
    var fileSizeChecked: Boolean = false,
    val originalMessage: String? = null,
    var qualities: List<VideoQuality> = emptyList(),
    var qualitiesChecked: Boolean = false,
    var hlsPlaylist: HlsPlaylist? = null,
    var subtitlePreview: String? = null,
    var subtitlePreviewChecked: Boolean = false,
    var isPlayable: Boolean? = null
) {
    val isSubtitle: Boolean
        get() = contentType?.contains("vtt", ignoreCase = true) == true ||
                contentType?.contains("subrip", ignoreCase = true) == true ||
                url.endsWith(".vtt", ignoreCase = true) ||
                url.endsWith(".srt", ignoreCase = true)
}

/**
 * Data class representing an active subtitle period
 */
data class Cue(val startTime: Long, val endTime: Long, val text: String) : Comparable<Cue> {
    override fun compareTo(other: Cue): Int {
        return this.startTime.compareTo(other.startTime)
    }
}

/**
 * Singleton that manages video detection from the WebExtension.
 * Stores detected videos **per Kotlin tab ID** so that each browser tab
 * has its own isolated list of detected videos.
 */
object VideoDetector {
    
    private const val TAG = "VideoDetector"
    
    // Per-tab storage: Kotlin tab ID -> list of detected videos
    private val tabVideos = mutableStateMapOf<String, SnapshotStateList<DetectedVideo>>()
    
    // Per-tab seen URLs to avoid duplicates
    private val tabSeenUrls = mutableMapOf<String, MutableSet<String>>()

    // Track ignored URLs (e.g., HLS variants) — global since variants can appear across tabs
    private val ignoredUrls = mutableSetOf<String>()
    
    /**
     * Get the observable video list for a specific tab.
     * Returns an empty list if no videos have been detected for the tab.
     */
    fun getVideosForTab(tabId: String): List<DetectedVideo> {
        return tabVideos[tabId] ?: emptyList()
    }
    
    /**
     * Get the count of detected videos for a specific tab.
     */
    fun getVideoCountForTab(tabId: String): Int {
        return tabVideos[tabId]?.size ?: 0
    }
    
    /**
     * Process a message received from the video detector extension,
     * associating it with the given Kotlin tab ID.
     */
    fun onMessageReceived(message: JsonObject, kotlinTabId: String) {
        Log.d(TAG, "Received message for tab $kotlinTabId: $message")
        
        val type = message["type"]?.jsonPrimitive?.content
        
        when (type) {
            "video_detected" -> {
                val url = message["url"]?.jsonPrimitive?.content ?: return
                
                // Check if URL is in exact ignore list or starts with an ignored segment prefix
                if (ignoredUrls.contains(url) || ignoredUrls.any { url.startsWith(it) }) {
                    Log.d(TAG, "Ignoring video URL (matched blocklist or segment prefix): $url")
                    return
                }
                
                val headersJson = try { message["headers"]?.jsonObject } catch(e: Exception) { null }
                val headers = headersJson?.mapValues { it.value.jsonPrimitive.content }
                
                // Get or create per-tab structures
                val videos = tabVideos.getOrPut(kotlinTabId) { mutableStateListOf() }
                val seenUrls = tabSeenUrls.getOrPut(kotlinTabId) { mutableSetOf() }
                
                // Check if already exists to update
                val existingIndex = videos.indexOfFirst { it.url == url }
                
                if (existingIndex != -1) {
                    val existing = videos[existingIndex]
                    // If we have new headers, update them
                    if (headers != null && headers.isNotEmpty()) {
                        Log.i(TAG, "Updating headers for video in tab $kotlinTabId: $url")
                        videos[existingIndex] = existing.copy(
                            headers = headers,
                            originUrl = message["originUrl"]?.jsonPrimitive?.content ?: existing.originUrl,
                            contentType = message["contentType"]?.jsonPrimitive?.content ?: existing.contentType,
                            originalMessage = message.toString()
                        )
                    }
                    return
                }
                
                val video = DetectedVideo(
                    url = url,
                    tabId = message["tabId"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
                    contentType = message["contentType"]?.jsonPrimitive?.content,
                    detectedBy = message["detectedBy"]?.jsonPrimitive?.content ?: "unknown",
                    originUrl = message["originUrl"]?.jsonPrimitive?.content,
                    headers = headers,
                    timestamp = message["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                    originalMessage = message.toString()
                )
                
                Log.i(TAG, "VIDEO DETECTED in tab $kotlinTabId: ${video.url}")
                Log.i(TAG, "  Type: ${video.contentType ?: "N/A"}")
                Log.i(TAG, "  Header Count: ${video.headers?.size ?: 0}")
                
                seenUrls.add(url)
                videos.add(video)
            }
            else -> {
                Log.w(TAG, "Unknown message type: $type")
            }
        }
    }

    /**
     * Legacy overload — routes to "unknown" tab. Prefer the tab-aware overload.
     */
    fun onMessageReceived(message: JsonObject) {
        onMessageReceived(message, "_unknown")
    }
    
    /**
     * Fetch file size for a video URL using HEAD request
     */
    suspend fun fetchFileSize(video: DetectedVideo): Long? {
        if (video.fileSizeChecked) {
            return video.fileSize
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(video.url)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = true
                
                // Set user agent and apply captured headers (crucial for auth/cookies)
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                video.headers?.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                }
                
                connection.connect()
                
                val contentLength = connection.contentLengthLong
                val responseCode = connection.responseCode
                connection.disconnect()
                
                if (responseCode in 200..299) {
                    video.isPlayable = true
                    video.fileSize = if (contentLength > 0) contentLength else null
                } else {
                    video.isPlayable = false
                    Log.w(TAG, "Video unplayable: HTTP $responseCode for ${video.url}")
                }
                
                video.fileSizeChecked = true
                
                Log.d(TAG, "File size for ${video.url.take(50)}: ${video.fileSize ?: "unknown"}")
                
                video.fileSize
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching file size: ${e.message}")
                video.fileSizeChecked = true
                null
            }
        }
    }
    
    /**
     * Fetch a small preview of a subtitle file to help identify language
     */
    suspend fun fetchSubtitlePreview(video: DetectedVideo): String? {
        if (video.subtitlePreviewChecked) {
            return video.subtitlePreview
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(video.url)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.instanceFollowRedirects = true
                
                // Set Range header to fetch only first 4KB to ensure we get a few cues
                connection.setRequestProperty("Range", "bytes=0-4096")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                
                connection.connect()
                
                // Check if response is partial content (206) or OK (200)
                if (connection.responseCode in 200..299) {
                    val content = connection.inputStream.bufferedReader().use { it.readText() }
                    
                    if (content.isNotEmpty()) {
                        val cues = if (video.url.endsWith(".vtt", ignoreCase = true) || 
                                     video.contentType?.contains("vtt", ignoreCase = true) == true) {
                            parseVtt(content)
                        } else {
                            parseSrt(content)
                        }
                        
                        // Extract first 3 cues
                        val previewText = cues.take(3).joinToString(" • ") { 
                            it.text.replace("\n", " ") 
                        }
                        
                        if (previewText.isNotEmpty()) {
                            video.subtitlePreview = previewText
                        } else {
                            // Fallback if parsing failed but we got text
                            val fallbackLines = content.lineSequence().map { it.trim() }.filter { trimmed ->
                                trimmed.isNotEmpty() &&
                                !trimmed.contains("WEBVTT", ignoreCase = true) &&
                                !trimmed.contains("-->") &&
                                trimmed.toIntOrNull() == null
                            }.take(2).toList()
                            video.subtitlePreview = fallbackLines.joinToString(" • ")
                        }
                        
                        video.subtitlePreviewChecked = true
                        Log.d(TAG, "Subtitle preview for ${video.url.take(30)}: ${video.subtitlePreview}")
                        video.subtitlePreview
                    } else {
                        video.subtitlePreviewChecked = true
                        null
                    }
                } else {
                    connection.disconnect()
                    video.subtitlePreviewChecked = true
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching subtitle preview: ${e.message}")
                video.subtitlePreviewChecked = true
                null
            }
        }
    }
    
    // Subtitle parsing helpers copied from TV's SubtitleManager
    
    private fun parseSrt(content: String): List<Cue> {
        val parsedCues = ArrayList<Cue>()
        val normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalizedContent.split("\n\n")
        
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.size >= 2) {
                val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
                if (timeLineIndex != -1) {
                     val timeLine = lines[timeLineIndex]
                     val textLines = lines.subList(timeLineIndex + 1, lines.size)
                     val text = textLines.joinToString("\n")
                     
                     val times = timeLine.split("-->")
                     if (times.size == 2) {
                         val start = parseTimestamp(times[0].trim().replace(',', '.'))
                         val end = parseTimestamp(times[1].trim().replace(',', '.'))
                         if (start != -1L && end != -1L) {
                             parsedCues.add(Cue(start, end, text))
                         }
                     }
                }
            }
        }
        return parsedCues
    }

    private fun parseVtt(content: String): List<Cue> {
        val parsedCues = ArrayList<Cue>()
        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.contains("-->")) {
                val times = line.split("-->")
                if (times.size == 2) {
                    val start = parseTimestamp(times[0].trim())
                    val end = parseTimestamp(times[1].trim())
                    
                    val textBuilder = StringBuilder()
                    i++
                    while (i < lines.size && lines[i].isNotBlank()) {
                         textBuilder.append(lines[i]).append("\n")
                         i++
                    }
                    val text = textBuilder.toString().trim()
                    
                    if (start != -1L && end != -1L && text.isNotEmpty()) {
                        parsedCues.add(Cue(start, end, text))
                    }
                    continue
                }
            }
            i++
        }
        return parsedCues
    }

    private fun parseTimestamp(timestamp: String): Long {
        return try {
            val parts = timestamp.split(':')
            var hours = 0L
            var minutes = 0L
            var seconds = 0.0
            
            if (parts.size == 3) {
                hours = parts[0].toLong()
                minutes = parts[1].toLong()
                seconds = parts[2].replace(',', '.').toDouble()
            } else if (parts.size == 2) {
                minutes = parts[0].toLong()
                seconds = parts[1].replace(',', '.').toDouble()
            } else {
                return -1
            }
            
            (hours * 3600000 + minutes * 60000 + (seconds * 1000)).toLong()
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * Fetch HLS variant qualities if the video is an m3u8 playlist.
     * Operates on a specific tab's video list for variant cleanup.
     */
    suspend fun fetchHlsQualities(video: DetectedVideo, kotlinTabId: String? = null): List<VideoQuality> {
        if (video.qualitiesChecked) {
            return video.qualities
        }

        return withContext(Dispatchers.IO) {
            try {
                // simple check if it looks like an m3u8 url
                if (video.url.contains(".m3u8", ignoreCase = true) || 
                    video.contentType?.contains("mpegurl", ignoreCase = true) == true) {
                    
                    val playlist = HlsParser.parsePlaylist(video.url)
                    video.hlsPlaylist = playlist
                    video.qualities = playlist.videoQualities
                    video.qualitiesChecked = true
                    
                    if (playlist.segmentPrefixes.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            ignoredUrls.addAll(playlist.segmentPrefixes)
                            
                            // Remove any already detected videos that are actually segments
                            val prefixes = playlist.segmentPrefixes
                            if (kotlinTabId != null) {
                                tabVideos[kotlinTabId]?.removeAll { detected ->
                                    prefixes.any { detected.url.startsWith(it) }
                                }
                            } else {
                                for ((_, videos) in tabVideos) {
                                    videos.removeAll { detected ->
                                        prefixes.any { detected.url.startsWith(it) }
                                    }
                                }
                            }
                        }
                    }

                    if (playlist.videoQualities.isNotEmpty()) {
                        video.isPlayable = true
                        withContext(Dispatchers.Main) {
                            // Add variants to ignore list
                            playlist.videoQualities.forEach { quality ->
                                ignoredUrls.add(quality.url)
                            }
                            
                            // Remove any already detected videos that are actually variants
                            if (kotlinTabId != null) {
                                val videos = tabVideos[kotlinTabId]
                                videos?.removeAll { detected ->
                                    ignoredUrls.contains(detected.url)
                                }
                            } else {
                                // Clean across all tabs
                                for ((_, videos) in tabVideos) {
                                    videos.removeAll { detected ->
                                        ignoredUrls.contains(detected.url)
                                    }
                                }
                            }
                        }
                    } else if (playlist.segmentPrefixes.isNotEmpty()) {
                        // It's a media playlist directly (no variants), so it is playable
                        video.isPlayable = true
                    }
                    
                    Log.d(TAG, "Fetched ${playlist.videoQualities.size} qualities for ${video.url}")
                    playlist.videoQualities

                } else {
                    video.qualitiesChecked = true
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching HLS qualities for ${video.url}: ${e.message}")
                video.isPlayable = false
                video.qualitiesChecked = true
                emptyList()
            }
        }
    }

    /**
     * Clear detected videos for a specific tab.
     */
    fun clearTab(tabId: String) {
        Log.d(TAG, "Clearing videos for tab $tabId (had ${tabVideos[tabId]?.size ?: 0})")
        tabVideos.remove(tabId)
        tabSeenUrls.remove(tabId)
    }

    /**
     * Clear all detected videos across all tabs.
     */
    fun clear() {
        Log.d(TAG, "Clearing all detected videos across ${tabVideos.size} tabs")
        tabVideos.clear()
        tabSeenUrls.clear()
        ignoredUrls.clear()
    }
    
    /**
     * Get count of detected videos across all tabs (for debugging).
     */
    fun getVideoCount(): Int = tabVideos.values.sumOf { it.size }
}
