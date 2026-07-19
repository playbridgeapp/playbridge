package com.playbridge.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryStoreMergeTest {
    @Test
    fun `unknown landed duration preserves existing history duration`() {
        assertEquals(600_000L, historyDurationForSave(0L, 600_000L))
    }

    @Test
    fun `known renderer duration replaces existing history duration`() {
        assertEquals(720_000L, historyDurationForSave(720_000L, 600_000L))
    }

    @Test
    fun `new history without duration remains unknown`() {
        assertEquals(0L, historyDurationForSave(0L, null))
    }
}
