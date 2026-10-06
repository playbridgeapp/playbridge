package com.playbridge.sender.cast.proxy

import com.playbridge.shared.protocol.createAddSubtitleCommandJson
import com.playbridge.shared.protocol.createPlaylistCommandJson
import com.playbridge.shared.protocol.createQueueAddCommandJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import playbridge.PlayPayload
import playbridge.PlaylistPayload
import playbridge.SubtitleResource
import playbridge.VisualMetadata
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativePlaybackLeasesTest {
    private class Fixture(scope: CoroutineScope = TestScope()) {
        val live = mutableMapOf<String, Int>()
        val leases = NativePlaybackLeases(scope) { url ->
            if (!url.startsWith("http://phone/")) null else {
                live[url] = (live[url] ?: 0) + 1
                AutoCloseable {
                    val count = live.getValue(url) - 1
                    if (count == 0) live.remove(url) else live[url] = count
                }
            }
        }
        fun send(action: String, payload: String, ok: Boolean = true) =
            leases.send("""{"type":"command","action":"$action","payload":$payload}""") { ok }
        fun start(url: String = "http://phone/video") = send("playlist", """{"items":[{"url":"$url"}]}""")
    }

    @Test fun canonicalWirePlaylistRetainsAllResources() {
        val f = Fixture()
        val message = createPlaylistCommandJson(PlaylistPayload(items = listOf(
            PlayPayload(url = "http://phone/video", subtitles = listOf("http://phone/sub"),
                subtitle_resources = listOf(SubtitleResource(url = "http://phone/structured")),
                visual_metadata = VisualMetadata(artwork_url = "http://phone/art", poster_url = "http://phone/poster",
                    backdrop_url = "http://phone/backdrop", logo_url = "http://phone/logo")),
            PlayPayload(url = "http://phone/next"),
        )))
        assertTrue(message.contains("subtitleResources"))
        assertTrue(message.contains("visualMetadata"))
        assertTrue(f.leases.send(message) { true })
        assertEquals(setOf("video", "sub", "structured", "art", "poster", "backdrop", "logo", "next"),
            f.live.keys.map { it.substringAfterLast('/') }.toSet())
        f.leases.clear()
    }
    @Test fun canonicalWireQueueAndLateSubtitleRetainResources() {
        val f = Fixture(); f.start()
        val item = PlayPayload(url = "http://phone/next", subtitle_resources = listOf(SubtitleResource(url = "http://phone/sub")))
        f.leases.send(createQueueAddCommandJson(item)) { true }
        f.leases.send(createQueueAddCommandJson(listOf(item.copy(url = "http://phone/last")), "current")) { true }
        val subtitle = createAddSubtitleCommandJson(SubtitleResource(url = "http://phone/late"))
        assertTrue(subtitle.contains("subtitleResource"))
        f.leases.send(subtitle) { true }
        assertEquals(setOf("video", "next", "last", "sub", "late"), f.live.keys.map { it.substringAfterLast('/') }.toSet())
        f.leases.clear()
    }
    @Test fun mixedSpellingsDeduplicateTheSameResource() {
        val f = Fixture()
        f.send("playlist", """{"items":[{"url":"http://phone/video",
            "subtitle_resources":[{"url":"http://phone/sub"}],"subtitleResources":[{"url":"http://phone/sub"}],
            "visual_metadata":{"artwork_url":"http://phone/art"},"visualMetadata":{"artworkUrl":"http://phone/art"}}]}""")
        assertEquals(mapOf("http://phone/video" to 1, "http://phone/sub" to 1, "http://phone/art" to 1), f.live)
        f.leases.clear()
    }

    @Test fun sustainedIdleReleasesButRepeatedIdleDoesNotPostponeGrace() = runTest {
        val f = Fixture(backgroundScope); f.start()
        f.leases.observe("""{"type":"playlist_status","items":[]}"""); runCurrent()
        advanceTimeBy(NativePlaybackLeases.IDLE_GRACE_MS - 1)
        f.leases.observe("""{"type":"context","active":"idle"}""")
        assertEquals(1, f.live.size)
        advanceTimeBy(1); runCurrent()
        assertTrue(f.live.isEmpty())
    }
    @Test fun transientIdleAndLongPauseDoNotRevoke() = runTest {
        val f = Fixture(backgroundScope); f.start()
        for (state in listOf("playing", "paused", "buffering")) {
            f.leases.observe("""{"type":"status","state":"idle"}"""); runCurrent()
            advanceTimeBy(1_000)
            f.leases.observe("""{"type":"status","state":"$state"}""")
            advanceTimeBy(24 * 60 * 60_000L); runCurrent()
            assertEquals(1, f.live.size)
        }
        f.leases.clear()
    }
    @Test fun lostConnectionGraceIsCancelledOnRecoveryAndStop() = runTest {
        val f = Fixture(backgroundScope); f.start()
        f.leases.inactive(); runCurrent(); advanceTimeBy(1_000)
        f.leases.active()
        advanceTimeBy(NativePlaybackLeases.IDLE_GRACE_MS); runCurrent()
        assertEquals(1, f.live.size)
        f.leases.inactive(); runCurrent(); advanceTimeBy(NativePlaybackLeases.IDLE_GRACE_MS); runCurrent()
        assertTrue(f.live.isEmpty())
        f.start(); f.leases.inactive(); runCurrent(); f.leases.clear()
        f.start("http://phone/new")
        advanceTimeBy(NativePlaybackLeases.IDLE_GRACE_MS); runCurrent()
        assertEquals(mapOf("http://phone/new" to 1), f.live)
        f.leases.clear()
    }
    @Test fun replacementCancelsOldIdleTimerAndFailedReplacementDoesNot() = runTest {
        val f = Fixture(backgroundScope); f.start()
        f.leases.inactive(); runCurrent(); f.start("http://phone/new")
        advanceTimeBy(NativePlaybackLeases.IDLE_GRACE_MS); runCurrent()
        assertEquals(mapOf("http://phone/new" to 1), f.live)
        f.leases.inactive(); runCurrent(); f.startFailed()
        advanceTimeBy(NativePlaybackLeases.IDLE_GRACE_MS); runCurrent()
        assertTrue(f.live.isEmpty())
    }

    @Test fun storedPlaylistDoesNotCancelTerminalGraceAndNewEpisodeActivityDoes() = runTest {
        val f = Fixture(backgroundScope); f.start()
        f.leases.observe("""{"type":"status","state":"playing","playbackId":"first"}""")
        f.leases.observe("""{"type":"status","state":"ended","playbackId":"first"}"""); runCurrent()
        f.leases.observe("""{"type":"playlist_status","items":[{"url":"http://phone/video"}]}""")
        advanceTimeBy(NativePlaybackLeases.IDLE_GRACE_MS); runCurrent()
        assertTrue(f.live.isEmpty())
        f.start()
        f.leases.observe("""{"type":"status","state":"ended","playbackId":"first"}"""); runCurrent()
        f.leases.observe("""{"type":"status","state":"paused","playbackId":"next"}""")
        f.leases.observe("""{"type":"status","state":"ended","playbackId":"first"}""")
        advanceTimeBy(NativePlaybackLeases.IDLE_GRACE_MS); runCurrent()
        assertEquals(1, f.live.size)
        f.leases.clear()
    }

    @Test fun nativePlaylistOwnsEveryItemSubtitleAndArtwork() {
        val f = Fixture()
        assertTrue(f.send("playlist", """{"items":[
            {"url":"http://phone/video","subtitles":["http://phone/sub"],
             "subtitle_resources":[{"url":"http://phone/structured-sub"}],
             "visual_metadata":{"artwork_url":"http://phone/art"}},
            {"url":"http://phone/next"},{"url":"https://direct.example/video"}]}"""))
        assertEquals(setOf("video", "sub", "structured-sub", "art", "next"), f.live.keys.map { it.substringAfterLast('/') }.toSet())
        f.leases.clear(); assertTrue(f.live.isEmpty())
    }
    @Test fun queueAddAndLateSubtitlesStayOwnedUntilExplicitStop() {
        val f = Fixture(); f.start()
        f.send("queue_add", """{"item":{"url":"http://phone/one"}}""")
        f.send("queue_add", """{"items":[{"url":"http://phone/two"}],"if_playback_id":"current"}""")
        f.send("control", """{"command":"add_subtitle:http://phone/sub"}""")
        f.send("control", """{"command":"add_subtitle","subtitle_resource":{"url":"http://phone/resource"}}""")
        for (command in listOf("pause", "play", "seek_to:200", "toggle")) f.send("control", """{"command":"$command"}""")
        f.send("playlist_jump", """{"index":1}""")
        assertEquals(5, f.live.size)
        f.send("control", """{"command":"stop"}""", ok = false)
        assertTrue("offline local stop must revoke too", f.live.isEmpty())
        f.leases.clear()
    }
    @Test fun failedReplacementAndAppendKeepOldOwnerAndReleaseNewGrants() {
        val f = Fixture(); f.start()
        assertFalse(f.startFailed())
        assertFalse(f.send("queue_add", """{"item":{"url":"http://phone/next"}}""", false))
        assertEquals(mapOf("http://phone/video" to 1), f.live)
        f.leases.clear()
    }
    private fun Fixture.startFailed() = send("playlist", """{"items":[{"url":"http://phone/failed"}]}""", false)

    @Test fun acceptedReplacementRetainsBeforeReleasingSharedUrl() {
        val f = Fixture(); f.start()
        assertTrue(f.leases.send("""{"type":"command","action":"playlist","payload":{"items":[{"url":"http://phone/video"}]}}""") {
            assertEquals(2, f.live["http://phone/video"]); true
        })
        assertEquals(1, f.live["http://phone/video"])
        f.leases.clear(); assertTrue(f.live.isEmpty())
    }
    @Test fun transportExceptionCleansUpOnlyIncomingOwner() {
        val f = Fixture(); f.start()
        try {
            f.leases.send("""{"type":"command","action":"playlist","payload":{"items":[{"url":"http://phone/new"}]}}""") { error("send failed") }
            fail("expected failure")
        } catch (_: IllegalStateException) { }
        assertEquals(mapOf("http://phone/video" to 1), f.live)
        f.leases.clear()
    }
    @Test fun receiverStatusDoesNotRevokeAndDetachIsIdempotent() {
        val f = Fixture(); f.start()
        for (state in listOf("idle", "stopped", "error", "ended")) {
            f.leases.send("""{"type":"status","state":"$state"}""") { true }
        }
        assertEquals(1, f.live["http://phone/video"])
        f.leases.clear(); f.leases.clear(); assertTrue(f.live.isEmpty())
    }
    @Test fun browserReplacementClearsPlaybackOnlyAfterSuccessfulSend() {
        val f = Fixture(); f.start()
        assertFalse(f.send("browser", """{"url":"https://page.example"}""", false))
        assertEquals(1, f.live.size)
        assertTrue(f.send("browser", """{"url":"https://page.example"}"""))
        assertTrue(f.live.isEmpty())
    }
}
