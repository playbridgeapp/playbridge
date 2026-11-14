package com.playbridge.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSelectionOverlayTest {
    @Test
    fun openSubtitlesOptionNamesUseNaturalNumberOrdering() {
        assertTrue(compareSubtitleOptionNames("OpenSubtitles #2", "OpenSubtitles #10") < 0)
        assertTrue(compareSubtitleOptionNames("OpenSubtitles #10", "OpenSubtitles #11") < 0)
    }
}

class SubtitleRowsTest {
    private fun track(id: String, name: String, type: String, selected: Boolean = false) =
        UnifiedTrack(id = id, name = name, isSelected = selected, type = type)

    @Test
    fun rowsAreOffThenEmbeddedThenPhoneThenLanguages() {
        val tracks = listOf(
            track("https://x/page.vtt", "page.vtt", "external_sub"),
            track("https://x/fr", "French · OpenSubtitles #1", "external_sub"),
            track("https://x/en", "English · OpenSubtitles #2", "external_sub"),
            track("0", "Spanish", "sub"),
            track("https://x/remote", "My phone file", "external_sub"),
            track("off", "Off", "sub", selected = true),
        )
        val rows = subtitleRows(tracks)
        assertEquals(listOf("Off", "Spanish", "page.vtt", "My phone file", "English", "French"), rows.map { it.title })
        assertTrue(rows.first().isOff)
        assertEquals("From phone", rows[2].trailing)
        assertEquals("From phone", rows[3].trailing)
        assertEquals("Online", rows[4].trailing)
        assertEquals("OpenSubtitles #2", rows[4].note)
    }

    @Test
    fun initialFocusPrefersSelectedTrackThenOff() {
        val withSelected = subtitleRows(listOf(
            track("off", "Off", "sub"),
            track("0", "English", "sub"),
            track("1", "Spanish", "sub", selected = true),
        ))
        assertEquals(2, subtitleInitialFocusIndex(withSelected))
        val offSelected = subtitleRows(listOf(track("off", "Off", "sub", selected = true), track("0", "English", "sub")))
        assertEquals(0, subtitleInitialFocusIndex(offSelected))
        assertEquals(0, subtitleInitialFocusIndex(emptyList()))
    }
}
