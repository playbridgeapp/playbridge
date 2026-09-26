package com.playbridge.sender.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastReplayStoreTest {
    @Test
    fun nativeSingleItemRetainsOriginalHeadersForCastSheet() {
        val source = replaySourceFromNativeCommand(
            """{"type":"command","action":"playlist","payload":{"items":[{"url":"https://example.com/video.m3u8","title":"Episode 1","headers":{"Origin":"https://example.com"},"mediaKind":"video","subtitles":["https://example.com/en.vtt"]}]}}"""
        )
        assertNotNull(source)
        assertEquals("Episode 1", source!!.title)
        assertEquals("https://example.com", source.headers["Origin"])
        assertEquals(listOf("https://example.com/en.vtt"), source.subtitles)
        assertNull(source.playlistPayloadJson)
    }

    @Test
    fun nativePlaylistPreservesItsItemsWithoutTreatingQueueEditsAsCasts() {
        val source = replaySourceFromNativeCommand(
            """{"type":"command","action":"playlist","payload":{"items":[{"url":"https://example.com/1.mp4"},{"url":"https://example.com/2.mp4"}]}}"""
        )
        assertNotNull(source?.playlistPayloadJson)
        assertNull(replaySourceFromNativeCommand(
            """{"type":"command","action":"queue_add","payload":{"item":{"url":"https://example.com/3.mp4"}}}"""
        ))
    }

    @Test
    fun temporaryPhoneProxyUrlsAreNeverSavedForRecast() {
        assertFalse(isReplayableMediaUrl("http://192.168.1.2:45231/s/abc/playlist.m3u8"))
        assertFalse(isReplayableMediaUrl("http://192.168.1.2:45231/0123456789abcdef.m3u8"))
        assertFalse(isReplayableMediaUrl("content://media/external/video/media/42"))
        assertTrue(isReplayableMediaUrl("https://example.com/video.m3u8"))
    }
}
