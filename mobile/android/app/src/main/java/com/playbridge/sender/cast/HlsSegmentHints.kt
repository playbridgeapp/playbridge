package com.playbridge.sender.cast

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * HLS segment-container hints for external Cast loads, mirroring Desktop's
 * TvCastMediaPreparer / GoogleCastTransport:
 *
 * Packaging probes the playlist (fetching it when not supplied) and attaches
 * `pb_hls_format` / `pb_hls_stream` query parameters to the packaged URL.
 * GoogleCastTarget reads them back and maps them onto the Cast LOAD metadata
 * (`hlsSegmentFormat` / `hlsVideoSegmentFormat` / `streamType`). Stream-proxy
 * servers ignore the extra query values; Cast receivers use the metadata to
 * accept segments whose bytes are MPEG-TS despite a misleading `.jpg` suffix
 * (some supported anime CDNs intentionally disguise transport-stream data).
 */
object HlsSegmentHints {

    private const val TAG = "HlsSegmentHints"
    private const val FORMAT_PARAM = "pb_hls_format"
    private const val STREAM_PARAM = "pb_hls_stream"

    /** Bound remote work for untrusted manifests (mirrors Desktop). */
    private const val MAX_VARIANTS = 16
    private const val MAX_BODY_CHARS = 1024 * 1024
    private const val FETCH_TIMEOUT_MS = 4000

    data class Evidence(
        val format: String?,
        val streamType: String,
    )

    fun isHlsContentType(contentType: String?): Boolean {
        val lower = contentType?.lowercase() ?: return false
        return lower.contains("mpegurl") || lower.contains("m3u8")
    }

    /**
     * Infers the segment container only from media-playlist URI evidence. A
     * missing EXT-X-MAP does not prove MPEG-TS: packed AAC/MP3 and WebVTT media
     * playlists are also map-less. Image extensions are treated as TS because
     * some supported anime CDNs intentionally disguise transport-stream bytes.
     */
    fun segmentFormatForBody(body: String?): String? {
        val source = body?.replace("\r\n", "\n") ?: ""
        val upper = source.uppercase()
        if (!upper.trimStart().startsWith("#EXTM3U")) return null
        if (upper.contains("#EXT-X-MAP:")) return "fmp4"
        val isMediaPlaylist = upper.contains("#EXTINF:") ||
            upper.contains("#EXT-X-TARGETDURATION:") ||
            upper.contains("#EXT-X-MEDIA-SEQUENCE:")
        if (!isMediaPlaylist) return null

        val references = mutableListOf<String>()
        val uriAttribute = Regex("(?:^|,)URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        for (rawLine in source.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("#")) {
                references.add(line)
                continue
            }
            val tag = line.uppercase()
            if (tag.startsWith("#EXT-X-PART:") ||
                (tag.startsWith("#EXT-X-PRELOAD-HINT:") && tag.contains("TYPE=PART"))
            ) {
                val reference = uriAttribute.find(line)?.groupValues?.get(1)
                if (reference != null) references.add(reference)
            }
        }
        if (references.isEmpty()) return null

