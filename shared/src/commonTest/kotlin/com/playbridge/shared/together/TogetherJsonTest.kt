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
        assertEquals(
            TogetherEvent.Stopped("r1", TogetherStop.RoomFull),
            TogetherJson.event("""{"event":"stopped","room":"r1","reason":"room_full"}"""),
        )
        assertNull(TogetherJson.event("""{"event":"something_new"}"""))
        assertNull(TogetherJson.event("not json"))
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
    fun parsesLiveStreams() {
        assertEquals(TogetherEvent.Live(true), TogetherJson.event("""{"event":"live","live":true}"""))
        assertEquals(
            TogetherEvent.Player(TogetherPlayerCommand.GoLive),
            TogetherJson.event("""{"event":"player","command":{"action":"go_live"}}"""),
        )
    }

    @Test
    fun policiesRoundTrip() {
        assertEquals(TogetherEvent.Policy(TogetherPolicy.Ask), TogetherJson.event("""{"event":"policy","policy":"ask"}"""))
        assertEquals("only_host", TogetherPolicy.OnlyHost.wire)
    }
}
