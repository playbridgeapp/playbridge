package com.playbridge.sender.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class SniffingSubtitleParserFactoryTest {
    @Test fun contentDecidesTheParser() {
        // OpenSubtitles serves this SRT labelled as vtt.
        assertEquals(MimeTypes.APPLICATION_SUBRIP, sniff("1\n00:00:01,000 --> 00:00:02,500\nHello\n"))
        assertEquals(MimeTypes.TEXT_VTT, sniff("﻿WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n"))
        assertEquals(MimeTypes.TEXT_SSA, sniff("[Script Info]\nTitle: x\n"))
        assertEquals(MimeTypes.APPLICATION_TTML, sniff("""<?xml version="1.0"?><tt xmlns="http://www.w3.org/ns/ttml">"""))
    }

    @Test fun onlyTheGivenRangeIsRead() {
        val bytes = "xxxxWEBVTT\n".toByteArray()
        assertEquals(MimeTypes.TEXT_VTT, SniffingSubtitleParserFactory.sniffedMimeType(bytes, 4, bytes.size - 4))
    }

    private fun sniff(text: String): String? {
        val bytes = text.toByteArray()
        return SniffingSubtitleParserFactory.sniffedMimeType(bytes, 0, bytes.size)
    }
}
