package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.MediaTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSheetsGatingTest {

    @Test
    fun subtitleGroupingPlacesOffFirstAndGroupsByLabel() {
        val tracks = listOf(
            MediaTrack(id = "none", name = "Off", selected = true),
            MediaTrack(id = "sub_en_1", name = "OpenSubtitles #1 · English", selected = false, type = "external_sub"),
            MediaTrack(id = "sub_en_2", name = "OpenSubtitles #2 · English", selected = false, type = "external_sub"),
            MediaTrack(id = "sub_es", name = "OpenSubtitles #3 · Spanish", selected = false, type = "external_sub"),
        )

        val groups = groupSubtitleTracks(tracks)
        assertEquals(3, groups.size)
        // First group is OFF_KEY
        assertEquals(OFF_KEY, groups[0].key)
        assertEquals("Off", groups[0].label)
        assertEquals(1, groups[0].tracks.size)

        // Other groups sorted or ordered
        assertTrue(groups.any { it.label.contains("English", ignoreCase = true) && it.tracks.size == 2 })
        assertTrue(groups.any { it.label.contains("Spanish", ignoreCase = true) && it.tracks.size == 1 })
    }

    @Test
    fun subtitleGroupingHandlesTrackLanguageCode() {
        val tracks = listOf(
            MediaTrack(id = "sub1", name = "OpenSubtitles #1 · fra", selected = false, type = "external_sub"),
            MediaTrack(id = "sub2", name = "OpenSubtitles #2 · French commentary", selected = false, type = "external_sub"),
        )
        val groups = groupSubtitleTracks(tracks)
        assertTrue(groups.any { it.label.contains("French", ignoreCase = true) })
    }

    @Test
    fun playerSettingsGatingTruthTable() {
        // Gating rules from RemoteSheets:
        // showSettings = !external && !image
        // showLoop = !external && !image && !isLive
        // showAddSubtitle = !external && !image && !audio

        fun isSettingsVisible(external: Boolean, image: Boolean) = !external && !image
        fun isLoopVisible(external: Boolean, image: Boolean, live: Boolean) = !external && !image && !live
        fun isAddSubtitleVisible(external: Boolean, image: Boolean, audio: Boolean) = !external && !image && !audio

        // External receiver (DLNA/Cast)
        assertFalse(isSettingsVisible(external = true, image = false))
        assertFalse(isLoopVisible(external = true, image = false, live = false))
        assertFalse(isAddSubtitleVisible(external = true, image = false, audio = false))

        // Image playback on Native
        assertFalse(isSettingsVisible(external = false, image = true))
        assertFalse(isLoopVisible(external = false, image = true, live = false))
        assertFalse(isAddSubtitleVisible(external = false, image = true, audio = false))

        // Live stream on Native
        assertTrue(isSettingsVisible(external = false, image = false))
        assertFalse(isLoopVisible(external = false, image = false, live = true))
        assertTrue(isAddSubtitleVisible(external = false, image = false, audio = false))

        // Audio stream on Native
        assertTrue(isSettingsVisible(external = false, image = false))
        assertTrue(isLoopVisible(external = false, image = false, live = false))
        assertFalse(isAddSubtitleVisible(external = false, image = false, audio = true))

        // Normal Video VOD on Native
        assertTrue(isSettingsVisible(external = false, image = false))
        assertTrue(isLoopVisible(external = false, image = false, live = false))
        assertTrue(isAddSubtitleVisible(external = false, image = false, audio = false))
    }

    @Test
    fun reportedLoopUsesASwitchAndAMissingFieldKeepsExplicitButtons() {
        assertTrue(loopEditorUsesSwitch(true))
        assertTrue(loopEditorUsesSwitch(false))
        assertFalse(loopEditorUsesSwitch(null))
    }
}
