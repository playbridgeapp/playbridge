package com.playbridge.sender.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalProxyServerParseTest {

    @Test
    fun sumsExtInfDurationsToMs() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXTINF:10.0,
            seg0.ts
            #EXTINF:10.0,
            seg1.ts
            #EXTINF:4.5,
            seg2.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        assertEquals(24_500L, LocalProxyServer.sumExtInf(playlist))
    }

    @Test
    fun ignoresExtInfTitleSuffix() {
        val playlist = "#EXTINF:6.0,Chapter One\nseg0.ts\n#EXTINF:6,\nseg1.ts"
        assertEquals(12_000L, LocalProxyServer.sumExtInf(playlist))
    }

    @Test
    fun masterPlaylistHasNoExtInfSoZero() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
            720p.m3u8
        """.trimIndent()
        assertEquals(0L, LocalProxyServer.sumExtInf(master))
    }
}
