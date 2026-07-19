package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoSurfaceSizingTest {
    @Test
    fun portraitVideoIsPillarboxedInsideLandscapeDisplay() {
        assertEquals(
            VideoSurfaceDimensions(width = 608, height = 1080),
            fittedVideoSurfaceDimensions(
                containerWidth = 1920,
                containerHeight = 1080,
                videoWidth = 1080,
                videoHeight = 1920,
            ),
        )
    }

    @Test
    fun matchingLandscapeVideoUsesTheWholeDisplay() {
        assertEquals(
            VideoSurfaceDimensions(width = 1920, height = 1080),
            fittedVideoSurfaceDimensions(
                containerWidth = 1920,
                containerHeight = 1080,
                videoWidth = 1920,
                videoHeight = 1080,
            ),
        )
    }
}
