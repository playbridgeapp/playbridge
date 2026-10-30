package com.playbridge.sender.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Deletes retired content settings without touching browser, receiver or user-media data. */
internal object RetiredContentCleanup {
    private const val COMPLETE = "retired_content_cleanup_v1"
    val dataStoreKeys = setOf("track_watch_progress", "auto_add_to_watching", "enable_local_scrapers", "send_subtitles_to_tv")
    private val settingsKeys = setOf(
        "debrid_provider", "debrid_api_key", "tmdb_api_key", "omdb_api_key", "tvdb_api_key",
        "series_meta_source", "show_card_text_overlay", "catalog_auto_refresh_enabled", "catalog_refresh_interval_min",
    )
    private val browserKeys = setOf("auto_select_enabled", "last_main_screen")
    val cacheFiles = listOf("stream_cache.json", "home_catalog_cache.json", "tmdb_image_cache", "image_cache")

    fun removedKeys(store: String, keys: Set<String>): Set<String> = keys.filterTo(mutableSetOf()) { key ->
        when (store) {
            "browser_settings" -> key in settingsKeys || key.startsWith("debrid_api_key_")
            "browser_prefs" -> key in browserKeys || key.startsWith("auto_stream_")
            else -> false
        }
    }

    fun deleteFiles(filesDir: File, cacheDir: File) {
        val targets = listOf(File(filesDir, "nuvio")) + cacheFiles.map { File(cacheDir, it) }
        targets.forEach { target ->
            if (!Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return@forEach
            Files.walkFileTree(target.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    suspend fun clearDataStore(dataStore: DataStore<Preferences>) {
        dataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name in dataStoreKeys }.forEach { prefs.remove(it) }
        }
    }

    suspend fun run(context: Context, dataStore: DataStore<Preferences>) = withContext(Dispatchers.IO) {
        val marker = context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE)
        if (marker.getBoolean(COMPLETE, false)) return@withContext
        SettingsRepository(dataStore).migrateLegacyPreferences(
            context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE),
        )
        for (name in listOf("browser_settings", "browser_prefs")) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            removedKeys(name, prefs.all.keys).forEach(editor::remove)
            check(editor.commit())
        }
        clearDataStore(dataStore)
        deleteFiles(context.filesDir, context.cacheDir)
        check(marker.edit().putBoolean(COMPLETE, true).commit())
    }
}
