package com.playbridge.sender.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.playbridge.sender.browser.CustomUserAgent
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.sync.withLock
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
        val MEDIA_DETECTION = stringPreferencesKey("media_detection")
        val BLOCK_POPUPS = booleanPreferencesKey("block_popups")
        val POPUP_WHITELIST = stringSetPreferencesKey("popup_whitelist")
        val POPUP_BLACKLIST = stringSetPreferencesKey("popup_blacklist")
        val IPTV_SORT = stringPreferencesKey("iptv_sort")           // "ADDED_DATE" | "NAME"
        val IPTV_SORT_ASCENDING = booleanPreferencesKey("iptv_sort_ascending")
        val IPTV_ACTIVE_FIRST = booleanPreferencesKey("iptv_active_first")
        val HIDE_EMPTY_CAST_SHEET_TABS = booleanPreferencesKey("hide_empty_cast_sheet_tabs")
        val LOGS_EXCLUDE_FILTERS = stringSetPreferencesKey("logs_exclude_filters")
        /** A built-in [com.playbridge.sender.browser.UserAgentPresets] preset id, or a "custom:&lt;id&gt;" selection. */
        val USER_AGENT_PRESET = stringPreferencesKey("user_agent_preset")
        /** JSON array of saved {id, name, value} custom user agents the person has added. */
        val CUSTOM_USER_AGENTS = stringPreferencesKey("custom_user_agents")
        /**
         * Keep an idle native TV WebSocket (and Connected FGS) while PlayBridge is
         * backgrounded. Default OFF — soft-stand-down after a grace period saves battery.
         * Casting always keeps the link regardless of this toggle.
         */
        val KEEP_TV_CONNECTION_IN_BACKGROUND = booleanPreferencesKey("keep_tv_connection_in_background")
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
    val mediaDetectionSettings: Flow<MediaDetectionSettings> = dataStore.data.catch { handleException(it) }.map { prefs ->
        MediaDetectionSettings.decode(prefs[Keys.MEDIA_DETECTION]).copy(enabled = prefs[Keys.DETECT_VIDEOS] ?: true)
    }
    val blockPopups: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.BLOCK_POPUPS] ?: true }
    val popupWhitelist: Flow<Set<String>> = dataStore.data.catch { handleException(it) }.map { it[Keys.POPUP_WHITELIST] ?: emptySet() }
    val popupBlacklist: Flow<Set<String>> = dataStore.data.catch { handleException(it) }.map { it[Keys.POPUP_BLACKLIST] ?: emptySet() }
    /** IPTV playlist sort key: "ADDED_DATE" (default) or "NAME". */
    val iptvSort: Flow<String> = dataStore.data.catch { handleException(it) }.map { it[Keys.IPTV_SORT] ?: "ADDED_DATE" }
    val iptvSortAscending: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.IPTV_SORT_ASCENDING] ?: false }
    /** When exploring a playlist, float probe-confirmed live channels to the top. */
    val iptvActiveFirst: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.IPTV_ACTIVE_FIRST] ?: true }
    val hideEmptyCastSheetTabs: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.HIDE_EMPTY_CAST_SHEET_TABS] ?: true }
    val logsExcludeFilters: Flow<Set<String>> = dataStore.data.catch { handleException(it) }.map { it[Keys.LOGS_EXCLUDE_FILTERS] ?: emptySet() }
    val userAgentPreset: Flow<String> = dataStore.data.catch { handleException(it) }.map { it[Keys.USER_AGENT_PRESET] ?: "default" }
    val customUserAgents: Flow<List<CustomUserAgent>> = dataStore.data.catch { handleException(it) }
        .map { decodeCustomUserAgents(it[Keys.CUSTOM_USER_AGENTS] ?: "[]") }
    /**
     * Keep idle TV link + Connected notification while the app is backgrounded.
     * Default false — approach B stand-down (close socket after grace when not casting).
     */
    val keepTvConnectionInBackground: Flow<Boolean> =
        dataStore.data.catch { handleException(it) }.map { it[Keys.KEEP_TV_CONNECTION_IN_BACKGROUND] ?: false }

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
    suspend fun setMediaDetectionSettings(value: MediaDetectionSettings) = write {
        it[Keys.DETECT_VIDEOS] = value.enabled
        it[Keys.MEDIA_DETECTION] = MediaDetectionSettings.json.encodeToString(value)
    }
    suspend fun setBlockPopups(value: Boolean) = write { it[Keys.BLOCK_POPUPS] = value }
    suspend fun setIptvSort(value: String) = write { it[Keys.IPTV_SORT] = value }
    suspend fun setIptvSortAscending(value: Boolean) = write { it[Keys.IPTV_SORT_ASCENDING] = value }
    suspend fun setIptvActiveFirst(value: Boolean) = write { it[Keys.IPTV_ACTIVE_FIRST] = value }
    suspend fun setHideEmptyCastSheetTabs(value: Boolean) = write { it[Keys.HIDE_EMPTY_CAST_SHEET_TABS] = value }
    suspend fun setLogsExcludeFilters(value: Set<String>) = write { it[Keys.LOGS_EXCLUDE_FILTERS] = value }
    suspend fun setUserAgentPreset(value: String) = write { it[Keys.USER_AGENT_PRESET] = value }
    suspend fun setKeepTvConnectionInBackground(value: Boolean) =
        write { it[Keys.KEEP_TV_CONNECTION_IN_BACKGROUND] = value }

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
    suspend fun migrateLegacyPreferences(prefs: android.content.SharedPreferences) = legacyMigrationMutex.withLock {
        if (!prefs.getBoolean("needs_datastore_migration", true)) return@withLock
        dataStore.edit { values ->
            values[Keys.AUTO_SWITCH_TO_REMOTE] = prefs.getBoolean("auto_switch_to_remote", true)
            values[Keys.MAX_ALIVE_TABS] = prefs.getInt("max_alive_tabs", 5)
            values[Keys.PREFERRED_AUDIO_LANG] = prefs.getString("preferred_audio_language", null)
                ?: prefs.getString("preferred_audio_lang", "").orEmpty()
            values[Keys.PREFERRED_SUBTITLE_LANG] = prefs.getString("preferred_subtitle_language", null)
                ?: prefs.getString("preferred_subtitle_lang", "").orEmpty()
            values[Keys.DEFAULT_VIDEO_QUALITY] = prefs.getString("default_video_quality", "Auto") ?: "Auto"
            values[Keys.MAX_BITRATE_CAP_MBPS] = prefs.getString("auto_stream_max_mbps", null)?.toDoubleOrNull()
                ?: prefs.getInt("max_bitrate_cap_mbps", 0).toDouble()
            values[Keys.TV_PLAYER_MODE] = prefs.getString("tv_player_mode", "tv") ?: "tv"
            values[Keys.DETECT_VIDEOS] = prefs.getBoolean("detect_videos", true)
            values[Keys.BLOCK_POPUPS] = prefs.getBoolean("block_popups", true)
        }
        check(prefs.edit().putBoolean("needs_datastore_migration", false).commit())
    }

    private companion object {
        val legacyMigrationMutex = kotlinx.coroutines.sync.Mutex()
    }

}
