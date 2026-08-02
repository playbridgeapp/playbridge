package com.playbridge.sender.cast

import com.playbridge.sender.cast.googlecast.GoogleCastReceiverEndedException
import com.playbridge.sender.cast.googlecast.GoogleCastSessionUnresponsiveException
import com.playbridge.sender.cast.googlecast.googleCastSessionErrorEndsSession
import com.playbridge.sender.cast.googlecast.googleCastStatusErrorEndsSession
import com.playbridge.sender.cast.googlecast.googleCastStatusFailuresRequireFreshSession
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalSessionPhaseTest {
    @Test
    fun `google cast stays connecting until receiver application is ready`() {
        assertEquals(
            SessionPhase.CONNECTING,
            externalSessionPhase(
                TargetKind.GOOGLE_CAST,
                PlaybackStatus(PlaybackState.BUFFERING),
                null,
            ),
        )
        assertEquals(
            SessionPhase.CONNECTED,
            externalSessionPhase(
                TargetKind.GOOGLE_CAST,
                PlaybackStatus(PlaybackState.STOPPED),
                null,
            ),
        )
    }

    @Test
    fun `google cast stop returns to connected ready state`() {
        assertEquals(
            SessionPhase.CONNECTED,
            externalSessionPhase(
                TargetKind.GOOGLE_CAST,
                PlaybackStatus(PlaybackState.STOPPED),
                "Movie",
            ),
        )
    }

    @Test
    fun `load and connection errors remain distinguishable`() {
        assertEquals(
            SessionPhase.PLAYING,
            externalSessionPhase(
                TargetKind.GOOGLE_CAST,
                PlaybackStatus(PlaybackState.PLAYING),
                "Movie",
            ),
        )
        assertEquals(
            SessionPhase.FAILED,
            externalSessionPhase(
                TargetKind.GOOGLE_CAST,
                PlaybackStatus(PlaybackState.ERROR),
                null,
            ),
        )
    }

    @Test
    fun `google cast picker remains open until the receiver is ready`() {
        assertEquals(false, googleCastPickerConnectionComplete(SessionPhase.CONNECTING))
        assertEquals(false, googleCastPickerConnectionComplete(SessionPhase.FAILED))
        assertEquals(true, googleCastPickerConnectionComplete(SessionPhase.CONNECTED))
        assertEquals(true, googleCastPickerConnectionComplete(SessionPhase.PLAYING))
    }

    @Test
    fun `receiver exit is distinct from a transient connection error`() {
        val error = GoogleCastReceiverEndedException()
        assertEquals(
            "Google Cast receiver application is no longer active",
            error.message,
        )
    }

    @Test
    fun `maintenance errors do not invalidate a ready native session`() {
        assertEquals(false, googleCastSessionErrorEndsSession(null))
        assertEquals(false, googleCastSessionErrorEndsSession(""))
        assertEquals(true, googleCastSessionErrorEndsSession("connection_lost"))
        assertEquals(true, googleCastSessionErrorEndsSession("receiver_ended"))
    }

    @Test
    fun `status timeout keeps client while terminal session errors replace it`() {
        assertEquals(
            false,
            googleCastStatusErrorEndsSession(IllegalStateException("status timed out")),
        )
        assertEquals(
            true,
            googleCastStatusErrorEndsSession(GoogleCastReceiverEndedException()),
        )
        assertEquals(
            true,
            googleCastStatusErrorEndsSession(GoogleCastSessionUnresponsiveException()),
        )
        assertEquals(false, googleCastStatusFailuresRequireFreshSession(2))
        assertEquals(true, googleCastStatusFailuresRequireFreshSession(3))
    }
}
