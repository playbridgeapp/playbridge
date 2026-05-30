package com.playbridge.player.player

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.PlayPayload

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
