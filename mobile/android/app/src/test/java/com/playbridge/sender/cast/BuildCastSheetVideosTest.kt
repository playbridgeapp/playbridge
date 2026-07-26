package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildCastSheetVideosTest {

    @Test
    fun withoutHandoff_ranksNormally() {
        val a = DetectedVideo(url = "https://cdn.example/a.mp4", timestamp = 1)
        val b = DetectedVideo(
            url = "https://cdn.example/master.m3u8",
            contentType = "application/vnd.apple.mpegurl",
            timestamp = 2,
        )
        val out = buildCastSheetVideos(listOf(a, b))
        assertEquals(listOf(b.url, a.url), out.map { it.url })
    }

    @Test
    fun withPlaylistBody_prependsSyntheticRow() {
        val raw = DetectedVideo(
            url = "https://cdn.example/chunklist_video.m3u8?session=1",
            contentType = "application/vnd.apple.mpegurl",
            playlistBody = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/v.m3u8\n",
            audioUrl = "https://cdn.example/audio.m3u8?session=1",
            timestamp = 10,
        )
        val other = DetectedVideo(url = "https://cdn.example/other.mp4", timestamp = 1)
        val out = buildCastSheetVideos(listOf(raw, other))

        assertEquals(2, out.size)
        assertEquals(SYNTHETIC_CAST_ITEM_TITLE, out[0].title)
        assertTrue(out[0].isSyntheticMaster)
        assertTrue(out[0].hasSyntheticHandoff)
        assertEquals(raw.url, out[0].url)
        assertEquals(raw.playlistBody, out[0].playlistBody)
        assertEquals(raw.audioUrl, out[0].audioUrl)
        assertEquals(other.url, out[1].url)
    }

    @Test
    fun detectorSynthetic_isPromotedAndNotDuplicated() {
        val synth = DetectedVideo(
            url = "https://cdn.example/session-video.m3u8",
            isSyntheticMaster = true,
            playlistBody = "#EXTM3U\n",
            detectedBy = "synthetic_hls_master",
            title = "old title",
            timestamp = 5,
        )
        val ladder = DetectedVideo(
            url = "https://cdn.example/chunklist_0.m3u8",
            contentType = "application/x-mpegurl",
            timestamp = 4,
        )
        val out = buildCastSheetVideos(listOf(ladder, synth))

        assertEquals(2, out.size)
        assertEquals(SYNTHETIC_CAST_ITEM_TITLE, out[0].title)
        assertEquals("synthetic", out[0].detectedBy)
        assertFalse(out.any { it !== out[0] && it.isSyntheticMaster })
        assertEquals(ladder.url, out[1].url)
    }

    @Test
    fun castScore_prefersSyntheticHandoff() {
        val synth = DetectedVideo(
            url = "https://cdn.example/a.m3u8",
            playlistBody = "#EXTM3U\n",
        )
        val hls = DetectedVideo(
            url = "https://cdn.example/master.m3u8",
            contentType = "application/vnd.apple.mpegurl",
        )
        assertTrue(synth.castScore() > hls.castScore())
    }
}
