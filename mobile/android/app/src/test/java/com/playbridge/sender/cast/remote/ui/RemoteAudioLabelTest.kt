package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.MediaTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteAudioLabelTest {
    @Test fun undeterminedLanguageDropsTheCodecAndUsesTrackN() {
        val tracks = listOf(
            MediaTrack(id = "a0", name = "und • EAC3", selected = true),
            MediaTrack(id = "a1", name = "mis • AAC • Stereo", selected = false),
            MediaTrack(id = "a2", name = "zxx", selected = false),
            MediaTrack(id = "a3", name = "undefined • MP4A", selected = false),
        )
        assertEquals("Track 1", audioOptionLabel(tracks[0], tracks))
        assertEquals("Track 2", audioOptionLabel(tracks[1], tracks))
        assertEquals("Track 3", audioOptionLabel(tracks[2], tracks))
        assertEquals("Track 4", audioOptionLabel(tracks[3], tracks))
        assertEquals("a0", tracks[0].id)
    }

    @Test fun missingUnknownAndOpaqueIdsBecomeTrackN() {
        val tracks = listOf(
            MediaTrack(id = "blank", name = "  ", selected = true),
            MediaTrack(id = "unk", name = "unknown • MP4A", selected = false),
            MediaTrack(id = "num", name = "196259847 • AAC", selected = false),
            MediaTrack(id = "hex", name = "a1b2c3d4-e5f6-7890-abcd-ef1234567890", selected = false),
        )
        assertEquals("Track 1", audioOptionLabel(tracks[0], tracks))
        assertEquals("Track 2", audioOptionLabel(tracks[1], tracks))
        assertEquals("Track 3", audioOptionLabel(tracks[2], tracks))
        assertEquals("Track 4", audioOptionLabel(tracks[3], tracks))
        assertEquals("num", tracks[2].id)
    }

    @Test fun knownLanguageKeepsTodaysLabel() {
        val coded = MediaTrack(id = "en-1", name = "en • EAC3", selected = true)
        assertEquals("en • EAC3", audioOptionLabel(coded, listOf(coded)))
        val named = MediaTrack(id = "en-2", name = "English • Stereo", selected = true)
        assertEquals("English • Stereo", audioOptionLabel(named, listOf(named)))
        assertEquals("en-1", coded.id)
    }

    @Test fun multipleStaysMultipleAndKeepsACodec() {
        val plain = MediaTrack(id = "m", name = "mul", selected = true)
        assertEquals("Multiple", audioOptionLabel(plain, listOf(plain)))
        val coded = MediaTrack(id = "m2", name = "mul • EAC3", selected = false)
        assertEquals("Multiple • EAC3", audioOptionLabel(coded, listOf(plain, coded)))
    }

    @Test fun ordinalFollowsPositionAmongAudioTracks() {
        val tracks = listOf(
            MediaTrack(id = "en", name = "en • EAC3", selected = true),
            MediaTrack(id = "und", name = "und • MP4A", selected = false),
            MediaTrack(id = "blank", name = "", selected = false),
        )
        assertEquals("en • EAC3", audioOptionLabel(tracks[0], tracks))
        assertEquals("Track 2", audioOptionLabel(tracks[1], tracks))
        assertEquals("Track 3", audioOptionLabel(tracks[2], tracks))
    }
}
