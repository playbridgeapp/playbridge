package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.MediaTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSubtitleLabelTest {
    @Test fun numericSubtitleIdBecomesSubtitleNAndKeepsTheId() {
        val tracks = listOf(
            MediaTrack(id = "off", name = "Off", selected = false),
            MediaTrack(id = "196259847", name = "196259847", selected = true),
            MediaTrack(id = "2", name = "English", selected = false),
        )
        assertEquals("Subtitle 1", subtitleOptionLabel(tracks[1], tracks))
        assertEquals("196259847", tracks[1].id)
        assertEquals("English", subtitleOptionLabel(tracks[2], tracks))
    }

    @Test fun languageBesideAnOpaqueIdIsShownInsteadOfTheId() {
        val track = MediaTrack(id = "196259847", name = "196259847 · eng", selected = true)
        assertEquals("English", subtitleOptionLabel(track, listOf(track)))
        assertEquals("196259847", track.id)
    }

    @Test fun readableNamesAndOffStayAsTheyAre() {
        assertEquals("Off", subtitleOptionLabel(MediaTrack("off", "Off", true), emptyList()))
        assertEquals("English • Forced", subtitleOptionLabel(MediaTrack("1", "English • Forced", true), listOf(MediaTrack("1", "English • Forced", true))))
        val hex = MediaTrack(id = "track", name = "a1b2c3d4-e5f6-7890-abcd-ef1234567890", selected = true)
        assertEquals("Subtitle 1", subtitleOptionLabel(hex, listOf(MediaTrack("off", "Off", false), hex)))
    }
}
