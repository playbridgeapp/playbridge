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
data class AudioTrack(
    val groupId: String,
    val name: String,
    val language: String? = null,
    val uri: String? = null,
    val isDefault: Boolean = false,
    val autoselect: Boolean = false,
    val channels: String? = null
)

/**
 * Represents the full parsed content of an HLS master playlist
 */
@Serializable
data class HlsPlaylist(
    val videoQualities: List<VideoQuality>,
    val audioTracks: List<AudioTrack> = emptyList(),
    val masterPlaylistUrl: String,
    val segmentPrefixes: Set<String> = emptySet()
)

/**
 * Video quality variant with audio group reference
 */
@Serializable
data class VideoQuality(
    val resolution: String,
    val bandwidth: Long, // in bits per second
    val url: String,
    val codecs: String? = null,
    val audioGroupId: String? = null,
    val frameRate: String? = null,
    val averageBandwidth: Long? = null
)

/**
 * Parser for HTTP Live Streaming (HLS) playlists (.m3u8).
 * Extracts available video variants/qualities and audio tracks.
 */
object HlsParser {

    /**
     * Parses the given M3U8 URL and returns a comprehensive HlsPlaylist object.
     */
    suspend fun parsePlaylist(masterPlaylistUrl: String): HlsPlaylist = withContext(Dispatchers.IO) {
        try {
            val content = fetchUrlContent(masterPlaylistUrl)
            
            // Basic check for M3U8 format
            if (!content.startsWith("#EXTM3U")) {
                return@withContext HlsPlaylist(emptyList(), emptyList(), masterPlaylistUrl)
            }

            val videoQualities = mutableListOf<VideoQuality>()
            val audioTracks = mutableListOf<AudioTrack>()
            val segmentPrefixes = mutableSetOf<String>()

            // Split by lines and process
            val lines = content.lines()
            var currentBandwidth: Long? = null
            var currentAverageBandwidth: Long? = null
            var currentResolution: String? = null
            var currentCodecs: String? = null
            var currentAudioGroup: String? = null
            var currentFrameRate: String? = null

            for (i in lines.indices) {
                val line = lines[i].trim()
                
                if (line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                    // Parse Audio Track
                    val attributes = line.substringAfter(":")
                    
                    val groupId = Regex("GROUP-ID=\"([^\"]+)\"").find(attributes)?.groupValues?.get(1)
                    val name = Regex("NAME=\"([^\"]+)\"").find(attributes)?.groupValues?.get(1)
                    val language = Regex("LANGUAGE=\"([^\"]+)\"").find(attributes)?.groupValues?.get(1)
                    val uri = Regex("URI=\"([^\"]+)\"").find(attributes)?.groupValues?.get(1)
                    val isDefault = Regex("DEFAULT=YES").containsMatchIn(attributes)
                    val autoselect = Regex("AUTOSELECT=YES").containsMatchIn(attributes)
                    val channels = Regex("CHANNELS=\"([^\"]+)\"").find(attributes)?.groupValues?.get(1) // e.g. "2"

                    if (groupId != null && name != null) {
                        // Resolve relative URI if present
                        val resolvedUri = uri?.let { resolveUrl(masterPlaylistUrl, it) }
                        
                        audioTracks.add(AudioTrack(
                            groupId = groupId,
                            name = name,
                            language = language,
                            uri = resolvedUri,
                            isDefault = isDefault,
                            autoselect = autoselect,
                            channels = channels
                        ))
                    }
                    
                } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val attributes = line.substringAfter(":")
                    
                    // Parse bandwidths
                    currentBandwidth = Regex("BANDWIDTH=(\\d+)").find(attributes)?.groupValues?.get(1)?.toLongOrNull()
                    currentAverageBandwidth = Regex("AVERAGE-BANDWIDTH=(\\d+)").find(attributes)?.groupValues?.get(1)?.toLongOrNull()
                    
                    // Parse dimensions
                    currentResolution = Regex("RESOLUTION=(\\d+x\\d+)").find(attributes)?.groupValues?.get(1)
                    
                    // Parse codecs
                    currentCodecs = Regex("CODECS=\"([^\"]+)\"").find(attributes)?.groupValues?.get(1)
                    
                    // Parse audio group ID
                    currentAudioGroup = Regex("AUDIO=\"([^\"]+)\"").find(attributes)?.groupValues?.get(1)
                    
                    // Parse frame rate
                    currentFrameRate = Regex("FRAME-RATE=([\\d\\.]+)").find(attributes)?.groupValues?.get(1)
                    
                } else if (!line.startsWith("#") && line.isNotEmpty()) {
                    if (currentBandwidth != null) {
                        // This is a URI line following a stream info tag (a variant playlist)
                        val variantUrl = resolveUrl(masterPlaylistUrl, line)
                        
                        // Create quality label
                        val resolutionLabel = currentResolution?.let { res ->
                            val height = res.substringAfter("x")
                            "${height}p"
                        } ?: "Unknown"

                        videoQualities.add(VideoQuality(
                            resolution = resolutionLabel,
                            bandwidth = currentBandwidth,
                            averageBandwidth = currentAverageBandwidth,
                            url = variantUrl,
                            codecs = currentCodecs,
                            audioGroupId = currentAudioGroup,
                            frameRate = currentFrameRate
                        ))
                        
                        // Reset for next entry
                        currentBandwidth = null
                        currentAverageBandwidth = null
                        currentResolution = null
                        currentCodecs = null
                        currentAudioGroup = null
                        currentFrameRate = null
                    } else {
                        // This is a URI line NOT following stream info, likely a media segment
                        val segmentUrl = resolveUrl(masterPlaylistUrl, line)
                        val prefix = segmentUrl.substringBeforeLast("/") + "/"
                        if (prefix.startsWith("http")) {
                            segmentPrefixes.add(prefix)
                        }
                    }
                }
            }
            
            // Sort by bandwidth descending (highest quality first)
            videoQualities.sortByDescending { it.bandwidth }
            
            HlsPlaylist(videoQualities, audioTracks, masterPlaylistUrl, segmentPrefixes)
            
        } catch (e: Exception) {
            e.printStackTrace()
            HlsPlaylist(emptyList(), emptyList(), masterPlaylistUrl, emptySet())
        }
    }

    /**
     * Parsing wrapper for backward compatibility.
     * Returns just list of video qualities.
     */
    suspend fun parse(masterPlaylistUrl: String): List<VideoQuality> {
        return parsePlaylist(masterPlaylistUrl).videoQualities
    }

    /**
     * Generates a new Master Playlist containing only the selected video quality
     * and its associated audio tracks.
     */
    fun generateFilteredPlaylist(playlist: HlsPlaylist, selectedQuality: VideoQuality): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n") // Version 3 is safe baseline, or use 6
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        
        // 1. Add Audio Tracks if the selected quality uses audio
        val audioGroupId = selectedQuality.audioGroupId
        if (audioGroupId != null) {
            val relevantAudio = playlist.audioTracks.filter { it.groupId == audioGroupId }
            
            for (track in relevantAudio) {
                sb.append("#EXT-X-MEDIA:TYPE=AUDIO")
                sb.append(",GROUP-ID=\"${track.groupId}\"")
                sb.append(",NAME=\"${track.name}\"")
                
                track.language?.let { sb.append(",LANGUAGE=\"$it\"") }
                if (track.isDefault) sb.append(",DEFAULT=YES")
                if (track.autoselect) sb.append(",AUTOSELECT=YES")
                track.channels?.let { sb.append(",CHANNELS=\"$it\"") }
                track.uri?.let { sb.append(",URI=\"$it\"") }
                
                sb.append("\n")
            }
        }
        
        // 2. Add the selected Video Stream
        sb.append("#EXT-X-STREAM-INF:")
        sb.append("BANDWIDTH=${selectedQuality.bandwidth}")
        selectedQuality.averageBandwidth?.let { sb.append(",AVERAGE-BANDWIDTH=$it") }
        
        // Reconstruct resolution string from "1080p" if needed, but we don't store raw WxH easily in VideoQuality unless we change it.
        // Actually we do store raw resolution string if we parsed it, but we modified it to "1080p".
        // Let's rely on the fact that for playback, BANDWIDTH and URL are critical.
        // If we want exact resolution we might want to store raw attributes in VideoQuality map? 
        // For now, let's omit RESOLUTION if we don't have the original string, or just don't worry as players usually adapt or just play the URL.
        // HOWEVER, ExoPlayer uses these attributes for track selection.
        // Let's assume the user just wants THIS stream.
        
        selectedQuality.codecs?.let { sb.append(",CODECS=\"$it\"") }
        selectedQuality.frameRate?.let { sb.append(",FRAME-RATE=$it") }
        if (audioGroupId != null) {
            sb.append(",AUDIO=\"$audioGroupId\"")
        }
        
        sb.append("\n")
        sb.append(selectedQuality.url)
        sb.append("\n")
        
        return sb.toString()
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
