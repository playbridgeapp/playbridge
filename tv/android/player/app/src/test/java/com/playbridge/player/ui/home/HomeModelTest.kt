package com.playbridge.player.ui.home

import com.playbridge.player.data.PlaybackHistoryItem
import com.playbridge.player.server.WebSocketServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeModelTest {
    @Test
    fun `bookmarks are favourites only, newest first`() {
        val a = item("a", 0, 0, ts = 1, fav = true)
        val b = item("b", 0, 0, ts = 3, fav = true)
        val c = item("c", 0, 0, ts = 2, fav = false)
        assertEquals(listOf("b", "a"), bookmarksOf(listOf(a, c, b)).map { it.id })
    }

    @Test
    fun `completed item restarts while unfinished item resumes`() {
        assertNull(resumePositionForHistoryItem(item("done", 590_000, 600_000)))
        assertEquals(240_000L, resumePositionForHistoryItem(item("half", 240_000, 600_000)))
    }

    @Test
    fun `thumbnail revision changes the cache key`() {
        val first = item("s", 0, 0).copy(thumbnailUrl = "file:///s.jpg", thumbnailRevision = 10)
        assertEquals("file:///s.jpg#10", historyThumbnailCacheKey(first))
        assertEquals("file:///s.jpg#20", historyThumbnailCacheKey(first.copy(thumbnailRevision = 20)))
    }

    @Test
    fun `clock formatting`() {
        assertEquals("6:42", formatClock(402_000))
        assertEquals("0:00", formatClock(-5))
        assertEquals("1:05:09", formatClock(3_909_000))
    }

    @Test
    fun `tile line for resumable, finished, unknown`() {
        // 6:42 of 15:00 -> 8 min 18 s left -> 8 min
        assertEquals("Resume at 6:42 · 8 min left", bookmarkTileLine(item("r", 402_000, 900_000)))
        assertEquals("From the start · 10 min", bookmarkTileLine(item("f", 590_000, 600_000)))
        assertEquals("From the start · 10 min", bookmarkTileLine(item("n", 5_000, 600_000)))
        assertNull(bookmarkTileLine(item("u", 0, 0)))
    }

    @Test
    fun `panel line and progress`() {
        val r = item("r", 450_000, 900_000)
        assertEquals("You're at 7:30 · 8 min left", bookmarkPanelLine(r))
        assertEquals(0.5f, bookmarkProgress(r)!!, 0.001f)
        assertNull(bookmarkProgress(item("n", 5_000, 600_000)))
        assertNull(bookmarkPanelLine(item("u", 0, 0)))
    }

    @Test
    fun `first run needs nothing paired and nothing bookmarked`() {
        assertTrue(isFirstRun(0, 0))
        assertFalse(isFirstRun(1, 0))
        assertFalse(isFirstRun(0, 2))
    }

    @Test
    fun `connected label`() {
        assertNull(connectedPhonesLabel(0))
        assertEquals("1 phone connected", connectedPhonesLabel(1))
        assertEquals("3 phones connected", connectedPhonesLabel(3))
    }

    @Test
    fun `ready status follows the server state`() {
        assertEquals(ReadyTone.Ok, readyStatus(WebSocketServer.ConnectionState.Running(8766)).tone)
        assertEquals(ReadyTone.Ok, readyStatus(WebSocketServer.ConnectionState.Connected("x")).tone)
        assertEquals(ReadyTone.Pending, readyStatus(WebSocketServer.ConnectionState.Starting).tone)
        assertEquals("Not ready: boom", readyStatus(WebSocketServer.ConnectionState.Error("boom")).text)
        assertEquals(ReadyTone.Problem, readyStatus(WebSocketServer.ConnectionState.Stopped).tone)
    }

    @Test
    fun `pairing code and countdown`() {
        assertEquals("482 915", formatPairingCode("482915"))
        assertEquals("1234", formatPairingCode("1234"))
        assertEquals(60, pairingSecondsLeft(0))
        assertEquals(48, pairingSecondsLeft(12_000))
        assertEquals(0, pairingSecondsLeft(70_000))
        assertEquals(0.8f, pairingFractionLeft(12_000), 0.001f)
        assertEquals("Expires in 1 second", expiresInText(1))
        assertEquals("Expires in 48 seconds", expiresInText(48))
    }

    private fun item(id: String, pos: Long, dur: Long, ts: Long = 0, fav: Boolean = false) =
        PlaybackHistoryItem(
            id = id, payloadJson = "{}", url = "https://example.invalid/$id", title = id,
            position = pos, duration = dur, timestamp = ts, isFavorite = fav,
        )
}
