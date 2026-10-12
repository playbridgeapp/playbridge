package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.remote.RemotePlayback
import com.playbridge.sender.cast.remote.RemoteQueueItem
import com.playbridge.sender.data.library.PlaybackResumeEntity
import com.playbridge.sender.data.library.WatchlistEntity
import com.playbridge.sender.data.library.WatchlistStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteQueueProgressTest {

    private fun testItem(
        index: Int = 0,
        id: String = "item1",
        title: String = "Test Episode",
        season: Int? = 1,
        episode: Int? = 1,
        tmdbId: String? = "100",
    ) = RemoteQueueItem(
        index = index,
        title = title,
        itemId = id,
        season = season,
        episode = episode,
        tmdbId = tmdbId,
    )

    @Test
    fun watchlistCompletedMarksAnyEpisodeWatched() {
        val item = testItem(season = 2, episode = 5)
        val watchlist = WatchlistEntity(
            tmdbId = 100,
            mediaType = "tv",
            title = "Test Show",
            posterUrl = null,
            year = "2024",
            rating = "8.0",
            status = WatchlistStatus.COMPLETED.value,
        )
        val status = resolveEpisodeWatchStatus(item, watchlist = watchlist)
        assertEquals(EpisodeWatchStatus.Watched, status)
    }

    @Test
    fun watchlistPointerMarksEarlierSeasonsAndEpisodesWatched() {
        val watchlist = WatchlistEntity(
            tmdbId = 100,
            mediaType = "tv",
            title = "Test Show",
            posterUrl = null,
            year = "2024",
            rating = "8.0",
            status = WatchlistStatus.WATCHING.value,
            seasonProgress = 2,
            episodeProgress = 3,
        )

        // Earlier season -> Watched
        assertEquals(
            EpisodeWatchStatus.Watched,
            resolveEpisodeWatchStatus(testItem(season = 1, episode = 10), watchlist = watchlist),
        )

        // Same season, earlier episode -> Watched
        assertEquals(
            EpisodeWatchStatus.Watched,
            resolveEpisodeWatchStatus(testItem(season = 2, episode = 2), watchlist = watchlist),
        )

        // Same season, current or later episode -> Neutral
        assertEquals(
            EpisodeWatchStatus.Neutral,
            resolveEpisodeWatchStatus(testItem(season = 2, episode = 3), watchlist = watchlist),
        )
        assertEquals(
            EpisodeWatchStatus.Neutral,
            resolveEpisodeWatchStatus(testItem(season = 2, episode = 4), watchlist = watchlist),
        )

        // Later season -> Neutral
        assertEquals(
            EpisodeWatchStatus.Neutral,
            resolveEpisodeWatchStatus(testItem(season = 3, episode = 1), watchlist = watchlist),
        )
    }

    @Test
    fun playbackResumeProgressResolvesCorrectly() {
        val item = testItem()

        // >= shared 90% threshold -> Watched
        val resumeAlmostDone = PlaybackResumeEntity(
            contentKey = "tmdb:100:1:1",
            tmdbId = 100,
            mediaType = "tv",
            positionMs = 960_000L,
            durationMs = 1_000_000L,
            updatedAt = 12345L,
        )
        assertEquals(
            EpisodeWatchStatus.Watched,
            resolveEpisodeWatchStatus(item, resume = resumeAlmostDone),
        )

        // 50% -> InProgress(0.5f)
        val resumeHalf = PlaybackResumeEntity(
            contentKey = "tmdb:100:1:1",
            tmdbId = 100,
            mediaType = "tv",
            positionMs = 500_000L,
            durationMs = 1_000_000L,
            updatedAt = 12345L,
        )
        val halfStatus = resolveEpisodeWatchStatus(item, resume = resumeHalf)
        assertTrue(halfStatus is EpisodeWatchStatus.InProgress)
        assertEquals(0.5f, (halfStatus as EpisodeWatchStatus.InProgress).progress, 0.001f)

        // < 2% -> Neutral
        val resumeBarelyStarted = PlaybackResumeEntity(
            contentKey = "tmdb:100:1:1",
            tmdbId = 100,
            mediaType = "tv",
            positionMs = 10_000L,
            durationMs = 1_000_000L,
            updatedAt = 12345L,
        )
        assertEquals(
            EpisodeWatchStatus.Neutral,
            resolveEpisodeWatchStatus(item, resume = resumeBarelyStarted),
        )
    }

    @Test
    fun currentItemUsesLivePlaybackPosition() {
        val item = testItem()
        val currentPlayback = RemotePlayback(
            durationMs = 100_000L,
            displayedPositionMs = 40_000L,
        )

        // isCurrent = true -> InProgress(0.4f)
        val status = resolveEpisodeWatchStatus(
            item = item,
            isCurrent = true,
            currentPlayback = currentPlayback,
        )
        assertTrue(status is EpisodeWatchStatus.InProgress)
        assertEquals(0.4f, (status as EpisodeWatchStatus.InProgress).progress, 0.001f)

        // isCurrent = false -> does not use currentPlayback, stays Neutral
        val notCurrentStatus = resolveEpisodeWatchStatus(
            item = item,
            isCurrent = false,
            currentPlayback = currentPlayback,
        )
        assertEquals(EpisodeWatchStatus.Neutral, notCurrentStatus)
    }

    @Test
    fun neutralityRuleNeverInfersWatchedFromIndexAlone() {
        // Unidentified items or missing tracking must never be inferred as watched
        val item = RemoteQueueItem(index = 0, title = "Episode 0")
        assertEquals(EpisodeWatchStatus.Neutral, resolveEpisodeWatchStatus(item))
    }

    @Test fun currentLiveProgressWinsOverSavedResumeIncludingRestartAndWatchedBoundary() {
        val item = testItem()
        val resume = PlaybackResumeEntity("tmdb:100:1:1", 100, "tv", positionMs = 100, durationMs = 1000)
        val live = RemotePlayback(displayedPositionMs = 650, durationMs = 1000)
        assertEquals(EpisodeWatchStatus.InProgress(0.65f), resolveEpisodeWatchStatus(item, resume = resume, isCurrent = true, currentPlayback = live))
        assertEquals(EpisodeWatchStatus.InProgress(0.1f), resolveEpisodeWatchStatus(item, resume = resume, isCurrent = false, currentPlayback = live))
        assertEquals(EpisodeWatchStatus.Watched, resolveEpisodeWatchStatus(item, resume = resume, isCurrent = true, currentPlayback = live.copy(displayedPositionMs = 900)))
        assertEquals(EpisodeWatchStatus.Neutral, resolveEpisodeWatchStatus(item, resume = resume, isCurrent = true, currentPlayback = live.copy(displayedPositionMs = 0)))
        assertEquals(EpisodeWatchStatus.InProgress(0.1f), resolveEpisodeWatchStatus(item, resume = resume, isCurrent = true, currentPlayback = live.copy(durationMs = 0)))
    }

    @Test fun sharedWatchedThresholdIncludesExactlyNinetyPercentForResumeAndLive() {
        val item = testItem()
        for (position in listOf(899L, 900L, 949L)) {
            val resume = PlaybackResumeEntity("tmdb:100:1:1", 100, "tv", positionMs = position, durationMs = 1000)
            val live = RemotePlayback(displayedPositionMs = position, durationMs = 1000)
            val expected = if (position >= 900) EpisodeWatchStatus.Watched else EpisodeWatchStatus.InProgress(position / 1000f)
            assertEquals(expected, resolveEpisodeWatchStatus(item, resume = resume))
            assertEquals(expected, resolveEpisodeWatchStatus(item, isCurrent = true, currentPlayback = live))
        }
    }
    @Test fun uiStateLookupUsesStoredProgressAndStableCurrentIdentity() {
        val item = testItem().copy(index = 7, itemId = "episode")
        val resume = PlaybackResumeEntity("tmdb:100:1:1", 100, "tv", positionMs = 50, durationMs = 100)
        val queue = com.playbridge.sender.cast.remote.RemoteQueueState(currentIndex = 7, currentItemId = "other")
        val state = com.playbridge.sender.cast.remote.RemoteUiState(queue = queue,
            playback = RemotePlayback(displayedPositionMs = 95, durationMs = 100),
            queueProgress = mapOf(resume.contentKey to com.playbridge.sender.cast.remote.RemoteQueueProgress(resume)))
        assertEquals(EpisodeWatchStatus.InProgress(0.5f), resolveEpisodeWatchStatus(item, state))
        assertEquals(EpisodeWatchStatus.Neutral, resolveEpisodeWatchStatus(item.copy(tmdbId = null), state))
        val pointer = WatchlistEntity(100, "tv", "Show", null, "", "", seasonProgress = 1, episodeProgress = 2)
        val tracked = state.copy(queueProgress = mapOf(resume.contentKey to com.playbridge.sender.cast.remote.RemoteQueueProgress(watchlist = pointer)))
        assertEquals(EpisodeWatchStatus.Watched, resolveEpisodeWatchStatus(item, tracked))
        val current = item.copy(episode = 2)
        val same = tracked.copy(queueProgress = mapOf("tmdb:100:1:2" to com.playbridge.sender.cast.remote.RemoteQueueProgress(watchlist = pointer)))
        assertEquals(EpisodeWatchStatus.Neutral, resolveEpisodeWatchStatus(current, same))
    }

    @Test
    fun formatEpisodeBadgeFormatting() {
        assertEquals("S1·E2", formatEpisodeBadge(season = 1, episode = 2, index = 0))
        assertEquals("E5", formatEpisodeBadge(season = null, episode = 5, index = 4))
        assertEquals("3", formatEpisodeBadge(season = null, episode = null, index = 2))
    }

    @Test
    fun buildResumeContentKeyFormatting() {
        assertEquals("tmdb:456:1:4", buildResumeContentKey("456", 1, 4))
        assertEquals("tmdb:456", buildResumeContentKey("456", null, null))
        assertNull(buildResumeContentKey(null, 1, 1))
        assertNull(buildResumeContentKey("not-a-number", 1, 1))
    }
}
