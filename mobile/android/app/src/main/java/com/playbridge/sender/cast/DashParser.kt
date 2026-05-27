package com.playbridge.sender.cast

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Parser for MPEG-DASH manifests (.mpd).
 *
 * Extracts video Representation entries from the manifest so the UI can show the user
 * what quality tiers are available. For playback, the original MPD URL is always sent to
 * the TV player — the player handles adaptive bitrate selection natively.
 */
object DashParser {

    suspend fun parseManifest(mpdUrl: String, headers: Map<String, String>? = null): List<VideoQuality> =
        withContext(Dispatchers.IO) {
            try {
                val content = fetchUrlContent(mpdUrl, headers)
                parseXml(content, mpdUrl)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

    private fun parseXml(xmlContent: String, mpdUrl: String): List<VideoQuality> {
        val qualities = mutableListOf<VideoQuality>()

        val parser: XmlPullParser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xmlContent.reader())

        // AdaptationSet-level fallback dimensions (some MPDs put width/height here)
        var inVideoAdaptationSet = false
        var adaptationWidth = 0
        var adaptationHeight = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "AdaptationSet" -> {
                        val mimeType   = parser.getAttributeValue(null, "mimeType")   ?: ""
                        val contentType = parser.getAttributeValue(null, "contentType") ?: ""
                        val codecs     = parser.getAttributeValue(null, "codecs")     ?: ""
                        inVideoAdaptationSet = mimeType.startsWith("video/") ||
                            contentType == "video" ||
                            VIDEO_CODECS.any { codecs.startsWith(it) }
                        adaptationWidth  = parser.getAttributeValue(null, "width")?.toIntOrNull()  ?: 0
                        adaptationHeight = parser.getAttributeValue(null, "height")?.toIntOrNull() ?: 0
                    }

                    "Representation" -> if (inVideoAdaptationSet) {
                        val bandwidth = parser.getAttributeValue(null, "bandwidth")?.toLongOrNull() ?: 0L
                        val width  = parser.getAttributeValue(null, "width")?.toIntOrNull()  ?: adaptationWidth
                        val height = parser.getAttributeValue(null, "height")?.toIntOrNull() ?: adaptationHeight
                        val codecs = parser.getAttributeValue(null, "codecs")

                        // Skip audio-only representations that sneak into video AdaptationSets
                        val isAudio = codecs != null && AUDIO_CODECS.any { codecs.startsWith(it) }

                        if (bandwidth > 0 && height > 0 && !isAudio) {
                            qualities.add(
                                VideoQuality(
                                    resolution = "${height}p",
                                    bandwidth  = bandwidth,
                                    // Always send the full MPD URL — TV player handles ABR
                                    url        = mpdUrl,
                                    codecs     = codecs
                                )
                            )
                        }
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "AdaptationSet" -> {
                        inVideoAdaptationSet = false
                        adaptationWidth  = 0
                        adaptationHeight = 0
                    }
                }
            }
            event = parser.next()
        }

        // Deduplicate by (resolution, bandwidth) — some MPDs repeat representations
        return qualities
            .distinctBy { "${it.resolution}@${it.bandwidth}" }
            .sortedByDescending { it.bandwidth }
    }

    private fun fetchUrlContent(urlString: String, headers: Map<String, String>? = null): String {
        val resolved = resolveUrl(urlString)
        val connection = URL(resolved).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout    = 10_000
        connection.instanceFollowRedirects = true

        headers?.forEach { (key, value) ->
            if (!key.equals("Range", ignoreCase = true)) connection.setRequestProperty(key, value)
        }
        if (headers?.keys?.none { it.equals("User-Agent", ignoreCase = true) } != false) {
            connection.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }

        return connection.inputStream.use { BufferedReader(InputStreamReader(it)).readText() }
    }

    private fun resolveUrl(url: String): String = try {
        URI(url).toString()
    } catch (e: Exception) {
        url
    }

    private val VIDEO_CODECS = listOf("avc", "hvc", "hev", "vp8", "vp9", "av01", "dvh")
    private val AUDIO_CODECS = listOf("mp4a", "ac-3", "ec-3", "opus", "flac", "vorbis")
}
