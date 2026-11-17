package com.playbridge.shared.together

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TogetherJsonTest {
    @Test
    fun parsesAPlayerLoadWithItsSignInHeaders() {
        val event = TogetherJson.event(
            """{"event":"player","command":{"action":"load","start_ms":1500.0,
            "headers":{"Referer":"https://a.test","Cookie":"s=1"},
            "item":{"id":"v1","title":"Film","site":"a.test","source":{"type":"url","url":"https://a.test/v.m3u8"},
            "headers":{"Referer":"https://a.test"},"start_ms":0,"shared_by":"ab"}}}""",
        )
        val load = (event as TogetherEvent.Player).command as TogetherPlayerCommand.Load
        assertEquals("v1", load.item.id)
        assertEquals(TogetherSource.Url("https://a.test/v.m3u8"), load.item.source)
        assertEquals("s=1", load.headers["Cookie"])
        assertNull(load.item.headers["Cookie"])
        assertEquals(1500L, load.startMs)
    }

    @Test
    fun parsesRoomAndStopEventsAndIgnoresUnknownOnes() {
        val room = TogetherJson.event(
            """{"event":"room","invite":"https://r.test/j/x#k=a&h=b","relay":"r.test","member":"m","box_pk":"bk","sign_pk":"sk","host":true}""",
        ) as TogetherEvent.Room
        assertTrue(room.host)
        assertFalse(room.toString().contains("#k="), "invite links hold the room key")
        assertNull(room.endsAtMs)
        assertNull(room.saved)
        val dated = TogetherJson.event(
            """{"event":"room","invite":"https://r.test/j/x#k=a&h=b","relay":"r.test","member":"m","box_pk":"bk","sign_pk":"sk","host":false,"ends_at":1800000000000.0,"saved":"c2VjcmV0"}""",
        ) as TogetherEvent.Room
        assertEquals(1_800_000_000_000L, dated.endsAtMs)
        assertEquals("c2VjcmV0", dated.saved)
        assertFalse(dated.toString().contains("c2VjcmV0"), "saved parties hold the member's keys")
        assertEquals(
            TogetherEvent.Stopped("r1", TogetherStop.RoomFull),
            TogetherJson.event("""{"event":"stopped","room":"r1","reason":"room_full"}"""),
        )
        assertEquals(
            TogetherEvent.Stopped("r1", TogetherStop.RoomLimit, 600),
            TogetherJson.event("""{"event":"stopped","room":"r1","reason":"room_limit","retry_after":600}"""),
        )
        assertEquals(TogetherEvent.Unreachable, TogetherJson.event("""{"event":"unreachable"}"""))
        assertNull(TogetherJson.event("""{"event":"something_new"}"""))
        assertNull(TogetherJson.event("not json"))
    }

    @Test
    fun saysWhenALimitedPartyMayTryAgain() {
        assertEquals("in a minute", togetherRetryIn(30))
        assertEquals("in 12 minutes", togetherRetryIn(700))
        assertEquals("in about an hour", togetherRetryIn(3_700))
        assertEquals("in about 5 hours", togetherRetryIn(18_000))
        assertEquals(
            "The watch party relay is busy today. Try again in about 2 hours.",
            togetherLimitMessage(TogetherStop.RelayBusy, 7_000),
        )
        assertNull(togetherLimitMessage(TogetherStop.RoomFull, 0))
    }

    @Test
    fun writesItemsWithoutAnEmptyId() {
        val item = TogetherJson.item(
            TogetherItem(title = "Film", source = TogetherSource.App("jellyfin", "42"), headers = mapOf("Cookie" to "s=1")),
        )
        assertFalse("id" in item)
        val json = item.toString()
        assertTrue(json.contains("\"type\":\"app\""))
        assertTrue(json.contains("\"Cookie\":\"s=1\""), "the engine moves sign-in headers out itself")
    }

    @Test
    fun parsesLiveStreamsClearEndedAndDuration() {
        assertEquals(TogetherEvent.Live(true), TogetherJson.event("""{"event":"live","live":true}"""))
        assertEquals(
            TogetherEvent.Player(TogetherPlayerCommand.GoLive),
            TogetherJson.event("""{"event":"player","command":{"action":"go_live"}}"""),
        )
        assertEquals(TogetherEvent.Duration(95_000L), TogetherJson.event("""{"event":"duration","duration_ms":95000}"""))
        assertEquals(TogetherEvent.Duration(null), TogetherJson.event("""{"event":"duration","duration_ms":null}"""))
        assertEquals(TogetherEvent.Ended("v1"), TogetherJson.event("""{"event":"ended","item":"v1"}"""))
        assertEquals(TogetherEvent.VideoCleared, TogetherJson.event("""{"event":"video_cleared"}"""))
        assertEquals(
            TogetherEvent.Player(TogetherPlayerCommand.Clear),
            TogetherJson.event("""{"event":"player","command":{"action":"clear"}}"""),
        )
    }

    @Test
    fun policiesRoundTrip() {
        assertEquals(TogetherEvent.Policy(TogetherPolicy.Ask), TogetherJson.event("""{"event":"policy","policy":"ask"}"""))
        assertEquals("only_host", TogetherPolicy.OnlyHost.wire)
    }
}
