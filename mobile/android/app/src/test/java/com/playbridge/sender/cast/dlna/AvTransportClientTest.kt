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
        assertEquals("Rejected https://example.test/private", AvTransportClient.upnpErrorDescription(body))
    }

    @Test
    fun failureMessageRedactsUrlsAndBoundsDescriptionButRetainsRawField() {
        val description = "Rejected https://media.example/private?token=secret " + "x".repeat(160)
        val failure = DlnaActionFailure("SetAVTransportURI", 500, "714", description)

        assertEquals(description, failure.upnpDescription)
        assertTrue(failure.message!!.contains("[URL redacted]"))
        assertFalse(failure.message!!.contains("https://media.example/private"))
        assertTrue(failure.message!!.length < 180)
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
        assertTrue(metadata.contains("http-get:*:application/vnd.apple.mpegurl:DLNA.ORG_OP=01;DLNA.ORG_CI=0"))
        assertTrue(metadata.contains("A &amp; B"))
        assertTrue(metadata.contains("token=one&amp;quality=auto"))
        val normalMetadata = dlnaLoadMetadata(
            media.copy(mimeType = "video/mp4", url = "http://phone.test/video.mp4"),
            "http://phone.test/video.mp4",
        )
        assertTrue(normalMetadata.contains("object.item.videoItem"))
        assertTrue(normalMetadata.contains("http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0"))
    }

    @Test
    fun normalMetadataRejectionRetriesWithoutMetadataOnlyForSpecifiedUpnpCodes() {
        val video = MediaItem(url = "http://phone.test/video.mp4")
        listOf("714", "716", "501").forEach { code ->
            assertTrue(shouldRetryWithoutMetadata(video, DlnaActionFailure("SetAVTransportURI", 500, code)))
        }
        assertFalse(shouldRetryWithoutMetadata(video, DlnaActionFailure("Play", 500, "501")))
        assertFalse(shouldRetryWithoutMetadata(video, DlnaActionFailure("SetAVTransportURI", 500, "701")))
        assertFalse(shouldRetryWithoutMetadata(video.copy(isScreenMirror = true), DlnaActionFailure("SetAVTransportURI", 500, "501")))
    }

    @Test
    fun transitionNotAvailableClassificationIncludesDescription() {
        assertTrue(shouldRetrySetAvTransportAfterStop("701", null))
        assertTrue(shouldRetrySetAvTransportAfterStop(null, "Transition not available"))
        assertFalse(shouldRetrySetAvTransportAfterStop("714", "Invalid URI"))
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
