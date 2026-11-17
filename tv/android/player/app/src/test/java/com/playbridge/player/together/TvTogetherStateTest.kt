package com.playbridge.player.together

import com.playbridge.player.ui.toPartyUi
import com.playbridge.player.ui.withMember
import com.playbridge.player.ui.withWaiting
import com.playbridge.player.ui.withoutMember
import com.playbridge.shared.together.TogetherDeviceKind
import com.playbridge.shared.together.TogetherEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvTogetherStateTest {
    @Test
    fun `member event for self updates host badge without adding self to member list or waitingFor`() {
        var party = TvTogether.State.InParty(relay = "relay.example", connected = true)
            .withMember(
                TogetherEvent.Member("me-tv", "Living Room TV", TogetherDeviceKind.Tv, host = false),
                selfMemberId = "me-tv",
            )
            .withMember(
                TogetherEvent.Member("host-1", "Alex", TogetherDeviceKind.Phone, host = true),
                selfMemberId = "me-tv",
            )
            .withWaiting(listOf("me-tv", "host-1"), selfMemberId = "me-tv")

        assertEquals(mapOf("host-1" to "Alex"), party.members)
        assertEquals("Alex", party.hostName)
        assertEquals(listOf("Alex"), party.waitingFor)

        val ui = party.toPartyUi()
        assertEquals("Alex’s watch party", ui.partyName)
        assertEquals(1, ui.members.size)
        assertEquals("Alex", ui.members.single().name)
        assertTrue(ui.members.single().isHost)
    }

    @Test
    fun `host name follows acting host transitions including self and no host`() {
        var party = TvTogether.State.InParty(relay = "relay.example", connected = true)
            .withMember(
                TogetherEvent.Member("host-1", "Alex", TogetherDeviceKind.Phone, host = true),
                selfMemberId = "me-tv",
            )
            .withMember(
                TogetherEvent.Member("me-tv", "Living Room TV", TogetherDeviceKind.Tv, host = false),
                selfMemberId = "me-tv",
            )
            .withMember(
                TogetherEvent.Member("guest-2", "Sam", TogetherDeviceKind.Phone, host = false),
                selfMemberId = "me-tv",
            )
        assertEquals("Alex’s watch party", party.toPartyUi().partyName)

        // Real host leaves; this TV (delegate) becomes acting host.
        party = party
            .withMember(
                TogetherEvent.Member("me-tv", "Living Room TV", TogetherDeviceKind.Tv, host = true),
                selfMemberId = "me-tv",
            )
            .withoutMember("host-1")
        assertEquals(mapOf("guest-2" to "Sam"), party.members)
        assertEquals("Living Room TV", party.hostName)
        val selfHostUi = party.toPartyUi()
        assertEquals("Living Room TV’s watch party", selfHostUi.partyName)
        assertEquals(1, selfHostUi.members.size)
        assertFalse(selfHostUi.members.single().isHost)

        // Real host returns: acting host loses badge, real host regains it.
        party = party
            .withMember(
                TogetherEvent.Member("me-tv", "Living Room TV", TogetherDeviceKind.Tv, host = false),
                selfMemberId = "me-tv",
            )
            .withMember(
                TogetherEvent.Member("host-1", "Alex", TogetherDeviceKind.Phone, host = true),
                selfMemberId = "me-tv",
            )
        assertEquals("Alex’s watch party", party.toPartyUi().partyName)
        assertTrue(party.toPartyUi().members.first { it.name == "Alex" }.isHost)

        // Last host leaves with no delegate: hostName becomes null and title falls back to "Watch party".
        party = party.withoutMember("host-1")
        assertNull(party.hostName)
        assertEquals("Watch party", party.toPartyUi().partyName)
        assertFalse(party.toPartyUi().members.any { it.isHost })
    }

    @Test
    fun `same display names still attribute Host badge only to the host member id`() {
        val ui = TvTogether.State.InParty(relay = "relay.example", connected = true)
            .withMember(
                TogetherEvent.Member("m1", "Pixel", TogetherDeviceKind.Phone, host = true),
                selfMemberId = "me-tv",
            )
            .withMember(
                TogetherEvent.Member("m2", "Pixel", TogetherDeviceKind.Phone, host = false),
                selfMemberId = "me-tv",
            )
            .toPartyUi()
        assertEquals(2, ui.members.size)
        assertTrue(ui.members[0].isHost)
        assertFalse(ui.members[1].isHost)
    }
}
