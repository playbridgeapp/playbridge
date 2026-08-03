package com.playbridge.sender.cast.routing

import com.playbridge.sender.cast.HlsParser
import com.playbridge.sender.cast.HlsPlaylistValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adaptive playlist parsing used by cast quality selection. */
class HlsPlaylistParsingTest {

    @Test
    fun parsesMasterAndResolvesRelativeVariant() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            media/360.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1280x720
            media/720.m3u8
        """.trimIndent()
        val playlist = HlsParser.parsePlaylistContent(
            "https://cdn.example/live/master.m3u8",
            master,
        )
        assertEquals(HlsPlaylistValidation.VALID_MASTER, playlist.validation)
        assertEquals(2, playlist.videoQualities.size)
        assertTrue(playlist.videoQualities[0].url.startsWith("https://cdn.example/live/media/"))
    }

    @Test
    fun invalidPlaylistRejected() {
        val playlist = HlsParser.parsePlaylistContent(
            "https://cdn.example/not-a-playlist",
            "<html>nope</html>",
        )
        assertEquals(HlsPlaylistValidation.INVALID, playlist.validation)
    }

    @Test
    fun mediaPlaylistValidation() {
        val media = """
            #EXTM3U
            #EXTINF:4.0,
            seg0.ts
            #EXTINF:4.0,
            seg1.ts
        """.trimIndent()
        val playlist = HlsParser.parsePlaylistContent(
            "https://cdn.example/media.m3u8",
            media,
        )
        assertEquals(HlsPlaylistValidation.VALID_MEDIA, playlist.validation)
    }
}
