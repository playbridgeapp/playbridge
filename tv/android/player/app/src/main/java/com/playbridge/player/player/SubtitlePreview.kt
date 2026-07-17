package com.playbridge.player.player

import android.util.Log
import com.playbridge.shared.logging.redactUrlForLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** A single subtitle cue (times in ms). */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Canonical SRT/VTT parser shared by the live renderer ([SubtitleManager]) and the subtitle
 * preview loader. Times in milliseconds.
 */
object SubtitleParser {

    fun parse(content: String, isVtt: Boolean): List<SubtitleCue> =
        if (isVtt) parseVtt(content) else parseSrt(content)

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            val utf8 = String(bytes, Charsets.UTF_8)
            if (utf8.contains("�")) throw Exception("Invalid UTF-8")
            utf8
        } catch (e: Exception) {
            String(bytes, java.nio.charset.Charset.forName("Windows-1252"))
        }
    }

    fun stripHtml(text: String): String = text.replace(Regex("<[^>]*>"), "").trim()

    private fun parseSrt(content: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        var start = -1L
        var end = -1L
        val text = StringBuilder()
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (start != -1L && end != -1L && text.isNotEmpty()) cues.add(SubtitleCue(start, end, text.toString().trimEnd()))
                start = -1L; end = -1L; text.clear()
            } else if (line.contains("-->")) {
                val times = line.split("-->")
                if (times.size == 2) {
                    start = parseTimestamp(times[0].trim().replace(',', '.').substringBefore(' '))
                    end = parseTimestamp(times[1].trim().replace(',', '.').substringBefore(' '))
                }
            } else if (start != -1L) {
                text.append(rawLine).append("\n")
            }
        }
        if (start != -1L && end != -1L && text.isNotEmpty()) cues.add(SubtitleCue(start, end, text.toString().trimEnd()))
        return cues
    }

    private fun parseVtt(content: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val it = content.lineSequence().iterator()
        while (it.hasNext()) {
            val line = it.next().trim()
            if (line.contains("-->")) {
                val times = line.split("-->")
                if (times.size == 2) {
                    val start = parseTimestamp(times[0].trim().substringBefore(' '))
                    val end = parseTimestamp(times[1].trim().substringBefore(' '))
                    val sb = StringBuilder()
                    while (it.hasNext()) {
                        val t = it.next()
                        if (t.trim().isEmpty()) break
                        sb.append(t).append("\n")
                    }
                    val txt = sb.toString().trim()
                    if (start != -1L && end != -1L && txt.isNotEmpty()) cues.add(SubtitleCue(start, end, txt))
                }
            }
        }
        return cues
    }

    private fun parseTimestamp(timestamp: String): Long {
        return try {
            val parts = timestamp.split(':')
            var hours = 0L; var minutes = 0L; var seconds = 0.0
            when (parts.size) {
                3 -> { hours = parts[0].toLong(); minutes = parts[1].toLong(); seconds = parts[2].replace(',', '.').toDouble() }
                2 -> { minutes = parts[0].toLong(); seconds = parts[1].replace(',', '.').toDouble() }
                else -> return -1
            }
            (hours * 3600000 + minutes * 60000 + (seconds * 1000)).toLong()
        } catch (e: Exception) {
            -1
        }
    }
}

/**
 * Downloads + parses subtitle files for the on-demand preview in the subtitle overlay, caching
 * parsed cues by URL so re-selecting a language (or nudging the sync offset) never re-fetches.
 */
object SubtitleCueLoader {

    private const val TAG = "SubtitleCueLoader"
    private val cache = ConcurrentHashMap<String, List<SubtitleCue>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Parsed cues if already loaded, else null. Cheap, non-blocking — used during render. */
    fun cached(url: String): List<SubtitleCue>? = cache[url]

    fun isLoading(url: String): Boolean = url in inFlight

    /** Drop cached previews (call when new media loads to avoid unbounded growth on a binge). */
    fun clear() {
        cache.clear()
        inFlight.clear()
    }

    /** Download + parse (once). Returns cues, or null on failure. */
    suspend fun load(url: String, headers: Map<String, String>?): List<SubtitleCue>? {
        cache[url]?.let { return it }
        if (!inFlight.add(url)) return cache[url]
        return try {
            withContext(Dispatchers.IO) {
                val bytes = download(url, headers)
                val content = SubtitleParser.decode(bytes)
                val isVtt = url.substringBefore('#').endsWith(".vtt", true) || content.startsWith("WEBVTT")
                val cues = SubtitleParser.parse(content, isVtt)
                cache[url] = cues
                cues
            }
        } catch (e: Exception) {
            Log.w(TAG, "preview load failed for ${redactUrlForLog(url)}: ${e.message}")
            null
        } finally {
            inFlight.remove(url)
        }
    }

    /**
     * Up to [count] cue texts (HTML-stripped) starting from the cue active at [atMs] (or the next
     * upcoming cue), for previewing what this subtitle shows at the current scene.
     */
    fun preview(cues: List<SubtitleCue>, atMs: Long, count: Int = 5): List<String> {
        if (cues.isEmpty()) return emptyList()
        var startIdx = cues.indexOfFirst { it.endMs >= atMs }
        if (startIdx < 0) startIdx = (cues.size - count).coerceAtLeast(0)
        return cues.drop(startIdx).take(count)
            .map { SubtitleParser.stripHtml(it.text).replace("\n", " ") }
            .filter { it.isNotBlank() }
    }

    private fun download(url: String, headers: Map<String, String>?): ByteArray {
        val sniffer = ContentSniffer()
        val client = sniffer.getOkHttpClient(allowLocalSelfSigned = sniffer.isLocalUrl(url))
        val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
        headers?.forEach { (k, v) -> if (!k.equals("Host", true)) builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }
}
