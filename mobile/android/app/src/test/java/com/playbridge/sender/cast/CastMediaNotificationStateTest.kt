package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastMediaNotificationStateTest {
    private val actions = CastMediaActions(true, true, true, true)
    private fun native(state: String, position: Long = 10_000, duration: Long = 120_000, live: Boolean = false) =
        nativeCastMediaPlayback(TvPlaybackStatus(state, position, duration, "Title"), true, live)
    private fun publication(state: CastMediaPlayback = native("playing"), at: Long = 0, owner: Any = "receiver-a") =
        CastPlaybackPublication(owner, state, actions, at)

    @Test fun nativePlayingPausedAndBufferingMapWithoutTreatingPausedAsIdle() {
        for ((wire, mapped) in mapOf("PLAYING" to PlaybackState.PLAYING, "paused" to PlaybackState.PAUSED, "buffering" to PlaybackState.BUFFERING)) {
            val state = native(wire)
            assertEquals(mapped, state.state)
            assertTrue(castNotificationUsesMedia(true, false, state))
        }
    }

    @Test fun nativeTerminalStatusesCannotRemainInTheMediaCarousel() {
        for ((wire, mapped) in mapOf("idle" to PlaybackState.IDLE, "stopped" to PlaybackState.STOPPED, "error" to PlaybackState.ERROR)) {
            val state = native(wire)
            assertEquals(mapped, state.state)
            assertFalse(castNotificationUsesMedia(true, false, state))
        }
    }

    @Test fun missingStatusIsBufferingOnlyWhenMediaIsActuallyLoaded() {
        val pending = nativeCastMediaPlayback(null, true, false)
        assertEquals(PlaybackState.BUFFERING, pending.state)
        assertEquals(-1L, pending.positionMs)
        assertEquals(0L, pending.durationMs)
        assertFalse(castNotificationCanSeek(pending, true))
        assertEquals(PlaybackState.IDLE, nativeCastMediaPlayback(null, false, false).state)
        assertEquals(PlaybackState.BUFFERING, externalCastMediaPlayback(null, true).state)
        assertEquals(PlaybackState.IDLE, externalCastMediaPlayback(null, false).state)
    }

    @Test fun allExternalStatusStatesMapWithoutLosingPositionOrDuration() {
        for (state in PlaybackState.entries) {
            val mapped = externalCastMediaPlayback(PlaybackStatus(state, 12_345, 100_000), true)
            assertEquals(state, mapped.state)
            assertEquals(12_345L, mapped.positionMs)
            assertEquals(100_000L, mapped.durationMs)
        }
    }

    @Test fun idleLinksAndStaleSnapshotsUsePlainNotifications() {
        assertFalse(castNotificationUsesMedia(false, false, native("playing")))
        assertFalse(castNotificationUsesMedia(false, false, native("paused")))
        assertFalse(castNotificationUsesMedia(true, false, native("idle")))
    }

    @Test fun mirroringKeepsThePlainNotificationEvenWithAStalePlayingSnapshot() {
        assertFalse(castNotificationUsesMedia(true, true, native("playing")))
        assertFalse(castNotificationUsesMedia(true, true, native("paused")))
    }

    @Test fun nativeImagesAreNotExposedAsAudioVideoMediaSessions() {
        val state = nativeCastMediaPlayback(TvPlaybackStatus("playing", 0, 5_000, "Photo", "image"), true, false)
        assertFalse(castNotificationUsesMedia(true, false, state))
        assertFalse(castNotificationCanSeek(state, true))
    }

    @Test fun seekingRequiresCapabilityAndFinitePositiveNonLiveDuration() {
        assertTrue(castNotificationCanSeek(native("playing"), true))
        assertTrue(castNotificationCanSeek(native("paused"), true))
        assertFalse(castNotificationCanSeek(native("playing"), false))
        for (duration in listOf(-1L, 0L, Long.MAX_VALUE)) {
            assertFalse(castNotificationCanSeek(native("playing", duration = duration), true))
        }
        assertFalse(castNotificationCanSeek(native("playing", live = true), true))
        assertFalse(castNotificationCanSeek(externalCastMediaPlayback(PlaybackStatus(PlaybackState.PLAYING, 0, 120_000, isLive = true), true), true))
        assertFalse(castNotificationCanSeek(native("stopped"), true))
    }

    @Test fun externalTerminalStatesUsePlainModeEvenBeforeTheManagerClearsLoaded() {
        for (state in listOf(PlaybackState.IDLE, PlaybackState.STOPPED, PlaybackState.ERROR)) {
            val playback = externalCastMediaPlayback(PlaybackStatus(state, 10_000, 120_000), true)
            assertFalse(castNotificationUsesMedia(true, false, playback))
        }
    }

    @Test fun malformedPositionsAreUnknownOrClampedToTheKnownEnd() {
        assertEquals(-1L, native("playing", position = -20).positionMs)
        assertEquals(120_000L, native("playing", position = Long.MAX_VALUE).positionMs)
        assertEquals(0L, native("playing", duration = Long.MAX_VALUE).durationMs)
    }

    @Test fun firstPlaybackAndOwnerReplacementAlwaysPublish() {
        val first = publication()
        assertTrue(castPlaybackNeedsPublication(null, first, 0))
        assertTrue(castPlaybackNeedsPublication(first, first.copy(owner = "receiver-b"), 0))
        assertTrue(castPlaybackNeedsPublication(first, first.copy(owner = "receiver-a-next-load"), 0))
    }

    @Test fun normalHalfSecondAndOneSecondTicksDoNotRepublishPlaybackState() {
        val first = publication()
        for (elapsed in listOf(500L, 1000L, 10_000L, 60_000L)) {
            val next = publication(native("playing", position = 10_000 + elapsed), at = elapsed)
            assertFalse(castPlaybackNeedsPublication(first, next, elapsed))
        }
    }

    @Test fun driftMustExceedTwoSecondsBeforeRepublishing() {
        val first = publication()
        assertFalse(castPlaybackNeedsPublication(first, publication(native("playing", position = 13_000), at = 1_000), 1_000))
        assertTrue(castPlaybackNeedsPublication(first, publication(native("playing", position = 13_001), at = 1_000), 1_000))
        assertTrue(castPlaybackNeedsPublication(first, publication(native("playing", position = 8_000), at = 1_000), 1_000))
    }

    @Test fun stateDurationAndActionChangesPublishImmediately() {
        val first = publication()
        assertTrue(castPlaybackNeedsPublication(first, publication(native("paused")), 0))
        assertTrue(castPlaybackNeedsPublication(first, publication(native("buffering")), 0))
        assertTrue(castPlaybackNeedsPublication(first, publication(native("playing", duration = 150_000)), 0))
        assertTrue(castPlaybackNeedsPublication(first, first.copy(actions = actions.copy(seek = false)), 0))
        assertTrue(castPlaybackNeedsPublication(first, first.copy(actions = actions.copy(volume = false)), 0))
    }

    @Test fun pausedAndBufferingPositionsNeverExtrapolate() {
        for (state in listOf("paused", "buffering")) {
            val first = publication(native(state))
            assertEquals(10_000L, first.positionAt(90_000))
            assertFalse(castPlaybackNeedsPublication(first, publication(native(state), at = 90_000), 90_000))
        }
    }

    @Test fun linkingAndUnlinkingRepublishSystemMediaActionsWithoutAPositionChange() {
        val plain = publication()
        val linked = plain.copy(actions = actions.copy(unlink = true))
        assertTrue(castPlaybackNeedsPublication(plain, linked, 0))
        assertTrue(castPlaybackNeedsPublication(linked, plain, 0))
    }

    @Test fun unknownPositionsPublishOnceWhenTheyBecomeKnown() {
        val unknown = publication(native("playing", position = -1))
        assertFalse(castPlaybackNeedsPublication(unknown, unknown.copy(atMs = 10_000), 10_000))
        assertTrue(castPlaybackNeedsPublication(unknown, publication(), 0))
        assertTrue(castPlaybackNeedsPublication(publication(), unknown, 0))
    }

    @Test fun extrapolationClampsToDurationAndHandlesBackwardsOrHugeTimes() {
        val state = publication(at = 1_000)
        assertEquals(10_000L, state.positionAt(0))
        assertEquals(120_000L, state.positionAt(Long.MAX_VALUE))
        assertFalse(castPlaybackNeedsPublication(state, publication(native("playing", position = 120_000), at = 500_000), 500_000))
    }
}
