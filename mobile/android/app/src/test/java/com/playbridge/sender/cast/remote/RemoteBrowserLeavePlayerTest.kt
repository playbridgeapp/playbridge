package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.MediaTrack
import com.playbridge.sender.cast.TvPlaybackStatus
import com.playbridge.sender.cast.TvPlayerSettings
import com.playbridge.sender.cast.resolveRemoteTimeline
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.connection.ReceiverQueueItem
import com.playbridge.sender.connection.ReceiverQueueState
import java.lang.reflect.Proxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteBrowserLeavePlayerTest {
    @Test fun playerToBrowserClearsSnapshotsAndBrowserStatusDrivesSeekAndTitle() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        coordinator.startLocalPlaybackSession()
        emit(
            messages,
            playlist("Old Movie"),
            status("paused", 800_000, 800_000, "Old Movie"),
            """{"type":"tracks","audio":[{"id":"a1","name":"English","selected":true}]}""",
            """{"type":"player_settings","isLive":true,"isSeekable":false}""",
        )
        runCurrent()
        assertEquals("Old Movie", coordinator.tvPlayback.value?.title)
        assertEquals(2, coordinator.tvPlaylistState.value?.items?.size)
        assertEquals(listOf("a1"), coordinator.tvAudioTracks.value.map(MediaTrack::id))
        assertTrue(coordinator.tvPlayerSettings.value.isLive)
        assertTrue(coordinator.remoteObservation.value.settingsReported)

        emit(messages, context("player"))
        runCurrent()
        assertEquals("Old Movie", coordinator.tvPlayback.value?.title)

        emit(messages, context("browser"))
        runCurrent()
        assertEquals("browser", coordinator.tvActiveContext.value)
        assertNull(coordinator.tvPlayback.value)
        assertNull(coordinator.tvPlaylistState.value)
        assertTrue(coordinator.tvVideoTracks.value.isEmpty())
        assertTrue(coordinator.tvAudioTracks.value.isEmpty())
        assertTrue(coordinator.tvSubtitleTracks.value.isEmpty())
        assertEquals(TvPlayerSettings(), coordinator.tvPlayerSettings.value)
        assertFalse(coordinator.remoteObservation.value.settingsReported)

        emit(messages, """{"type":"status","state":"idle","position":0,"duration":0}""")
        runCurrent()
        var shown = presented(coordinator)
        var layout = resolveRemoteLayout(shown, RemoteViewport())
        assertEquals(RemotePlaybackState.Idle, shown.playback?.reportedState)
        assertFalse(layout.wave)
        assertFalse(layout.info)
        assertFalse(layout.showQueue)
        assertNull(RemoteTitlePolicy.browserHeading(shown.playback?.title))

        emit(messages, status("playing", 1_000, 0, "Example Page"))
        runCurrent()
        shown = presented(coordinator)
        layout = resolveRemoteLayout(shown, RemoteViewport())
        assertFalse(layout.wave)
        assertTrue(layout.info)
        assertEquals("Example Page", RemoteTitlePolicy.browserHeading(shown.playback?.title))
        assertNull(RemoteTitlePolicy.subtitle(shown))

        emit(messages, status("paused", 13 * 60_000, 20 * 60_000, "https://videos.example.com/watch"))
        runCurrent()
        shown = presented(coordinator)
        layout = resolveRemoteLayout(shown, RemoteViewport())
        assertTrue(layout.wave)
        assertTrue(layout.info)
        assertFalse(layout.showQueue)
        assertEquals("videos.example.com", RemoteTitlePolicy.browserHeading(shown.playback?.title))
        assertNull(RemoteTitlePolicy.subtitle(shown.copy(queue = RemoteQueueState(currentIndex = 0, items = listOf(
            RemoteQueueItem(0, "Old Movie", season = 1, episode = 2),
            RemoteQueueItem(1, "Next", season = 1, episode = 3),
        )))))
        assertFalse(resolveRemoteLayout(shown.copy(queue = RemoteQueueState(items = listOf(
            RemoteQueueItem(0, "A"), RemoteQueueItem(1, "B"),
        ))), RemoteViewport()).showQueue)

        emit(messages, context("browser"))
        runCurrent()
        assertEquals("https://videos.example.com/watch", coordinator.tvPlayback.value?.title)

        emit(
            messages,
            context("player"),
            playlist("New Episode"),
            status("playing", 5_000, 600_000, "New Episode"),
        )
        runCurrent()
        shown = presented(coordinator)
        layout = resolveRemoteLayout(shown, RemoteViewport())
        assertEquals(RemoteContext.Player, shown.context)
        assertEquals("New Episode", shown.playback?.title)
        assertEquals("New Episode", coordinator.tvPlaylistState.value?.items?.first()?.title)
        assertTrue(layout.wave)
        assertTrue(layout.info)
        assertTrue(layout.showQueue)
    }

    @Test fun screenMirrorAndIdleAlsoDropThePlayerSnapshots() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        emit(messages, context("player"), playlist("Old Movie"), status("playing", 1_000, 50_000, "Old Movie"))
        runCurrent()
        emit(messages, context("screen_mirror"))
        runCurrent()
        assertEquals("screen_mirror", coordinator.tvActiveContext.value)
        assertNull(coordinator.tvPlayback.value)
        assertNull(coordinator.tvPlaylistState.value)

        emit(messages, context("browser"), status("playing", 1_000, 50_000, "Page"))
        runCurrent()
        assertEquals("Page", coordinator.tvPlayback.value?.title)
        emit(messages, context("idle"))
        runCurrent()
        assertNull(coordinator.tvPlayback.value)
        assertNull(coordinator.tvPlaylistState.value)
    }

    @Test fun startLocalPlaybackSessionReplacesIdentityAndDropsTheOldPosition() = runTest {
        val coordinator = ConnectionCoordinator(MutableSharedFlow(extraBufferCapacity = 1), null, backgroundScope) { 0L }
        coordinator.tvActiveContext.value = "player"
        coordinator.tvPlayback.value = TvPlaybackStatus("playing", 500_000, 600_000, "Old")
        coordinator.tvPlaylistState.value = ReceiverQueueState(items = listOf(ReceiverQueueItem(0, "Old")))
        coordinator.startLocalPlaybackSession()
        assertEquals("player", coordinator.tvActiveContext.value)
        assertNull(coordinator.tvPlayback.value)
        assertNull(coordinator.tvPlaylistState.value)
    }



    @Test fun browserHeadingUsesThePageTitleOrTheUrlHostAndDropsTheEpisodeLine() {
        assertNull(RemoteTitlePolicy.browserHeading(null))
        assertNull(RemoteTitlePolicy.browserHeading("  "))
        assertEquals("Example Page", RemoteTitlePolicy.browserHeading("Example Page"))
        assertEquals("videos.example.com", RemoteTitlePolicy.browserHeading("https://videos.example.com/watch?v=1"))
        assertEquals("example.com", RemoteTitlePolicy.browserHeading("https://example.com:8443/a"))
        assertEquals("Watch https://example.com today", RemoteTitlePolicy.browserHeading("Watch https://example.com today"))
        val player = RemoteUiState(
            context = RemoteContext.Player,
            playback = RemotePlayback(title = "Show", displayedState = RemotePlaybackState.Paused),
            queue = RemoteQueueState(items = listOf(RemoteQueueItem(0, "Ep", season = 1, episode = 2))),
        )
        assertEquals("S1E2", RemoteTitlePolicy.subtitle(player))
        assertNull(RemoteTitlePolicy.subtitle(player.copy(context = RemoteContext.Browser)))
    }

    @Test fun pausedPlayerBehindTheBrowserStaysOffTheCardUntilPlayerReturns() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 32)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        emit(
            messages,
            context("browser"),
            context("browser"),
            context("browser"),
            playlistOne("Old Movie"),
            """{"type":"tracks","audio":[{"id":"a1","name":"English","selected":true}]}""",
            """{"type":"player_settings","speed":1.5,"isLive":true,"isSeekable":true}""",
            playlistOne("Old Movie"),
            """{"type":"tracks","audio":[{"id":"a1","name":"English","selected":true}]}""",
            """{"type":"player_settings","speed":1.5,"isLive":true,"isSeekable":true}""",
            playerStatus("paused", 5_000, 962_000, "Old Movie"),
        )
        runCurrent()
        assertEquals("browser", coordinator.tvActiveContext.value)
        assertNull(coordinator.tvPlayback.value)
        assertNull(coordinator.tvPlaylistState.value)
        assertTrue(coordinator.tvAudioTracks.value.isEmpty())
        assertEquals(TvPlayerSettings(), coordinator.tvPlayerSettings.value)
        assertFalse(coordinator.remoteObservation.value.settingsReported)
        var shown = presented(coordinator)
        var layout = resolveRemoteLayout(shown, RemoteViewport())
        assertFalse(layout.wave)
        assertFalse(layout.info)
        assertFalse(layout.showQueue)
        assertNull(RemoteTitlePolicy.browserHeading(shown.playback?.title))

        emit(messages, """{"type":"status","state":"paused","position":12000,"duration":60000,"title":"Example Page","muted":true}""")
        runCurrent()
        shown = presented(coordinator)
        layout = resolveRemoteLayout(shown, RemoteViewport())
        assertTrue(layout.wave)
        assertTrue(layout.info)
        assertFalse(layout.showQueue)
        assertEquals("Example Page", shown.playback?.title)
        assertEquals(true, coordinator.remoteObservation.value.browserMuted)

        emit(
            messages,
            playerStatus("paused", 8_000, 962_000, "Behind"),
            playerStatus("paused", 9_000, 962_000, "Webhook", mediaKind = null, currentItemId = null),
            playerStatus("paused", 10_000, 962_000, "Item", mediaKind = null, playbackId = null),
        )
        runCurrent()
        assertEquals("Example Page", coordinator.tvPlayback.value?.title)
        assertEquals(12_000L, coordinator.tvPlayback.value?.positionMs)
        assertEquals(true, coordinator.remoteObservation.value.browserMuted)
        assertNull(coordinator.tvPlaylistState.value)
        shown = presented(coordinator)
        layout = resolveRemoteLayout(shown, RemoteViewport())
        assertTrue(layout.wave)
        assertEquals("Example Page", RemoteTitlePolicy.browserHeading(shown.playback?.title))

        emit(messages, playerStatus("paused", 5_000, 962_000, "Old Movie"))
        runCurrent()
        assertEquals("Example Page", coordinator.tvPlayback.value?.title)

        emit(messages, context("player"))
        runCurrent()
        shown = presented(coordinator)
        layout = resolveRemoteLayout(shown, RemoteViewport())
        assertEquals(RemoteContext.Player, shown.context)
        assertEquals("Old Movie", coordinator.tvPlayback.value?.title)
        assertEquals(5_000L, coordinator.tvPlayback.value?.positionMs)
        assertEquals(962_000L, coordinator.tvPlayback.value?.durationMs)
        assertEquals(1, coordinator.tvPlaylistState.value?.totalCount)
        assertEquals("Old Movie", coordinator.tvPlaylistState.value?.items?.single()?.title)
        assertEquals(listOf("a1"), coordinator.tvAudioTracks.value.map(MediaTrack::id))
        assertEquals(1.5f, coordinator.tvPlayerSettings.value.speed, 0f)
        assertTrue(coordinator.tvPlayerSettings.value.isLive)
        assertTrue(coordinator.remoteObservation.value.settingsReported)
        assertTrue(layout.wave)
        assertTrue(layout.info)
        assertFalse(layout.showQueue)
    }

    @Test fun browserExternalAlsoHoldsPlayerStatus() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        emit(
            messages,
            context("browser_external"),
            playerStatus("paused", 5_000, 962_000, "Old Movie"),
            status("playing", 1_000, 30_000, "Plugin page"),
        )
        runCurrent()
        assertEquals("Plugin page", coordinator.tvPlayback.value?.title)
        assertEquals(1_000L, coordinator.tvPlayback.value?.positionMs)
        emit(messages, context("player"))
        runCurrent()
        assertEquals("Old Movie", coordinator.tvPlayback.value?.title)
        assertEquals(5_000L, coordinator.tvPlayback.value?.positionMs)
    }

    @Test fun idleStillAppliesAPlayerStatusFrame() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        emit(
            messages,
            context("idle"),
            playlistOne("Old Movie"),
            playerStatus("paused", 5_000, 962_000, "Old Movie"),
        )
        runCurrent()
        assertEquals("idle", coordinator.tvActiveContext.value)
        assertEquals("Old Movie", coordinator.tvPlayback.value?.title)
        assertNull(coordinator.tvPlaylistState.value)
        emit(messages, context("player"))
        runCurrent()
        assertEquals("Old Movie", coordinator.tvPlaylistState.value?.items?.single()?.title)
        assertEquals(5_000L, coordinator.tvPlayback.value?.positionMs)
    }

    @Test fun startLocalPlaybackSessionDiscardsThePausedPlayerBehindTheBrowser() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        emit(
            messages,
            context("browser"),
            playlistOne("Old Movie"),
            playerStatus("paused", 5_000, 962_000, "Old Movie"),
        )
        runCurrent()
        assertNull(coordinator.tvPlayback.value)
        coordinator.startLocalPlaybackSession()
        assertEquals("player", coordinator.tvActiveContext.value)
        assertNull(coordinator.tvPlayback.value)
        assertNull(coordinator.tvPlaylistState.value)
        emit(messages, context("player"))
        runCurrent()
        assertNull(coordinator.tvPlayback.value)
        assertNull(coordinator.tvPlaylistState.value)
        emit(messages, status("playing", 1_000, 50_000, "New Title"))
        runCurrent()
        assertEquals("New Title", coordinator.tvPlayback.value?.title)
        emit(messages, context("player"))
        runCurrent()
        assertEquals("New Title", coordinator.tvPlayback.value?.title)
        assertNull(coordinator.tvPlaylistState.value)
    }


    private fun presented(coordinator: ConnectionCoordinator): RemoteUiState {
        val wire = coordinator.tvActiveContext.value
        val playback = coordinator.tvPlayback.value
        val timeline = resolveRemoteTimeline(wire, playback, coordinator.tvPlayerSettings.value)
        val queue = coordinator.tvPlaylistState.value
        return RemoteUiState(
            connection = RemoteConnection.Connected("TV"),
            context = remoteContextFromWire(wire),
            playback = playback?.let {
                RemotePlayback(
                    title = it.title?.takeIf { title -> title.isNotEmpty() },
                    mediaKind = remoteMediaKind(it.mediaKind),
                    reportedState = remotePlaybackState(it.state),
                    reportedPositionMs = it.positionMs,
                    durationMs = it.durationMs,
                    isLive = timeline.isLive,
                    seekable = support(timeline.isSeekable),
                )
            },
            queue = queue?.let {
                RemoteQueueState(
                    currentIndex = it.currentIndex,
                    items = it.items.map { item ->
                        RemoteQueueItem(item.index, item.title, item.itemId, season = item.season, episode = item.episode, tmdbId = item.tmdbId)
                    },
                )
            },
        )
    }
}

