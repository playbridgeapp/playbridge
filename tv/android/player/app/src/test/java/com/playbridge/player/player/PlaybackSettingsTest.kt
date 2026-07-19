package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Test
import playbridge.PlayPayload

class PlaybackSettingsTest {
    @Test
    fun defaultQualityPreferenceBecomesSessionMaximum() {
        assertEquals(0, defaultQualityMaxHeight(null))
        assertEquals(0, defaultQualityMaxHeight(PlayPayload(default_video_quality = "auto")))
        assertEquals(720, defaultQualityMaxHeight(PlayPayload(default_video_quality = "720p")))
        assertEquals(1080, defaultQualityMaxHeight(PlayPayload(default_video_quality = "1080p")))
        assertEquals(2160, defaultQualityMaxHeight(PlayPayload(default_video_quality = "4K")))
    }
}
