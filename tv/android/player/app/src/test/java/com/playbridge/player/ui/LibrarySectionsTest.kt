package com.playbridge.player.ui

import com.playbridge.player.data.PlaybackHistoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySectionsTest {
    @Test
    fun `recent excludes continue watching entries while favorites remain independent`() {
        val recentFavorite = item("favorite", position = 60_000, duration = 600_000, timestamp = 3, favorite = true)
        val completed = item("complete", position = 590_000, duration = 600_000, timestamp = 2)
        val barelyStarted = item("start", position = 10_000, duration = 600_000, timestamp = 1)

        val sections = buildLibrarySections(listOf(barelyStarted, completed, recentFavorite))

        assertEquals(listOf("complete", "start"), sections.recent.map { it.id })
        assertEquals(listOf("favorite"), sections.continueWatching.map { it.id })
        assertEquals(listOf("favorite"), sections.favorites.map { it.id })
        assertTrue(sections.favorites.first().isFavorite)
    }

    @Test
    fun `completed recent item restarts while unfinished item resumes`() {
        val completed = item("complete", position = 590_000, duration = 600_000, timestamp = 2)
        val unfinished = item("unfinished", position = 240_000, duration = 600_000, timestamp = 1)

        assertNull(resumePositionForHistoryItem(completed))
        assertEquals(240_000L, resumePositionForHistoryItem(unfinished))
    }

    @Test
    fun `thumbnail revision invalidates the library image cache key`() {
        val first = item("snapshot", position = 60_000, duration = 600_000, timestamp = 1)
            .copy(thumbnailUrl = "file:///snapshot.jpg", thumbnailRevision = 10)
        val refreshed = first.copy(thumbnailRevision = 20)

        assertEquals("file:///snapshot.jpg#10", historyThumbnailCacheKey(first))
        assertEquals("file:///snapshot.jpg#20", historyThumbnailCacheKey(refreshed))
    }

    private fun item(
        id: String,
        position: Long,
        duration: Long,
        timestamp: Long,
        favorite: Boolean = false,
    ) = PlaybackHistoryItem(
        id = id,
        payloadJson = "{}",
        url = "https://example.invalid/$id",
        title = id,
        position = position,
        duration = duration,
        timestamp = timestamp,
        isFavorite = favorite,
    )
}
