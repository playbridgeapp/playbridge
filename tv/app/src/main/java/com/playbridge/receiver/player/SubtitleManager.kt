package com.playbridge.receiver.player

import android.os.Handler
import android.os.Looper
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
import java.net.HttpURLConnection
import java.net.URL
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

    data class Cue(val startTime: Long, val endTime: Long, val text: String) : Comparable<Cue> {
        override fun compareTo(other: Cue): Int {
            return this.startTime.compareTo(other.startTime)
        }
    }

    fun setPlayer(getPlayerPosition: () -> Long) {
        this.getPlayerPosition = getPlayerPosition
    }

    fun loadSubtitle(url: String) {
        Log.i(TAG, "Loading subtitle from: $url")
        subtitleJob?.cancel()
        syncJob?.cancel()
        
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
        // Find the active cue
        val activeCue = synchronized(cues) {
            cues.find { currentPos >= it.startTime && currentPos <= it.endTime }
        }
        
        if (activeCue != null) {
            if (textView.text.toString() != activeCue.text) {
                textView.text = activeCue.text
                textView.visibility = android.view.View.VISIBLE
            }
        } else {
            if (textView.text.isNotEmpty()) {
                textView.text = ""
                textView.visibility = android.view.View.GONE
            }
        }
    }

    private fun downloadUrl(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.requestMethod = "GET"
        
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")

        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun parseSrt(content: String): List<Cue> {
        val parsedCues = ArrayList<Cue>()
        // Normalize line endings
        val normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalizedContent.split("\n\n")
        
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.size >= 2) {
                // Try to find timestamp line (contains -->)
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
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=0-4096") // Fetch first 4KB
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            
            val content = connection.inputStream.bufferedReader().use { it.readText() }
            
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
        textView.post {
            textView.visibility = android.view.View.GONE
            textView.text = ""
        }
    }
}
