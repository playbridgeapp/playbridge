package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleContentCheckTest {
    @Test
    fun `subtitle formats are recognised`() {
        assertTrue(looksLikeSubtitles("﻿WEBVTT\n\n00:01.000 --> 00:02.000\nHi"))
        assertTrue(looksLikeSubtitles("1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\n"))
        assertTrue(looksLikeSubtitles("\n\n12\n01:02:03.400 --> 01:02:05.000\nNo header"))
        assertTrue(looksLikeSubtitles("[Script Info]\nTitle: Episode"))
        assertTrue(looksLikeSubtitles("<?xml version=\"1.0\"?>\n<tt xmlns=\"http://www.w3.org/ns/ttml\">"))
        assertTrue(looksLikeSubtitles("#EXTM3U\n#EXT-X-TARGETDURATION:6\nsub-1.vtt"))
    }

    @Test
    fun `other content served at a subtitle URL is rejected`() {
        assertFalse(looksLikeSubtitles("<!DOCTYPE html><html><body>403 Forbidden</body></html>"))
        assertFalse(looksLikeSubtitles("{\"error\":\"not found\"}"))
        assertFalse(looksLikeSubtitles("<?xml version=\"1.0\"?><Error><Code>AccessDenied</Code></Error>"))
        assertFalse(looksLikeSubtitles("Just some text without any timings"))
    }

    @Test
    fun `utf-16 files with a byte order mark are decoded`() {
        val text = "WEBVTT\n"
        assertEquals(text, decodeSubtitleHead(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, decodeSubtitleHead(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
        assertEquals(text, decodeSubtitleHead(text.toByteArray()))
    }

    @Test
    fun `subtitles found not to be subtitles drop out of the list`() {
        val good = DetectedVideo(url = "https://cdn.test/en.vtt", timestamp = 1)
        val bad = DetectedVideo(url = "https://cdn.test/fr.vtt", timestamp = 2, subtitleContentValid = false)
        val unchecked = DetectedVideo(url = "https://cdn.test/de.srt", timestamp = 3)
        assertEquals(listOf(unchecked, good), newestSubtitlesFirst(listOf(good, bad, unchecked)))
    }

    @Test
    fun `thumbnail sprite tracks are not subtitles`() {
        assertTrue(isImageTrack(listOf("preview-01.jpg#xywh=0,0,320,180", "preview-01.jpg#xywh=320,0,320,180")))
        assertTrue(isImageTrack(listOf("https://cdn.test/thumbs/0001.webp", "https://cdn.test/thumbs/0002.webp")))
        assertFalse(isImageTrack(listOf("Shall we go fishing?", "At last!")))
        assertFalse(isImageTrack(emptyList()))
    }
}
