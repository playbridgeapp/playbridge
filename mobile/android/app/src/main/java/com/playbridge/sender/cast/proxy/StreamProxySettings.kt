package com.playbridge.sender.cast.proxy

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Stream-proxy preferences: remote host for [StreamRouteMode.VIA_PROXY] and the
 * default cast-sheet route. Migrates one-shot from legacy mediaflow keys.
 */
data class StreamProxySettings(
    val remoteBaseUrl: String = "",
    val remotePassword: String = "",
    val defaultRoute: StreamRouteMode = StreamRouteMode.DIRECT,
) {
    val isRemoteConfigured: Boolean
        get() = remoteBaseUrl.isNotBlank()

    /** Initial sheet mode: never start on Via proxy when remote is empty. */
    fun initialRouteMode(): StreamRouteMode =
        if (defaultRoute == StreamRouteMode.VIA_PROXY && !isRemoteConfigured) {
            StreamRouteMode.DIRECT
        } else {
            defaultRoute
        }
}

object StreamProxySettingsStore {
    const val PREFS_NAME = "browser_settings"

    const val KEY_REMOTE_URL = "stream_proxy_remote_url"
    const val KEY_REMOTE_PASSWORD = "stream_proxy_remote_password"
    const val KEY_DEFAULT_ROUTE = "stream_route_default"

    // Legacy mediaflow keys (read once for migration).
    private const val LEGACY_URL = "mediaflow_proxy_url"
    private const val LEGACY_PASSWORD = "mediaflow_proxy_password"
    private const val MIGRATION_DONE = "stream_proxy_migrated_from_mediaflow"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): StreamProxySettings {
        val p = prefs(context)
        migrateFromMediaflowIfNeeded(p)
        return StreamProxySettings(
            remoteBaseUrl = p.getString(KEY_REMOTE_URL, "")?.trim().orEmpty(),
            remotePassword = p.getString(KEY_REMOTE_PASSWORD, "").orEmpty(),
            defaultRoute = StreamRouteMode.fromPrefs(p.getString(KEY_DEFAULT_ROUTE, null)),
        )
    }

    fun save(context: Context, settings: StreamProxySettings) {
        prefs(context).edit {
            putString(KEY_REMOTE_URL, settings.remoteBaseUrl.trim())
            putString(KEY_REMOTE_PASSWORD, settings.remotePassword)
            putString(KEY_DEFAULT_ROUTE, settings.defaultRoute.prefsValue)
        }
    }

    private fun migrateFromMediaflowIfNeeded(p: SharedPreferences) {
        if (p.getBoolean(MIGRATION_DONE, false)) return
        val legacyUrl = p.getString(LEGACY_URL, null)?.trim().orEmpty()
        val legacyPassword = p.getString(LEGACY_PASSWORD, null).orEmpty()
        val alreadyHas = !p.getString(KEY_REMOTE_URL, null).isNullOrBlank()
        p.edit {
            if (!alreadyHas && legacyUrl.isNotEmpty()) {
                putString(KEY_REMOTE_URL, legacyUrl)
                putString(KEY_REMOTE_PASSWORD, legacyPassword)
            }
            putBoolean(MIGRATION_DONE, true)
        }
    }
}
