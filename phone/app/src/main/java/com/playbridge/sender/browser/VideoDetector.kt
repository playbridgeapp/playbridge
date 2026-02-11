package com.playbridge.sender.browser

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
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
    var hlsPlaylist: HlsPlaylist? = null
)

/**
 * Singleton that manages video detection from the WebExtension.
 * Receives native messages from the video_detector extension and stores detected videos.
 */
object VideoDetector {
    
    private const val TAG = "VideoDetector"
    
    // Observable list of detected videos
    val detectedVideos = mutableStateListOf<DetectedVideo>()
    
    // Track videos by URL to avoid duplicates
    private val seenUrls = mutableSetOf<String>()

    // Track ignored URLs (e.g., HLS variants)
    private val ignoredUrls = mutableSetOf<String>()
    
    /**
     * Process a message received from the video detector extension
     */
    fun onMessageReceived(message: JsonObject) {
        Log.d(TAG, "Received message from extension: $message")
        
        val type = message["type"]?.jsonPrimitive?.content
        
        when (type) {
            "video_detected" -> {
                val url = message["url"]?.jsonPrimitive?.content ?: return
                
                // Check if URL is ignored
                if (ignoredUrls.contains(url)) {
                    Log.d(TAG, "Ignoring video URL: $url")
                    return
                }
                
                val headersJson = try { message["headers"]?.jsonObject } catch(e: Exception) { null }
                val headers = headersJson?.mapValues { it.value.jsonPrimitive.content }
                
                 // Check if already exists to update
                val existingIndex = detectedVideos.indexOfFirst { it.url == url }
                
                if (existingIndex != -1) {
                     val existing = detectedVideos[existingIndex]
                     // If we have new headers, update them
                     if (headers != null && headers.isNotEmpty()) {
                         Log.i(TAG, "Updating headers for video: $url")
                         detectedVideos[existingIndex] = existing.copy(
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
                
                Log.i(TAG, "VIDEO DETECTED: ${video.url}")
                Log.i(TAG, "  Type: ${video.contentType ?: "N/A"}")
                Log.i(TAG, "  Header Count: ${video.headers?.size ?: 0}")
                
                seenUrls.add(url)
                detectedVideos.add(video)
            }
            else -> {
                Log.w(TAG, "Unknown message type: $type")
            }
        }
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
                
                // Set user agent to avoid being blocked
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                
                connection.connect()
                
                val contentLength = connection.contentLengthLong
                connection.disconnect()
                
                video.fileSize = if (contentLength > 0) contentLength else null
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
     * Fetch HLS variant qualities if the video is an m3u8 playlist
     */
    suspend fun fetchHlsQualities(video: DetectedVideo): List<VideoQuality> {
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
                    
                    if (playlist.videoQualities.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            // Add variants to ignore list
                            playlist.videoQualities.forEach { quality ->
                                ignoredUrls.add(quality.url)
                            }
                            
                            // Remove any already detected videos that are actually variants
                            val removed = detectedVideos.removeAll { detected ->
                                ignoredUrls.contains(detected.url)
                            }
                            
                            if (removed) {
                                Log.d(TAG, "Removed detected videos that were actually variants")
                            }
                        }
                    }
                    
                    Log.d(TAG, "Fetched ${playlist.videoQualities.size} qualities for ${video.url}")
                    playlist.videoQualities

                } else {
                    video.qualitiesChecked = true
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching HLS qualities: ${e.message}")
                video.qualitiesChecked = true
                emptyList()
            }
        }
    }

    /**
     * Clear all detected videos
     */
    fun clear() {
        Log.d(TAG, "Clearing ${detectedVideos.size} detected videos")
        detectedVideos.clear()
        seenUrls.clear()
        ignoredUrls.clear()
    }
    
    /**
     * Get count of detected videos
     */
    fun getVideoCount(): Int = detectedVideos.size
}
