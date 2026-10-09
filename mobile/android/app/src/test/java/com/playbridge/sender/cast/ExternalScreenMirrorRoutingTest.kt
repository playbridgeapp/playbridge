package com.playbridge.sender.cast

import com.playbridge.sender.cast.googlecast.buildDlnaLoadFields
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
        hasAudio = true,
    )

    @Test
    fun `google cast receives live hls over the phone path`() {
        val media = externalScreenMirrorMedia(TargetKind.GOOGLE_CAST, urls)

        assertEquals(urls.hls, media.url)
        assertEquals("application/x-mpegURL", media.mimeType)
        assertEquals("LIVE", media.streamType)
        assertEquals("mpeg2_ts", media.hlsVideoSegmentFormat)
        assertEquals("ts_aac", media.hlsSegmentFormat)
        assertNull(media.mirrorHlsUrl)
        assertEquals(StreamRouteMode.VIA_PHONE, media.effectiveRoute)
        assertTrue(media.isScreenMirror)
    }

    @Test
    fun `google cast omits audio hint when playback capture did not start`() {
        val media = externalScreenMirrorMedia(
            TargetKind.GOOGLE_CAST,
            urls.copy(hasAudio = false),
        )

        assertNull(media.hlsSegmentFormat)
        assertEquals("mpeg2_ts", media.hlsVideoSegmentFormat)
    }

    @Test
    fun `dlna mirror load sends continuous ts and live hls fallback to rust`() {
        val media = externalScreenMirrorMedia(TargetKind.DLNA, urls)
        val fields = buildDlnaLoadFields(
            url = media.url,
            contentType = media.mimeType,
            title = media.title,
            startSeconds = media.startPositionMs / 1000.0,
            durationMs = media.durationMs,
            streamType = media.streamType,
            isScreenMirror = media.isScreenMirror,
            fallbackUrl = media.mirrorHlsUrl,
            fallbackContentType = "application/x-mpegURL",
        )

        assertEquals(urls.continuousTs, fields.getString("url"))
        assertEquals("video/mp2t", fields.getString("content_type"))
        assertEquals("LIVE", fields.getString("stream_type"))
        assertEquals(0.0, fields.getDouble("duration_seconds"), 0.0)
        assertTrue(fields.getBoolean("is_screen_mirror"))
        assertEquals(urls.hls, fields.getString("fallback_url"))
        assertEquals("application/x-mpegURL", fields.getString("fallback_content_type"))
    }

    @Test
    fun `dlna receives the same mpeg ts capture as a continuous stream`() {
        val media = externalScreenMirrorMedia(TargetKind.DLNA, urls)

        assertEquals(urls.continuousTs, media.url)
        assertEquals("video/mp2t", media.mimeType)
        assertEquals(urls.hls, media.mirrorHlsUrl)
        assertEquals("LIVE", media.streamType)
        assertNull(media.hlsVideoSegmentFormat)
        assertNull(media.hlsSegmentFormat)
        assertEquals(StreamRouteMode.VIA_PHONE, media.effectiveRoute)
        assertTrue(media.isScreenMirror)
    }
}
