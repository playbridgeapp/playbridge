package com.playbridge.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvTrackInfoTest {
    @Test
    fun parsesEachTrackType() {
        val raw = """[
            {"id":1,"type":"video","codec":"hevc","demux-w":3840,"demux-h":2160,"demux-bitrate":18000000,"selected":true},
            {"id":2,"type":"video","albumart":true,"codec":"mjpeg"},
            {"id":1,"type":"audio","lang":"eng","title":"Atmos","codec":"truehd","demux-channel-count":8},
            {"id":3,"type":"sub","lang":"spa","codec":"subrip"}
        ]"""
        assertEquals(
            listOf(
                MpvTrackInfo(MpvTrackType.VIDEO, "1", null, null, "hevc", true, width = 3840, height = 2160, bitrate = 18_000_000),
                MpvTrackInfo(MpvTrackType.AUDIO, "1", "eng", "Atmos", "truehd", false, channelCount = 8),
                MpvTrackInfo(MpvTrackType.SUBTITLE, "3", "spa", null, "subrip", false),
            ),
            parseMpvTrackList(raw),
        )
    }

    @Test
    fun skipsMalformedEntriesWithoutDroppingTheRest() {
        val raw = """[{"type":"audio"},{"id":4,"type":"data"},"junk",{"id":5,"type":"sub","title":""}]"""
        assertEquals(listOf(MpvTrackInfo(MpvTrackType.SUBTITLE, "5", null, null, null, false)), parseMpvTrackList(raw))
        assertTrue(parseMpvTrackList("not json").isEmpty())
    }
}
