package com.playbridge.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
