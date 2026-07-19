package com.playbridge.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryThumbnailModeTest {
    @Test
    fun `smart capture is the default for missing and unknown preferences`() {
        assertEquals(HistoryThumbnailMode.SMART, HistoryThumbnailMode.fromPreference(null))
        assertEquals(HistoryThumbnailMode.SMART, HistoryThumbnailMode.fromPreference("unknown"))
    }

    @Test
    fun `known preferences round trip`() {
        HistoryThumbnailMode.entries.forEach { mode ->
            assertEquals(mode, HistoryThumbnailMode.fromPreference(mode.preferenceValue))
        }
    }
}
