package com.playbridge.sender.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
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
        val DETECT_VIDEOS = booleanPreferencesKey("detect_videos")
        val BLOCK_POPUPS = booleanPreferencesKey("block_popups")
        val POPUP_WHITELIST = stringSetPreferencesKey("popup_whitelist")
        val POPUP_BLACKLIST = stringSetPreferencesKey("popup_blacklist")
    }

    // 2. Flow definitions for reactive Compose collectors
    val autoSwitchToRemote: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.AUTO_SWITCH_TO_REMOTE] ?: true }
    val maxAliveTabs: Flow<Int> = dataStore.data.catch { handleException(it) }.map { it[Keys.MAX_ALIVE_TABS] ?: 5 }
    val preferredAudioLang: Flow<String> = dataStore.data.catch { handleException(it) }.map { it[Keys.PREFERRED_AUDIO_LANG] ?: "" }
    val preferredSubtitleLang: Flow<String> = dataStore.data.catch { handleException(it) }.map { it[Keys.PREFERRED_SUBTITLE_LANG] ?: "" }
    val defaultVideoQuality: Flow<String> = dataStore.data.catch { handleException(it) }.map { it[Keys.DEFAULT_VIDEO_QUALITY] ?: "Auto" }
    val maxBitrateCapMbps: Flow<Double> = dataStore.data.catch { handleException(it) }.map { it[Keys.MAX_BITRATE_CAP_MBPS] ?: 0.0 }
    val tvPlayerMode: Flow<String> = dataStore.data.catch { handleException(it) }.map { it[Keys.TV_PLAYER_MODE] ?: "tv" }
    val detectVideos: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.DETECT_VIDEOS] ?: true }
    val blockPopups: Flow<Boolean> = dataStore.data.catch { handleException(it) }.map { it[Keys.BLOCK_POPUPS] ?: true }
    val popupWhitelist: Flow<Set<String>> = dataStore.data.catch { handleException(it) }.map { it[Keys.POPUP_WHITELIST] ?: emptySet() }
    val popupBlacklist: Flow<Set<String>> = dataStore.data.catch { handleException(it) }.map { it[Keys.POPUP_BLACKLIST] ?: emptySet() }

    // 3. Mutator methods
    suspend fun setAutoSwitchToRemote(value: Boolean) = write { it[Keys.AUTO_SWITCH_TO_REMOTE] = value }
    suspend fun setMaxAliveTabs(value: Int) = write { it[Keys.MAX_ALIVE_TABS] = value }
    suspend fun setPreferredAudioLang(value: String) = write { it[Keys.PREFERRED_AUDIO_LANG] = value }
    suspend fun setPreferredSubtitleLang(value: String) = write { it[Keys.PREFERRED_SUBTITLE_LANG] = value }
    suspend fun setDefaultVideoQuality(value: String) = write { it[Keys.DEFAULT_VIDEO_QUALITY] = value }
    suspend fun setMaxBitrateCapMbps(value: Double) = write { it[Keys.MAX_BITRATE_CAP_MBPS] = value }
    suspend fun setTvPlayerMode(value: String) = write { it[Keys.TV_PLAYER_MODE] = value }
    suspend fun setDetectVideos(value: Boolean) = write { it[Keys.DETECT_VIDEOS] = value }
    suspend fun setBlockPopups(value: Boolean) = write { it[Keys.BLOCK_POPUPS] = value }
    
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
