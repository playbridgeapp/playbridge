package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteTitlePolicyTest {
    @Test fun filenamesLoseExtensionsSeparatorsAndKnownReleaseTags() {
        assertEquals("Below S1E4 The Ocean", RemoteTitlePolicy.clean("Below.S1E4.The_Ocean.1080p.WEB-DL.x265.mkv"))
        assertEquals("A Movie", RemoteTitlePolicy.clean("A.Movie.[2160p].HEVC.BluRay.mp4"))
        assertEquals("My Song", RemoteTitlePolicy.clean("My_Song.flac"))
        assertEquals("Holiday Photo", RemoteTitlePolicy.clean("Holiday_Photo.jpg"))
    }
    @Test fun humanTitlesAndMeaningfulNumbersRemainUntouched() {
        for (title in listOf("Dr. No", "WALL-E", "1917", "1080p", "WEB-DL", "Mr. Robot · S1E4", "A. B. C.")) {
            assertEquals(title, RemoteTitlePolicy.clean(title))
        }
        assertEquals("1917 Directors Cut", RemoteTitlePolicy.clean("1917.Directors.Cut.1080p.mkv"))
        assertEquals("Film MysteryTag", RemoteTitlePolicy.clean("Film.MysteryTag.x265.mkv"))
    }
    @Test fun nullEmptyAndTagOnlyInputsAreSafe() {
        assertNull(RemoteTitlePolicy.clean(null))
        assertEquals("", RemoteTitlePolicy.clean(""))
        assertEquals("x265.mkv", RemoteTitlePolicy.clean("x265.mkv"))
    }
    @Test fun cleaningNeverMutatesStoredTitleAndEpisodeUsesStableCurrentItem() {
        val raw = testSnapshot().state.copy(playback = RemotePlayback(title = "Series.S1E2.1080p.mkv"),
            queue = RemoteQueueState(currentItemId = "two", currentIndex = 0, items = listOf(
                RemoteQueueItem(0, "First", itemId = "one", season = 1, episode = 1),
                RemoteQueueItem(8, "Second", itemId = "two", season = 1, episode = 2))))
        assertEquals("Series S1E2", RemoteTitlePolicy.clean(raw.playback?.title))
        assertEquals("Series.S1E2.1080p.mkv", raw.playback?.title)
        assertNull(RemoteTitlePolicy.subtitle(raw))
        assertEquals("S1E2", RemoteTitlePolicy.subtitle(raw.copy(playback = raw.playback?.copy(title = "Second"))))
        assertNull(RemoteTitlePolicy.subtitle(raw.copy(queue = raw.queue?.copy(currentItemId = "missing"))))
    }
    @Test fun subtitleLeavesPlaybackStateToTheSeekBar() {
        fun line(playback: RemotePlayback) = RemoteTitlePolicy.subtitle(RemoteUiState(playback = playback))
        for (playback in listOf(
            RemotePlayback(displayedState = RemotePlaybackState.Paused),
            RemotePlayback(displayedState = RemotePlaybackState.Buffering),
            RemotePlayback(displayedState = RemotePlaybackState.Playing),
            RemotePlayback(isLive = true),
        )) assertNull(line(playback))
        assertEquals("Image", line(RemotePlayback(mediaKind = RemoteMediaKind.Image)))
    }
    @Test fun duplicateEpisodeCodeIsHiddenAndMatchesNumericMetadata() {
        val queue = RemoteQueueState(currentItemId = "seven", items = listOf(RemoteQueueItem(0, "", "seven", season = 4, episode = 7)))
        val state = RemoteUiState(queue = queue, playback = RemotePlayback(title = "Reacher · S4E7 · Vote for Sampson", displayedState = RemotePlaybackState.Playing))
        assertNull(RemoteTitlePolicy.subtitle(state))
        assertNull(RemoteTitlePolicy.subtitle(state.copy(playback = state.playback?.copy(displayedState = RemotePlaybackState.Paused))))
        assertNull(RemoteTitlePolicy.subtitle(state.copy(playback = state.playback?.copy(title = "Reacher.S04.E007.Vote.1080p.mkv"))))
        assertEquals("S4E7", RemoteTitlePolicy.subtitle(state.copy(playback = state.playback?.copy(title = "Reacher · S4E70 · Vote"))))
    }
    @Test fun episodeCardsLoseBadgeCodeAndShowPrefixForDisplayOnly() {
        for (raw in listOf("S4E7 · Vote for Sampson", "Reacher · S4E7 · Vote for Sampson", "Reacher - s04e07: Vote for Sampson", "Reacher.S04E07.Vote_for_Sampson.1080p.mkv")) {
            val item = RemoteQueueItem(0, raw, "seven", season = 4, episode = 7)
            assertEquals("Vote for Sampson", RemoteTitlePolicy.episodeTitle(item))
            assertEquals(raw, item.title)
            assertEquals("seven", item.itemId)
        }
        assertEquals("Vote for Sampson", RemoteTitlePolicy.episodeTitle(RemoteQueueItem(0, "Vote for Sampson (S4E7)", season = 4, episode = 7)))
        assertEquals("Episode", RemoteTitlePolicy.episodeTitle(RemoteQueueItem(0, "S4E7", season = 4, episode = 7)))
    }
    @Test fun episodeCleaningPreservesUnknownOrMismatchedBadgeInformation() {
        val item = RemoteQueueItem(0, "Reacher · S4E8 · Cut", season = 4, episode = 7)
        assertEquals(item.title, RemoteTitlePolicy.episodeTitle(item))
        assertEquals(item.title, RemoteTitlePolicy.episodeTitle(item.copy(season = null)))
        assertEquals("Vote for Sampson", RemoteTitlePolicy.episodeTitle(item.copy(title = "Vote for Sampson")))
        assertEquals("Episode 1", RemoteTitlePolicy.episodeTitle(item.copy(title = "")))
    }
}
