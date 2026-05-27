package com.playbridge.sender.data.backup

import android.content.Context
import com.playbridge.sender.browser.Components
import com.playbridge.sender.settings.ExportedBookmark
import com.playbridge.sender.settings.ExportedSettings
import com.playbridge.sender.settings.ExportedTab
import com.playbridge.sender.settings.ExportedWatchlist
import com.playbridge.sender.data.debrid.DebridRepository
import com.playbridge.sender.data.history.BookmarkEntity
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.library.AddonRepository
import com.playbridge.sender.data.library.WatchlistEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.TabSessionState

object BackupUtils {

    suspend fun createExportJson(context: Context): String = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val tmdbPrefs = context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
        val database = DatabaseProvider.getDatabase(context)
        val addonDao = database.addonDao()

        val addons = addonDao.getAllSync()
        val currentBookmarks = database.bookmarkDao().getAllSync().map { 
            ExportedBookmark(url = it.url, title = it.title) 
        }
        val currentTabs = database.tabDao().getAll().map { 
            ExportedTab(id = it.id, url = it.url, title = it.title, parentId = it.parentId) 
        }
        val currentWatchlist = database.watchlistDao().getAllSync().map {
            ExportedWatchlist(
                tmdbId = it.tmdbId,
                mediaType = it.mediaType,
                title = it.title,
                posterUrl = it.posterUrl,
                year = it.year,
                rating = it.rating,
                addedAt = it.addedAt
            )
        }

        val exported = ExportedSettings(
            debridProvider = tmdbPrefs.getString(DebridRepository.KEY_DEBRID_PROVIDER, DebridRepository.PROVIDER_NONE),
            debridApiKey = tmdbPrefs.getString(DebridRepository.KEY_DEBRID_API_KEY, ""),
            debridApiKeys = DebridRepository.ALL_PROVIDERS.associateWith {
                tmdbPrefs.getString(DebridRepository.apiKeyPrefName(it), "") ?: ""
            }.filterValues { it.isNotBlank() },
            tmdbApiKey = tmdbPrefs.getString("tmdb_api_key", ""),
            omdbApiKey = tmdbPrefs.getString("omdb_api_key", ""),
            tvPlayerMode = prefs.getString("tv_player_mode", "tv"),
            tvBrowserMode = prefs.getString("tv_browser_mode", "tv"),
            addonUrls = addons.map { it.manifestUrl },
            tabs = currentTabs,
            bookmarks = currentBookmarks,
            watchlist = currentWatchlist,
            mediaflowProxyUrl = tmdbPrefs.getString(com.playbridge.sender.cast.MediaflowProxy.PREFS_KEY_URL, ""),
            mediaflowProxyPassword = tmdbPrefs.getString(com.playbridge.sender.cast.MediaflowProxy.PREFS_KEY_PASSWORD, ""),
        )

        Json { prettyPrint = true; encodeDefaults = false }.encodeToString(exported)
    }

    suspend fun importFromJson(context: Context, jsonString: String): ImportResult = withContext(Dispatchers.IO) {
        try {
            val imported = Json { ignoreUnknownKeys = true }.decodeFromString<ExportedSettings>(jsonString)
            
            val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
            val tmdbPrefs = context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
            val database = DatabaseProvider.getDatabase(context)
            val addonRepository = AddonRepository(database.addonDao())

            prefs.edit().apply {
                if (imported.tvPlayerMode != null) putString("tv_player_mode", imported.tvPlayerMode)
                if (imported.tvBrowserMode != null) putString("tv_browser_mode", imported.tvBrowserMode)
                apply()
            }

            tmdbPrefs.edit().apply {
                if (imported.tmdbApiKey != null) putString("tmdb_api_key", imported.tmdbApiKey)
                if (imported.omdbApiKey != null) putString("omdb_api_key", imported.omdbApiKey)
                if (imported.debridProvider != null) putString(DebridRepository.KEY_DEBRID_PROVIDER, imported.debridProvider)
                if (imported.debridApiKey != null) putString(DebridRepository.KEY_DEBRID_API_KEY, imported.debridApiKey)
                imported.debridApiKeys?.forEach { (provider, key) ->
                    putString(DebridRepository.apiKeyPrefName(provider), key)
                }
                if (imported.mediaflowProxyUrl != null) putString(com.playbridge.sender.cast.MediaflowProxy.PREFS_KEY_URL, imported.mediaflowProxyUrl)
                if (imported.mediaflowProxyPassword != null) putString(com.playbridge.sender.cast.MediaflowProxy.PREFS_KEY_PASSWORD, imported.mediaflowProxyPassword)
                apply()
            }

            imported.addonUrls.forEach { url -> addonRepository.installAddon(url) }

            if (imported.bookmarks != null) {
                val bookmarkDao = database.bookmarkDao()
                imported.bookmarks.forEach { bookmark ->
                    bookmarkDao.insert(BookmarkEntity(url = bookmark.url, title = bookmark.title))
                }
            }

            if (imported.watchlist != null) {
                val watchlistDao = database.watchlistDao()
                imported.watchlist.forEach { item ->
                    watchlistDao.insert(WatchlistEntity(
                        tmdbId = item.tmdbId,
                        mediaType = item.mediaType,
                        title = item.title,
                        posterUrl = item.posterUrl,
                        year = item.year,
                        rating = item.rating,
                        addedAt = item.addedAt
                    ))
                }
            }

            if (imported.tabs != null && imported.tabs.isNotEmpty() && Components.isEngineInitialized()) {
                val currentUrls = Components.store.state.tabs.map { it.content.url }
                val hasDuplicates = imported.tabs.any { it.url in currentUrls }
                
                if (hasDuplicates) {
                    ImportResult.Success(imported.tabs, hasDuplicates = true)
                } else {
                    val sessionTabs = imported.tabs.map { tab ->
                        TabSessionState(
                            id = tab.id,
                            content = ContentState(url = tab.url, title = tab.title ?: ""),
                            parentId = tab.parentId
                        )
                    }
                    withContext(Dispatchers.Main) {
                        Components.tabManager?.restoreTabs(sessionTabs, null, Components.store)
                    }
                    ImportResult.Success(null, hasDuplicates = false)
                }
            } else {
                ImportResult.Success(null, hasDuplicates = false)
            }
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Unknown error")
        }
    }

    sealed class ImportResult {
        data class Success(val tabsToRestore: List<ExportedTab>?, val hasDuplicates: Boolean) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }
}

@Serializable
data class CloudBackupConfig(
    val endpoint: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val region: String
)
