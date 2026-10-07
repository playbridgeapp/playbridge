package com.playbridge.sender.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageDestinationPrivacyTest {
    private val secret = ByteArray(32) { 0x11 }

    @Test
    fun `per-origin id is HMAC-SHA256 of origin and endpoint key`() {
        val id = PageDestinationPrivacy.idForOrigin(secret, "https://site.example", "playbridge:living-room")
        assertEquals("37d57a2370dbcf92f916f78ccbbc329e462c706f7fe15bafe2e5f6f2ca775411", id)
        assertNotEquals(
            id,
            PageDestinationPrivacy.idForOrigin(secret, "https://other.example", "playbridge:living-room"),
        )
        assertNotEquals("playbridge:living-room", id)
    }

    @Test
    fun `unconsented destination hides id and name`() {
        val destination = pagePlaybackDestination(
            rawId = "playbridge:living-room",
            rawName = "Living Room",
            kind = "native",
            connected = true,
            origin = "https://site.example",
            consented = false,
            installSecret = secret,
        )
        assertTrue(destination.isNull("id"))
        assertTrue(destination.isNull("name"))
        assertEquals("native", destination.getString("kind"))
        assertEquals(true, destination.getBoolean("connected"))
    }

    @Test
    fun `consented destination uses a per-origin id and keeps this device literal`() {
        val tv = pagePlaybackDestination(
            rawId = "playbridge:living-room",
            rawName = "Living Room",
            kind = "native",
            connected = true,
            origin = "https://site.example",
            consented = true,
            installSecret = secret,
        )
        assertEquals(
            "37d57a2370dbcf92f916f78ccbbc329e462c706f7fe15bafe2e5f6f2ca775411",
            tv.getString("id"),
        )
        assertEquals("Living Room", tv.getString("name"))
        val local = pagePlaybackDestination(
            rawId = "this-device",
            rawName = "This device",
            kind = "local",
            connected = true,
            origin = "https://site.example",
            consented = true,
            installSecret = secret,
        )
        assertEquals("this-device", local.getString("id"))
        assertEquals("This device", local.getString("name"))
    }

    @Test
    fun `play matches the page id and never the raw endpoint key`() {
        val origin = "https://site.example"
        val raw = "playbridge:living-room"
        val pageId = PageDestinationPrivacy.idForOrigin(secret, origin, raw)
        assertTrue(pageDestinationIdMatches(pageId, raw, origin, secret))
        assertTrue(pageDestinationIdMatches("this-device", "this-device", origin, secret))
        assertFalse(pageDestinationIdMatches(raw, raw, origin, secret))
        assertFalse(pageDestinationIdMatches(pageId, raw, "https://other.example", secret))
        assertFalse(pageDestinationIdMatches("unavailable", "unavailable", origin, secret))
    }

    @Test
    fun `destination changes require an attested gesture and do not disconnect a live receiver`() {
        assertEquals(
            PageDestinationGesture.OPEN_PICKER,
            pageDestinationGesture(false, null, true, receiverConnected = true),
        )
        assertEquals(
            PageDestinationGesture.OPEN_PICKER,
            pageDestinationGesture(false, null, null, receiverConnected = false),
        )
        assertEquals(
            PageDestinationGesture.REJECT_GESTURE,
            pageDestinationGesture(false, null, false, receiverConnected = false),
        )
        assertEquals(
            PageDestinationGesture.REJECT_INVALID,
            pageDestinationGesture(true, "playbridge:living-room", true, receiverConnected = true),
        )
        assertEquals(
            PageDestinationGesture.REJECT_GESTURE,
            pageDestinationGesture(true, "this-device", null, receiverConnected = true),
        )
        assertEquals(
            PageDestinationGesture.REJECT_GESTURE,
            pageDestinationGesture(true, "this-device", false, receiverConnected = false),
        )
        assertEquals(
            PageDestinationGesture.OPEN_PICKER,
            pageDestinationGesture(true, "this-device", true, receiverConnected = true),
        )
        assertEquals(
            PageDestinationGesture.SELECT_THIS_DEVICE,
            pageDestinationGesture(true, "this-device", true, receiverConnected = false),
        )
    }

    @Test
    fun `non boolean activation is not an attested gesture`() {
        assertEquals(null, jsonBooleanOrNull(null))
        assertEquals(null, jsonBooleanOrNull(JSONObject.NULL))
        assertEquals(null, jsonBooleanOrNull("true"))
        assertEquals(true, jsonBooleanOrNull(true))
        assertEquals(false, jsonBooleanOrNull(false))
    }
}
