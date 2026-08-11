package com.playbridge.sender.cast.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenMirrorQualityTest {
    @Test
    fun `mirror options default to recommended quality with device audio`() {
        assertEquals(ScreenMirrorCoordinator.Quality.DEFAULT, ScreenMirrorCoordinator.Options().quality)
        assertEquals(true, ScreenMirrorCoordinator.Options().deviceAudio)
    }

    @Test
    fun `default preserves portrait aspect ratio within 1280 long edge`() {
        assertEquals(
            590 to 1_280,
            screenMirrorCaptureSize(1_080, 2_340, ScreenMirrorCoordinator.Quality.DEFAULT),
        )
    }

    @Test
    fun `high preserves portrait aspect ratio within 1920 long edge`() {
        assertEquals(
            886 to 1_920,
            screenMirrorCaptureSize(1_080, 2_340, ScreenMirrorCoordinator.Quality.HIGH),
        )
    }

    @Test
    fun `maximum does not upscale a smaller native display`() {
        assertEquals(
            1_080 to 2_340,
            screenMirrorCaptureSize(1_080, 2_340, ScreenMirrorCoordinator.Quality.MAXIMUM),
        )
    }

    @Test
    fun `maximum caps very large displays at 2560 long edge`() {
        assertEquals(
            1_152 to 2_560,
            screenMirrorCaptureSize(1_440, 3_200, ScreenMirrorCoordinator.Quality.MAXIMUM),
        )
    }

    @Test
    fun `default preserves landscape aspect ratio`() {
        assertEquals(
            1_280 to 576,
            screenMirrorCaptureSize(2_400, 1_080, ScreenMirrorCoordinator.Quality.DEFAULT),
        )
    }
}
