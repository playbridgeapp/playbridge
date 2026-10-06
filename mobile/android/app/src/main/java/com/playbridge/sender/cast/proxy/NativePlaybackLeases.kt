package com.playbridge.sender.cast.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Owns the native-TV playback bundle at the common command dispatch boundary,
 * not in an Activity/ViewModel. Transient receiver states do not release it. */
internal class NativePlaybackLeases(
    private val scope: CoroutineScope,
    private val retain: (String) -> AutoCloseable?,
) {
    private val held = mutableListOf<AutoCloseable>()
    private var idleJob: Job? = null
    private var idleReason: String? = null
    private var playbackId: String? = null

    /** Five minutes of receiver inactivity or a lost connection, not a playback cap. */
    @Synchronized
    fun inactive(reason: String = "connection") {
        if (held.isEmpty()) return
        if (idleJob != null) {
            if (reason == "receiver") idleReason = reason
            return
        }
        idleReason = reason
        idleJob = scope.launch {
            delay(IDLE_GRACE_MS)
            val context = currentCoroutineContext()
            synchronized(this@NativePlaybackLeases) {
                context.ensureActive()
                clear()
            }
        }
    }

    @Synchronized
    fun active(reason: String? = null) {
        if (reason != null && idleReason != reason) return
        idleJob?.cancel()
        idleJob = null
        idleReason = null
    }

    @Synchronized
    fun observe(message: String) {
        val obj = runCatching { JSONObject(message) }.getOrNull() ?: return
        val id = obj.optString("playbackId").takeIf { it.isNotBlank() }
        val state = obj.optString("state").lowercase()
        val hasActivity = state in setOf("playing", "paused", "buffering")
        if (!hasActivity && id != null && playbackId != null && id != playbackId) return
        when (obj.optString("type")) {
            "status" -> when (state) {
                "playing", "paused", "buffering" -> { playbackId = id ?: playbackId; active() }
                "idle", "stopped", "ended", "finished", "complete", "none", "error" -> inactive("receiver")
            }
            "context" -> when (obj.optString("active")) {
                "idle" -> inactive("receiver")
                "player" -> active()
            }
            "playlist_status" -> obj.optJSONArray("items")?.let {
                if (it.length() == 0) inactive("playlist") else active("playlist")
            }
            "error" -> inactive("receiver")
        }
    }

    @Synchronized
    fun send(message: String, transport: () -> Boolean): Boolean {
        val envelope = runCatching { JSONObject(message) }.getOrNull()
        val action = envelope?.takeIf { it.optString("type") == "command" }?.optString("action")
        val payload = envelope?.optJSONObject("payload")
        val control = if (action == "control") payload?.optString("command") else null
        val replace = action == "playlist" || action == "browser"
        val append = action == "queue_add" || control?.startsWith("add_subtitle") == true
        if (control == "stop") {
            // Explicit local stop must revoke even when the socket is unavailable.
            clear()
            return transport()
        }
        if (!replace && !append) return transport()
        val incoming = mutableListOf<AutoCloseable>()
        try {
            if (action != "browser") urls(payload, control).forEach { url ->
                retain(url)?.let(incoming::add)
            }
            val sent = transport()
            if (sent) {
                if (replace) clear()
                held.addAll(incoming)
                incoming.clear()
                if (replace || action == "queue_add") active()
            }
            return sent
        } finally {
            incoming.forEach { it.close() }
        }
    }

    @Synchronized
    fun clear() {
        active()
        playbackId = null
        held.forEach { it.close() }
        held.clear()
    }

    private fun urls(payload: JSONObject?, control: String?): Set<String> = buildSet {
        fun resource(value: JSONObject?) {
            value?.optString("url")?.takeIf { it.isNotBlank() }?.let(::add)
        }
        fun item(value: JSONObject?) {
            resource(value)
            val subtitles = value?.optJSONArray("subtitles")
            for (i in 0 until (subtitles?.length() ?: 0)) {
                subtitles?.optString(i)?.takeIf { it.isNotBlank() }?.let(::add)
            }
            for (name in listOf("subtitleResources", "subtitle_resources")) {
                val resources = value?.optJSONArray(name)
                for (i in 0 until (resources?.length() ?: 0)) resource(resources?.optJSONObject(i))
            }
            for (name in listOf("visualMetadata", "visual_metadata")) {
                val visual = value?.optJSONObject(name)
                for (key in listOf("artworkUrl", "artwork_url", "posterUrl", "poster_url",
                    "backdropUrl", "backdrop_url", "logoUrl", "logo_url")) {
                    visual?.optString(key)?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        val items = payload?.optJSONArray("items")
        for (i in 0 until (items?.length() ?: 0)) item(items?.optJSONObject(i))
        item(payload?.optJSONObject("item"))
        if (control?.startsWith("add_subtitle:") == true) add(control.substringAfter("add_subtitle:"))
        resource(payload?.optJSONObject("subtitleResource"))
        resource(payload?.optJSONObject("subtitle_resource"))
    }

    companion object {
        internal const val IDLE_GRACE_MS = 5 * 60_000L
    }
}
