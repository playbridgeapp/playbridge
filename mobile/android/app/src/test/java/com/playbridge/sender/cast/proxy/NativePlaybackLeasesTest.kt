package com.playbridge.sender.cast.proxy

import org.junit.Assert.*
import org.junit.Test

class NativePlaybackLeasesTest {
    private class Fixture {
        val live = mutableMapOf<String, Int>()
        val leases = NativePlaybackLeases { url ->
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
