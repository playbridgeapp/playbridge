package com.playbridge.sender.data.backup

import android.content.Context
import com.playbridge.sender.browser.Components
import com.playbridge.sender.settings.ExportedAppSettings
import com.playbridge.sender.settings.ExportedBookmark
import com.playbridge.sender.settings.ExportedCollection
import com.playbridge.sender.settings.ExportedCollectionItem
import com.playbridge.sender.settings.ExportedIptvPlaylist
import com.playbridge.sender.settings.ExportedSettings
import com.playbridge.sender.settings.ExportedTab
import com.playbridge.sender.data.collection.CollectionEntity
import com.playbridge.sender.data.collection.CollectionItemEntity
import com.playbridge.sender.data.iptv.IptvPlaylistEntity
import com.playbridge.sender.data.history.BookmarkEntity
import com.playbridge.sender.data.history.DatabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.TabSessionState

object BackupUtils {

    suspend fun createExportJson(context: Context): String = withContext(Dispatchers.IO) {
        val settingsRepository = org.koin.core.context.GlobalContext.get().get<com.playbridge.sender.data.settings.SettingsRepository>()
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val proxyPrefs = context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
        val database = DatabaseProvider.getDatabase(context)

        val currentBookmarks = database.bookmarkDao().getAllSync().map { 
            ExportedBookmark(url = it.url, title = it.title) 
        }
        val currentTabs = database.tabDao().getAll().map { 
            ExportedTab(id = it.id, url = it.url, title = it.title, parentId = it.parentId) 
        }
        // IPTV playlist sources (channels are a cache; re-parsed after import/refresh).
        val currentIptvPlaylists = database.iptvPlaylistDao().observeAll().first().map {
            ExportedIptvPlaylist(
                name = it.name,
                source = it.source,
                sourceType = it.sourceType,
                addedAt = it.addedAt,
            )
        }
        // Collections with their ordered items (items are self-contained and playable).
        val collectionItemDao = database.collectionItemDao()
        val currentCollections = database.collectionDao().observeAll().first().map { c ->
            ExportedCollection(
                name = c.name,
                addedAt = c.addedAt,
                items = collectionItemDao.getForCollection(c.id).map { item ->
                    ExportedCollectionItem(
                        title = item.title,
                        url = item.url,
                        kind = item.kind,
                        mimeType = item.mimeType,
                        headersJson = item.headersJson,
                        logo = item.logo,
                        sourceTag = item.sourceTag,
                        orderIndex = item.orderIndex,
                        addedAt = item.addedAt,
                    )
                }
            )
        }
        val appSettings = ExportedAppSettings(
            autoSwitchToRemote = settingsRepository.autoSwitchToRemote.first(),
            maxAliveTabs = settingsRepository.maxAliveTabs.first(),
            preferredAudioLang = settingsRepository.preferredAudioLang.first(),
            preferredSubtitleLang = settingsRepository.preferredSubtitleLang.first(),
            defaultVideoQuality = settingsRepository.defaultVideoQuality.first(),
            maxBitrateCapMbps = settingsRepository.maxBitrateCapMbps.first(),
            tvPrefetchWindow = settingsRepository.tvPrefetchWindow.first(),
            detectVideos = settingsRepository.detectVideos.first(),
            mediaDetection = settingsRepository.mediaDetectionSettings.first(),
            blockPopups = settingsRepository.blockPopups.first(),
            popupWhitelist = settingsRepository.popupWhitelist.first().toList(),
            popupBlacklist = settingsRepository.popupBlacklist.first().toList(),
            iptvSort = settingsRepository.iptvSort.first(),
            iptvSortAscending = settingsRepository.iptvSortAscending.first(),
            iptvActiveFirst = settingsRepository.iptvActiveFirst.first(),
            hideEmptyCastSheetTabs = settingsRepository.hideEmptyCastSheetTabs.first(),
        )

        val exported = ExportedSettings(
            tvPlayerMode = settingsRepository.tvPlayerMode.first(),
            tvBrowserMode = prefs.getString("tv_browser_mode", "tv"),
            tabs = currentTabs,
            bookmarks = currentBookmarks,
            iptvPlaylists = currentIptvPlaylists,
            collections = currentCollections,
            appSettings = appSettings,
            streamProxyRemoteUrl = proxyPrefs.getString(
                com.playbridge.sender.cast.proxy.StreamProxySettingsStore.KEY_REMOTE_URL,
                "",
            ),
            streamProxyRemotePassword = proxyPrefs.getString(
                com.playbridge.sender.cast.proxy.StreamProxySettingsStore.KEY_REMOTE_PASSWORD,
                "",
            ),
            streamRouteDefault = proxyPrefs.getString(
                com.playbridge.sender.cast.proxy.StreamProxySettingsStore.KEY_DEFAULT_ROUTE,
                com.playbridge.sender.cast.proxy.StreamRouteMode.DIRECT.prefsValue,
            ),
        )

        Json { prettyPrint = true; encodeDefaults = false }.encodeToString(exported)
    }

