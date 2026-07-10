package com.playbridge.sender.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalContainerSnifferTest {

    @Test
    fun looksLikeMpegTs_detectsSyncTrain() {
        val buf = ByteArray(188 * 5)
        for (i in 0 until 5) {
            buf[i * 188] = 0x47
            // fill rest with non-sync noise
            for (j in 1 until 188) buf[i * 188 + j] = (j % 50).toByte()
        }
        assertTrue(LocalContainerSniffer.looksLikeMpegTs(buf))
        assertEquals(MimeTypes.VIDEO_MP2T, LocalContainerSniffer.detect(buf))
    }

    @Test
    fun looksLikeMpegTs_detectsOffsetSync() {
        val buf = ByteArray(20 + 188 * 4)
        for (i in 0 until 4) {
            buf[20 + i * 188] = 0x47
        }
        assertTrue(LocalContainerSniffer.looksLikeMpegTs(buf))
    }

    @Test
    fun looksLikeMpegTs_rejectsRandomNoise() {
        val buf = ByteArray(188 * 4) { 0x11 }
        buf[0] = 0x47 // single sync, no train
        assertFalse(LocalContainerSniffer.looksLikeMpegTs(buf))
    }

    @Test
    fun detect_mp4Ftyp() {
        val buf = ByteArray(32)
        buf[4] = 'f'.code.toByte()
        buf[5] = 't'.code.toByte()
        buf[6] = 'y'.code.toByte()
        buf[7] = 'p'.code.toByte()
        assertEquals(MimeTypes.VIDEO_MP4, LocalContainerSniffer.detect(buf))
    }

    @Test
    fun detect_tsWinsEvenWhenFilenameWouldSayMp4() {
        // Pure TS payload — must not be confused with empty "mp4" claim.
        val buf = ByteArray(188 * 4)
        for (i in 0 until 4) buf[i * 188] = 0x47
        assertEquals(MimeTypes.VIDEO_MP2T, LocalContainerSniffer.detect(buf))
    }

    @Test
    fun detect_emptyIsNull() {
        assertNull(LocalContainerSniffer.detect(ByteArray(0)))
    }

    @Test
    fun isLocalUri() {
        assertTrue(LocalContainerSniffer.isLocalUri("content://media/external/video/media/1"))
        assertTrue(LocalContainerSniffer.isLocalUri("file:///sdcard/a.mp4"))
        assertFalse(LocalContainerSniffer.isLocalUri("https://cdn.example/a.mp4"))
    }

    @Test
    fun mapContentType_forcesTsNotMp4() {
        assertEquals(
            MimeTypes.VIDEO_MP2T,
            PhoneExoPlayerFactory.mapContentTypeToMime("video/mp2t", "content://x"),
        )
        assertEquals(
            null,
            PhoneExoPlayerFactory.mapContentTypeToMime("video/mp4", "content://x"),
        )
    }

    @Test
    fun mapContentType_hlsAndDash() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            PhoneExoPlayerFactory.mapContentTypeToMime(null, "https://cdn.example/master.m3u8"),
        )
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            PhoneExoPlayerFactory.mapContentTypeToMime("application/dash+xml", "https://cdn.example/x"),
        )
    }
}
