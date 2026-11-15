package com.playbridge.sender.together

import com.playbridge.shared.together.TogetherEvent
import com.playbridge.shared.together.TogetherItem
import com.playbridge.shared.together.TogetherPolicy
import com.playbridge.shared.together.TogetherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.PlayPayload
import playbridge.SubtitleResource

class PartyQueueTest {
    private fun item(id: String, title: String = "Video $id") = PartyQueueItem(
        id = id,
        url = "https://cdn.example.com/$id.mp4",
        title = title,
        site = "cdn.example.com",
    )

    private fun togetherItem(id: String, title: String = "Video $id") = TogetherItem(
        id = id,
        title = title,
        site = "cdn.example.com",
        source = TogetherSource.Url("https://cdn.example.com/$id.mp4"),
    )

    private fun party(
        host: Boolean = true,
        policy: TogetherPolicy = TogetherPolicy.OnlyHost,
        locked: Boolean = false,
        item: TogetherItem? = togetherItem("v1"),
    ) = PhoneTogether.Party(
        roomId = "room1",
        invite = "https://party.playbridge.app/j/room1#k=a&h=b",
        relay = "https://party.playbridge.app",
        me = "m1",
        host = host,
        watching = false,
        connected = true,
        item = item,
        policy = policy,
        locked = locked,
    )

    @Test
    fun `queue preserves insertion order, supports move, remove, takeNext, and clear`() {
        val queue = PartyQueue()
        queue.addAll(listOf(item("a"), item("b"), item("c")))
        assertEquals(listOf("a", "b", "c"), queue.items.map { it.id })

        assertTrue(queue.move(2, 0))
        assertEquals(listOf("c", "a", "b"), queue.items.map { it.id })
        assertFalse(queue.move(-1, 0))
        assertFalse(queue.move(1, 1))

        assertEquals("a", queue.remove("a")?.id)
        assertNull(queue.remove("missing"))
        assertEquals(listOf("c", "b"), queue.items.map { it.id })

        assertEquals("c", queue.peek()?.id)
        assertEquals("c", queue.takeNext()?.id)
        assertEquals(listOf("b"), queue.items.map { it.id })

        queue.clear()
        assertTrue(queue.isEmpty)
        assertNull(queue.takeNext())
    }

    @Test
    fun `splitForPlay puts first item to play now and queues remaining in order`() {
        val emptySplit = PartyMediaSupport.splitForPlay(emptyList())
        assertNull(emptySplit.first)
        assertTrue(emptySplit.rest.isEmpty())

        val singleSplit = PartyMediaSupport.splitForPlay(listOf(item("ep1")))
        assertEquals("ep1", singleSplit.first?.id)
        assertTrue(singleSplit.rest.isEmpty())

        val multiSplit = PartyMediaSupport.splitForPlay(listOf(item("ep1"), item("ep2"), item("ep3")))
        assertEquals("ep1", multiSplit.first?.id)
        assertEquals(listOf("ep2", "ep3"), multiSplit.rest.map { it.id })
    }

    @Test
    fun `itemOf extracts title, headers, subtitles, and startMs from PlayPayload`() {
        val payload = PlayPayload(
            url = "https://media.example.org/watch/123",
            title = "",
            headers = mapOf("Referer" to "https://media.example.org/"),
            subtitles = listOf("https://media.example.org/en.vtt"),
            subtitle_resources = listOf(SubtitleResource(url = "https://media.example.org/es.vtt", label = "Spanish")),
            start_position_ms = 45_000L,
        )
        val built = PartyMediaSupport.itemOf(payload, fallbackTitle = "Episode 1")
        assertEquals("Episode 1", built.title)
        assertEquals("media.example.org", built.site)
        assertEquals(mapOf("Referer" to "https://media.example.org/"), built.headers)
        assertEquals(
            listOf("https://media.example.org/en.vtt", "https://media.example.org/es.vtt"),
            built.subtitles,
        )
        assertEquals(45_000L, built.startMs)
    }

