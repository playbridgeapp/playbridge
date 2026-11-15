package com.playbridge.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvFailureLogStateTest {
    @Test fun recoveredHttpErrorCannotLabelALaterUnrelatedFailure() {
        val logs = MpvFailureLogState()
        logs.record("ffmpeg/https", 20, "HTTP error 404 subtitle missing")
        logs.playbackHealthy() // First frame / PLAYBACK_RESTART / playing.
        logs.record("cplayer", 20, "Failed to open private-url")
        assertEquals("mpv_load_failed", logs.failureCode.value)
    }

    @Test fun healthyRestartClearsBothPendingAndPublishedCategories() {
        val logs = MpvFailureLogState()
        logs.record("ffmpeg/https", 20, "HTTP error 404 segment missing")
        logs.record("cplayer", 20, "Failed to open private-url")
        assertEquals("mpv_http_404", logs.failureCode.value)
        logs.playbackHealthy()
        assertNull(logs.failureCode.value)
        logs.record("cplayer", 20, "Failed to open another-private-url")
        assertEquals("mpv_load_failed", logs.failureCode.value)
    }

    @Test fun continuedPlaybackClearsRecoverableSegmentErrorsButStalledSamplesDoNot() {
        val logs = MpvFailureLogState()
        logs.positionChanged(1_000, playing = true)
        logs.record("ffmpeg/https", 20, "HTTP error 404 segment missing")
        logs.positionChanged(2_000, playing = true)
        logs.record("cplayer", 20, "Failed to open private-url")
        assertEquals("mpv_load_failed", logs.failureCode.value)
        logs.beginFile()
        assertNull(logs.failureCode.value)
        logs.record("ffmpeg/https", 20, "HTTP error 403 expired")
        logs.positionChanged(0, playing = false)
        logs.record("cplayer", 20, "Failed to open private-url")
        assertEquals("mpv_http_403", logs.failureCode.value)
    }

    @Test fun truncationSignalSurvivesHealthyTicksWithinSameFileAndResetsOnNewFile() {
        val logs = MpvFailureLogState()
        assertFalse(logs.hadTruncationOrNetworkError)
        logs.record("ffmpeg/http", 30, "Will reconnect at 1048576 in 1 second(s), error=End of file.")
        assertTrue(logs.hadTruncationOrNetworkError)
        logs.positionChanged(5_000, playing = true)
        logs.positionChanged(6_000, playing = true)
        assertTrue(logs.hadTruncationOrNetworkError)
        logs.beginFile()
        assertFalse(logs.hadTruncationOrNetworkError)
    }
}
