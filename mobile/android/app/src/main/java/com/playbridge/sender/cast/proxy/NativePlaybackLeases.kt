package com.playbridge.sender.cast.proxy

import org.json.JSONObject

/** Owns the native-TV playback bundle at the common command dispatch boundary,
 * not in an Activity/ViewModel. Receiver status messages do not release it. */
internal class NativePlaybackLeases(private val retain: (String) -> AutoCloseable?) {
    private val held = mutableListOf<AutoCloseable>()

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
            }
            return sent
        } finally {
            incoming.forEach { it.close() }
        }
    }

    @Synchronized
    fun clear() {
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
            val resources = value?.optJSONArray("subtitle_resources")
            for (i in 0 until (resources?.length() ?: 0)) resource(resources?.optJSONObject(i))
            val visual = value?.optJSONObject("visual_metadata")
            for (key in listOf("artwork_url", "poster_url", "backdrop_url", "logo_url")) {
                visual?.optString(key)?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        val items = payload?.optJSONArray("items")
        for (i in 0 until (items?.length() ?: 0)) item(items?.optJSONObject(i))
        item(payload?.optJSONObject("item"))
        if (control?.startsWith("add_subtitle:") == true) add(control.substringAfter("add_subtitle:"))
        resource(payload?.optJSONObject("subtitle_resource"))
    }
}
