package com.playbridge.player.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun entry(id: String, favorite: Boolean = false, bookmarkOnly: Boolean = false) =
        PlaybackHistoryItem(
            id = id,
            payloadJson = "{}",
            url = "https://example.com/$id",
            title = id,
            position = 0L,
            duration = 0L,
            isFavorite = favorite,
            bookmarkOnly = bookmarkOnly,
        )

    @Test
    fun `a private or history-off play is written only for a bookmarked entry`() {
        assertTrue(historyWriteAllowed(recordable = true, existing = null))
        assertFalse(historyWriteAllowed(recordable = false, existing = null))
        assertFalse(historyWriteAllowed(recordable = false, existing = entry("a")))
        assertTrue(historyWriteAllowed(recordable = false, existing = entry("a", favorite = true)))
    }

    @Test
    fun `removing a bookmark keeps an ordinary entry and removes a bookmark-only one`() {
        val items = listOf(
            entry("plain", favorite = true),
            entry("private", favorite = true, bookmarkOnly = true),
            entry("other"),
        )
        val afterPlain = historyAfterBookmarkToggle(items, "plain")
        assertEquals(listOf("plain", "private", "other"), afterPlain.map { it.id })
        assertFalse(afterPlain.first { it.id == "plain" }.isFavorite)

        val afterPrivate = historyAfterBookmarkToggle(items, "private")
        assertEquals(listOf("plain", "other"), afterPrivate.map { it.id })

        val bookmarked = historyAfterBookmarkToggle(items, "other")
        assertTrue(bookmarked.first { it.id == "other" }.isFavorite)
    }
}
