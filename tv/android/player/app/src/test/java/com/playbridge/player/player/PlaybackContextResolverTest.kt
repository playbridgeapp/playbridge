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
    fun `exact id is used when saved metadata remains compatible`() {
        val result = resolveTrackPreference(
            tracks,
            PlaybackTrackPreference(
                id = "1:2",
                label = "English Commentary",
                language = "eng",
            ),
        )

        assertEquals("1:2", result?.id)
    }

    @Test
    fun `reused id cannot override conflicting saved metadata`() {
        val result = resolveTrackPreference(
            tracks,
            PlaybackTrackPreference(id = "1:2", label = "Japanese", language = "ja"),
        )

        assertEquals("1:1", result?.id)
    }

    @Test
    fun `saved role label wins when id is reused by another same-language track`() {
        val result = resolveTrackPreference(
            listOf(
                PlaybackTrackCandidate("2:0", "English Stereo", "en"),
                PlaybackTrackCandidate("5:0", "English Commentary", "en"),
            ),
            PlaybackTrackPreference(
                id = "2:0",
                label = "English Commentary",
                language = "en",
            ),
        )

        assertEquals("5:0", result?.id)
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

    @Test
    fun `placeholder-only track snapshots are not ready for restoration`() {
        val ready = hasRestorableTrackCandidates(
            tracks = listOf(PlaybackTrackCandidate("auto", "Auto / Default", null)),
            excludedIds = setOf("auto"),
        )

        assertEquals(false, ready)
    }

    @Test
    fun `real track makes snapshot ready for restoration`() {
        val ready = hasRestorableTrackCandidates(
            tracks = listOf(
                PlaybackTrackCandidate("auto", "Auto / Default", null),
                PlaybackTrackCandidate("2:0", "English", "en"),
            ),
            excludedIds = setOf("auto"),
        )

        assertEquals(true, ready)
    }
}
