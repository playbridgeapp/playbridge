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

    private fun item(id: String, favorite: Boolean) = PlaybackHistoryItem(
        id = id, payloadJson = "{}", url = "u", title = null, position = 0, duration = 0, isFavorite = favorite,
    )

    @Test
    fun `clearing history keeps only bookmarks`() {
        val kept = historyKeptWhenCleared(listOf(item("a", false), item("b", true), item("c", false)))
        assertEquals(listOf("b"), kept.map { it.id })
    }

    @Test
    fun `clearing history with no bookmarks keeps nothing`() {
        assertEquals(emptyList<PlaybackHistoryItem>(), historyKeptWhenCleared(listOf(item("a", false))))
    }
}
