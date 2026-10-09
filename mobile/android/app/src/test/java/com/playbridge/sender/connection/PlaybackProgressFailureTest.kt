package com.playbridge.sender.connection

import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.dlna.DlnaActionFailure
import com.playbridge.sender.cast.googlecast.GoogleCastPlaybackFailedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressFailureTest {
    @Test
    fun actionAndReceiverPlaybackFailuresAreNotProgressCompletionCandidates() {
        assertTrue(
            isExternalPlaybackFailure(
                PlaybackStatus(PlaybackState.ERROR, failure = DlnaActionFailure("Play", 500, 501)),
            ),
        )
        assertTrue(
            isExternalPlaybackFailure(
                PlaybackStatus(PlaybackState.ERROR, failure = GoogleCastPlaybackFailedException()),
            ),
        )
        assertFalse(isExternalPlaybackFailure(PlaybackStatus(PlaybackState.PLAYING)))
        assertFalse(isExternalPlaybackFailure(null))
    }
}
