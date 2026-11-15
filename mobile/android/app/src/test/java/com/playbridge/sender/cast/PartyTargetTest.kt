package com.playbridge.sender.cast

import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.together.PhoneTogether
import com.playbridge.shared.together.TogetherDeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyTargetTest {
    private val tv = TvDevice(
        ip = "192.0.2.10",
        port = 8765,
        token = "",
        name = "Living Room TV",
    )

    private val externalKey = tv.copy(name = "BedroomCast", protocol = CastProtocol.GOOGLE_CAST).endpointKey

    @Test
    fun `target selection state enters party, restores prior route, and clears on switch or leave`() {
        val enteredFromTv = onEnterPartySelection(CastSessionManager.Route.NativeTv)
        assertTrue(enteredFromTv.partySelected)
        assertEquals(CastSessionManager.Route.NativeTv, enteredFromTv.restoreRoute)

        val pickedPhoneWhileInParty = onPickNonPartyTarget(enteredFromTv)
        assertFalse(pickedPhoneWhileInParty.partySelected)
        assertEquals(CastSessionManager.Route.NativeTv, pickedPhoneWhileInParty.restoreRoute)

        val reselectedParty = onSelectPartyTarget()
        assertTrue(reselectedParty.partySelected)

        val leftParty = onLeaveOrEndParty()
        assertFalse(leftParty.partySelected)
        assertNull(leftParty.restoreRoute)
    }

    @Test
    fun `resolveTargetVisualKind only picks WATCH_PARTY when both in party and party selected`() {
        val externalRoute = CastSessionManager.Route.External(externalKey, "Bedroom")

        assertEquals(
            TargetVisualKind.WATCH_PARTY,
            resolveTargetVisualKind(
                route = CastSessionManager.Route.ThisDevice,
                partySelected = true,
                inParty = true,
            ),
        )
        assertEquals(
            TargetVisualKind.THIS_PHONE,
            resolveTargetVisualKind(
                route = CastSessionManager.Route.ThisDevice,
                partySelected = false,
                inParty = true,
            ),
        )
        assertEquals(
            TargetVisualKind.PLAYBRIDGE_TV,
            resolveTargetVisualKind(
                route = CastSessionManager.Route.NativeTv,
                partySelected = false,
                inParty = true,
            ),
        )
        assertEquals(
            TargetVisualKind.OTHER_RECEIVER,
            resolveTargetVisualKind(
                route = externalRoute,
                partySelected = false,
                inParty = true,
                externalProtocol = CastProtocol.GOOGLE_CAST,
            ),
        )
        assertEquals(
            TargetVisualKind.THIS_PHONE,
            resolveTargetVisualKind(
                route = CastSessionManager.Route.ThisDevice,
                partySelected = true,
                inParty = false,
            ),
        )
    }

    @Test
    fun `playOnStatus presents watch party target and switches cleanly to TV or phone while still in party`() {
        val partyStatus = playOnStatus(
            route = CastSessionManager.Route.ThisDevice,
            socketConnected = false,
            nativeDevice = tv,
            externalDevice = null,
            externalReady = false,
            partyWatchingCount = 3,
            partyPlayingTitle = "Big Buck Bunny",
        )
        assertEquals(PlayOnStatus.State.WATCH_PARTY, partyStatus.state)
        assertTrue(partyStatus.isWatchParty)
        assertTrue(partyStatus.tvChosen)
        assertTrue(partyStatus.isConnected)
        assertEquals("Watch party · 3", partyStatus.deviceName)
        assertEquals("Playing Big Buck Bunny · Tap to switch", partyStatus.statusLine)

        val idlePartyStatus = playOnStatus(
            route = CastSessionManager.Route.NativeTv,
            socketConnected = true,
            nativeDevice = tv,
            externalDevice = null,
            externalReady = false,
            partyWatchingCount = 1,
            partyPlayingTitle = null,
        )
        assertEquals(PlayOnStatus.State.WATCH_PARTY, idlePartyStatus.state)
        assertEquals("Watch party · 1", idlePartyStatus.deviceName)
        assertEquals("Tap to switch or open party", idlePartyStatus.statusLine)

        val switchedToTvStatus = playOnStatus(
            route = CastSessionManager.Route.NativeTv,
            socketConnected = true,
            nativeDevice = tv,
            externalDevice = null,
            externalReady = false,
            socketSecure = true,
            partyWatchingCount = null,
        )
        assertEquals(PlayOnStatus.State.CONNECTED, switchedToTvStatus.state)
        assertFalse(switchedToTvStatus.isWatchParty)
        assertEquals("Living Room TV", switchedToTvStatus.deviceName)
    }

    @Test
    fun `isOwnTvMemberMatch distinguishes duplicate-name TVs by UUID and endpoint while handling pairing fallback`() {
        val addedTv = TvDevice(
            ip = "192.168.1.10",
            port = 8765,
            token = "tok",
            name = "Living Room TV",
            uuid = "uuid-tv-1",
        )
        val otherSameNameTv = TvDevice(
            ip = "192.168.1.11",
            port = 8765,
            token = "tok2",
            name = "Living Room TV",
            uuid = "uuid-tv-2",
        )
        val renamedSameUuid = addedTv.copy(name = "Bedroom TV", ip = "192.168.1.50")

        val party = PhoneTogether.Party(
            roomId = "room1",
            invite = "https://party.playbridge.app/j/room1#k=a&h=b",
            relay = "https://party.playbridge.app",
            me = "m-phone",
            host = true,
            own = setOf("m-tv-1"),
            members = mapOf(
                "m-tv-1" to PhoneTogether.Member(name = "Living Room TV", kind = TogetherDeviceKind.Tv, host = false),
                "m-guest-tv" to PhoneTogether.Member(name = "Guest TV", kind = TogetherDeviceKind.Tv, host = false),
            ),
        )
        val lanIdentities = mapOf(
            "m-tv-1" to PhoneTogether.OwnTvIdentity(
                name = "Living Room TV",
                uuid = "uuid-tv-1",
                endpointKey = addedTv.endpointKey.toString(),
            ),
        )

        // Exact LAN match succeeds, duplicate-name TV does not collide, renamed TV with same UUID matches.
        assertTrue(isOwnTvMemberMatch(addedTv, party, lanIdentities))
        assertFalse(isOwnTvMemberMatch(otherSameNameTv, party, lanIdentities))
        assertTrue(isOwnTvMemberMatch(renamedSameUuid, party, lanIdentities))

        // Another guest's TV with same display name (not in party.own) does not match.
        val guestNamedTv = TvDevice(ip = "192.168.1.30", port = 8765, token = "", name = "Guest TV", uuid = "uuid-guest")
        assertFalse(isOwnTvMemberMatch(guestNamedTv, party, lanIdentities))

        // After MemberLeft removes "m-tv-1", neither LAN identity nor own set matches.
        assertFalse(
            isOwnTvMemberMatch(
                addedTv,
                party.copy(own = emptySet(), members = emptyMap()),
                emptyMap(),
            ),
        )

        // Pairing-code fallback (in party.own with no LAN identity recorded) matches saved TV by name.
        val pairedTv = TvDevice(ip = "192.168.1.20", port = 8765, token = "tok3", name = "Living Room TV")
        val pairingParty = party.copy(
            own = setOf("m-paired-tv"),
            members = mapOf(
                "m-paired-tv" to PhoneTogether.Member(name = "Living Room TV", kind = TogetherDeviceKind.Tv, host = false),
            ),
        )
        assertTrue(isOwnTvMemberMatch(pairedTv, pairingParty, emptyMap()))
        assertFalse(isOwnTvMemberMatch(pairedTv.copy(name = "Other TV"), pairingParty, emptyMap()))
    }
}
