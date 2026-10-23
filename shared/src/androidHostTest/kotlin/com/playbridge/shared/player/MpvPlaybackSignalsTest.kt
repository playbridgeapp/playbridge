package com.playbridge.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvPlaybackSignalsTest {
    @Test
    fun preplayPauseAfterEofSurvivesTheNextFilesPlaybackRestart() {
        val signals = MpvPlaybackSignals()
        assertEquals(PlaybackState.Playing, signals.playbackRestarted())
        assertNull(signals.propertyChanged("pause", true, PlaybackState.Ended))
        assertTrue(signals.isPaused)
        // START_FILE clears cache state, but must preserve the host's preplay pause.
        signals.cacheStalled = false
        assertEquals(PlaybackState.Paused, signals.playbackRestarted())
        assertEquals(PlaybackState.Playing, signals.propertyChanged("pause", false, PlaybackState.Paused))
    }

    @Test
    fun terminalStatesRetainCacheAndPauseChangesWithoutReplacingTheFailureOrEof() {
        for (terminal in listOf(PlaybackState.Ended, PlaybackState.Error("failed", "failed"))) {
            val signals = MpvPlaybackSignals()
            assertNull(signals.propertyChanged("paused-for-cache", true, terminal))
            assertTrue(signals.cacheStalled)
            assertNull(signals.propertyChanged("pause", true, terminal))
            assertTrue(signals.isPaused)
            assertNull(signals.propertyChanged("pause", false, terminal))
            assertFalse(signals.isPaused)
            assertNull(signals.propertyChanged("paused-for-cache", false, terminal))
            assertFalse(signals.cacheStalled)
        }
    }

    @Test
    fun cacheBufferingRespectsUserPauseAndResetsOnRestart() {
        val signals = MpvPlaybackSignals()
        assertEquals(PlaybackState.Buffering, signals.propertyChanged("paused-for-cache", true, PlaybackState.Playing))
        assertEquals(PlaybackState.Paused, signals.propertyChanged("pause", true, PlaybackState.Buffering))
        assertEquals(PlaybackState.Paused, signals.playbackRestarted())
        assertFalse(signals.cacheStalled)
        assertNull(signals.propertyChanged("unrelated", true, PlaybackState.Paused))
    }
}
