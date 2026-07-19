package com.playbridge.player.ui.player

import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSelectionOverlayTest {
    @Test
    fun openSubtitlesOptionNamesUseNaturalNumberOrdering() {
        assertTrue(compareSubtitleOptionNames("OpenSubtitles #2", "OpenSubtitles #10") < 0)
        assertTrue(compareSubtitleOptionNames("OpenSubtitles #10", "OpenSubtitles #11") < 0)
    }
}
