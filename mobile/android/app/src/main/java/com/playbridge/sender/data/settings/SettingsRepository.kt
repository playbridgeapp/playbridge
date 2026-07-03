package com.playbridge.sender.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.playbridge.sender.browser.CustomUserAgent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class SettingsRepository(
    private val dataStore: DataStore<Preferences>
) {
    private val TAG = "SettingsRepository"

    // 1. Define Preference Keys
    object Keys {
        val AUTO_SWITCH_TO_REMOTE = booleanPreferencesKey("auto_switch_to_remote")
        val MAX_ALIVE_TABS = intPreferencesKey("max_alive_tabs")
        val PREFERRED_AUDIO_LANG = stringPreferencesKey("preferred_audio_language")
        val PREFERRED_SUBTITLE_LANG = stringPreferencesKey("preferred_subtitle_language")
        val DEFAULT_VIDEO_QUALITY = stringPreferencesKey("default_video_quality")
        val MAX_BITRATE_CAP_MBPS = doublePreferencesKey("max_bitrate_cap_mbps")
        val TV_PLAYER_MODE = stringPreferencesKey("tv_player_mode")
        val TV_PREFETCH_WINDOW = intPreferencesKey("tv_prefetch_window")
        val DETECT_VIDEOS = booleanPreferencesKey("detect_videos")
        val TRACK_WATCH_PROGRESS = booleanPreferencesKey("track_watch_progress")
        val AUTO_ADD_TO_WATCHING = booleanPreferencesKey("auto_add_to_watching")
        val BLOCK_POPUPS = booleanPreferencesKey("block_popups")
        val POPUP_WHITELIST = stringSetPreferencesKey("popup_whitelist")
        val POPUP_BLACKLIST = stringSetPreferencesKey("popup_blacklist")
        val IPTV_SORT = stringPreferencesKey("iptv_sort")           // "ADDED_DATE" | "NAME"
        val IPTV_SORT_ASCENDING = booleanPreferencesKey("iptv_sort_ascending")
        val IPTV_ACTIVE_FIRST = booleanPreferencesKey("iptv_active_first")
        val SEND_SUBTITLES_TO_TV = booleanPreferencesKey("send_subtitles_to_tv")
        val LOGS_EXCLUDE_FILTERS = stringSetPreferencesKey("logs_exclude_filters")
        /** A built-in [com.playbridge.sender.browser.UserAgentPresets] preset id, or a "custom:&lt;id&gt;" selection. */
        val USER_AGENT_PRESET = stringPreferencesKey("user_agent_preset")
        /** JSON array of saved {id, name, value} custom user agents the person has added. */
        val CUSTOM_USER_AGENTS = stringPreferencesKey("custom_user_agents")
        /** Master switch for Nuvio local JS scrapers. Default OFF — they run third-party code. */
        val ENABLE_LOCAL_SCRAPERS = booleanPreferencesKey("enable_local_scrapers")
    }

    // 2. Flow definitions for reactive Compose collectors
    val autoSwitchToRemote: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.AUTO_SWITCH_TO_REMOTE] ?: true }
    val maxAliveTabs: Flow<Int> = dataStore.data.catch { handleException(it) }.map { it[Keys.MAX_ALIVE_TABS] ?: 5 }
    val preferredAudioLang: Flow<String> = dataStore.data.catch { handleException(it) }.map { it[Keys.PREFERRED_AUDIO_LANG] ?: "" }
    val preferredSubtitleLang: Flow<String> = dataStore.data.catch { handleException(it) }.map { it[Keys.PREFERRED_SUBTITLE_LANG] ?: "" }
    val defaultVideoQuality: Flow<String> = dataStore.data.catch { handleException(it) }.map { it[Keys.DEFAULT_VIDEO_QUALITY] ?: "Auto" }
    val maxBitrateCapMbps: Flow<Double> = dataStore.data.catch { handleException(it) }.map { it[Keys.MAX_BITRATE_CAP_MBPS] ?: 0.0 }
    val tvPlayerMode: Flow<String> = dataStore.data.catch { handleException(it) }.map { it[Keys.TV_PLAYER_MODE] ?: "tv" }
    /** How many episodes to keep resolved & queued ahead on the TV for series without a play-endpoint addon. */
    val tvPrefetchWindow: Flow<Int> = dataStore.data.catch { handleException(it) }.map { (it[Keys.TV_PREFETCH_WINDOW] ?: 1).coerceIn(1, 10) }
    val detectVideos: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.DETECT_VIDEOS] ?: true }
    /** Automatically update watchlist progress / watched state from TV playback. */
    val trackWatchProgress: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.TRACK_WATCH_PROGRESS] ?: true }
    /** When auto-tracking, add untracked shows/movies to the watchlist as Watching. */
    val autoAddToWatching: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.AUTO_ADD_TO_WATCHING] ?: true }
    val blockPopups: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.BLOCK_POPUPS] ?: true }
    val popupWhitelist: Flow<Set<String>> = dataStore.data.catch { handleException(it) }.map { it[Keys.POPUP_WHITELIST] ?: emptySet() }
    val popupBlacklist: Flow<Set<String>> = dataStore.data.catch { handleException(it) }.map { it[Keys.POPUP_BLACKLIST] ?: emptySet() }
    /** IPTV playlist sort key: "ADDED_DATE" (default) or "NAME". */
    val iptvSort: Flow<String> = dataStore.data.catch { handleException(it) }.map { it[Keys.IPTV_SORT] ?: "ADDED_DATE" }
    val iptvSortAscending: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.IPTV_SORT_ASCENDING] ?: false }
    /** When exploring a playlist, float probe-confirmed live channels to the top. */
    val iptvActiveFirst: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.IPTV_ACTIVE_FIRST] ?: true }
    val sendSubtitlesToTv: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.SEND_SUBTITLES_TO_TV] ?: true }
    val logsExcludeFilters: Flow<Set<String>> = dataStore.data.catch { handleException(it) }.map { it[Keys.LOGS_EXCLUDE_FILTERS] ?: emptySet() }
    val userAgentPreset: Flow<String> = dataStore.data.catch { handleException(it) }.map { it[Keys.USER_AGENT_PRESET] ?: "default" }
    val customUserAgents: Flow<List<CustomUserAgent>> = dataStore.data.catch { handleException(it) }
        .map { decodeCustomUserAgents(it[Keys.CUSTOM_USER_AGENTS] ?: "[]") }
    /** Whether Nuvio local JS scrapers may run. Default false (opt-in; runs third-party code). */
    val enableLocalScrapers: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.ENABLE_LOCAL_SCRAPERS] ?: false }

    // 3. Mutator methods
    suspend fun setAutoSwitchToRemote(value: Boolean) = write { it[Keys.AUTO_SWITCH_TO_REMOTE] = value }
    suspend fun setMaxAliveTabs(value: Int) = write { it[Keys.MAX_ALIVE_TABS] = value }
    suspend fun setPreferredAudioLang(value: String) = write { it[Keys.PREFERRED_AUDIO_LANG] = value }
    suspend fun setPreferredSubtitleLang(value: String) = write { it[Keys.PREFERRED_SUBTITLE_LANG] = value }
    suspend fun setDefaultVideoQuality(value: String) = write { it[Keys.DEFAULT_VIDEO_QUALITY] = value }
    suspend fun setMaxBitrateCapMbps(value: Double) = write { it[Keys.MAX_BITRATE_CAP_MBPS] = value }
    suspend fun setTvPlayerMode(value: String) = write { it[Keys.TV_PLAYER_MODE] = value }
    suspend fun setTvPrefetchWindow(value: Int) = write { it[Keys.TV_PREFETCH_WINDOW] = value.coerceIn(1, 10) }
    suspend fun setDetectVideos(value: Boolean) = write { it[Keys.DETECT_VIDEOS] = value }
    suspend fun setTrackWatchProgress(value: Boolean) = write { it[Keys.TRACK_WATCH_PROGRESS] = value }
    suspend fun setAutoAddToWatching(value: Boolean) = write { it[Keys.AUTO_ADD_TO_WATCHING] = value }
    suspend fun setBlockPopups(value: Boolean) = write { it[Keys.BLOCK_POPUPS] = value }
    suspend fun setIptvSort(value: String) = write { it[Keys.IPTV_SORT] = value }
    suspend fun setIptvSortAscending(value: Boolean) = write { it[Keys.IPTV_SORT_ASCENDING] = value }
    suspend fun setIptvActiveFirst(value: Boolean) = write { it[Keys.IPTV_ACTIVE_FIRST] = value }
    suspend fun setSendSubtitlesToTv(value: Boolean) = write { it[Keys.SEND_SUBTITLES_TO_TV] = value }
    suspend fun setLogsExcludeFilters(value: Set<String>) = write { it[Keys.LOGS_EXCLUDE_FILTERS] = value }
    suspend fun setUserAgentPreset(value: String) = write { it[Keys.USER_AGENT_PRESET] = value }
    suspend fun setEnableLocalScrapers(value: Boolean) = write { it[Keys.ENABLE_LOCAL_SCRAPERS] = value }

    /** Append a new saved custom user agent. */
    suspend fun addCustomUserAgent(agent: CustomUserAgent) = write { prefs ->
        val current = decodeCustomUserAgents(prefs[Keys.CUSTOM_USER_AGENTS] ?: "[]")
        prefs[Keys.CUSTOM_USER_AGENTS] = encodeCustomUserAgents(current + agent)
    }

    /** Remove a saved custom user agent by id. */
    suspend fun removeCustomUserAgent(id: String) = write { prefs ->
        val current = decodeCustomUserAgents(prefs[Keys.CUSTOM_USER_AGENTS] ?: "[]")
        prefs[Keys.CUSTOM_USER_AGENTS] = encodeCustomUserAgents(current.filter { it.id != id })
    }

    private fun encodeCustomUserAgents(list: List<CustomUserAgent>): String {
        val arr = JSONArray()
        list.forEach { agent ->
            arr.put(
                JSONObject().apply {
                    put("id", agent.id)
                    put("name", agent.name)
                    put("value", agent.value)
                }
            )
        }
        return arr.toString()
    }

    private fun decodeCustomUserAgents(json: String): List<CustomUserAgent> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CustomUserAgent(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    value = obj.getString("value"),
                )
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error decoding custom user agents", e)
            emptyList()
        }
    }


    suspend fun addPopupWhitelist(host: String) = write { prefs ->
        val current = prefs[Keys.POPUP_WHITELIST] ?: emptySet()
        prefs[Keys.POPUP_WHITELIST] = current + host
    }

    suspend fun removePopupWhitelist(host: String) = write { prefs ->
        val current = prefs[Keys.POPUP_WHITELIST] ?: emptySet()
        val target = host.trim().lowercase()
        prefs[Keys.POPUP_WHITELIST] = current.filter { exception ->
            val ext = exception.trim().lowercase()
            ext != target && !target.endsWith(".$ext") && !ext.endsWith(".$target")
        }.toSet()
    }

    suspend fun savePopupWhitelist(hosts: Set<String>) = write { prefs ->
        prefs[Keys.POPUP_WHITELIST] = hosts
    }

    suspend fun addPopupBlacklist(host: String) = write { prefs ->
        val current = prefs[Keys.POPUP_BLACKLIST] ?: emptySet()
        prefs[Keys.POPUP_BLACKLIST] = current + host
    }

    suspend fun removePopupBlacklist(host: String) = write { prefs ->
        val current = prefs[Keys.POPUP_BLACKLIST] ?: emptySet()
        val target = host.trim().lowercase()
        prefs[Keys.POPUP_BLACKLIST] = current.filter { exception ->
            val ext = exception.trim().lowercase()
            ext != target && !target.endsWith(".$ext") && !ext.endsWith(".$target")
        }.toSet()
    }

    suspend fun savePopupBlacklist(hosts: Set<String>) = write { prefs ->
        prefs[Keys.POPUP_BLACKLIST] = hosts
    }

    private suspend fun write(block: (MutablePreferences) -> Unit) {
        try {
            dataStore.edit(block)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error writing preferences", e)
        }
    }

    private fun handleException(throwable: Throwable): Preferences {
        if (throwable is IOException) {
            android.util.Log.e(TAG, "Error reading preferences", throwable)
            return emptyPreferences()
        }
        throw throwable
    }
}
