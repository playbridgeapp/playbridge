package com.playbridge.player.preplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrePlayFormatTest {
    @Test
    fun `formats minutes and hours`() {
        assertEquals("1:10", formatPlaybackTime(70_000))
        assertEquals("0:00", formatPlaybackTime(-5))
        assertEquals("1:02:03", formatPlaybackTime(3_723_000))
        assertEquals("12:05", formatPlaybackTime(725_900))
    }

    @Test
    fun `episode label needs both numbers`() {
        assertEquals("Season 1 · Episode 2", episodeLabel(1, 2))
        assertNull(episodeLabel(1, null))
        assertNull(episodeLabel(null, 2))
    }

    @Test
    fun `choice window progress runs from empty to full`() {
        assertEquals(0f, choiceWindowProgress(5, 5), 0.001f)
        assertEquals(0.6f, choiceWindowProgress(2, 5), 0.001f)
        assertEquals(1f, choiceWindowProgress(0, 5), 0.001f)
        assertEquals(1f, choiceWindowProgress(3, 0), 0.001f)
    }
}
