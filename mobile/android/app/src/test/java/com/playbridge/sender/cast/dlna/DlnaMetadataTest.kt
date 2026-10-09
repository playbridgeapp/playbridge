package com.playbridge.sender.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaMetadataTest {
    @Test
    fun didlIncludesEscapedMetadataClassDurationAndOnlyContractFeatures() {
        val didl = buildDlnaDidl(
            url = "http://phone.test/a?x=1&name=<clip>",
            title = "A & <B> \"clip\"",
            mimeType = "video/mp4",
            durationMs = 90_000,
        )
        assertTrue(didl.contains("<upnp:class>object.item.videoItem</upnp:class>"))
        assertTrue(didl.contains("duration=\"00:01:30\""))
        assertTrue(didl.contains("http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0"))
        assertTrue(didl.contains("A &amp; &lt;B&gt; &quot;clip&quot;"))
        assertTrue(didl.contains("x=1&amp;name=&lt;clip&gt;"))
        assertTrue(didl.contains("DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"))
        assertFalse(didl.contains("DLNA.ORG_PN"))
    }

    @Test
    fun didlClassFollowsAudioAndImageMimeCategories() {
        assertTrue(buildDlnaDidl("http://x", "Audio", "audio/mpeg").contains("object.item.audioItem.musicTrack"))
        assertTrue(buildDlnaDidl("http://x", "Image", "image/jpeg").contains("object.item.imageItem.photo"))
    }

    @Test
    fun featuresRepresentSeekabilityAndNeverTranscoding() {
        assertEquals("DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000", dlnaContentFeatures(true))
        assertEquals("DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000", dlnaContentFeatures(false))
    }

    @Test
    fun responseHeadersUseStreamingAndInteractiveModes() {
        assertEquals(
            listOf(
                "transferMode.dlna.org" to "Streaming",
                "realTimeInfo.dlna.org" to "DLNA.ORG_TLAG=*",
                "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            ),
            dlnaResponseHeaders("video/mp4", requestContentFeatures = true, byteSeek = true),
        )
        assertEquals(
            listOf("transferMode.dlna.org" to "Interactive"),
            dlnaResponseHeaders("image/jpeg", requestContentFeatures = false, byteSeek = true),
        )
    }

    @Test
    fun contentFeaturesRequestHeaderNameIsCaseInsensitive() {
        assertTrue(requestsDlnaContentFeatures(listOf("GETCONTENTFEATURES.DLNA.ORG" to "1")))
        assertFalse(requestsDlnaContentFeatures(listOf("getcontentFeatures.dlna.org" to "0")))
    }

    @Test
    fun protocolInfoPreflightSkipsNonMediaMimeCategories() {
        assertTrue(shouldPreflightDlnaMime("audio/mpeg"))
        assertTrue(shouldPreflightDlnaMime("video/mp4"))
        assertTrue(shouldPreflightDlnaMime("image/jpeg"))
        assertFalse(shouldPreflightDlnaMime("application/x-mpegURL"))
        assertFalse(shouldPreflightDlnaMime("text/plain"))
    }

    @Test
    fun protocolInfoMatchingIsCategoryBasedAndPermissiveForUnparsableSink() {
        assertNull(protocolInfoAllows("", "video/mp4"))
        assertNull(protocolInfoAllows("not protocol info", "video/mp4"))
        assertEquals(true, protocolInfoAllows("http-get:*:video/mpeg:*", "video/mp4"))
        assertEquals(true, protocolInfoAllows("http-get:*:*:*", "image/jpeg"))
        assertEquals(false, protocolInfoAllows("http-get:*:image/jpeg:*", "video/mp4"))
        assertEquals(false, protocolInfoAllows("rtsp-rtp-udp:*:video/mp4:*", "video/mp4"))
    }

    @Test
    fun mpostHeadersUseRequiredSoapEnvelopeAndActionHeaders() {
        assertEquals(
            mapOf(
                "MAN" to "\"http://schemas.xmlsoap.org/soap/envelope/\"; ns=01",
                "01-SOAPACTION" to "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            ),
            dlnaMpostHeaders("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""),
        )
    }

    @Test
    fun mimeUsesExplicitThenExtensionThenVideoDefaultAndMirrorOverride() {
        assertEquals("audio/mpeg", dlnaMimeType(com.playbridge.sender.cast.MediaItem("https://x.test/song.mp3")))
        assertEquals("video/mp4", dlnaMimeType(com.playbridge.sender.cast.MediaItem("https://x.test/file.unknown")))
        val mirror = com.playbridge.sender.cast.MediaItem("https://x.test/mirror.ts", isScreenMirror = true)
        assertEquals("video/mpeg", dlnaMimeType(mirror))
        assertTrue(dlnaLoadMetadata(mirror, mirror.url).contains("DLNA.ORG_OP=00;DLNA.ORG_CI=0"))
        val live = com.playbridge.sender.cast.MediaItem("https://x.test/live.mp4", streamType = "LIVE")
        assertTrue(dlnaLoadMetadata(live, live.url).contains("DLNA.ORG_OP=00;DLNA.ORG_CI=0"))
    }
}
