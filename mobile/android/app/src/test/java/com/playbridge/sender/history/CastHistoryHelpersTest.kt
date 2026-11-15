package com.playbridge.sender.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastHistoryHelpersTest {
    @Test
    fun readsTheCurrentItemOfAPlaylistCommand() {
        val info = nativeCastInfo(
            """{"type":"command","action":"playlist","payload":{"start_index":1,"items":[
                {"url":"https://a.example/1.mp4","title":"One"},
                {"url":"https://a.example/2.mp4","title":"Two","detected_by":"page_cast"}]}}""",
        )!!
        assertEquals("Two", info.title)
        assertEquals(2, info.itemCount)
        assertEquals(1, info.startIndex)
        assertEquals("page_cast", info.detectedBy)
    }

    @Test
    fun readsTheOriginFromTheCommandTheAppReallySends() {
        val json = com.playbridge.shared.protocol.createPlaylistCommandJson(
            playbridge.PlaylistPayload(
                items = listOf(
                    playbridge.PlayPayload(url = "https://a.example/1.mp4", title = "One"),
                    playbridge.PlayPayload(url = "https://a.example/2.mp4", title = "Two", detected_by = "linked_page"),
                ),
                start_index = 1,
            ),
        )
        val info = nativeCastInfo(json)!!
        assertEquals("Two", info.title)
        assertEquals(1, info.startIndex)
        assertEquals("linked_page", info.detectedBy)
    }

    @Test
    fun ignoresCommandsThatAreNotAPlaylistStart() {
        assertNull(nativeCastInfo("""{"type":"command","action":"queue_add","payload":{"item":{"url":"https://a.example/3.mp4"}}}"""))
        assertNull(nativeCastInfo("not json"))
        assertNull(nativeCastInfo("""{"action":"playlist","payload":{"items":[]}}"""))
    }

    @Test
    fun anOutOfRangeStartIndexIsClamped() {
        val info = nativeCastInfo("""{"action":"playlist","payload":{"start_index":9,"items":[{"url":"https://a.example/1.mp4","title":"Only"}]}}""")!!
        assertEquals(0, info.startIndex)
        assertEquals("Only", info.title)
    }

    @Test
    fun statusFollowsWhatWasReported() {
        fun entry(
            failed: Boolean = false, live: Boolean = false, finished: Boolean = false,
            reported: Boolean = true, pos: Long = 0, dur: Long = 100_000,
        ) = CastHistoryEntry(
            "i", "t", "h", null, null, "DLNA", 0, pos, dur, reported, finished, live, 0, failed, null,
            null, null, null, null, null, null, true,
        )
        assertEquals(CastHistoryStatus.FAILED, entry(failed = true, finished = true).status)
        assertEquals(CastHistoryStatus.LIVE, entry(live = true).status)
        assertEquals(CastHistoryStatus.FINISHED, entry(finished = true, pos = 99_000).status)
        assertEquals(CastHistoryStatus.PROGRESS_UNKNOWN, entry(reported = false).status)
        assertEquals(CastHistoryStatus.IN_PROGRESS, entry(pos = 20_000).status)
        assertEquals(CastHistoryStatus.NOT_STARTED, entry(pos = 3_000).status)
        assertNull(entry(reported = false).progressFraction)
        assertNull(entry(live = true).progressFraction)
        assertTrue(entry(pos = 50_000).progressFraction == 0.5f)
    }

    @Test
    fun theGateFailsClosedForWebsitesAndHonoursEveryOffSwitch() {
        val bridged = BridgedOriginPolicy { it.startsWith("https://app.example") }
        val on = CastHistorySettings()
        fun gate(cfg: CastHistorySettings = on, o: CastOrigin = CastOrigin(), party: Boolean = false) =
            CastHistoryRepository.gate(cfg, o, bridged, party)
        assertNull(gate())
        assertEquals("watch_party", gate(party = true))
        assertEquals("disabled", gate(on.copy(enabled = false)))
        assertEquals("bridged_app", gate(o = CastOrigin(pageUrl = "https://app.example/x")))
        assertEquals("unknown_website_origin", gate(o = CastOrigin(detectedBy = "page_cast")))
        assertEquals("unknown_website_origin", gate(o = CastOrigin(pageInitiated = true)))
        assertNull(gate(o = CastOrigin(pageUrl = "https://plain.example", detectedBy = "page_cast")))
        assertNull(gate(on.copy(excludeBridgedApps = false), CastOrigin(pageUrl = "https://app.example/x")))
        assertNull(gate(on.copy(excludeBridgedApps = false), CastOrigin(detectedBy = "linked_page")))
    }
}
