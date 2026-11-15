package com.playbridge.player.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesignDensityTest {
    @Test fun `1080p screen is 1280 design dp wide`() {
        assertEquals(1.5f, designDensity(1920)!!, 0.0001f)
        assertEquals(1920f, 1280f * designDensity(1920)!!, 0.01f)
    }

    @Test fun `720p and 4K scale the same`() {
        assertEquals(1.0f, designDensity(1280)!!, 0.0001f)
        assertEquals(3.0f, designDensity(3840)!!, 0.0001f)
    }

    @Test fun `unknown width gives null`() {
        assertNull(designDensity(0))
        assertNull(designDensity(-5))
    }
}
