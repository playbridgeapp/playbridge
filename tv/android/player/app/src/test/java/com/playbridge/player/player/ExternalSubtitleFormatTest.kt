package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalSubtitleFormatTest {
    @Test
    fun `url extension wins when browser response has a generic content type`() {
        assertEquals(
            ExternalSubtitleFormat.ASS,
            detectSubtitleFormat(
                url = "https://example.test/subtitle.ass?token=redacted#English",
                contentType = "application/octet-stream",
                bytes = "[Script Info]".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `content type identifies extensionless webvtt`() {
        assertEquals(
            ExternalSubtitleFormat.WEBVTT,
            detectSubtitleFormat(
                url = "https://example.test/subtitle",
                contentType = "text/vtt; charset=utf-8",
                bytes = "WEBVTT".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `content sniffing identifies ass and ttml`() {
        assertEquals(
            ExternalSubtitleFormat.ASS,
            detectSubtitleFormat(
                url = "https://example.test/subtitle",
                contentType = null,
                bytes = "[Script Info]\n[V4+ Styles]".encodeToByteArray(),
            ),
        )
        assertEquals(
            ExternalSubtitleFormat.TTML,
            detectSubtitleFormat(
                url = "https://example.test/subtitle",
                contentType = null,
                bytes = "<tt xmlns=\"http://www.w3.org/ns/ttml\">".encodeToByteArray(),
            ),
        )
    }

    @Test
    fun `unknown plain text defaults to subrip`() {
        assertEquals(
            ExternalSubtitleFormat.SUBRIP,
            detectSubtitleFormat(
                url = "https://example.test/subtitle",
                contentType = "text/plain",
                bytes = "1\n00:00:01,000 --> 00:00:02,000\nHello".encodeToByteArray(),
            ),
        )
    }
}
