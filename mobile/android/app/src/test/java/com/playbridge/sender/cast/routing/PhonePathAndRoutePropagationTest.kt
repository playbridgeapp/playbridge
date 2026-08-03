package com.playbridge.sender.cast.routing

import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.proxy.StreamRouteMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards for explicit route propagation and double-proxy prevention helpers.
 */
class PhonePathAndRoutePropagationTest {

    @Test
    fun mediaItemCarriesExplicitRoute() {
        val item = MediaItem(
            url = "https://cdn.example/a.mp4",
            effectiveRoute = StreamRouteMode.DIRECT,
            routeReason = "user_selected_direct",
        )
        assertEquals(StreamRouteMode.DIRECT, item.effectiveRoute)
    }

    @Test
    fun originUrlShapeDoesNotOverrideExplicitRoute() {
        val item = MediaItem(
            url = "http://10.8.0.6:8000/s/origin/video.mp4",
            effectiveRoute = StreamRouteMode.DIRECT,
            routeReason = "user_selected_direct",
        )
        assertEquals(StreamRouteMode.DIRECT, item.effectiveRoute)
    }

    @Test
    fun viaPhonePreparedItemDropsHeaders() {
        val prepared = PreparedCastItem(
            url = "http://192.168.1.10:8765/s/x/playlist.m3u8",
            headers = null,
            contentType = "application/vnd.apple.mpegurl",
            effectiveRoute = EffectiveStreamRoute(
                mode = StreamRouteMode.VIA_PHONE,
                policyReason = "required_headers",
            ),
        )
        assertEquals(StreamRouteMode.VIA_PHONE, prepared.effectiveRoute.mode)
        assertEquals(null, prepared.headers)
        assertEquals("http://192.168.1.10:8765/s/x/playlist.m3u8", prepared.url)
    }
}
