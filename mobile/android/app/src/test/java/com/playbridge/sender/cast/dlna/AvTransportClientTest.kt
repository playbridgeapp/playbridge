package com.playbridge.sender.cast.dlna

import com.playbridge.sender.cast.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvTransportClientTest {
    @Test
    fun extractsUpnpCodeWithoutLoggingFaultBody() {
        val body = """
            <s:Fault><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
            <errorCode>714</errorCode><errorDescription>Rejected https://example.test/private</errorDescription>
            </UPnPError></detail></s:Fault>
        """.trimIndent()

        assertEquals("714", AvTransportClient.upnpErrorCode(body))
    }

    @Test
    fun extractsNamespacedCodeAndIgnoresMissingCode() {
        assertEquals("716", AvTransportClient.upnpErrorCode("<u:errorCode value=\"x\">716</u:errorCode>"))
        assertNull(AvTransportClient.upnpErrorCode("<s:Fault>no code</s:Fault>"))
    }

    @Test
    fun mirrorHlsFallbackOnlyFollowsSpecificSetUriRejection() {
        val mirror = MediaItem(
            url = "http://phone.test/stream.ts",
            isScreenMirror = true,
            mirrorHlsUrl = "http://phone.test/index.m3u8",
        )

        assertTrue(shouldTryMirrorHlsFallback(mirror, DlnaActionFailure("SetAVTransportURI", 500, "501")))
        assertFalse(shouldTryMirrorHlsFallback(mirror, DlnaActionFailure("Play", 500, "501")))
        assertFalse(shouldTryMirrorHlsFallback(mirror, DlnaActionFailure("SetAVTransportURI", 500, "714")))
        assertFalse(shouldTryMirrorHlsFallback(mirror.copy(isScreenMirror = false), DlnaActionFailure("SetAVTransportURI", 500, "501")))
    }

    @Test
    fun hlsLoadAdvertisesPlaylistAndEscapesMetadata() {
        val media = MediaItem(
            url = "http://phone.test/playlist.m3u8?token=one&quality=auto",
            mimeType = "application/vnd.apple.mpegurl",
            title = "A & B",
        )

        val metadata = dlnaLoadMetadata(media, media.url)
        assertTrue(metadata.contains("http-get:*:application/x-mpegURL:*"))
        assertTrue(metadata.contains("A &amp; B"))
        assertTrue(metadata.contains("token=one&amp;quality=auto"))
        assertEquals("", dlnaLoadMetadata(media.copy(mimeType = "video/mp4", url = "http://phone.test/video.mp4"), "http://phone.test/video.mp4"))
    }

    @Test
    fun hlsMetadataRejectionFallsBackOnlyForSetUriAction() {
        val hls = MediaItem(url = "http://phone.test/playlist.m3u8")
        assertTrue(shouldRetryHlsWithoutMetadata(hls, DlnaActionFailure("SetAVTransportURI", 500, "501")))
        assertFalse(shouldRetryHlsWithoutMetadata(hls, DlnaActionFailure("Play", 500, "501")))
        assertFalse(shouldRetryHlsWithoutMetadata(hls, DlnaActionFailure("SetAVTransportURI", 500, "714")))
        assertFalse(shouldRetryHlsWithoutMetadata(hls.copy(url = "http://phone.test/video.mp4"), DlnaActionFailure("SetAVTransportURI", 500, "501")))
    }
}
