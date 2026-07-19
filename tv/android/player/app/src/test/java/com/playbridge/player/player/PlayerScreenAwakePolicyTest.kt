package com.playbridge.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerScreenAwakePolicyTest {
    @Test
    fun `playing foreground host keeps screen awake`() {
        assertTrue(
            shouldKeepPlayerScreenOn(
                isHostStarted = true,
                isPlaying = true,
                isBuffering = false,
                hasTransition = false,
                hasPrePlay = false,
                isStillWatchingPrompting = false,
            ),
        )
    }

    @Test
    fun `background host never keeps screen awake`() {
        assertFalse(
            shouldKeepPlayerScreenOn(
                isHostStarted = false,
                isPlaying = true,
                isBuffering = true,
                hasTransition = true,
                hasPrePlay = true,
                isStillWatchingPrompting = true,
            ),
        )
    }

    @Test
    fun `paused idle foreground host allows screensaver`() {
        assertFalse(
            shouldKeepPlayerScreenOn(
                isHostStarted = true,
                isPlaying = false,
                isBuffering = false,
                hasTransition = false,
                hasPrePlay = false,
                isStillWatchingPrompting = false,
            ),
        )
    }
}
