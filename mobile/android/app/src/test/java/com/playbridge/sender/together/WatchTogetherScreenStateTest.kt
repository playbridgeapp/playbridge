package com.playbridge.sender.together

import com.playbridge.shared.together.TogetherDeviceKind
import com.playbridge.shared.together.TogetherEvent
import com.playbridge.shared.together.TogetherItem
import com.playbridge.shared.together.TogetherPolicy
import com.playbridge.shared.together.TogetherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTogetherScreenStateTest {
    private val sampleItem = TogetherItem(
        id = "v1",
        title = "Demo clip",
        site = "example.com",
        source = TogetherSource.Url("https://example.com/v.mp4"),
    )

    private fun party(
        host: Boolean = true,
        policy: TogetherPolicy = TogetherPolicy.OnlyHost,
        locked: Boolean = false,
        item: TogetherItem? = sampleItem,
        durationMs: Long? = 120_000L,
        live: Boolean = false,
        watching: Boolean = false,
    ) = PhoneTogether.Party(
        roomId = "room1",
        invite = "https://party.playbridge.app/j/room1#k=a&h=b",
        relay = "https://party.playbridge.app",
        me = "m1",
        host = host,
        watching = watching,
        connected = true,
        item = item,
        durationMs = durationMs,
        live = live,
        policy = policy,
        locked = locked,
    )

    @Test
    fun `on demand video shows seek bar and stop when allowed`() {
        val ui = mapNowPlayingState(party(host = true, durationMs = 120_000L), positionMs = 30_000L)
        assertTrue(ui.hasVideo)
        assertTrue(ui.showSeekBar)
        assertEquals(30_000L, ui.positionMs)
        assertEquals(120_000L, ui.durationMs)
        assertEquals("example.com", ui.subtitle)
        assertTrue(ui.canStop)
    }

    @Test
    fun `live stream hides seek bar and shows Live label`() {
        val ui = mapNowPlayingState(party(live = true, durationMs = null), positionMs = 15_000L)
        assertTrue(ui.hasVideo)
        assertTrue(ui.live)
        assertFalse(ui.showSeekBar)
        assertEquals("example.com · Live", ui.subtitle)
    }

    @Test
    fun `stop button is hidden when member cannot change video`() {
        val guestAsk = mapNowPlayingState(party(host = false, policy = TogetherPolicy.Ask), positionMs = 0L)
        assertFalse(guestAsk.canStop)

        val guestAnyoneUnlocked = mapNowPlayingState(party(host = false, policy = TogetherPolicy.Anyone, locked = false), positionMs = 0L)
        assertTrue(guestAnyoneUnlocked.canStop)

        val guestAnyoneLocked = mapNowPlayingState(party(host = false, policy = TogetherPolicy.Anyone, locked = true), positionMs = 0L)
        assertFalse(guestAnyoneLocked.canStop)
    }

    @Test
    fun `video cleared returns party to preplay state while keeping membership and watch preference`() {
        var current = party(host = true, watching = true, durationMs = 90_000L)
        current = reducePartyEvent(
            current,
            TogetherEvent.Timeline(item = "v1", playing = true, positionMs = 10_000L, rev = 2L),
            elapsedMs = 1_000L,
        )
        assertEquals(15_000L, current.positionNow(elapsedMs = 6_000L))

        current = reducePartyEvent(current, TogetherEvent.VideoCleared, elapsedMs = 7_000L)
        assertNull(current.item)
        assertNull(current.durationMs)
        assertFalse(current.playing)
        assertFalse(current.live)
        assertTrue(current.watching)
        assertEquals("room1", current.roomId)

        val ui = mapNowPlayingState(current, positionMs = current.positionNow(elapsedMs = 8_000L))
        assertFalse(ui.hasVideo)
        assertFalse(ui.showSeekBar)
        assertFalse(ui.canStop)
    }

    @Test
    fun `ended event clears matching item while keeping watch preference and ignoring stale item`() {
        val current = party(host = false, watching = true, durationMs = 60_000L)
        val stale = reducePartyEvent(current, TogetherEvent.Ended(item = "other"), elapsedMs = 2_000L)
        assertEquals("v1", stale.item?.id)

        val cleared = reducePartyEvent(stale, TogetherEvent.Ended(item = "v1"), elapsedMs = 3_000L)
        assertNull(cleared.item)
        assertNull(cleared.durationMs)
        assertFalse(cleared.playing)
        assertFalse(cleared.live)
        assertTrue(cleared.watching)
        assertEquals("room1", cleared.roomId)
    }

    @Test
    fun `member ordering puts host and self first and memberLeft cannot drop self`() {
        val withSelf = party(host = false).copy(
            me = "m1",
            members = mapOf(
                "m1" to PhoneTogether.Member(name = "My Phone", kind = TogetherDeviceKind.Phone, host = false),
                "m-host" to PhoneTogether.Member(name = "Zed Host", kind = TogetherDeviceKind.Tv, host = true),
                "m-desk" to PhoneTogether.Member(name = "Alice Mac", kind = TogetherDeviceKind.Desktop, host = false),
            ),
        )
        assertEquals(listOf("m-host", "m1", "m-desk"), orderedPartyMembers(withSelf).map { it.first })

        val afterStraySelfLeave = reducePartyEvent(withSelf, TogetherEvent.MemberLeft("m1"), elapsedMs = 4_000L)
        assertTrue("m1" in afterStraySelfLeave.members)

        val afterPeerLeave = reducePartyEvent(withSelf, TogetherEvent.MemberLeft("m-desk"), elapsedMs = 4_100L)
        assertFalse("m-desk" in afterPeerLeave.members)
        assertEquals(2, afterPeerLeave.members.size)
    }
}
