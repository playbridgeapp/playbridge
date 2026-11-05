package com.playbridge.sender.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePlayerEngineTest {
    @Test
    fun `unknown or missing setting is Automatic`() {
        assertEquals(PhonePlayerMode.AUTOMATIC, PhonePlayerMode.fromId(null))
        assertEquals(PhonePlayerMode.AUTOMATIC, PhonePlayerMode.fromId("vlc"))
        assertEquals(PhonePlayerMode.MPV, PhonePlayerMode.fromId("mpv"))
        assertEquals(PhonePlayerMode.EXOPLAYER, PhonePlayerMode.fromId("exo"))
    }

    @Test
    fun `only the mpv setting starts with mpv`() {
        assertEquals(PhonePlayerEngine.EXOPLAYER, PhonePlayerMode.AUTOMATIC.initialEngine)
        assertEquals(PhonePlayerEngine.EXOPLAYER, PhonePlayerMode.EXOPLAYER.initialEngine)
        assertEquals(PhonePlayerEngine.MPV, PhonePlayerMode.MPV.initialEngine)
    }

    @Test
    fun `format and decoder errors switch, network and DRM errors do not`() {
        assertTrue(PhonePlayerFallback.isFormatError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertTrue(PhonePlayerFallback.isFormatError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertTrue(PhonePlayerFallback.isFormatError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertFalse(PhonePlayerFallback.isFormatError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(PhonePlayerFallback.isFormatError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertFalse(PhonePlayerFallback.isFormatError(PlaybackException.ERROR_CODE_DRM_UNSPECIFIED))
        assertFalse(PhonePlayerFallback.isFormatError(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
    }

    @Test
    fun `a video with no supported video track switches`() {
        val groups = listOf(C.TRACK_TYPE_VIDEO to false, C.TRACK_TYPE_AUDIO to true)
        assertTrue(PhonePlayerFallback.hasUnplayableTrackType(groups))
        assertTrue(PhonePlayerFallback.hasUnplayableTrackType(listOf(C.TRACK_TYPE_AUDIO to false)))
    }

    @Test
    fun `one supported track of each type is enough`() {
        val groups = listOf(C.TRACK_TYPE_AUDIO to false, C.TRACK_TYPE_AUDIO to true, C.TRACK_TYPE_VIDEO to true)
        assertFalse(PhonePlayerFallback.hasUnplayableTrackType(groups))
        // Unsupported subtitles alone don't justify a switch.
        assertFalse(PhonePlayerFallback.hasUnplayableTrackType(listOf(C.TRACK_TYPE_TEXT to false, C.TRACK_TYPE_VIDEO to true)))
        assertFalse(PhonePlayerFallback.hasUnplayableTrackType(emptyList()))
    }

    @Test
    fun `mpv opens network files and content URIs only`() {
        assertTrue(PhonePlayerFallback.canOpen("https://example.com/a.mkv"))
        assertTrue(PhonePlayerFallback.canOpen("content://downloads/1"))
        assertTrue(PhonePlayerFallback.canOpen("/storage/emulated/0/a.mp4"))
        assertFalse(PhonePlayerFallback.canOpen("rtsp://camera/stream"))
        assertFalse(PhonePlayerFallback.canOpen(""))
    }

    @Test
    fun `mpv track list is parsed without cover art`() {
        val json = """
            [
              {"id":1,"type":"video","codec":"hevc","demux-w":3840,"demux-h":2160,"selected":true},
              {"id":2,"type":"video","codec":"mjpeg","albumart":true},
              {"id":1,"type":"audio","codec":"truehd","lang":"eng","title":"Atmos","demux-channel-count":8,"selected":true},
              {"id":2,"type":"audio","codec":"aac","lang":"fra"},
              {"id":1,"type":"sub","codec":"subrip","lang":"eng","selected":false},
              {"id":2,"type":"sub","codec":"hdmv_pgs_subtitle"}
            ]
        """.trimIndent()
        val parsed = MpvTrackList.parse(json)
        assertEquals(listOf("mpv:video:1", "mpv:audio:1", "mpv:audio:2", "mpv:sub:1", "mpv:sub:2"), parsed.map { it.groupId })

        val atmos = parsed[1]
        assertEquals(C.TRACK_TYPE_AUDIO, atmos.type)
        assertTrue(atmos.selected)
        assertEquals("Atmos", atmos.title)
        assertEquals("eng", atmos.language)
        assertEquals(8, atmos.channelCount)
        assertEquals(2160, parsed[0].height)
        assertFalse(parsed[3].selected)
    }

    @Test
    fun `mpv codecs map to Media3 mime types`() {
        assertEquals(MimeTypes.VIDEO_H265, MpvTrackList.mimeType(C.TRACK_TYPE_VIDEO, "hevc"))
        assertEquals(MimeTypes.VIDEO_UNKNOWN, MpvTrackList.mimeType(C.TRACK_TYPE_VIDEO, "prores"))
        assertEquals(MimeTypes.AUDIO_TRUEHD, MpvTrackList.mimeType(C.TRACK_TYPE_AUDIO, "truehd"))
        assertEquals(MimeTypes.AUDIO_UNKNOWN, MpvTrackList.mimeType(C.TRACK_TYPE_AUDIO, null))
        assertEquals(MimeTypes.TEXT_SSA, MpvTrackList.mimeType(C.TRACK_TYPE_TEXT, "ass"))
        assertEquals(MimeTypes.APPLICATION_PGS, MpvTrackList.mimeType(C.TRACK_TYPE_TEXT, "hdmv_pgs_subtitle"))
        assertEquals(MimeTypes.TEXT_UNKNOWN, MpvTrackList.mimeType(C.TRACK_TYPE_TEXT, "mov_text"))
    }

    @Test
    fun `group ids map a selection back to mpv`() {
        assertEquals(C.TRACK_TYPE_AUDIO to "2", MpvTrackList.parseGroupId("mpv:audio:2"))
        assertEquals(C.TRACK_TYPE_TEXT to "7", MpvTrackList.parseGroupId("mpv:sub:7"))
        assertNull(MpvTrackList.parseGroupId("0:1"))
        assertNull(MpvTrackList.parseGroupId("mpv:data:1"))
    }

    @Test
    fun `malformed track lists are empty`() {
        assertTrue(MpvTrackList.parse("not json").isEmpty())
        assertTrue(MpvTrackList.parse("""[{"type":"audio"}]""").isEmpty())
    }
}
