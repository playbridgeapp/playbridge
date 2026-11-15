package com.playbridge.player.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserChromeTest {
    @Test
    fun `sizes`() {
        assertEquals("0 B", formatDownloadSize(0))
        assertEquals("512 B", formatDownloadSize(512))
        assertEquals("1.5 MB", formatDownloadSize(1_572_864))
        assertEquals("120 MB", formatDownloadSize(125_829_120))
    }

    @Test
    fun `detail line`() {
        assertEquals("1.0 MB of 2.0 MB · saves to Downloads", downloadDetail(1_048_576, 2_097_152))
        assertEquals("1.0 MB · saves to Downloads", downloadDetail(1_048_576, 0))
    }
}
