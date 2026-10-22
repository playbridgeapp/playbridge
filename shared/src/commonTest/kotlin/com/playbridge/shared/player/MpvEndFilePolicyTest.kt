package com.playbridge.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MpvEndFilePolicyTest {
    @Test fun failedLoadIsAnImmediateError() {
        assertEquals("mpv_load_failed", assertIs<PlaybackState.Error>(classify(false)).code)
    }

    @Test fun earlyEndIsAPlaybackError() {
        assertEquals("mpv_playback_failed", assertIs<PlaybackState.Error>(classify(position = 20_000)).code)
    }

    @Test fun genuineEofAndEitherToleranceAreEnded() {
        for ((position, duration) in listOf(100_000L to 100_000L, 90_000L to 100_000L, 990_000L to 1_000_000L)) {
            assertEquals(PlaybackState.Ended, classify(position = position, duration = duration))
        }
    }

    @Test fun transitionAndExplicitStopAreIgnoredEvenBeforeLoad() {
        assertNull(classify(loaded = false, transitioning = true))
        assertNull(classify(transitioning = true))
    }

    @Test fun liveAndUnknownDurationEndNormally() {
        assertEquals(PlaybackState.Ended, classify(duration = 0))
        assertEquals(PlaybackState.Ended, classify(duration = -1))
    }

    private fun classify(loaded: Boolean = true, transitioning: Boolean = false, position: Long = 0, duration: Long = 100_000) =
        MpvEndFilePolicy.classify(loaded, transitioning, position, duration)
}