        val formats = mutableSetOf<String>()
        for (reference in references) {
            val format = segmentFormatForReference(reference) ?: return null
            formats.add(format)
        }
        return if (formats.size == 1) formats.single() else null
    }

    private fun segmentFormatForReference(reference: String): String? {
        val path = runCatching { URI(reference).path }
            .getOrNull()
            ?.lowercase()
            ?: reference.substringBefore('?').lowercase()
        val extension = path.substringAfterLast('.', "")
        return when (extension) {
            "m4s", "mp4", "cmfv", "cmfa" -> "fmp4"
            "ts", "m2ts", "mts" -> "ts"
            // Known CDN deception: the response body is MPEG-TS despite the suffix.
            "jpg", "jpeg" -> "ts"
            else -> null
        }
    }

    /**
     * Returns every distinct master-playlist variant in descending bandwidth
     * order. Format metadata is safe only after every returned rendition has
     * been inspected.
     */
    fun variantUrlsForBody(body: String?, baseUrl: String): List<String> {
        val source = body?.replace("\r\n", "\n") ?: ""
        val base = runCatching { URI(baseUrl) }.getOrNull() ?: return emptyList()
        var pendingUri: String? = null
        val variants = mutableListOf<Pair<String, Int>>()
        var malformedVariant = false
        fun addVariant(value: String, bandwidth: Int) {
            runCatching { variants.add(base.resolve(value).toString() to bandwidth) }
                .onFailure { malformedVariant = true }
        }

        val lines = source.split('\n')
        val uriAttribute = Regex("(?:^|,)URI=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val bandwidthRegex = Regex("(?:^|,)BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) {
                val attributeUri = uriAttribute.find(line)?.groupValues?.get(1)
                if (attributeUri != null) addVariant(attributeUri, 0)
                index++
                continue
            }
            if (!line.startsWith("#EXT-X-STREAM-INF:")) {
                index++
                continue
            }
            val bandwidth = bandwidthRegex.find(line.substring("#EXT-X-STREAM-INF:".length))
                ?.groupValues?.get(1)
                ?.toIntOrNull() ?: 0
            pendingUri = null
            var child = index + 1
            while (child < lines.size) {
                val candidate = lines[child].trim()
                if (candidate.isEmpty()) {
                    child++
                    continue
                }
                if (candidate.startsWith("#")) break
                pendingUri = candidate
                break
            }
            pendingUri?.let { addVariant(it, bandwidth) }
            index++
        }
        if (malformedVariant) return emptyList()
        variants.sortByDescending { it.second }
        return variants.map { it.first }.distinct()
    }

    /**
     * Returns a container only when all supplied media playlists are readable
     * and agree. Mixed TS/fMP4 masters must not receive master-wide metadata.
     */
    fun commonSegmentFormatForBodies(bodies: Iterable<String?>): String? {
        val formats = mutableSetOf<String>()
        var count = 0
        for (body in bodies) {
            count++
            val format = segmentFormatForBody(body) ?: return null
            formats.add(format)
        }
        return if (count > 0 && formats.size == 1) formats.single() else null
    }

    /**
     * Treat ambiguous web-video HLS as stored media. Live is used only when the
     * supplied media playlist has explicit live/event markers and no terminal
     * ENDLIST; a master playlist alone does not prove that its media is live.
     */
    fun streamTypeForBody(body: String?): String {
        val upper = body?.uppercase() ?: ""
        if (upper.contains("#EXT-X-ENDLIST") ||
            upper.contains("#EXT-X-PLAYLIST-TYPE:VOD")
        ) {
            return "buffered"
        }
        if (upper.contains("#EXT-X-PLAYLIST-TYPE:EVENT") ||
            upper.contains("#EXT-X-SERVER-CONTROL:") ||
            upper.contains("#EXT-X-PART:")
        ) {
            return "live"
        }
        return "buffered"
    }

    /**
     * Carries the HLS container/stream-type hints on the packaged URL without
     * exposing origin headers to the receiver. Stream-proxy servers ignore
     * these query values; GoogleCastTarget consumes them when building Cast
     * LOAD metadata.
     */
    fun withHints(url: String, format: String?, streamType: String): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return url
        val builder = httpUrl.newBuilder()
        builder.removeAllQueryParameters(FORMAT_PARAM)
        builder.removeAllQueryParameters(STREAM_PARAM)
        if (format != null) builder.addQueryParameter(FORMAT_PARAM, format)
        builder.addQueryParameter(STREAM_PARAM, streamType)
        return builder.build().toString()
    }

    /** Reads the packaged container hint: only proven values survive. */
    fun formatFromUrl(url: String): String? =
        when (val hint = url.toHttpUrlOrNull()?.queryParameter(FORMAT_PARAM)?.lowercase()) {
            "ts", "fmp4" -> hint
            else -> null
        }

    /**
     * Maps the packaged stream-type hint onto the Cast LOAD enum. Returns null
     * when the URL carries no hint so the Rust session keeps its URL-based
     * inference for unpackaged loads.
     */
    fun streamTypeFromUrl(url: String): String? =
        when (url.toHttpUrlOrNull()?.queryParameter(STREAM_PARAM)?.lowercase()) {
            "live" -> "LIVE"
            "buffered" -> "BUFFERED"
            else -> null
        }

    fun googleCastAudioFormat(container: String?): String? = when (container) {
        "ts" -> "ts_aac"
        "fmp4" -> "fmp4"
        else -> null
    }

    fun googleCastVideoFormat(container: String?): String? = when (container) {
        "ts" -> "mpeg2_ts"
        "fmp4" -> "fmp4"
        else -> null
    }

    /**
     * Probes playlist evidence for [url], using [suppliedBody] when the caller
     * already holds the playlist (extension synthetic masters, data URIs).
     * Returns null on any failure so packaging can omit hints rather than guess.
     */
    suspend fun probe(
        url: String,
        headers: Map<String, String>,
        suppliedBody: String? = null,
    ): Evidence? = withContext(Dispatchers.IO) {
        try {
            val body = suppliedBody ?: fetchPlaylist(url, headers)
            if (body == null || !body.trimStart().startsWith("#EXTM3U")) {
                return@withContext null
            }
            val variants = variantUrlsForBody(body, url)
            if (variants.isNotEmpty()) {
                // Bound remote work for untrusted manifests. More variants means
                // the format cannot be proven safely, so omit the hint.
                if (variants.size > MAX_VARIANTS) {
                    return@withContext Evidence(format = null, streamType = "buffered")
                }
                val variantBodies = coroutineScope {
                    variants.map { variant -> async { fetchPlaylist(variant, headers) } }.awaitAll()
                }
                val commonFormat = commonSegmentFormatForBodies(variantBodies)
                val streamTypes = mutableSetOf<String>()
                var allReadable = true
                for (variantBody in variantBodies) {
                    if (variantBody == null) {
                        allReadable = false
                        continue
                    }
                    streamTypes.add(streamTypeForBody(variantBody))
                }
                return@withContext Evidence(
                    format = commonFormat,
                    streamType = if (allReadable && streamTypes.size == 1) {
                        streamTypes.single()
                    } else {
                        "buffered"
                    },
                )
            }
            val format = segmentFormatForBody(body) ?: return@withContext null
            Evidence(format = format, streamType = streamTypeForBody(body))
        } catch (error: Exception) {
            Log.d(TAG, "HLS metadata probe failed; omitting format hint: ${error.message}")
            null
        }
    }

    private fun fetchPlaylist(url: String, headers: Map<String, String>): String? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val connection = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = FETCH_TIMEOUT_MS
                readTimeout = FETCH_TIMEOUT_MS
                instanceFollowRedirects = true
                headers.forEach { (key, value) ->
                    // Skip headers that HttpURLConnection manages or that break playlist fetching.
                    if (!key.equals("Range", ignoreCase = true)) setRequestProperty(key, value)
                }
            }
        }.getOrNull() ?: return null
        return runCatching {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { stream ->
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                val contents = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    contents.append(buffer, 0, read)
                    if (contents.length > MAX_BODY_CHARS) return null
                }
                contents.toString()
            }
        }.getOrNull()
    }
}
