package com.playbridge.sender.settings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyBackupTest {
    @Test
    fun `old backup ignores retired sections and retains bookmarks collections IPTV and browser settings`() {
        val fixture = """{
          "debridProvider":"retired", "debridApiKey":"synthetic", "debridApiKeys":{"retired":"synthetic"},
          "tmdbApiKey":"synthetic", "omdbApiKey":"synthetic", "addonUrls":["https://fixture.example/manifest.json"],
          "watchlist":[{"tmdbId":1}], "resume":[{"contentKey":"fixture"}], "nuvio":{"scripts":["fixture"]},
          "bookmarks":[{"url":"https://video.example/","title":"Saved"}],
          "iptvPlaylists":[{"name":"Own playlist","source":"https://video.example/list.m3u","sourceType":"URL","addedAt":1}],
          "collections":[{"name":"Own collection","addedAt":1}],
          "appSettings":{"sendSubtitlesToTv":true,"trackWatchProgress":true,"autoAddToWatching":true,"tvPrefetchWindow":10,"detectVideos":true}
        }"""
        val imported = SettingsBackupCodec.decode(fixture)
        assertEquals("Saved", imported.bookmarks!!.single().title)
        assertEquals("Own playlist", imported.iptvPlaylists!!.single().name)
        assertEquals("Own collection", imported.collections!!.single().name)
        assertEquals(true, imported.appSettings!!.detectVideos)
        assertEquals(10, imported.appSettings!!.tvPrefetchWindow)
        val exported = Json.encodeToString(imported)
        listOf("debrid", "tmdb", "omdb", "addonUrls", "watchlist", "resume", "nuvio", "trackWatchProgress", "sendSubtitlesToTv").forEach {
            assertFalse("Retired section was exported: $it", exported.contains(it))
        }
    }
}