    @Test
    fun `evaluatePartyUrl allows remote and LAN hosts while rejecting phone-only, loopback, and own phone addresses`() {
        val ownAddresses = setOf("192.168.1.25", "fe80::1234", "fd00::42")

        val remoteHttps = PartyMediaSupport.evaluatePartyUrl("https://cdn.example.com/movie.m3u8", ownAddresses)
        assertNull(remoteHttps.rejection)
        assertFalse(remoteHttps.isPrivateNetwork)

        val remoteHttp = PartyMediaSupport.evaluatePartyUrl("http://stream.example.org:8080/video.mp4", ownAddresses)
        assertNull(remoteHttp.rejection)
        assertFalse(remoteHttp.isPrivateNetwork)

        val allowedLan = listOf(
            "http://livingroom.local:8765/proxy",
            "http://192.168.1.44:8096/Items/1/Download",
            "http://10.0.0.12/stream.m3u8",
            "http://172.16.5.2/video.mp4",
            "http://169.254.1.10/video.mp4",
            "http://100.64.0.5/video.mp4",
            "http://[fd00::99]:8096/stream.mp4",
        )
        allowedLan.forEach { url ->
            val result = PartyMediaSupport.evaluatePartyUrl(url, ownAddresses)
            assertNull("Expected LAN URL '$url' to be allowed, got: ${result.rejection}", result.rejection)
            assertTrue("Expected LAN flag for '$url'", result.isPrivateNetwork)
        }

        val rejected = listOf(
            "",
            "content://media/external/video/media/42",
            "file:///storage/emulated/0/Movies/clip.mp4",
            "data:video/mp4;base64,AAAA",
            "blob:https://example.com/1234",
            "playlist://history-1",
            "ftp://example.com/clip.mp4",
            "http://localhost:8080/stream.mp4",
            "http://app.localhost/video.mp4",
            "http://127.0.0.1:9000/v.mp4",
            "http://127.20.0.5:9000/v.mp4",
            "http://0.0.0.0:8080/v.mp4",
            "http://[::1]:8080/stream.mp4",
            "http://192.168.1.25:8765/proxy/video.mp4",
            "http://[fe80::1234]:8765/stream.mp4",
            "http://[FD00::42]/video.mp4",
        )
        rejected.forEach { url ->
            assertNotNull("Expected rejection for '$url'", PartyMediaSupport.evaluatePartyUrl(url, ownAddresses).rejection)
        }
    }

