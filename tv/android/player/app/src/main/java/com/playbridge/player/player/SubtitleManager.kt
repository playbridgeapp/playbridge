package com.playbridge.player.player

import android.util.Log
import com.playbridge.shared.logging.redactUrlForLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.IOException
import java.util.Collections

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
        Log.i(TAG, "Loading subtitle from: ${redactUrlForLog(url)}")
        subtitleJob?.cancel()
        syncJob?.cancel()
        lastCueText = null
        onCueChanged(null)
        cues.clear()

        subtitleJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                val bytes = downloadUrlBytes(url, headers)
                val content = SubtitleParser.decode(bytes)
                val isVtt = url.substringBefore('#').endsWith(".vtt", true) || content.startsWith("WEBVTT")
                val parsed = SubtitleParser.parse(content, isVtt)

                cues.addAll(parsed.map { Cue(it.startMs, it.endMs, it.text) })
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
                val cleanText = SubtitleParser.stripHtml(combinedText)
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
        val client = sniffer.getOkHttpClient(allowLocalSelfSigned = sniffer.isLocalUrl(urlString))
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

    fun disable() {
        subtitleJob?.cancel()
        syncJob?.cancel()
        lastCueText = null
        onCueChanged(null)
    }
}

/**
 * Display name for an external subtitle URL. The phone appends a "#<label>" fragment carrying a
 * human-readable language name (the fragment is never sent over HTTP, so the download is
 * unaffected). Falls back to the decoded filename, then a generic label.
 */
fun externalSubtitleName(url: String): String {
    val frag = url.substringAfter('#', "")
    if (frag.isNotEmpty()) {
        return runCatching { java.net.URLDecoder.decode(frag, "UTF-8") }.getOrDefault(frag)
    }
    return runCatching {
        android.net.Uri.parse(url).path?.substringAfterLast('/')
            ?.takeIf { it.isNotEmpty() }?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    }.getOrNull() ?: "External subtitle"
}
