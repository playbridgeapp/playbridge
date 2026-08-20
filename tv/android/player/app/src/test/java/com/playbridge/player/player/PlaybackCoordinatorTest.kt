package com.playbridge.player.player

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.PlayPayload
import playbridge.VisualMetadata

class PlaybackCoordinatorTest {

    private class FakeHost : PlaybackCoordinator.Host {
        val loaded = mutableListOf<Pair<PlayPayload, String?>>()
        var savedThumbnailFlags = mutableListOf<Boolean>()
        var playlistChangedCount = 0
        var lastChangeIndex = -1
        val messages = mutableListOf<String>()
        var finished = false

        override fun loadItem(item: PlayPayload, displayTitle: String?) {
            loaded.add(item to displayTitle)
        }

        override suspend fun saveProgressBeforeAdvance(captureThumbnail: Boolean) {
            savedThumbnailFlags.add(captureThumbnail)
        }

        override fun onPlaylistChanged(items: List<PlayPayload>, index: Int) {
            playlistChangedCount++
            lastChangeIndex = index
        }

        override fun showMessage(message: String) {
            messages.add(message)
        }

        override fun onPlaylistFinished() {
            finished = true
        }
    }

    private fun payload(n: Int) = PlayPayload(url = "https://a.com/$n.mp4", title = "Ep$n")

    private val three = listOf(payload(1), payload(2), payload(3))