    @Test
    fun `advance tracker waits for VideoCleared after Ended and advances once`() {
        val tracker = PartyAdvanceTracker()
        val queue = PartyQueue().apply { addAll(listOf(item("v2"), item("v3"))) }
        val p = party(item = togetherItem("v1"))

        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.Video(togetherItem("v1")), p, queue))

        // Ended enters WaitForClear without immediately taking v2.
        val waitDecision = tracker.onEvent(TogetherEvent.Ended("v1"), p, queue)
        assertEquals(PartyAdvanceDecision.WaitForClear("v1"), waitDecision)
        assertTrue(tracker.isWaitingForClear)
        assertEquals(listOf("v2", "v3"), queue.items.map { it.id })

        // Repeat Ended while waiting still waits once without taking from queue.
        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.Ended("v1"), p, queue))

        // Room VideoCleared advances once to v2.
        val firstAdvance = tracker.onEvent(TogetherEvent.VideoCleared, p, queue)
        assertTrue(firstAdvance is PartyAdvanceDecision.Load)
        assertEquals("v2", (firstAdvance as PartyAdvanceDecision.Load).item.id)
        assertFalse(tracker.isWaitingForClear)
        assertEquals(listOf("v3"), queue.items.map { it.id })

        // Repeat Ended, trailing VideoCleared, or stale grace callback must not skip to v3.
        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.Ended("v1"), p, queue))
        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.VideoCleared, p, queue))
        assertEquals(PartyAdvanceDecision.None, tracker.onGraceElapsed(p, queue))
        assertEquals(listOf("v3"), queue.items.map { it.id })
    }

    @Test
    fun `Ended without VideoCleared advances once when grace timer elapses`() {
        val tracker = PartyAdvanceTracker()
        val queue = PartyQueue().apply { addAll(listOf(item("v2"), item("v3"))) }
        val p = party(item = togetherItem("v1"))

        assertEquals(PartyAdvanceDecision.WaitForClear("v1"), tracker.onEvent(TogetherEvent.Ended("v1"), p, queue))
        val graceAdvance = tracker.onGraceElapsed(p, queue)
        assertTrue(graceAdvance is PartyAdvanceDecision.Load)
        assertEquals("v2", (graceAdvance as PartyAdvanceDecision.Load).item.id)
        assertFalse(tracker.isWaitingForClear)
        assertEquals(listOf("v3"), queue.items.map { it.id })

        // Duplicate grace call or late VideoCleared does not double-advance.
        assertEquals(PartyAdvanceDecision.None, tracker.onGraceElapsed(p, queue))
        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.VideoCleared, p, queue))
        assertEquals(listOf("v3"), queue.items.map { it.id })
    }

    @Test
    fun `new Video arriving after Ended cancels pending auto-advance and preserves queue`() {
        val tracker = PartyAdvanceTracker()
        val queue = PartyQueue().apply { addAll(listOf(item("v2"), item("v3"))) }
        val p1 = party(item = togetherItem("v1"))

        assertEquals(PartyAdvanceDecision.WaitForClear("v1"), tracker.onEvent(TogetherEvent.Ended("v1"), p1, queue))
        assertTrue(tracker.isWaitingForClear)

        // Someone else picks another video during the grace window.
        val externalItem = togetherItem("manual-pick")
        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.Video(externalItem), p1, queue))
        assertFalse(tracker.isWaitingForClear)
        assertEquals(listOf("v2", "v3"), queue.items.map { it.id })

        // Neither grace nor VideoCleared for anything prior advances now.
        val p2 = party(item = externalItem)
        assertEquals(PartyAdvanceDecision.None, tracker.onGraceElapsed(p2, queue))
        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.VideoCleared, p2, queue))
        assertEquals(listOf("v2", "v3"), queue.items.map { it.id })
    }

    @Test
    fun `unprompted VideoCleared without Ended does not advance queue`() {
        val tracker = PartyAdvanceTracker()
        val queue = PartyQueue().apply { addAll(listOf(item("v2"), item("v3"))) }
        val p = party(item = togetherItem("v1"))

        tracker.onEvent(TogetherEvent.Video(togetherItem("v1")), p, queue)
        // Another host/delegate pressed Stop: room sends VideoCleared with no prior Ended.
        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.VideoCleared, p, queue))
        assertFalse(tracker.isWaitingForClear)
        assertEquals(listOf("v2", "v3"), queue.items.map { it.id })

        // Even after room item becomes null, grace elapsed has nothing pending.
        val clearedParty = party(item = null)
        assertEquals(PartyAdvanceDecision.None, tracker.onGraceElapsed(clearedParty, queue))
        assertEquals(listOf("v2", "v3"), queue.items.map { it.id })
    }

    @Test
    fun `stale Ended event for an older video is ignored`() {
        val tracker = PartyAdvanceTracker()
        val queue = PartyQueue().apply { add(item("v3")) }
        val p = party(item = togetherItem("v2"))

        tracker.onEvent(TogetherEvent.Video(togetherItem("v2")), p, queue)
        val decision = tracker.onEvent(TogetherEvent.Ended("v1"), p, queue)
        assertEquals(PartyAdvanceDecision.None, decision)
        assertFalse(tracker.isWaitingForClear)
        assertEquals(listOf("v3"), queue.items.map { it.id })
    }

    @Test
    fun `manual stopVideo suppresses auto-advance and preserves Up next`() {
        val tracker = PartyAdvanceTracker()
        val queue = PartyQueue().apply { addAll(listOf(item("v2"), item("v3"))) }
        val p = party(item = togetherItem("v1"))

        tracker.onEvent(TogetherEvent.Video(togetherItem("v1")), p, queue)
        tracker.onManualStopRequested("v1")

        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.VideoCleared, p, queue))
        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.Ended("v1"), p, queue))
        assertEquals(PartyAdvanceDecision.None, tracker.onGraceElapsed(p, queue))
        assertEquals(listOf("v2", "v3"), queue.items.map { it.id })

        // Playing a new video clears the manual-stop latch.
        val p2 = party(item = togetherItem("v2b"))
        tracker.onExplicitLoadSent(fromQueue = false)
        tracker.onEvent(TogetherEvent.Video(togetherItem("v2b")), p2, queue)
        assertEquals(PartyAdvanceDecision.WaitForClear("v2b"), tracker.onEvent(TogetherEvent.Ended("v2b"), p2, queue))
        val afterSecondClears = tracker.onEvent(TogetherEvent.VideoCleared, p2, queue)
        assertTrue(afterSecondClears is PartyAdvanceDecision.Load)
        assertEquals("v2", (afterSecondClears as PartyAdvanceDecision.Load).item.id)
    }

    @Test
    fun `losing permission pauses auto-advance without dropping queued items`() {
        val tracker = PartyAdvanceTracker()
        val queue = PartyQueue().apply { addAll(listOf(item("v2"), item("v3"))) }

        val lockedParty = party(host = false, policy = TogetherPolicy.Anyone, locked = true, item = togetherItem("v1"))
        assertEquals(PartyAdvanceDecision.WaitForClear("v1"), tracker.onEvent(TogetherEvent.Ended("v1"), lockedParty, queue))
        val lockedDecision = tracker.onEvent(TogetherEvent.VideoCleared, lockedParty, queue)
        assertTrue(lockedDecision is PartyAdvanceDecision.Paused)
        assertEquals(listOf("v2", "v3"), queue.items.map { it.id })

        val hostOnlyParty = party(host = false, policy = TogetherPolicy.OnlyHost, locked = false, item = togetherItem("v1b"))
        assertEquals(PartyAdvanceDecision.WaitForClear("v1b"), tracker.onEvent(TogetherEvent.Ended("v1b"), hostOnlyParty, queue))
        val hostOnlyDecision = tracker.onGraceElapsed(hostOnlyParty, queue)
        assertTrue(hostOnlyDecision is PartyAdvanceDecision.Paused)
        assertEquals(listOf("v2", "v3"), queue.items.map { it.id })
    }

    @Test
    fun `queued load failure halts auto-advance without skipping through remaining queue`() {
        val tracker = PartyAdvanceTracker()
        val queue = PartyQueue().apply { addAll(listOf(item("bad"), item("good1"), item("good2"))) }
        val p1 = party(item = togetherItem("v1"))

        assertEquals(PartyAdvanceDecision.WaitForClear("v1"), tracker.onEvent(TogetherEvent.Ended("v1"), p1, queue))
        val loadBad = tracker.onEvent(TogetherEvent.VideoCleared, p1, queue)
        assertEquals("bad", (loadBad as PartyAdvanceDecision.Load).item.id)

        // Queued item appears as room video, then player reports a load failure before playback.
        val badRoomItem = togetherItem("bad-room")
        val pBad = party(item = badRoomItem)
        tracker.onEvent(TogetherEvent.Video(badRoomItem), pBad, queue)
        assertTrue(tracker.onPhonePlayerLoadFailed(badRoomItem.id))
        assertTrue(tracker.isHaltedOnFailure)

        // Room clearing/ending the failed item must NOT advance to good1.
        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.Ended(badRoomItem.id), pBad, queue))
        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.VideoCleared, pBad, queue))
        assertEquals(listOf("good1", "good2"), queue.items.map { it.id })

        // Play now / Play next now (fromQueue = true) also halts on failure instead of skipping remaining items.
        tracker.reset()
        tracker.onExplicitLoadSent(fromQueue = true)
        val playNowRoomItem = togetherItem("manual-bad")
        val pManualBad = party(item = playNowRoomItem)
        tracker.onEvent(TogetherEvent.Video(playNowRoomItem), pManualBad, queue)
        assertTrue(tracker.onPhonePlayerLoadFailed(playNowRoomItem.id))
        assertTrue(tracker.isHaltedOnFailure)
        assertEquals(PartyAdvanceDecision.None, tracker.onEvent(TogetherEvent.Ended(playNowRoomItem.id), pManualBad, queue))
        assertEquals(listOf("good1", "good2"), queue.items.map { it.id })

        // Direct synchronous send/load failure also pauses without skipping.
        tracker.reset()
        tracker.onExplicitLoadSent(fromQueue = true)
        val paused = tracker.onQueuedLoadFailed()
        assertTrue(paused is PartyAdvanceDecision.Paused)
        assertEquals(listOf("good1", "good2"), queue.items.map { it.id })
    }
}
