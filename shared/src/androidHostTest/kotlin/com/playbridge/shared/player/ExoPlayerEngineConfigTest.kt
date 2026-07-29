package com.playbridge.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExoPlayerEngineConfigTest {
    @Test
    fun zeroBitrateMeansNoCap() {
        assertNull(maxVideoBitrateBps(null))
        assertNull(maxVideoBitrateBps(0.0))
        assertNull(maxVideoBitrateBps(-1.0))
        assertNull(maxVideoBitrateBps(Double.NaN))
        assertEquals(5_500_000, maxVideoBitrateBps(5.5))
    }

    @Test
    fun directLibraryAndDebridSourcesUseModernHttpStack() {
        assertFalse(shouldUseLegacyHttpDataSource(null))
        assertFalse(shouldUseLegacyHttpDataSource("library"))
        assertFalse(shouldUseLegacyHttpDataSource("history"))
        assertFalse(shouldUseLegacyHttpDataSource("debrid_library"))
        assertFalse(shouldUseLegacyHttpDataSource("stremio_addon"))
    }

    @Test
    fun browserAndLiveDetectionsKeepLegacyHttpCompatibility() {
        assertTrue(shouldUseLegacyHttpDataSource("content_type"))
        assertTrue(shouldUseLegacyHttpDataSource("body_content_m3u8"))
        assertTrue(shouldUseLegacyHttpDataSource("dom_source"))
        assertTrue(shouldUseLegacyHttpDataSource("iptv_m3u"))
    }

    @Test
    fun subtitleDelayRefreshesWhenEffectiveCuePositionMovesBackwards() {
        assertTrue(
            shouldRefreshSubtitleRenderer(
                previousDelayMs = 1_000L,
                newDelayMs = 0L,
                isPlaying = true,
            ),
        )
        assertTrue(
            shouldRefreshSubtitleRenderer(
                previousDelayMs = 0L,
                newDelayMs = -100L,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun subtitleDelayAvoidsUnnecessarySeekWhilePlayingForward() {
        assertFalse(
            shouldRefreshSubtitleRenderer(
                previousDelayMs = 0L,
                newDelayMs = 100L,
                isPlaying = true,
            ),
        )
        assertFalse(
            shouldRefreshSubtitleRenderer(
                previousDelayMs = 100L,
                newDelayMs = 100L,
                isPlaying = false,
            ),
        )
        assertTrue(
            shouldRefreshSubtitleRenderer(
                previousDelayMs = 0L,
                newDelayMs = 100L,
                isPlaying = false,
            ),
        )
    }
}
