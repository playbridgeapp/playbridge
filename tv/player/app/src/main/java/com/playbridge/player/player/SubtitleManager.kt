package com.playbridge.player.player

import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import okhttp3.Request
import java.io.IOException
import java.util.Collections
import java.util.TreeMap
import java.util.regex.Pattern

class SubtitleManager(
    private val textView: TextView,
    private val coroutineScope: CoroutineScope
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

    fun loadSubtitle(url: String) {
        Log.i(TAG, "Loading subtitle from: $url")
        subtitleJob?.cancel()
        syncJob?.cancel()
        lastCueText = null

        textView.post {
            textView.text = ""
            textView.visibility = android.view.View.GONE
        }
        cues.clear()

        subtitleJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val content = downloadUrl(url)
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
                delay(200) // Update every 200ms
            }
        }
    }

    private fun updateSubtitle(currentPos: Long) {
        val adjustedPos = currentPos + offsetMs
        // Find the active cue
        val activeCue = synchronized(cues) {
            cues.find { adjustedPos >= it.startTime && adjustedPos <= it.endTime }
        }

        if (activeCue != null) {
            if (lastCueText != activeCue.text) {
                lastCueText = activeCue.text
                val html = activeCue.text.replace("\n", "<br>")
                @Suppress("DEPRECATION")
                textView.text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
                textView.visibility = android.view.View.VISIBLE
            }
        } else {
            if (lastCueText != null) {
                lastCueText = null
                textView.text = ""
                textView.visibility = android.view.View.GONE
            }
        }
    }

    private fun downloadUrl(urlString: String): String {
        val sniffer = ContentSniffer()
        val client = sniffer.getOkHttpClient(trustAllCerts = sniffer.isLocalUrl(urlString))
        val request = Request.Builder()
            .url(urlString)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected HTTP code: " + response.code)
            return response.body?.string() ?: ""
        }
    }

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
                    currentStart = parseTimestamp(times[0].trim().replace(',', '.').substringBefore(' '))
                    currentEnd = parseTimestamp(times[1].trim().replace(',', '.').substringBefore(' '))
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
            // HH:MM:SS.ms or MM:SS.ms
            val parts = timestamp.split(':')
            var hours = 0L
            var minutes = 0L
            var seconds = 0.0
            
            if (parts.size == 3) {
                hours = parts[0].toLong()
                minutes = parts[1].toLong()
                // Handle comma or dot for decimal seaparator if not handled correctly before
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
    
    suspend fun getPreview(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val sniffer = ContentSniffer()
            val client = sniffer.getOkHttpClient(trustAllCerts = sniffer.isLocalUrl(url))
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-4096") // Fetch first 4KB
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val content = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected HTTP code: " + response.code)
                response.body?.string() ?: ""
            }
            
            val cues = if (url.endsWith(".vtt", true)) {
                parseVtt(content)
            } else {
                parseSrt(content)
            }
            
            // Return first 3 non-empty cues
            cues.take(3).joinToString("\n\n") { it.text }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch preview", e)
            null
        }
    }

    fun disable() {
        subtitleJob?.cancel()
        syncJob?.cancel()
        lastCueText = null
        textView.post {
            textView.visibility = android.view.View.GONE
            textView.text = ""
        }
    }
}
