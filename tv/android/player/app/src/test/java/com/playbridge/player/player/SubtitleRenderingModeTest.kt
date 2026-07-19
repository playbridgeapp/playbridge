package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleRenderingModeTest {
    @Test
    fun `auto is the fallback for missing or unknown preferences`() {
        assertEquals(SubtitleRenderingMode.AUTO, SubtitleRenderingMode.fromPreference(null))
        assertEquals(SubtitleRenderingMode.AUTO, SubtitleRenderingMode.fromPreference("unknown"))
    }

    @Test
    fun `stored values resolve to their rendering modes`() {
        SubtitleRenderingMode.entries.forEach { mode ->
            assertEquals(mode, SubtitleRenderingMode.fromPreference(mode.preferenceValue))
        }
    }
}
