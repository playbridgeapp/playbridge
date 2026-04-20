package com.playbridge.sender.browser

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    var isPlayable: Boolean? = null,
    val playlistPayload: List<com.playbridge.shared.protocol.PlayPayload>? = null,
    val title: String? = null
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

    private var appContext: Context? = null

    /** Call once from Application or Activity.onCreate so HLS thumbnail extraction has a cache dir. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Headers that are browser-context-specific and must not be forwarded to media players.
     * Sending `sec-fetch-site: same-origin` to a CDN (different domain) causes the CDN to
     * reject segment requests. `sec-ch-ua-*` are client-hint fingerprinting headers that
     * media players should never send.
     */
    val PLAYER_SKIP_HEADERS: Set<String> = setOf(
        "Range", "Accept-Encoding", "Host", "Connection", "Content-Length",
        "Sec-Fetch-Dest", "Sec-Fetch-Mode", "Sec-Fetch-Site", "Sec-Fetch-Storage-Access",
        "Sec-GPC", "Sec-CH-UA", "Sec-CH-UA-Mobile", "Sec-CH-UA-Platform",
        "Priority", "Upgrade-Insecure-Requests", "TE", "Pragma"
    )

    /** Returns the cached thumbnail for [url], or null if not yet fetched. */
    fun getCachedThumbnail(url: String): Bitmap? = synchronized(thumbnailCache) { thumbnailCache[url] }

    /** Returns true if a thumbnail has already been fetched and cached for this URL. */
    fun hasThumbnail(url: String): Boolean = synchronized(thumbnailCache) { thumbnailCache.containsKey(url) }

    /** Returns a headers map safe to pass to ExoPlayer or MediaMetadataRetriever. */
    fun mediaHeaders(video: DetectedVideo): HashMap<String, String> {
        val result = HashMap<String, String>()
        video.headers?.forEach { (k, v) ->
            if (PLAYER_SKIP_HEADERS.none { it.equals(k, ignoreCase = true) }) result[k] = v
        }
        if (result.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            result["User-Agent"] =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
        return result
    }

    // Per-tab storage: Kotlin tab ID -> list of detected videos
    private val tabVideos = mutableStateMapOf<String, SnapshotStateList<DetectedVideo>>()

    // Thumbnail cache: URL -> Bitmap (max 20 entries, LRU eviction)
    private val thumbnailCache: LinkedHashMap<String, Bitmap> =
        object : LinkedHashMap<String, Bitmap>(20, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Bitmap>) = size > 20
        }

    // Per-tab seen URLs to avoid duplicates
    private val tabSeenUrls = mutableMapOf<String, MutableSet<String>>()

    // Track ignored URLs (e.g., HLS variants) — global since variants can appear across tabs
    private val ignoredUrls = mutableSetOf<String>()

    /**
     * Incremented on the main thread whenever a video's playability or quality status changes.
     * Observed inside derivedStateOf in BrowserActivity so the video list re-derives and the
     * sheet's sort order updates automatically without the user closing and reopening the sheet.
     */
    var processingVersion by mutableStateOf(0)
        private set

    /** Must be called from the main thread after updating any video's sort-relevant fields. */
    private fun notifyVideoUpdated() { processingVersion++ }

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
                notifyVideoUpdated()
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

                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                video.headers?.forEach { (key, value) ->
                    if (!key.equals("Range", ignoreCase = true)) {
                        connection.setRequestProperty(key, value)
                    }
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

                withContext(Dispatchers.Main) { notifyVideoUpdated() }
                video.fileSize
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching file size: ${e.message}")
                video.fileSizeChecked = true
                withContext(Dispatchers.Main) { notifyVideoUpdated() }
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
        var currentStart = -1L
        var currentEnd = -1L
        val currentText = StringBuilder()

        // Use lineSequence() for O(1) memory parsing
        val iterator = content.lineSequence().iterator()
        while (iterator.hasNext()) {
            val rawLine = iterator.next()
            val trimmedLine = rawLine.trim()

            if (trimmedLine.isEmpty()) {
                if (currentStart != -1L && currentEnd != -1L && currentText.isNotEmpty()) {
                    parsedCues.add(Cue(currentStart, currentEnd, currentText.toString().trimEnd()))
                }
                currentStart = -1L
                currentEnd = -1L
                currentText.clear()
            } else if (trimmedLine.contains("-->")) {
                val times = trimmedLine.split("-->")
                if (times.size == 2) {
                    currentStart = parseTimestamp(times[0].trim().replace(',', '.'))
                    currentEnd = parseTimestamp(times[1].trim().replace(',', '.'))
                }
            } else if (currentStart != -1L) {
                // If we have a start time, any subsequent non-empty line is part of the text
                currentText.append(rawLine).append("\n")
            }
        }
        // Add final cue if file doesn't end with blank line
        if (currentStart != -1L && currentEnd != -1L && currentText.isNotEmpty()) {
            parsedCues.add(Cue(currentStart, currentEnd, currentText.toString().trimEnd()))
        }
        return parsedCues
    }

    private fun parseVtt(content: String): List<Cue> {
        val parsedCues = ArrayList<Cue>()

        // Use lineSequence() for O(1) memory parsing
        val iterator = content.lineSequence().iterator()
        while (iterator.hasNext()) {
            val line = iterator.next().trim()
            if (line.contains("-->")) {
                val times = line.split("-->")
                if (times.size == 2) {
                    val start = parseTimestamp(times[0].trim())
                    val end = parseTimestamp(times[1].trim())

                    val textBuilder = StringBuilder()
                    while (iterator.hasNext()) {
                        val textLine = iterator.next()
                        if (textLine.trim().isEmpty()) break
                        textBuilder.append(textLine).append("\n")
                    }
                    val text = textBuilder.toString().trim()

                    if (start != -1L && end != -1L && text.isNotEmpty()) {
                        parsedCues.add(Cue(start, end, text))
                    }
                }
            }
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
                // DASH/MPD
                if (video.url.contains(".mpd", ignoreCase = true) ||
                    video.contentType?.contains("dash", ignoreCase = true) == true) {

                    val qualities = DashParser.parseManifest(video.url, video.headers)
                    video.qualities = qualities
                    video.qualitiesChecked = true
                    if (qualities.isNotEmpty()) video.isPlayable = true
                    Log.d(TAG, "Fetched ${qualities.size} DASH qualities for ${video.url}")
                    return@withContext qualities
                }

                // simple check if it looks like an m3u8 url
                if (video.url.contains(".m3u8", ignoreCase = true) ||
                    video.contentType?.contains("mpegurl", ignoreCase = true) == true) {

                    val playlist = HlsParser.parsePlaylist(video.url, video.headers)
                    video.hlsPlaylist = playlist
                    video.qualities = playlist.videoQualities
                    video.qualitiesChecked = true

                    if (playlist.segmentPrefixes.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            // Only add prefixes that don't accidentally match the master playlist
                            // URL itself (e.g. stream.m3u8 is in the same directory as segments).
                            val safeSegmentPrefixes = playlist.segmentPrefixes
                                .filter { !video.url.startsWith(it) }
                            ignoredUrls.addAll(safeSegmentPrefixes)

                            if (kotlinTabId != null) {
                                val prefixes = playlist.segmentPrefixes
                                tabVideos[kotlinTabId]?.removeAll { detected ->
                                    detected.url != video.url &&
                                    prefixes.any { detected.url.startsWith(it) }
                                }
                            }
                        }
                    }

                    if (playlist.videoQualities.isNotEmpty()) {
                        video.isPlayable = true
                        withContext(Dispatchers.Main) {
                            // Add variants to ignore list so future detections are filtered
                            playlist.videoQualities.forEach { quality ->
                                ignoredUrls.add(quality.url)
                            }

                            // Only remove existing items when we have a confirmed tab ID.
                            if (kotlinTabId != null) {
                                tabVideos[kotlinTabId]?.removeAll { detected ->
                                    ignoredUrls.contains(detected.url)
                                }
                            }
                        }
                    } else if (playlist.segmentPrefixes.isNotEmpty()) {
                        // It's a media playlist directly (no variants), so it is playable
                        video.isPlayable = true
                    }

                    Log.d(TAG, "Fetched ${playlist.videoQualities.size} qualities for ${video.url}")
                    withContext(Dispatchers.Main) { notifyVideoUpdated() }
                    playlist.videoQualities

                } else {
                    video.qualitiesChecked = true
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching HLS qualities for ${video.url}: ${e.message}")
                video.isPlayable = false
                video.qualitiesChecked = true
                withContext(Dispatchers.Main) { notifyVideoUpdated() }
                emptyList()
            }
        }
    }

    /**
     * Fetch a video thumbnail. Routes to HLS-aware extraction for m3u8 streams (downloads a TS
     * segment and runs MMR locally) or falls back to direct MMR for progressive files.
     */
    suspend fun fetchThumbnail(video: DetectedVideo): Bitmap? {
        if (video.isSubtitle) return null
        if (video.url.startsWith("data:", ignoreCase = true)) return null
        synchronized(thumbnailCache) { thumbnailCache[video.url]?.let { return it } }

        return withContext(Dispatchers.IO) {
            val isHls = video.url.contains(".m3u8", ignoreCase = true) ||
                        video.contentType?.contains("mpegurl", ignoreCase = true) == true
            val bmp: Bitmap? = if (isHls && appContext != null) {
                fetchHlsThumbnail(video)
            } else {
                fetchMmrThumbnail(video)
            }
            if (bmp != null) {
                video.isPlayable = true
                synchronized(thumbnailCache) { thumbnailCache[video.url] = bmp }
            }
            withContext(Dispatchers.Main) { notifyVideoUpdated() }
            bmp
        }
    }

    /**
     * Direct MMR thumbnail for progressive video files (MP4, MKV, WebM, etc.).
     * Runs on a raw Thread because MediaMetadataRetriever.setDataSource is a JNI blocking call
     * that cannot be interrupted by coroutine cancellation.
     */
    private fun fetchMmrThumbnail(video: DetectedVideo): Bitmap? {
        var result: Bitmap? = null
        var exception: Exception? = null
        val latch = CountDownLatch(1)
        Thread {
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.url, mediaHeaders(video))
                    val durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    val seekUs = if (durationMs > 8_000L) {
                        val windowStartUs = (durationMs * 0.25).toLong() * 1_000L
                        val windowEndUs   = (durationMs * 0.75).toLong() * 1_000L
                        windowStartUs + (Math.random() * (windowEndUs - windowStartUs)).toLong()
                    } else {
                        1_000_000L
                    }
                    result = retriever.getFrameAtTime(seekUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            } catch (e: Exception) {
                exception = e
            }
            latch.countDown()
        }.start()

        val completed = latch.await(12L, TimeUnit.SECONDS)
        return when {
            !completed -> {
                Log.w(TAG, "MMR thumbnail timed out for ${video.url.take(60)}")
                null
            }
            exception != null -> {
                Log.w(TAG, "MMR thumbnail failed for ${video.url.take(60)}: ${exception!!.message}")
                video.isPlayable = false
                null
            }
            else -> {
                if (result != null) Log.d(TAG, "MMR thumbnail fetched for ${video.url.take(60)}")
                result
            }
        }
    }

    /**
     * HLS thumbnail extraction:
     * 1. Fetch the media playlist to get segment URLs.
     * 2. Download ~3 MB of a segment from around the 25% mark.
     * 3. Run MMR on the local .ts file — MMR handles MPEG-TS reliably without needing to speak HLS.
     */
    private fun fetchHlsThumbnail(video: DetectedVideo): Bitmap? {
        val ctx = appContext ?: return null

        // If fetchHlsQualities already ran and found variant playlists, use the lowest-bandwidth
        // variant's media playlist URL to save bandwidth. Otherwise video.url is the playlist itself.
        val mediaPlaylistUrl: String = run {
            val playlist = video.hlsPlaylist
            if (playlist != null && playlist.videoQualities.isNotEmpty()) {
                playlist.videoQualities.minByOrNull { it.bandwidth }?.url ?: video.url
            } else {
                video.url
            }
        }

        val segmentUrls = fetchMediaSegmentUrls(mediaPlaylistUrl, video.headers)
        if (segmentUrls.isNullOrEmpty()) {
            Log.w(TAG, "HLS thumbnail: no segments found in $mediaPlaylistUrl")
            return null
        }

        // ~25% into the segment list for a mid-stream frame (avoids intros)
        val targetIndex = ((segmentUrls.size - 1) * 0.25).toInt()
        val segmentUrl = segmentUrls[targetIndex]
        Log.d(TAG, "HLS thumbnail: segment [${targetIndex + 1}/${segmentUrls.size}]")

        val tempFile = File.createTempFile("playbridge_thumb_", ".ts", ctx.cacheDir)
        return try {
            if (!downloadSegmentToFile(segmentUrl, video.headers, tempFile)) {
                Log.w(TAG, "HLS thumbnail: segment download failed")
                return null
            }

            var result: Bitmap? = null
            val latch = CountDownLatch(1)
            Thread {
                try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(tempFile.absolutePath)
                        val durationMs = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                        val seekUs = if (durationMs > 2_000L) (durationMs / 2 * 1_000L) else 500_000L
                        result = retriever.getFrameAtTime(seekUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally {
                        retriever.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "HLS segment MMR failed: ${e.message}")
                }
                latch.countDown()
            }.start()

            if (!latch.await(10L, TimeUnit.SECONDS)) {
                Log.w(TAG, "HLS segment thumbnail timed out")
            }
            if (result != null) Log.d(TAG, "HLS thumbnail fetched for ${video.url.take(60)}")
            result
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Fetches a media (.m3u8) playlist and returns all segment URLs in order.
     * Automatically recurses into a master playlist's first variant if needed.
     */
    private fun fetchMediaSegmentUrls(mediaPlaylistUrl: String, headers: Map<String, String>?): List<String>? {
        return try {
            val conn = URL(mediaPlaylistUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            headers?.forEach { (k, v) ->
                if (!k.equals("Range", ignoreCase = true) &&
                    PLAYER_SKIP_HEADERS.none { it.equals(k, ignoreCase = true) }) {
                    conn.setRequestProperty(k, v)
                }
            }
            if (headers?.keys?.none { it.equals("User-Agent", ignoreCase = true) } != false) {
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            }
            val content = conn.inputStream.use { BufferedReader(InputStreamReader(it)).readText() }
            if (!content.startsWith("#EXTM3U")) return null

            val baseUri = URI(mediaPlaylistUrl)
            // If this is a master playlist, recurse into its first variant
            if (content.contains("#EXT-X-STREAM-INF")) {
                val variantUrl = content.lineSequence()
                    .dropWhile { !it.startsWith("#EXT-X-STREAM-INF") }
                    .drop(1)
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                    ?.let { baseUri.resolve(it).toString() }
                    ?: return null
                return fetchMediaSegmentUrls(variantUrl, headers)
            }

            content.lineSequence()
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { baseUri.resolve(it).toString() }
                .toList()
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "fetchMediaSegmentUrls failed for $mediaPlaylistUrl: ${e.message}")
            null
        }
    }

    /**
     * Downloads up to [maxBytes] of [segmentUrl] into [outFile].
     * Returns true if the file is non-empty after the download.
     */
    private fun downloadSegmentToFile(
        segmentUrl: String,
        headers: Map<String, String>?,
        outFile: File,
        maxBytes: Int = 3 * 1024 * 1024
    ): Boolean {
        return try {
            val conn = URL(segmentUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            headers?.forEach { (k, v) ->
                if (PLAYER_SKIP_HEADERS.none { it.equals(k, ignoreCase = true) }) {
                    conn.setRequestProperty(k, v)
                }
            }
            if (headers?.keys?.none { it.equals("User-Agent", ignoreCase = true) } != false) {
                conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            }
            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buffer = ByteArray(8_192)
                    var totalRead = 0
                    while (totalRead < maxBytes) {
                        val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - totalRead))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        totalRead += read
                    }
                }
            }
            outFile.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "downloadSegmentToFile failed for $segmentUrl: ${e.message}")
            false
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
