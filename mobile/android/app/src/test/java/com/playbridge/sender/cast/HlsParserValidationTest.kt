package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsParserValidationTest {

    @Test
    fun htmlResponseIsInvalid() {
        val playlist = HlsParser.parsePlaylistContent(
            "https://cdn.example/master.m3u8",
            "<html><body>not a manifest</body></html>",
        )

        assertEquals(HlsPlaylistValidation.INVALID, playlist.validation)
        assertFalse(playlist.isPlayable)
    }

    @Test
    fun extm3uWithBareImageUrlIsNotAcceptedAsVideoPlaylist() {
        val playlist = HlsParser.parsePlaylistContent(
            "https://cdn.example/deceptive.m3u8",
            "#EXTM3U\nhttps://cdn.example/poster.jpg\n",
        )

        assertEquals(HlsPlaylistValidation.INVALID, playlist.validation)
        assertFalse(playlist.isPlayable)
    }

    @Test
    fun masterPlaylistIsVerifiedAndResolvesVariant() {
        val playlist = HlsParser.parsePlaylistContent(
            "https://cdn.example/path/master.m3u8",
            """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
                720/index.m3u8
            """.trimIndent(),
        )

        assertEquals(HlsPlaylistValidation.VALID_MASTER, playlist.validation)
        assertTrue(playlist.isPlayable)
        assertEquals("https://cdn.example/path/720/index.m3u8", playlist.videoQualities.single().url)
    }

    @Test
    fun mediaPlaylistIsVerifiedWithoutVariants() {
        val playlist = HlsParser.parsePlaylistContent(
            "https://cdn.example/path/media.m3u8",
            """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:6.0,
                segment-1.ts
            """.trimIndent(),
        )

        assertEquals(HlsPlaylistValidation.VALID_MEDIA, playlist.validation)
        assertTrue(playlist.isPlayable)
        assertTrue(playlist.videoQualities.isEmpty())
    }

    @Test
    fun lowLatencyPartsVerifyMediaPlaylist() {
        val playlist = HlsParser.parsePlaylistContent(
            "https://cdn.example/live/media.m3u8",
            """
                #EXTM3U
                #EXT-X-TARGETDURATION:2
                #EXT-X-PART:DURATION=0.5,URI="part-101.m4s"
                #EXT-X-PRELOAD-HINT:TYPE=PART,URI="part-102.m4s"
            """.trimIndent(),
        )

        assertEquals(HlsPlaylistValidation.VALID_MEDIA, playlist.validation)
        assertTrue(playlist.isPlayable)
        assertEquals(setOf("https://cdn.example/live/"), playlist.segmentPrefixes)
    }
}
