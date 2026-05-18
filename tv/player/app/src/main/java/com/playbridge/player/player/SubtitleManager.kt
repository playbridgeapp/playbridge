package com.playbridge.player.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.IOException
import java.util.Collections
import java.util.regex.Pattern

class SubtitleManager(
    private val coroutineScope: CoroutineScope,
    private val onCueChanged: (String?) -> Unit
) {
    private val TAG = "SubtitleManager"
    private var subtitleJob: Job? = null
    private var syncJob: Job? = null
    private val cues = Collections.synchronizedList(ArrayList<Cue>())
    private var getPlayerPosition: (() -> Long)? = null
    private var lastCueText: String? = null
    private var offsetMs: Long = 0L

    data class Cue(val startTime: Long, val endTime: Long, val text: String) : Comparable<Cue> {
        override fun compareTo(other: Cue): Int {
            return this.startTime.compareTo(other.startTime)
        }
    }

    fun setPlayer(getPlayerPosition: () -> Long) {
        this.getPlayerPosition = getPlayerPosition
    }

    fun setOffset(offsetMs: Long) {
        this.offsetMs = offsetMs
    }

    fun loadSubtitle(url: String, headers: Map<String, String>? = null) {
        Log.i(TAG, "Loading subtitle from: $url")
        subtitleJob?.cancel()
        syncJob?.cancel()
        lastCueText = null
        onCueChanged(null)
        cues.clear()

        subtitleJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val bytes = downloadUrlBytes(url, headers)
                // Simple encoding detection
                val content = detectEncodingAndDecode(bytes)
                
                val parsedCues = if (url.endsWith(".vtt", true)) {
                    parseVtt(content)
                } else {
                    parseSrt(content)
                }
                
                cues.addAll(parsedCues)
                Collections.sort(cues)
                Log.i(TAG, "Loaded ${cues.size} cues")

                startSyncing()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load subtitle", e)
            }
        }
    }

    private fun startSyncing() {
        syncJob?.cancel()
        syncJob = coroutineScope.launch(Dispatchers.Main) {
            while (isActive) {
                val currentPos = getPlayerPosition?.invoke() ?: 0L
                updateSubtitle(currentPos)
                // High precision sync: 32ms (approx 30fps) for crisp transitions
                delay(32) 
            }
        }
    }

    private fun updateSubtitle(currentPos: Long) {
        val adjustedPos = currentPos + offsetMs
        // Find all active cues (some subtitles have multiple overlapping cues for different screen positions)
        val activeCues = synchronized(cues) {
            cues.filter { adjustedPos >= it.startTime && adjustedPos <= it.endTime }
        }

        if (activeCues.isNotEmpty()) {
            val combinedText = activeCues.joinToString("\n") { it.text }
            if (lastCueText != combinedText) {
                lastCueText = combinedText
                // Strip HTML tags for clean Compose rendering
                val cleanText = stripHtml(combinedText)
                onCueChanged(cleanText)
            }
        } else {
            if (lastCueText != null) {
                lastCueText = null
                onCueChanged(null)
            }
        }
    }

    private fun downloadUrlBytes(urlString: String, headers: Map<String, String>? = null): ByteArray {
        val sniffer = ContentSniffer()
        val client = sniffer.getOkHttpClient(trustAllCerts = sniffer.isLocalUrl(urlString))
        val requestBuilder = Request.Builder()
            .url(urlString)
            .header("User-Agent", "Mozilla/5.0")
            
        headers?.forEach { (key, value) ->
            // Prevent overriding the URL host if a custom Host header is passed maliciously
            if (!key.equals("Host", ignoreCase = true)) {
                requestBuilder.header(key, value)
            }
        }
            
        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected HTTP code: " + response.code)
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun detectEncodingAndDecode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // Very basic heuristic: if it contains many nulls, it's probably UTF-16
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        
        // Default to UTF-8 but fallback to Windows-1252 if it looks like typical western encoding
        // In a full Nuvio impl, we would use juniversalchardet here.
        return try {
            val utf8 = String(bytes, Charsets.UTF_8)
            // If it contains "replacement characters", it likely wasn't UTF-8
            if (utf8.contains("\uFFFD")) throw Exception("Invalid UTF-8")
            utf8
        } catch (e: Exception) {
            String(bytes, java.nio.charset.Charset.forName("Windows-1252"))
        }
    }

    private fun stripHtml(text: String): String {
        // Simple regex to remove tags like <b>, <i>, <font color="...">
        return text.replace(Regex("<[^>]*>"), "").trim()
    }

    private fun parseSrt(content: String): List<Cue> {
        val parsedCues = ArrayList<Cue>()
        var currentStart = -1L
        var currentEnd = -1L
        val currentText = StringBuilder()
        
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
                    currentStart = parseTimestamp(times[0].trim().replace(',', '.').substringBefore(' '))
                    currentEnd = parseTimestamp(times[1].trim().replace(',', '.').substringBefore(' '))
                }
            } else if (currentStart != -1L) {
                currentText.append(rawLine).append("\n")
            }
        }
        if (currentStart != -1L && currentEnd != -1L && currentText.isNotEmpty()) {
            parsedCues.add(Cue(currentStart, currentEnd, currentText.toString().trimEnd()))
        }
        return parsedCues
    }

    private fun parseVtt(content: String): List<Cue> {
        val parsedCues = ArrayList<Cue>()
        val iterator = content.lineSequence().iterator()
        while (iterator.hasNext()) {
            val line = iterator.next().trim()
            if (line.contains("-->")) {
                val times = line.split("-->")
                if (times.size == 2) {
                    val start = parseTimestamp(times[0].trim().substringBefore(' '))
                    val end = parseTimestamp(times[1].trim().substringBefore(' '))
                    
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
        try {
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
            
            return (hours * 3600000 + minutes * 60000 + (seconds * 1000)).toLong()
        } catch (e: Exception) {
            return -1
        }
    }

    fun disable() {
        subtitleJob?.cancel()
        syncJob?.cancel()
        lastCueText = null
        onCueChanged(null)
    }
}