    @Test
    fun `single video is not treated as a playlist`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(listOf(payload(1)), 0)
        assertFalse(c.hasPlaylist)
    }

    @Test
    fun `next advances cursor and loads next item`() = runTest {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(three, 0)

        c.next()

        assertEquals(1, c.index)
        assertEquals(three[1], host.loaded.single().first)
        assertEquals("Ep2 (2/3)", host.loaded.single().second)
        assertEquals(listOf(true), host.savedThumbnailFlags)
        assertTrue(host.playlistChangedCount > 0)
        assertFalse(host.finished)
    }

    @Test
    fun `next at end finishes`() = runTest {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(three, 2)

        c.next()

        assertTrue(host.finished)
        assertTrue(host.loaded.isEmpty())
    }

    @Test
    fun `next on empty queue finishes`() = runTest {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.next()
        assertTrue(host.finished)
    }

    @Test
    fun `previous goes back`() = runTest {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(three, 2)

        c.previous()

        assertEquals(1, c.index)
        assertEquals(three[1], host.loaded.single().first)
    }

    @Test
    fun `previous at start shows message and does not load`() = runTest {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(three, 0)

        c.previous()

        assertEquals(0, c.index)
        assertTrue(host.loaded.isEmpty())
        assertEquals(listOf("Already on first episode"), host.messages)
    }

    @Test
    fun `jumpTo loads target and does not capture thumbnail`() = runTest {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(three, 0)

        c.jumpTo(2)

        assertEquals(2, c.index)
        assertEquals(three[2], host.loaded.single().first)
        assertEquals(listOf(false), host.savedThumbnailFlags)
    }

    @Test
    fun `jumpTo out of bounds is ignored`() = runTest {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(three, 0)

        c.jumpTo(9)

        assertEquals(0, c.index)
        assertTrue(host.loaded.isEmpty())
    }

    @Test
    fun `queueAdd appends and notifies`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(listOf(payload(1)), 0)

        c.queueAdd(listOf(payload(2), payload(3)))

        assertEquals(3, c.playlist.size)
        assertTrue(c.hasPlaylist)
        assertEquals(1, host.playlistChangedCount)
    }

    @Test
    fun `queueAdd ignores empty`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(listOf(payload(1)), 0)
        c.queueAdd(emptyList())
        assertEquals(1, c.playlist.size)
        assertEquals(0, host.playlistChangedCount)
    }

    /** Episode payload keyed by season/episode (and imdb), with a quality-specific URL. */
    private fun episode(season: Int, ep: Int, url: String, imdb: String? = "tt0944947") =
        PlayPayload(
            url = url,
            title = "S${season}E$ep",
            visual_metadata = VisualMetadata(season = season, episode = ep, imdb_id = imdb),
        )

    @Test
    fun `queueAdd skips an episode already present by season-episode`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(listOf(episode(2, 5, "https://cdn/720/s2e5.mp4")), 0)

        // Same S2E5 re-resolved at a different quality/URL — must not duplicate.
        c.queueAdd(listOf(episode(2, 5, "https://cdn/1080/s2e5.mp4")))

        assertEquals(1, c.playlist.size)
        assertEquals(0, host.playlistChangedCount)
    }

    @Test
    fun `queueAdd appends a genuinely new episode`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(listOf(episode(2, 5, "https://cdn/s2e5.mp4")), 0)

        c.queueAdd(listOf(episode(2, 6, "https://cdn/s2e6.mp4")))

        assertEquals(2, c.playlist.size)
        assertEquals(1, host.playlistChangedCount)
    }

    @Test
    fun `queueAdd appends only the new episodes from a mixed batch`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(listOf(episode(2, 5, "https://cdn/s2e5.mp4")), 0)

        // E5 is a duplicate, E6 is new — only E6 should be appended.
        c.queueAdd(
            listOf(
                episode(2, 5, "https://cdn/alt/s2e5.mp4"),
                episode(2, 6, "https://cdn/s2e6.mp4"),
            )
        )

        assertEquals(2, c.playlist.size)
        assertEquals("S2E6", c.playlist.last().title)
        assertEquals(1, host.playlistChangedCount)
    }

    @Test
    fun `queueAdd dedupes non-episode items by exact url`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        // payload(n) has no visual_metadata — falls back to URL identity.
        c.setPlaylist(listOf(payload(1)), 0)

        c.queueAdd(listOf(payload(1))) // same URL
        assertEquals(1, c.playlist.size)
        assertEquals(0, host.playlistChangedCount)

        c.queueAdd(listOf(payload(2))) // different URL
        assertEquals(2, c.playlist.size)
        assertEquals(1, host.playlistChangedCount)
    }

    @Test
    fun `queueAdd keeps linked page items that share a url but have distinct payloads`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        val first = PlayPayload(
            url = "https://cdn/stream.m3u8",
            title = "Linked item 1",
            detected_by = "linked_page",
        )
        val second = first.copy(title = "Linked item 2")
        c.setPlaylist(listOf(first), 0)

        c.queueAdd(listOf(second))

        assertEquals(listOf("Linked item 1", "Linked item 2"), c.playlist.map { it.title })
        assertEquals(1, host.playlistChangedCount)
    }

    @Test
    fun `queueAdd suppresses an exact linked page retry`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        val item = PlayPayload(
            url = "https://cdn/stream.m3u8",
            title = "Linked item 1",
            detected_by = "linked_page",
        )
        c.setPlaylist(listOf(item), 0)

        c.queueAdd(listOf(item))

        assertEquals(1, c.playlist.size)
        assertEquals(0, host.playlistChangedCount)
    }

    @Test
    fun `queueAdd of an all-duplicate batch does not notify`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(listOf(episode(2, 5, "https://cdn/s2e5.mp4")), 0)

        c.queueAdd(listOf(episode(2, 5, "https://cdn/other/s2e5.mp4")))

        assertEquals(1, c.playlist.size)
        assertEquals(0, host.playlistChangedCount)
    }

    @Test
    fun `queueAdd dedupes same episode twice in one drain batch`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(listOf(episode(2, 5, "https://cdn/s2e5.mp4")), 0)

        // Phone concurrent top-up / retry can land two queue_adds in pendingQueueItems
        // before the player drains them as a single list. Filtering only against the
        // pre-existing queue would keep both.
        c.queueAdd(
            listOf(
                episode(2, 6, "https://cdn/720/s2e6.mp4"),
                episode(2, 6, "https://cdn/1080/s2e6.mp4"),
            )
        )

        assertEquals(2, c.playlist.size)
        assertEquals("S2E6", c.playlist.last().title)
        assertEquals(1, host.playlistChangedCount)
    }

    @Test
    fun `queueAdd dedupes same url twice in one drain batch`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(listOf(payload(1)), 0)

        c.queueAdd(listOf(payload(2), payload(2), payload(3)))

        assertEquals(3, c.playlist.size)
        assertEquals(listOf("Ep1", "Ep2", "Ep3"), c.playlist.map { it.title })
    }

    @Test
    fun `queueAdd keeps only first of mixed duplicates in batch`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(listOf(episode(1, 1, "https://cdn/s1e1.mp4")), 0)

        c.queueAdd(
            listOf(
                episode(1, 2, "https://cdn/a/s1e2.mp4"),
                episode(1, 1, "https://cdn/alt/s1e1.mp4"), // already on queue
                episode(1, 2, "https://cdn/b/s1e2.mp4"), // dup of first in batch
                episode(1, 3, "https://cdn/s1e3.mp4"),
            )
        )

        assertEquals(3, c.playlist.size)
        assertEquals(listOf("S1E1", "S1E2", "S1E3"), c.playlist.map { it.title })
    }

    @Test
    fun `markCurrentFailed prefixes title once`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(three, 1)

        c.markCurrentFailed()
        assertEquals("[FAILED] Ep2", c.playlist[1].title)

        // Idempotent — does not double-prefix.
        c.markCurrentFailed()
        assertEquals("[FAILED] Ep2", c.playlist[1].title)
    }

    @Test
    fun `displayTitle has no suffix for single video`() {
        val host = FakeHost()
        val c = PlaybackCoordinator(host)
        c.setPlaylist(listOf(payload(1)), 0)
        assertEquals("Ep1", c.displayTitle(payload(1), 0))
    }
}
