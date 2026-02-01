package com.playbridge.sender.browser

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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
    val timestamp: Long = System.currentTimeMillis(),
    var fileSize: Long? = null,  // Will be fetched asynchronously
    var fileSizeChecked: Boolean = false
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
    
    /**
     * Process a message received from the video detector extension
     */
    fun onMessageReceived(message: JsonObject) {
        Log.d(TAG, "Received message from extension: $message")
        
        val type = message["type"]?.jsonPrimitive?.content
        
        when (type) {
            "video_detected" -> {
                val url = message["url"]?.jsonPrimitive?.content ?: return
                
                // Skip duplicates
                if (seenUrls.contains(url)) {
                    Log.d(TAG, "Skipping duplicate video: $url")
                    return
                }
                
                val video = DetectedVideo(
                    url = url,
                    tabId = message["tabId"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1,
                    contentType = message["contentType"]?.jsonPrimitive?.content,
                    detectedBy = message["detectedBy"]?.jsonPrimitive?.content ?: "unknown",
                    originUrl = message["originUrl"]?.jsonPrimitive?.content,
                    timestamp = message["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                )
                
                Log.i(TAG, "VIDEO DETECTED: ${video.url}")
                Log.i(TAG, "  Type: ${video.contentType ?: "N/A"}")
                Log.i(TAG, "  Detected by: ${video.detectedBy}")
                Log.i(TAG, "  Origin: ${video.originUrl ?: "N/A"}")
                
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
     * Clear all detected videos
     */
    fun clear() {
        Log.d(TAG, "Clearing ${detectedVideos.size} detected videos")
        detectedVideos.clear()
        seenUrls.clear()
    }
    
    /**
     * Get count of detected videos
     */
    fun getVideoCount(): Int = detectedVideos.size
}
