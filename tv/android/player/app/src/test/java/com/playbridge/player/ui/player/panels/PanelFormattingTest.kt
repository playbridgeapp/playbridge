package com.playbridge.player.ui.player.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelFormattingTest {
    @Test
    fun subtitleDelayReadsAsSeconds() {
        assertEquals("In sync", formatSubtitleDelay(0))
        assertEquals("+0.2 s", formatSubtitleDelay(200))
        assertEquals("-1.5 s", formatSubtitleDelay(-1500))
        assertEquals("+0.25 s", formatSubtitleDelay(250))
        assertEquals("+12.0 s", formatSubtitleDelay(12_000))
    }

    @Test
    fun chapterTimesAndTitles() {
        assertEquals("0:00", formatChapterTime(0))
        assertEquals("1:45", formatChapterTime(105_000))
        assertEquals("10:20", formatChapterTime(620_000))
        assertEquals("1:02:03", formatChapterTime(3_723_000))
        assertEquals("The Heist", chapterPanelTitle("Chapter 3 · The Heist"))
        assertEquals("Chapter 3", chapterPanelTitle("Chapter 3"))
    }

    @Test
    fun episodeLabelsDropSeriesPrefix() {
        assertEquals("S2E5 - Breakage", shortEpisodeLabel("Breaking Bad S2E5 - Breakage"))
        assertEquals("[FAILED] S2E5 - Breakage", shortEpisodeLabel("[FAILED] Breaking Bad S2E5 - Breakage"))
        assertEquals("Big Buck Bunny", shortEpisodeLabel("Big Buck Bunny"))
        assertNull(shortEpisodeLabel(null))
    }

    @Test
    fun queueRowsMarkPlayingNextAndFailed() {
        val playing = queueRowText(1, "Gran Dillama", currentIndex = 1)
        assertEquals("2. Gran Dillama", playing.title)
        assertEquals("Playing now", playing.subtitle)
        assertEquals("Up next", queueRowText(2, "Llamigos", 1).subtitle)
        assertNull(queueRowText(3, "Big Buck Bunny", 1).subtitle)
        val failed = queueRowText(0, "[FAILED] Show S1E1 - Pilot", 1)
        assertTrue(failed.failed)
        assertEquals("1. S1E1 - Pilot", failed.title)
        assertEquals("Couldn't play", failed.subtitle)
        assertEquals("3. Episode 3", queueRowText(2, null, 0).title)
        assertFalse(queueRowText(0, "x", 0).failed)
    }

    @Test
    fun queuePositionHandlesEmptyAndBounds() {
        assertEquals("2 of 4", queuePosition(1, 4))
        assertNull(queuePosition(0, 0))
        assertEquals("4 of 4", queuePosition(9, 4))
    }

    @Test
    fun speedScalingAndPlayerLabels() {
        assertEquals("Normal", speedLabel(1.0f))
        assertEquals("1.5x", speedLabel(1.5f))
        assertEquals("2x", speedLabel(2.0f))
        assertEquals("0.25x", speedLabel(0.25f))
        assertEquals("Fit screen", scalingLabel("Fit"))
        assertEquals("Crop to fill", scalingLabel("Zoom"))
        assertEquals("Stretch", scalingLabel("Fill"))
        assertEquals("ExoPlayer", playerEngineLabel("EXO"))
        assertEquals("MPV", playerEngineLabel("mpv"))
        assertEquals(1, playerChoiceFocusIndex("mpv"))
        assertEquals(0, playerChoiceFocusIndex("unknown"))
    }
}
