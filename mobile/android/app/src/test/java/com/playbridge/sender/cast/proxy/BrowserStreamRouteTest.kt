package com.playbridge.sender.cast.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserStreamRouteTest {
    @Test
    fun localAlwaysViaPhone() {
        assertEquals(
            StreamRouteMode.VIA_PHONE,
            BrowserStreamRoute.effectiveMode(StreamRouteMode.VIA_PROXY, isLocalMedia = true),
        )
        assertEquals(
            StreamRouteMode.VIA_PHONE,
            BrowserStreamRoute.effectiveMode(StreamRouteMode.DIRECT, isLocalMedia = true),
        )
    }

    @Test
    fun directHonoredForRemote() {
        assertEquals(
            StreamRouteMode.DIRECT,
            BrowserStreamRoute.effectiveMode(StreamRouteMode.DIRECT, isLocalMedia = false),
        )
    }

    @Test
    fun viaProxyHonoredForRemote() {
        assertEquals(
            StreamRouteMode.VIA_PROXY,
            BrowserStreamRoute.effectiveMode(StreamRouteMode.VIA_PROXY, isLocalMedia = false),
        )
    }

    @Test
    fun viaPhoneUnchanged() {
        assertEquals(
            StreamRouteMode.VIA_PHONE,
            BrowserStreamRoute.effectiveMode(StreamRouteMode.VIA_PHONE, isLocalMedia = false),
        )
    }

    @Test
    fun localUrlDetection() {
        assertTrue(BrowserStreamRoute.isLocalMediaUrl("content://media/1"))
        assertTrue(BrowserStreamRoute.isLocalMediaUrl("file:///sdcard/a.mp4"))
        assertTrue(BrowserStreamRoute.isLocalMediaUrl("data:application/x-mpegurl;base64,abc"))
    }

    @Test
    fun overrideReasons() {
        assertEquals(
            "Local files cast via this phone.",
            BrowserStreamRoute.overrideReason(
                StreamRouteMode.VIA_PROXY,
                StreamRouteMode.VIA_PHONE,
                isLocalMedia = true,
            ),
        )
        assertNull(
            BrowserStreamRoute.overrideReason(
                StreamRouteMode.DIRECT,
                StreamRouteMode.DIRECT,
                isLocalMedia = false,
            ),
        )
        assertNull(
            BrowserStreamRoute.overrideReason(
                StreamRouteMode.VIA_PHONE,
                StreamRouteMode.VIA_PHONE,
                isLocalMedia = false,
            ),
        )
    }
}
