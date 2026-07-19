package com.playbridge.player.player

import com.playbridge.player.data.PlaybackTrackPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackContextResolverTest {
    private val tracks = listOf(
        PlaybackTrackCandidate("1:0", "English Stereo", "en"),
        PlaybackTrackCandidate("1:1", "Japanese", "ja"),
        PlaybackTrackCandidate("1:2", "English Commentary", "en"),
    )

    @Test
    fun `exact id wins when renderer exposes the saved track`() {
        val result = resolveTrackPreference(
            tracks,
            PlaybackTrackPreference(id = "1:2", label = "stale", language = "ja"),
        )

        assertEquals("1:2", result?.id)
    }

    @Test
    fun `language and label restore when track ids change between episodes`() {
        val result = resolveTrackPreference(
            tracks,
            PlaybackTrackPreference(id = "old-id", label = "Japanese", language = "JA"),
        )

        assertEquals("1:1", result?.id)
    }

    @Test
    fun `two and three letter language codes match across renderers`() {
        val result = resolveTrackPreference(
            tracks,
            PlaybackTrackPreference(id = "old-id", language = "jpn"),
        )

        assertEquals("1:1", result?.id)
    }

    @Test
    fun `payload language is used when saved selection is unavailable`() {
        val result = resolveTrackPreference(
            tracks,
            PlaybackTrackPreference(id = "missing", label = "German", language = "de"),
            fallbackLanguage = "en",
        )

        assertEquals("1:0", result?.id)
    }

    @Test
    fun `pseudo tracks are excluded from semantic fallback`() {
        val result = resolveTrackPreference(
            listOf(PlaybackTrackCandidate("off", "Off", null)),
            PlaybackTrackPreference(label = "Off"),
            excludedIds = setOf("off"),
        )

        assertNull(result)
    }
}
