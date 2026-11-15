package com.playbridge.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneConfirmationsTest {
    @Test fun `paused names the phone or says a phone`() {
        assertEquals("Paused from Pixel 9", pausedFromText("Pixel 9"))
        assertEquals("Paused from a phone", pausedFromText(null))
        assertEquals("Paused from a phone", pausedFromText("  "))
    }

    @Test fun `seeking and changed lines`() {
        assertEquals("Pixel 9 is seeking", seekingText("Pixel 9"))
        assertEquals("A phone is seeking", seekingText(null))
        assertEquals("Changed from Pixel 9", changedFromText("Pixel 9"))
        assertEquals("Changed from a phone", changedFromText(null))
        assertEquals("Pixel 9 is seeking · Chapter 4", phoneSeekCaption("Pixel 9", "Chapter 4"))
        assertEquals("A phone is seeking", phoneSeekCaption(null, null))
    }

    @Test fun `track and speed titles use real values`() {
        assertEquals("Subtitles: English", subtitleChangeTitle("English", false))
        assertEquals("Subtitles: Off", subtitleChangeTitle(null, true))
        assertEquals("Subtitles changed", subtitleChangeTitle(null, false))
        assertEquals("Audio: Japanese 5.1", audioChangeTitle("Japanese 5.1"))
        assertEquals("Audio track changed", audioChangeTitle(null))
        assertEquals("Speed: 1.5×", speedChangeTitle(1.5f))
        assertEquals("Speed: 1.25×", speedChangeTitle(1.25f))
        assertEquals("Speed: 2×", speedChangeTitle(2.0f))
        assertEquals("Speed: Normal", speedChangeTitle(1.0f))
    }

    @Test fun `subtitle offset wording`() {
        assertEquals("Subtitles 0.5 s later", subtitleOffsetTitle(500))
        assertEquals("Subtitles 1 s earlier", subtitleOffsetTitle(-1000))
        assertEquals("Subtitles 1.25 s later", subtitleOffsetTitle(1250))
        assertEquals("Subtitles in sync", subtitleOffsetTitle(0))
    }

    @Test fun `queue title falls back to position`() {
        assertEquals("Now playing: Sintel", queuePositionTitle("Sintel", 1, 4))
        assertEquals("Now playing: item 2 of 4", queuePositionTitle(null, 1, 4))
        assertEquals(PhoneChangeCard(PhoneChangeKind.SPEED, "Speed: 2×", "Changed from a phone"),
            phoneChangeCard(PhoneChangeKind.SPEED, "Speed: 2×", null))
    }
}