private const val DURATION_MS = 10 * 60_000L

private fun emit(messages: MutableSharedFlow<String>, vararg frames: String) {
    frames.forEach { check(messages.tryEmit(it)) }
}

private fun context(active: String) = """{"type":"context","active":"$active"}"""

private fun status(state: String, position: Long, duration: Long, title: String): String =
    """{"type":"status","state":"$state","position":$position,"duration":$duration,"title":${JSONObject.quote(title)}}"""

private fun playlist(title: String) = """{"type":"playlist_status","currentIndex":0,"totalCount":2,"playbackId":"pb","items":[
    {"index":0,"title":${JSONObject.quote(title)},"season":1,"episode":2,"itemId":"ep","tmdbId":"42"},
    {"index":1,"title":"Next","season":1,"episode":3,"itemId":"next","tmdbId":"42"}
]}"""

private fun playlistOne(title: String) = """{"type":"playlist_status","currentIndex":0,"totalCount":1,"playbackId":"pb","items":[
    {"index":0,"title":${JSONObject.quote(title)},"season":1,"episode":2,"itemId":"ep","tmdbId":"42"}
]}"""

private fun playerStatus(
    state: String,
    position: Long,
    duration: Long,
    title: String,
    mediaKind: String? = "video",
    playbackId: String? = "pb-1",
    currentItemId: String? = "ep",
): String {
    val extra = buildList {
        if (mediaKind != null) add(""""mediaKind":${JSONObject.quote(mediaKind)}""")
        if (playbackId != null) add(""""playbackId":${JSONObject.quote(playbackId)}""")
        if (currentItemId != null) add(""""currentItemId":${JSONObject.quote(currentItemId)}""")
    }.joinToString(",")
    val suffix = if (extra.isEmpty()) "" else ",$extra"
    return """{"type":"status","state":"$state","position":$position,"duration":$duration,"title":${JSONObject.quote(title)}$suffix}"""
}

private fun <T : Any> dao(type: Class<T>, call: (proxy: Any, method: String, args: Array<out Any>?) -> Any?): T {
    val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
        call(instance, method.name, args)
    }
    return type.cast(proxy) ?: error("DAO proxy was null")
}
