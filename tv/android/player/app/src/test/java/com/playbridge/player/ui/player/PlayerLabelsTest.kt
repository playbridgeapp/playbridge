package com.playbridge.player.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerLabelsTest {
    @Test fun `time left rounds to the nearest minute and says less than a minute`() {
        assertEquals("8 min left", timeLeftText(durationMs = 882_000L, positionMs = 402_000L))
        assertEquals("Less than a minute left", timeLeftText(60_000L, 21_000L))
        assertEquals("Less than a minute left", timeLeftText(60_000L, 48_000L))
        assertEquals("Less than a minute left", timeLeftText(60_000L, 60_000L))
        assertEquals("1 min left", timeLeftText(120_000L, 40_000L))
        assertEquals("40 min left", timeLeftText(2_400_000L, 20_000L))
        assertEquals("1 hr left", timeLeftText(3_600_000L, 0L))
        assertEquals("1 hr 5 min left", timeLeftText(3_900_000L, 0L))
        assertEquals(null, timeLeftText(0L, 0L))
    }

    @Test fun `duration text`() {
        assertEquals("10 min", durationText(600_000L))
        assertEquals("Less than a minute", durationText(30_000L))
    }

    @Test fun `context line uses only data that exists`() {
        assertNull(controlsContextLine(PlayerControlsState(isPlaying = true)))
        assertEquals(
            "Paused · 8 min left",
            controlsContextLine(PlayerControlsState(isPlaying = false, duration = 882_000L, currentPosition = 402_000L)),
        )
        assertEquals(
            "From Pixel 9 · 2 of 4 in the queue",
            controlsContextLine(
                PlayerControlsState(
                    isPlaying = true, senderName = "Pixel 9", hasPlaylist = true, playlistIndex = 1,
                    playlistItems = List(4) { playbridge.PlayPayload(url = "u$it") },
                ),
            ),
        )
        // Paused with an unknown length has nothing real to say.
        assertNull(controlsContextLine(PlayerControlsState(isPlaying = false)))
    }

    @Test fun `skip labels`() {
        assertEquals("Skip intro", skipButtonLabel("intro"))
        assertEquals("Skip recap", skipButtonLabel("recap"))
        assertEquals("Skip credits", skipButtonLabel("outro"))
        assertEquals("Skip preview", skipButtonLabel("preview"))
        assertEquals("Skip filler", skipButtonLabel("Filler"))
    }

    @Test fun `still watching countdown text and fraction`() {
        assertEquals("Stopping in 42 seconds", stillWatchingCountdownText(42))
        assertEquals("Stopping in 1 second", stillWatchingCountdownText(1))
        assertEquals("Stopping in 5:00", stillWatchingCountdownText(300))
        assertEquals("Stopping in 0 seconds", stillWatchingCountdownText(-3))
        assertEquals(0.5f, stillWatchingFraction(150, 300), 0.001f)
        assertEquals(0f, stillWatchingFraction(10, 0), 0.001f)
        assertEquals(1f, stillWatchingFraction(400, 300), 0.001f)
    }

    @Test fun `photo counter only for a queue`() {
        assertNull(photoCounterText(PlayerControlsState()))
        assertEquals(
            "3 of 24",
            photoCounterText(
                PlayerControlsState(playlistIndex = 2, playlistItems = List(24) { playbridge.PlayPayload(url = "u$it") }),
            ),
        )
    }

    @Test fun `clock format`() {
        assertEquals("6:42", formatClock(402_000L))
        assertEquals("1:02:03", formatClock(3_723_000L))
        assertEquals("0:00", formatClock(-5L))
    }

    @Test fun `bookmark labels and notices`() {
        assertEquals("Bookmark", ControlLabels.bookmark(BookmarkState.OFF))
        assertEquals("Bookmarked", ControlLabels.bookmark(BookmarkState.ON))
        assertEquals("Bookmarked. Your place is saved as you watch.", bookmarkNoticeText(true))
        assertEquals("Bookmark removed.", bookmarkNoticeText(false))
    }
}
