package com.playbridge.sender.cast.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamRouteModeTest {
    @Test
    fun fromPrefs_acceptsPrefsValues() {
        assertEquals(StreamRouteMode.DIRECT, StreamRouteMode.fromPrefs("direct"))
        assertEquals(StreamRouteMode.VIA_PHONE, StreamRouteMode.fromPrefs("via_phone"))
        assertEquals(StreamRouteMode.VIA_PROXY, StreamRouteMode.fromPrefs("via_proxy"))
    }

    @Test
    fun fromPrefs_acceptsEnumNames() {
        assertEquals(StreamRouteMode.VIA_PHONE, StreamRouteMode.fromPrefs("VIA_PHONE"))
    }

    @Test
    fun fromPrefs_defaultsUnknownToDirect() {
        assertEquals(StreamRouteMode.DIRECT, StreamRouteMode.fromPrefs(null))
        assertEquals(StreamRouteMode.DIRECT, StreamRouteMode.fromPrefs(""))
        assertEquals(StreamRouteMode.DIRECT, StreamRouteMode.fromPrefs("nope"))
    }

    @Test
    fun initialRouteMode_fallsBackWhenProxyUnconfigured() {
        val settings = StreamProxySettings(
            remoteBaseUrl = "",
            defaultRoute = StreamRouteMode.VIA_PROXY,
        )
        assertEquals(StreamRouteMode.DIRECT, settings.initialRouteMode())
    }

    @Test
    fun initialRouteMode_keepsViaProxyWhenConfigured() {
        val settings = StreamProxySettings(
            remoteBaseUrl = "http://192.168.1.10:8888",
            defaultRoute = StreamRouteMode.VIA_PROXY,
        )
        assertEquals(StreamRouteMode.VIA_PROXY, settings.initialRouteMode())
    }
}
