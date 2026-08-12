package com.playbridge.sender.cast

import com.playbridge.sender.cast.mirror.ExternalScreenMirrorCoordinator
import com.playbridge.sender.cast.proxy.StreamRouteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalScreenMirrorRoutingTest {
    private val urls = ExternalScreenMirrorCoordinator.Urls(
        hls = "http://192.168.1.4:1234/screen/token/index.m3u8",
        continuousTs = "http://192.168.1.4:1234/screen/token/stream.ts",
    )

    @Test
    fun `google cast receives live hls over the phone path`() {
        val media = externalScreenMirrorMedia(TargetKind.GOOGLE_CAST, urls)

        assertEquals(urls.hls, media.url)
        assertEquals("application/x-mpegURL", media.mimeType)
        assertEquals("LIVE", media.streamType)
        assertEquals("mpeg2_ts", media.hlsVideoSegmentFormat)
        assertEquals(StreamRouteMode.VIA_PHONE, media.effectiveRoute)
        assertTrue(media.isScreenMirror)
    }

    @Test
    fun `dlna receives the same mpeg ts capture as a continuous stream`() {
        val media = externalScreenMirrorMedia(TargetKind.DLNA, urls)

        assertEquals(urls.continuousTs, media.url)
        assertEquals("video/mp2t", media.mimeType)
        assertEquals("LIVE", media.streamType)
        assertNull(media.hlsVideoSegmentFormat)
        assertEquals(StreamRouteMode.VIA_PHONE, media.effectiveRoute)
        assertTrue(media.isScreenMirror)
    }
}
