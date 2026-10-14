package com.playbridge.shared.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageZoomTest {
    @Test
    fun relativeFactorsAndNormalizedFocalPointReachTheTopFrameController() {
        assertEquals("window.__pbvc&&window.__pbvc.zoomPage(1.5,0.25,0.75)", pageZoomScript(1.5f, 0.25f, 0.75f))
        assertEquals("window.__pbvc&&window.__pbvc.zoomPage(0.8,0.0,1.0)", pageZoomScript(0.8f, -1f, 2f))
    }

    @Test
    fun nonfiniteFactorsAndCoordinatesNeverReachJavascript() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f).forEach {
            assertNull(pageZoomScript(it))
        }
        assertNull(pageZoomScript(1f, Float.NaN, 0f))
        assertNull(pageZoomScript(1f, 0f, Float.POSITIVE_INFINITY))
    }
}
