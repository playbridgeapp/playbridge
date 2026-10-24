package com.playbridge.sender.cast

import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayOnDevicesTest {
    private fun tv(name: String, ip: String, uuid: String = "", lastConnected: Long = 0L,
                   protocol: CastProtocol = CastProtocol.PLAYBRIDGE) =
        TvDevice(ip = ip, port = 8765, token = "", name = name, uuid = uuid,
            protocol = protocol, lastConnected = lastConnected)

    @Test
    fun `saved device found at a new address is reachable and uses the live address`() {
        val saved = tv("Mac", "192.168.1.30", uuid = "mac")
        val live = tv("Mac", "192.168.0.28", uuid = "mac")
        val groups = groupPlayOnDevices(listOf(saved), listOf(live), emptyList(), emptyMap())
        assertEquals(1, groups.saved.size)
        assertEquals("192.168.0.28", groups.saved.single().device.ip)
        assertTrue(groups.nearby.isEmpty())
        assertTrue(groups.unavailable.isEmpty())
    }

    @Test
    fun `discovered devices that are not saved go to nearby, PlayBridge first`() {
        val cast = tv("Bedroom", "10.0.0.2", uuid = "gc", protocol = CastProtocol.GOOGLE_CAST)
        val newTv = tv("Sony", "10.0.0.3", uuid = "sony")
        val groups = groupPlayOnDevices(emptyList(), listOf(cast, newTv), emptyList(), emptyMap())
        assertEquals(listOf("Sony", "Bedroom"), groups.nearby.map { it.device.name })
        assertTrue(groups.nearby.all { it.saved == null })
    }

    @Test
    fun `unreachable saved devices are grouped separately, reachable ones by recency`() {
        val old = tv("Old", "10.0.0.4", uuid = "old", lastConnected = 1)
        val recent = tv("Recent", "10.0.0.5", uuid = "recent", lastConnected = 5)
        val gone = tv("Gone", "10.0.0.6", uuid = "gone", lastConnected = 9)
        val pings = mapOf(old.endpointKey.toString() to true, recent.endpointKey.toString() to true,
            gone.endpointKey.toString() to false)
        val groups = groupPlayOnDevices(listOf(old, recent, gone), emptyList(), emptyList(), pings)
        assertEquals(listOf("Recent", "Old"), groups.saved.map { it.device.name })
        assertEquals(listOf("Gone"), groups.unavailable.map { it.device.name })
    }

    @Test
    fun `PlayBridge-only pickers hide other protocols`() {
        val cast = tv("Bedroom", "10.0.0.2", uuid = "gc", protocol = CastProtocol.GOOGLE_CAST)
        val groups = groupPlayOnDevices(listOf(cast), listOf(cast), emptyList(), emptyMap(), playBridgeOnly = true)
        assertTrue(groups.saved.isEmpty() && groups.nearby.isEmpty() && groups.unavailable.isEmpty())
    }

    @Test
    fun `manual entries validate per protocol`() {
        assertEquals(8060, manualPlayOnDevice(CastProtocol.ROKU, "192.168.0.50")?.port)
        assertEquals(8765, manualPlayOnDevice(CastProtocol.PLAYBRIDGE, "192.168.0.41", 8765)?.port)
        assertNull(manualPlayOnDevice(CastProtocol.PLAYBRIDGE, "192.168.0.41", 70000))
        assertNull(manualPlayOnDevice(CastProtocol.ROKU, "192.168.0.50/path"))
        val dlna = manualPlayOnDevice(CastProtocol.DLNA, "http://192.168.0.60:49152/description.xml")
        assertNotNull(dlna)
        assertEquals("192.168.0.60", dlna!!.ip)
        assertEquals(49152, dlna.port)
        assertNull(manualPlayOnDevice(CastProtocol.DLNA, "ftp://192.168.0.60/description.xml"))
    }

    @Test
    fun `a saved TV whose old address now belongs to another receiver is not available`() {
        val appleTv = tv("Apple TV 4K", "192.168.0.28", uuid = "atv", lastConnected = 2)
        val mac = tv("Atuls-MacBook-Air.local", "192.168.0.28", uuid = "mac")
        val pings = mapOf(appleTv.endpointKey.toString() to true)
        val groups = groupPlayOnDevices(listOf(appleTv), listOf(mac), emptyList(), pings)
        assertTrue(groups.saved.isEmpty())
        assertEquals(listOf("Apple TV 4K"), groups.unavailable.map { it.device.name })
        assertEquals(listOf("Atuls-MacBook-Air.local"), groups.nearby.map { it.device.name })
    }

    @Test
    fun `desktop hostnames drop the local suffix`() {
        val mac = tv("Atuls-MacBook-Air.local", "10.0.0.9")
        assertEquals("Atuls-MacBook-Air", mac.playOnName)
        assertTrue(mac.isDesktopReceiver)
    }
}
