package com.playbridge.sender.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RetiredContentCleanupTest {
    @Test
    fun `cleanup removes all provider keys and retired preferences while preserving browser settings`() {
        val removed = setOf("debrid_provider", "debrid_api_key", "debrid_api_key_Real-Debrid",
            "debrid_api_key_All-Debrid", "debrid_api_key_Premiumize", "debrid_api_key_TorBox",
            "tmdb_api_key", "omdb_api_key", "tvdb_api_key", "series_meta_source", "show_card_text_overlay",
            "catalog_auto_refresh_enabled", "catalog_refresh_interval_min")
        val retained = setOf("stream_proxy_remote_password", "stream_route_default", "tv_player_mode", "pairing_token")
        assertEquals(removed, RetiredContentCleanup.removedKeys("browser_settings", removed + retained))
        val browser = setOf("auto_select_enabled", "last_main_screen", "auto_stream_addon", "auto_stream_source_types", "auto_stream_min_gb",
            "auto_stream_max_gb", "auto_stream_min_mbps", "auto_stream_max_mbps")
        assertEquals(browser, RetiredContentCleanup.removedKeys("browser_prefs", browser + setOf("last_browser_tab_id", "preferred_audio_lang")))
        assertTrue(RetiredContentCleanup.removedKeys("receiver_credentials", removed).isEmpty())
        assertTrue(RetiredContentCleanup.removedKeys("browser_settings", retained).isEmpty())
        assertEquals(setOf("track_watch_progress", "auto_add_to_watching", "enable_local_scrapers", "send_subtitles_to_tv"), RetiredContentCleanup.dataStoreKeys)
    }

    @Test
    fun `DataStore cleanup preserves casting and browser preferences`() = kotlinx.coroutines.test.runTest {
        val values = kotlinx.coroutines.flow.MutableStateFlow<androidx.datastore.preferences.core.Preferences>(
            androidx.datastore.preferences.core.mutablePreferencesOf(
                androidx.datastore.preferences.core.booleanPreferencesKey("track_watch_progress") to true,
                androidx.datastore.preferences.core.booleanPreferencesKey("auto_add_to_watching") to true,
                androidx.datastore.preferences.core.booleanPreferencesKey("enable_local_scrapers") to true,
                androidx.datastore.preferences.core.booleanPreferencesKey("send_subtitles_to_tv") to true,
                androidx.datastore.preferences.core.intPreferencesKey("tv_prefetch_window") to 4,
                androidx.datastore.preferences.core.stringPreferencesKey("default_video_quality") to "1080p",
                androidx.datastore.preferences.core.booleanPreferencesKey("detect_videos") to true,
            ))
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = values
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences {
                values.value = transform(values.value)
                return values.value
            }
        }
        RetiredContentCleanup.clearDataStore(store)
        RetiredContentCleanup.clearDataStore(store)
        assertEquals(setOf("tv_prefetch_window", "default_video_quality", "detect_videos"), values.value.asMap().keys.map { it.name }.toSet())
    }

    @Test
    fun `cleanup deletes scripts approvals and image caches idempotently without following symlinks`() {
        val root = Files.createTempDirectory("retired-content-test").toFile()
        try {
            val files = File(root, "files").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            val downloads = File(files, "downloads/video.mp4").apply { parentFile.mkdirs(); writeText("keep") }
            val outside = File(root, "outside").apply { mkdirs() }
            val protected = File(outside, "keep.txt").apply { writeText("keep") }
            File(files, "nuvio/state/approvals.json").apply { parentFile.mkdirs(); writeText("{}") }
            File(files, "nuvio/repos/provider/active/script.js").apply { parentFile.mkdirs(); writeText("fixture") }
            Files.createSymbolicLink(File(files, "nuvio/link").toPath(), outside.toPath())
            for (name in RetiredContentCleanup.cacheFiles) {
                File(cache, name).writeText("fixture cache")
            }
            RetiredContentCleanup.deleteFiles(files, cache)
            RetiredContentCleanup.deleteFiles(files, cache)
            assertFalse(File(files, "nuvio").exists())
            assertTrue(RetiredContentCleanup.cacheFiles.none { File(cache, it).exists() })
            assertEquals("keep", downloads.readText())
            assertEquals("keep", protected.readText())
        } finally { root.deleteRecursively() }
    }
}
