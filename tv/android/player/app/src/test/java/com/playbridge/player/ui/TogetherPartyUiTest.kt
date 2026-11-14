package com.playbridge.player.ui

import com.playbridge.player.together.TvTogether
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherPartyUiTest {
    private fun party(
        connected: Boolean = true,
        host: String? = "Alex",
        title: String? = null,
        waiting: List<String> = emptyList(),
    ) = TvTogether.State.InParty(
        relay = "relay.example",
        connected = connected,
        members = linkedMapOf("1" to "Alex", "2" to "Sam’s TV"),
        hostName = host,
        title = title,
        waitingFor = waiting,
    )

    @Test
    fun `status follows connection and title`() {
        assertEquals(PartyStatus.Connecting("relay.example"), party(connected = false).toPartyUi().status)
        assertEquals(PartyStatus.WaitingForHost, party().toPartyUi().status)
        assertEquals(PartyStatus.NowPlaying("Sintel"), party(title = "Sintel").toPartyUi().status)
    }

    @Test
    fun `host and waiting members are marked`() {
        val ui = party(waiting = listOf("Sam’s TV")).toPartyUi()
        assertEquals("Alex’s watch party", ui.partyName)
        assertTrue(ui.members[0].isHost)
        assertTrue(ui.members[1].isWaiting)
        assertEquals("Watch party", party(host = null).toPartyUi().partyName)
    }

    @Test
    fun `waiting label`() {
        assertEquals("", waitingLabel(emptyList()))
        assertEquals("Waiting for Sam…", waitingLabel(listOf("Sam")))
        assertEquals("Waiting for Sam and Alex…", waitingLabel(listOf("Sam", "Alex")))
        assertEquals("Waiting for 3 devices…", waitingLabel(listOf("a", "b", "c")))
    }

    @Test
    fun `expiry label`() {
        assertEquals("10 minutes", expiryLabel(600_000))
        assertEquals("1 minute", expiryLabel(60_000))
        assertEquals("45 seconds", expiryLabel(45_000))
        assertNull(expiryLabel(0))
    }

    @Test
    fun `code is grouped`() {
        assertEquals("418-207-593", groupPartyCode("418207593"))
    }
}
