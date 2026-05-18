package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadUtilsTest {

    @Test
    fun testFormatFileSize() {
        assertEquals("0 B", DownloadUtils.formatFileSize(0))
        assertEquals("0 B", DownloadUtils.formatFileSize(-100))
        assertEquals("1023 B", DownloadUtils.formatFileSize(1023))
        assertEquals("1.0 KB", DownloadUtils.formatFileSize(1024))
        assertEquals("1.5 KB", DownloadUtils.formatFileSize(1536))
        assertEquals("1.0 MB", DownloadUtils.formatFileSize(1024 * 1024))
        assertEquals("1.00 GB", DownloadUtils.formatFileSize(1024L * 1024 * 1024))
        assertEquals("1.00 TB", DownloadUtils.formatFileSize(1024L * 1024 * 1024 * 1024))
    }
}
