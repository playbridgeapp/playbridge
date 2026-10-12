package com.playbridge.sender.cast.remote

import com.playbridge.sender.data.library.PlaybackResumeDao
import com.playbridge.sender.data.library.PlaybackResumeEntity
import com.playbridge.sender.data.library.WatchlistDao
import com.playbridge.sender.data.library.WatchlistEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteQueueProgressSourceTest {
    private class Tracking {
        val resumes = MutableStateFlow<List<PlaybackResumeEntity>>(emptyList())
        val watchlist = MutableStateFlow<WatchlistEntity?>(null)
        val resumeReads = mutableListOf<Int>()
        val watchlistReads = mutableListOf<Int>()
        val resumeDao = Proxy.newProxyInstance(PlaybackResumeDao::class.java.classLoader, arrayOf(PlaybackResumeDao::class.java)) { _, method, args ->
            check(method.name == "observeForTmdb") { "Unexpected DAO operation ${method.name}" }
            resumeReads += args!![0] as Int; resumes
        } as PlaybackResumeDao
        val watchlistDao = Proxy.newProxyInstance(WatchlistDao::class.java.classLoader, arrayOf(WatchlistDao::class.java)) { _, method, args ->
            check(method.name == "getById") { "Unexpected DAO operation ${method.name}" }
            watchlistReads += args!![0] as Int; watchlist
        } as WatchlistDao
        val source = LibraryRemoteQueueProgressSource(resumeDao, watchlistDao)
    }
    @Test fun existingDaoFlowsUpdateEachContentKeyWithoutWritesOrQueueIndexInference() = runTest {
        val f = Tracking()
        val episode = RemoteProgressKey(100, 1, 2)
        val other = RemoteProgressKey(100, 1, 3)
        var current: Map<String, RemoteQueueProgress> = emptyMap()
        f.source.observe(setOf(episode, other)).onEach { current = it }.launchIn(backgroundScope)
        runCurrent()
        assertTrue(current.isEmpty())
        assertEquals(listOf(100), f.resumeReads)
        assertEquals(listOf(100), f.watchlistReads)
        val resume = PlaybackResumeEntity(episode.contentKey, 100, "tv", 1, 2, positionMs = 500, durationMs = 1000)
        f.resumes.value = listOf(resume); runCurrent()
        assertEquals(resume, current[episode.contentKey]?.resume)
        assertNull(current[other.contentKey])
        val watchlist = WatchlistEntity(100, "tv", "Show", null, "", "", seasonProgress = 1, episodeProgress = 3)
        f.watchlist.value = watchlist; runCurrent()
        assertEquals(watchlist, current[other.contentKey]?.watchlist)
        assertNull(current[other.contentKey]?.resume)
        f.resumes.value = emptyList(); f.watchlist.value = null; runCurrent()
        assertTrue(current.isEmpty())
    }
    @Test fun emptyQueueDoesNotReadDaosAndMismatchedMediaTypesStayUnknown() = runTest {
        val f = Tracking()
        assertTrue(f.source.observe(emptySet()).first().isEmpty())
        assertTrue(f.resumeReads.isEmpty()); assertTrue(f.watchlistReads.isEmpty())
        f.watchlist.value = WatchlistEntity(100, "movie", "Movie", null, "", "", status = "completed")
        val episode = RemoteProgressKey(100, 1, 2)
        f.resumes.value = listOf(PlaybackResumeEntity(episode.contentKey, 100, "movie", positionMs = 900, durationMs = 1000))
        assertTrue(f.source.observe(setOf(episode)).first().isEmpty())
    }
    @Test fun onlyCompleteContentIdentityCanJoinTracking() {
        fun key(id: String?, season: Int?, episode: Int?) = RemoteProgressKey.from(RemoteQueueItem(0, "", tmdbId = id, season = season, episode = episode))
        assertEquals("tmdb:100:0:2", key("100", 0, 2)?.contentKey)
        assertEquals("tmdb:100", key("100", null, null)?.contentKey)
        assertNull(key(null, 1, 2)); assertNull(key("invalid", 1, 2)); assertNull(key("-1", 1, 2))
        assertNull(key("100", 1, null)); assertNull(key("100", null, 2)); assertNull(key("100", 1, 0))
    }
}
