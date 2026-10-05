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
    fun peakBandwidthIsIndependentOfAverageAttributeOrder() {
        for (attrs in listOf(
            "AVERAGE-BANDWIDTH=4000000,BANDWIDTH=9000000",
            "BANDWIDTH=9000000,AVERAGE-BANDWIDTH=4000000",
            "AVERAGE-BANDWIDTH=4000000, BANDWIDTH=9000000",
            "PROGRAM-ID=1, BANDWIDTH=9000000,AVERAGE-BANDWIDTH=4000000",
            "\tBANDWIDTH=9000000,AVERAGE-BANDWIDTH=4000000",
        )) {
            val playlist = HlsParser.parsePlaylistContent(
                "https://cdn.example/master.m3u8",
                """
                    #EXTM3U
                    #EXT-X-STREAM-INF:$attrs,RESOLUTION=1920x1080
                    peak.m3u8
                    #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=5000000,BANDWIDTH=6000000,RESOLUTION=1280x720
                    average.m3u8
                    #EXT-X-STREAM-INF:BANDWIDTH=1000000
                    peak-only.m3u8
                """.trimIndent(),
            )
            assertEquals(listOf(9000000L, 6000000L, 1000000L), playlist.videoQualities.map { it.bandwidth })
            assertEquals(listOf(4000000L, 5000000L, null), playlist.videoQualities.map { it.averageBandwidth })
            assertTrue(playlist.videoQualities.first().url.endsWith("/peak.m3u8"))
            val filtered = HlsParser.generateFilteredPlaylist(playlist, playlist.videoQualities.first())
            assertTrue(filtered.contains("#EXT-X-STREAM-INF:BANDWIDTH=9000000,AVERAGE-BANDWIDTH=4000000"))
        }
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
