package com.playbridge.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uParserTest {

    @Test
    fun parsesChannelsWithAttributes() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="cnn.us" tvg-logo="http://logo/cnn.png" group-title="News",CNN
            http://example.com/cnn.m3u8
            #EXTINF:-1 tvg-logo="http://logo/espn.png" group-title="Sports",ESPN
            http://example.com/espn.m3u8
        """.trimIndent()

        val channels = M3uParser.parseM3uText(text)
        assertEquals(2, channels?.size)
        val cnn = channels!![0]
        assertEquals("CNN", cnn.name)
        assertEquals("News", cnn.groupTitle)
        assertEquals("cnn.us", cnn.tvgId)
        assertEquals("http://logo/cnn.png", cnn.logo)
        assertEquals("http://example.com/cnn.m3u8", cnn.url)
        assertEquals(0, cnn.order)
        assertEquals("Sports", channels[1].groupTitle)
        assertEquals(1, channels[1].order)
    }

    @Test
    fun capturesPerChannelHeaders() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Channel One
            #EXTVLCOPT:http-user-agent=MyAgent/1.0
            #EXTVLCOPT:http-referrer=http://ref.example
            http://example.com/one
        """.trimIndent()

        val channels = M3uParser.parseM3uText(text, baseHeaders = mapOf("Cookie" to "abc"))
        val ch = channels!!.single()
        assertEquals("MyAgent/1.0", ch.headers["User-Agent"])
        assertEquals("http://ref.example", ch.headers["Referer"])
        assertEquals("abc", ch.headers["Cookie"]) // base header preserved
    }

    @Test
    fun headersDoNotLeakAcrossChannels() {
        val text = """
            #EXTM3U
            #EXTINF:-1,One
            #EXTVLCOPT:http-user-agent=Agent1
            http://example.com/one
            #EXTINF:-1,Two
            http://example.com/two
        """.trimIndent()

        val channels = M3uParser.parseM3uText(text)!!
        assertEquals("Agent1", channels[0].headers["User-Agent"])
        assertNull(channels[1].headers["User-Agent"])
    }

    @Test
    fun extGrpSetsGroupForFollowingChannels() {
        val text = """
            #EXTM3U
            #EXTGRP:Movies
            #EXTINF:-1,Movie Channel
            http://example.com/movie
        """.trimIndent()

        val channels = M3uParser.parseM3uText(text)!!
        assertEquals("Movies", channels.single().groupTitle)
    }

    @Test
    fun resolvesRelativeUrlsAgainstBase() {
        val text = """
            #EXTM3U
            #EXTINF:-1,Relative
            stream/chan.m3u8
        """.trimIndent()

        val channels = M3uParser.parseM3uText(text, baseUrl = "http://host.tv/lists/playlist.m3u")!!
        assertEquals("http://host.tv/lists/stream/chan.m3u8", channels.single().url)
    }

    @Test
    fun returnsNullForHlsManifest() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000,RESOLUTION=1280x720
            chunk.m3u8
        """.trimIndent()

        assertNull(M3uParser.parseM3uText(text))
    }

    @Test
    fun returnsNullForNonM3u() {
        assertNull(M3uParser.parseM3uText("just some text\nnot a playlist"))
    }

    @Test
    fun fallsBackToTvgNameWhenNoTitle() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-name="Fallback Name" group-title="X",
            http://example.com/x
        """.trimIndent()

        val ch = M3uParser.parseM3uText(text)!!.single()
        assertEquals("Fallback Name", ch.name)
    }

    @Test
    fun toPlayPayloadCarriesHeadersAndTitle() {
        val ch = IptvChannel(
            name = "CNN",
            url = "http://example.com/cnn",
            headers = mapOf("User-Agent" to "A"),
        )
        val payload = ch.toPlayPayload()
        assertEquals("CNN", payload.title)
        assertEquals("http://example.com/cnn", payload.url)
        assertEquals("A", payload.headers["User-Agent"])
        assertTrue(payload.detected_by == "iptv_m3u")
    }
}
