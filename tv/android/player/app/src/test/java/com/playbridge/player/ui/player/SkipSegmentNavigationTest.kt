package com.playbridge.player.ui.player

import com.playbridge.player.player.SkipSegment
import com.playbridge.player.player.SkipSegmentFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipSegmentNavigationTest {
    @Test
    fun `open ended outro advances playback directly`() {
        val segment = SkipSegment("outro", 500_000L, SkipSegmentFetcher.OPEN_ENDED_MS)

        assertTrue(skipSegmentEndsPlayback(segment, 600_000L))
    }

    @Test
    fun `outro ending at duration advances playback directly`() {
        val segment = SkipSegment("outro", 500_000L, 600_000L)

        assertTrue(skipSegmentEndsPlayback(segment, 600_000L))
        assertEquals(599_750L, skipTargetMs(segment, 600_000L))
    }

    @Test
    fun `bounded intro still seeks past its segment`() {
        val segment = SkipSegment("intro", 10_000L, 70_000L)

        assertFalse(skipSegmentEndsPlayback(segment, 600_000L))
        assertEquals(71_000L, skipTargetMs(segment, 600_000L))
    }
}