    suspend fun importFromJson(context: Context, jsonString: String): ImportResult = withContext(Dispatchers.IO) {
        try {
            val settingsRepository = org.koin.core.context.GlobalContext.get().get<com.playbridge.sender.data.settings.SettingsRepository>()
            val imported = com.playbridge.sender.settings.SettingsBackupCodec.decode(jsonString)
            
            val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
            val proxyPrefs = context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
            val database = DatabaseProvider.getDatabase(context)

            prefs.edit().apply {
                if (imported.tvBrowserMode != null) putString("tv_browser_mode", imported.tvBrowserMode)
                apply()
            }

            if (imported.tvPlayerMode != null) {
                settingsRepository.setTvPlayerMode(imported.tvPlayerMode)
            }

            proxyPrefs.edit().apply {
                val remoteUrl = imported.streamProxyRemoteUrl ?: imported.mediaflowProxyUrl
                val remotePassword =
                    imported.streamProxyRemotePassword ?: imported.mediaflowProxyPassword
                if (remoteUrl != null) {
                    putString(
                        com.playbridge.sender.cast.proxy.StreamProxySettingsStore.KEY_REMOTE_URL,
                        remoteUrl,
                    )
                }
                if (remotePassword != null) {
                    putString(
                        com.playbridge.sender.cast.proxy.StreamProxySettingsStore.KEY_REMOTE_PASSWORD,
                        remotePassword,
                    )
                }
                if (imported.streamRouteDefault != null) {
                    putString(
                        com.playbridge.sender.cast.proxy.StreamProxySettingsStore.KEY_DEFAULT_ROUTE,
                        imported.streamRouteDefault,
                    )
                }
                apply()
            }

            // App-level DataStore settings.
            imported.appSettings?.let { s ->
                s.autoSwitchToRemote?.let { settingsRepository.setAutoSwitchToRemote(it) }
                s.maxAliveTabs?.let { settingsRepository.setMaxAliveTabs(it) }
                s.preferredAudioLang?.let { settingsRepository.setPreferredAudioLang(it) }
                s.preferredSubtitleLang?.let { settingsRepository.setPreferredSubtitleLang(it) }
                s.defaultVideoQuality?.let { settingsRepository.setDefaultVideoQuality(it) }
                s.maxBitrateCapMbps?.let { settingsRepository.setMaxBitrateCapMbps(it) }
                s.tvPrefetchWindow?.let { settingsRepository.setTvPrefetchWindow(it) }
                s.mediaDetection?.let { settingsRepository.setMediaDetectionSettings(it) }
                s.detectVideos?.let { settingsRepository.setDetectVideos(it) }
                s.blockPopups?.let { settingsRepository.setBlockPopups(it) }
                s.popupWhitelist?.let { settingsRepository.savePopupWhitelist(it.toSet()) }
                s.popupBlacklist?.let { settingsRepository.savePopupBlacklist(it.toSet()) }
                s.iptvSort?.let { settingsRepository.setIptvSort(it) }
                s.iptvSortAscending?.let { settingsRepository.setIptvSortAscending(it) }
                s.iptvActiveFirst?.let { settingsRepository.setIptvActiveFirst(it) }
                s.hideEmptyCastSheetTabs?.let { settingsRepository.setHideEmptyCastSheetTabs(it) }
            }


            if (imported.bookmarks != null) {
                val bookmarkDao = database.bookmarkDao()
                imported.bookmarks.forEach { bookmark ->
                    bookmarkDao.insert(BookmarkEntity(url = bookmark.url, title = bookmark.title))
                }
            }

            // IPTV playlist sources — deduped by source so re-imports don't double up.
            // Channels re-populate on the playlist's first open/refresh.
            if (imported.iptvPlaylists != null) {
                val iptvDao = database.iptvPlaylistDao()
                val existingSources = iptvDao.observeAll().first().map { it.source }.toSet()
                imported.iptvPlaylists.forEach { p ->
                    if (p.source !in existingSources) {
                        iptvDao.insert(IptvPlaylistEntity(
                            name = p.name,
                            source = p.source,
                            sourceType = p.sourceType,
                            addedAt = p.addedAt,
                        ))
                    }
                }
            }

            // Collections + items — deduped by collection name; ids are re-generated
            // locally, items keep their order.
            if (imported.collections != null) {
                val collectionDao = database.collectionDao()
                val itemDao = database.collectionItemDao()
                val existingNames = collectionDao.observeAll().first().map { it.name }.toSet()
                imported.collections.forEach { c ->
                    if (c.name !in existingNames) {
                        val newId = collectionDao.insert(CollectionEntity(
                            name = c.name,
                            addedAt = c.addedAt,
                            itemCount = c.items.size,
                        ))
                        c.items.forEach { item ->
                            itemDao.insert(CollectionItemEntity(
                                collectionId = newId,
                                title = item.title,
                                url = item.url,
                                kind = item.kind,
                                mimeType = item.mimeType,
                                headersJson = item.headersJson,
                                logo = item.logo,
                                sourceTag = item.sourceTag,
                                orderIndex = item.orderIndex,
                                addedAt = item.addedAt,
                            ))
                        }
                    }
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
                        Components.tabManager.restoreTabs(sessionTabs, null, Components.store)
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
