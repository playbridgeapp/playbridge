package com.playbridge.player.player

import com.playbridge.shared.player.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvProcessTest {
    @Test
    fun readyCanBeRecoveredAfterItsStateWasConflated() {
        for (state in listOf(PlaybackState.Ready, PlaybackState.Playing, PlaybackState.Paused, PlaybackState.Buffering)) {
            assertTrue(mpvNeedsFileReady(state, hasLoadedFile = true, transitioning = false, alreadyReady = false))
        }
    }

    @Test
    fun outgoingUnloadedAndAlreadyReadySessionsCannotPublishReadiness() {
        assertFalse(mpvNeedsFileReady(PlaybackState.Playing, false, false, false))
        assertFalse(mpvNeedsFileReady(PlaybackState.Playing, true, true, false))
        assertFalse(mpvNeedsFileReady(PlaybackState.Playing, true, false, true))
    }

    @Test
    fun terminalAndIdleStatesCannotDeclareReadiness() {
        for (state in listOf(PlaybackState.Idle, PlaybackState.Ended, PlaybackState.Error("failed", "failed"))) {
            assertFalse(mpvNeedsFileReady(state, true, false, false))
        }
    }

    @Test
    fun identifiesOnlyThePrivateMpvProcess() {
        assertTrue(MpvProcess.isMpvProcessName("com.playbridge.player", "com.playbridge.player:mpv"))
        assertFalse(MpvProcess.isMpvProcessName("com.playbridge.player", "com.playbridge.player"))
        assertFalse(MpvProcess.isMpvProcessName("com.playbridge.player", null))
    }

    @Test
    fun identifiesTheTrackAddedByMpvSubAdd() {
        val tracks = listOf("1" to "English", "2" to "Spanish", "4" to "request-marker")
        assertEquals(
            "4",
            findAddedSubtitleTrackId(tracks, setOf("1", "2"), "request-marker"),
        )
        assertNull(findAddedSubtitleTrackId(tracks, setOf("1", "2"), "other-marker"))
    }

    @Test
    fun `mpvReportedStateName keeps paused state when seek restart arrives while paused`() {
        assertEquals(
            "paused",
            mpvReportedStateName(
                state = PlaybackState.Playing,
                playWhenReady = false,
                isFileReady = true,
            ),
        )
        assertEquals(
            "playing",
            mpvReportedStateName(
                state = PlaybackState.Playing,
                playWhenReady = true,
                isFileReady = true,
            ),
        )
        assertEquals(
            "buffering",
            mpvReportedStateName(
                state = PlaybackState.Playing,
                playWhenReady = true,
                isFileReady = false,
            ),
        )
    }
}
