package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.remote.RemotePlayback
import com.playbridge.sender.cast.remote.RemoteQueueItem
import com.playbridge.sender.cast.remote.RemoteQueueState
import com.playbridge.sender.cast.remote.RemoteUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteQueueProgressTest {
    @Test
    fun `only current live playback contributes progress`() {
        val current = RemoteQueueItem(0, "Current", itemId = "current")
        val next = RemoteQueueItem(1, "Next", itemId = "next")
        val state = RemoteUiState(queue = RemoteQueueState(currentIndex = 0, items = listOf(current, next)),
            playback = RemotePlayback(displayedPositionMs = 40_000, durationMs = 100_000))
        assertEquals(EpisodeWatchStatus.InProgress(0.4f), resolveEpisodeWatchStatus(current, state))
        assertEquals(EpisodeWatchStatus.Neutral, resolveEpisodeWatchStatus(next, state))
        assertEquals(EpisodeWatchStatus.Neutral, resolveEpisodeWatchStatus(current, RemoteUiState()))
    }

    @Test
    fun `generic queue labels retain optional episode metadata`() {
        assertEquals("S2·E4", formatEpisodeBadge(2, 4, 0))
        assertEquals("E4", formatEpisodeBadge(null, 4, 0))
        assertEquals("3", formatEpisodeBadge(null, null, 2))
    }
}
