package com.playbridge.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExoExternalSubtitleTrackTest {
    @Test
    fun recognizesDirectAndMergedExternalTrackIds() {
        assertTrue(isExternalSubtitleTrackId("playbridge-external-subtitle"))
        assertTrue(isExternalSubtitleTrackId("1:playbridge-external-subtitle"))
        assertTrue(isExternalSubtitleTrackId("0:1:playbridge-external-subtitle"))
    }

    @Test
    fun rejectsOtherOrMissingTrackIds() {
        assertFalse(isExternalSubtitleTrackId(null))
        assertFalse(isExternalSubtitleTrackId("embedded-subtitle"))
        assertFalse(isExternalSubtitleTrackId("playbridge-external-subtitle-copy"))
    }
}
