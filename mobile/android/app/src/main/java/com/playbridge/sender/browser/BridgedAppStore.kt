package com.playbridge.sender.browser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** A website that explicitly opts in to PlayBridge's embedded app experience. */
data class BridgedApp(
    val origin: String,
    val name: String,
    val startUrl: String,
    val iconUrl: String?,
    val tabId: String? = null,
) {
    fun editing(name: String, homeUrl: String): BridgedApp? {
        val trimmedName = name.trim()
        val trimmedUrl = homeUrl.trim()
        if (trimmedName.isEmpty() || trimmedName.length > 60 ||
            BridgedAppStore.originFor(trimmedUrl) != origin) return null
        return copy(name = trimmedName, startUrl = trimmedUrl)
    }
}

/** The installed list is local to this PlayBridge installation; cast grants remain separate. */
class BridgedAppStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bridged_apps", Context.MODE_PRIVATE)
    private val mutableApps = MutableStateFlow(readAndSeed())
    val apps: StateFlow<List<BridgedApp>> = mutableApps

    private fun readAndSeed(): List<BridgedApp> {
        val current = read()
        val alreadySeeded = prefs.getBoolean(PREF_KEY_SEEDED_STREAMS_LIBRARY, false)
        if (alreadySeeded) return current

        val seeded = seedStreamsLibrary(current, alreadySeeded = false)
        prefs.edit().putBoolean(PREF_KEY_SEEDED_STREAMS_LIBRARY, true).apply()
        if (seeded != current) {
            write(seeded)
        }
        return seeded
    }

    fun install(app: BridgedApp) = update { existing ->
        existing.filterNot { it.origin == app.origin } + app.copy(tabId = existing.find { it.origin == app.origin }?.tabId)
    }

    fun edit(origin: String, name: String, homeUrl: String): Boolean {
        val edited = mutableApps.value.find { it.origin == origin }?.editing(name, homeUrl) ?: return false
        update { apps -> apps.map { if (it.origin == origin) edited else it } }
        return true
    }

    fun remove(origin: String) = update { it.filterNot { app -> app.origin == origin } }

    fun setTab(origin: String, tabId: String?) = update { apps ->
        apps.map { if (it.origin == origin) it.copy(tabId = tabId) else it }
    }

    private fun write(apps: List<BridgedApp>) {
        prefs.edit().putString("installed", JSONArray().apply {
            apps.forEach { app ->
                put(JSONObject().apply {
                    put("origin", app.origin)
                    put("name", app.name)
                    put("startUrl", app.startUrl)
                    put("iconUrl", app.iconUrl)
                    put("tabId", app.tabId)
                })
            }
        }.toString()).apply()
    }

    private fun update(transform: (List<BridgedApp>) -> List<BridgedApp>) {
        val next = transform(mutableApps.value)
        write(next)
        mutableApps.value = next
    }

    private fun read(): List<BridgedApp> = runCatching {
        val array = JSONArray(prefs.getString("installed", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val origin = item.optString("origin")
            val startUrl = item.optString("startUrl")
            if (originFor(startUrl) != origin) return@mapNotNull null
            BridgedApp(origin, item.optString("name"), startUrl,
                item.optString("iconUrl").takeIf { it.isNotBlank() && it != "null" },
                item.optString("tabId").takeIf { it.isNotBlank() && it != "null" })
        }
    }.getOrDefault(emptyList())

    companion object {
        fun originFor(value: String): String? {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.rawUserInfo != null ||
                (scheme != "https" && (scheme != "http" || !isLocalHost(host)))) return null
            val port = uri.port
            if (port == 0 || port > 65535) return null
            val defaultPort = if (scheme == "https") 443 else 80
            return "$scheme://$host${if (port > 0 && port != defaultPort) ":$port" else ""}"
        }

        private fun isLocalHost(host: String): Boolean {
            if (host == "localhost") return true
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { char -> !char.isDigit() } }) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return octets[0] == 10 || octets[0] == 127 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }

        internal fun isExternalWebNavigation(appOrigin: String, targetUrl: String): Boolean {
            val target = runCatching { URI(targetUrl) }.getOrNull() ?: return false
            val scheme = target.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            if (target.host.isNullOrBlank()) return false
            return originFor(targetUrl) != appOrigin
        }

        internal fun parseManifest(origin: String, json: JSONObject): BridgedApp? {
            if (json.opt("protocol") != "playbridge-app-v1") return null
            val name = (json.opt("name") as? String)?.trim()?.take(60) ?: return null
            if (name.isEmpty()) return null
            val startPath = if (json.isNull("start_url")) "/" else json.opt("start_url") as? String ?: return null
            val startUrl = runCatching { URL(URL("$origin/"), startPath).toString() }
                .getOrNull() ?: return null
            if (originFor(startUrl) != origin) return null
            val iconUrl = json.optString("icon_url").takeIf { it.isNotBlank() }
                ?.let { runCatching { URL(URL("$origin/"), it).toString() }.getOrNull() }
                ?.takeIf { originFor(it) == origin }
            return BridgedApp(origin, name, startUrl, iconUrl)
        }

        /** A fixed same-origin document is an explicit opt-in; the injected JS API is on every page. */
        suspend fun discover(pageUrl: String): BridgedApp? = withContext(Dispatchers.IO) {
            val origin = originFor(pageUrl) ?: return@withContext null
            runCatching {
                val connection = (URL("$origin/.well-known/playbridge-app.json").openConnection() as HttpURLConnection)
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000
                    connection.setRequestProperty("Accept", "application/json")
                    if (connection.responseCode != 200 || connection.contentLengthLong > 16384L) return@runCatching null
                    val bytes = connection.inputStream.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 16384) return@runCatching null
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    parseManifest(origin, JSONObject(bytes.toString(Charsets.UTF_8)))
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }

        const val STREAMS_ORIGIN = "https://streams.playbridge.app"
        const val STREAMS_START_URL = "https://streams.playbridge.app/"
        const val STREAMS_DEFAULT_NAME = "Library"
        const val PREF_KEY_SEEDED_STREAMS_LIBRARY = "seeded_streams_library_v1"

        /**
         * Pure seed logic for preinstalling the Streams bridged app.
         *
         * @param existing The currently installed apps.
         * @param alreadySeeded Whether the one-time seed flag has already been set.
         * @return The updated list of apps. If already seeded or Streams is already installed,
         *         the list is returned unchanged. Otherwise, a new BridgedApp entry is added.
         */
        fun seedStreamsLibrary(existing: List<BridgedApp>, alreadySeeded: Boolean): List<BridgedApp> {
            if (alreadySeeded) return existing
            if (existing.any { it.origin == STREAMS_ORIGIN }) return existing
            val defaultStreamsApp = BridgedApp(
                origin = STREAMS_ORIGIN,
                name = STREAMS_DEFAULT_NAME,
                startUrl = STREAMS_START_URL,
                iconUrl = null,
            )
            return existing + defaultStreamsApp
        }
    }
}
