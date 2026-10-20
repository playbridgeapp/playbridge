package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.MediaTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSheetsGatingTest {

    @Test
    fun subtitleRowsSortByOriginAndSkipOff() {
        val tracks = listOf(
            MediaTrack(id = "none", name = "Off", selected = true),
            MediaTrack(id = "2", name = "English", selected = false),
            MediaTrack(id = "sub_en_1", name = "OpenSubtitles #1 · English", selected = false, type = "external_sub"),
            MediaTrack(id = "sub_es", name = "OpenSubtitles #2 · Spanish", selected = false, type = "external_sub"),
            MediaTrack(id = "external_1", name = "movie.srt", selected = false, type = "external_sub"),
        )
        val rows = subtitleRows(tracks)
        assertEquals(listOf("2", "sub_en_1", "sub_es", "external_1"), rows.map { it.track.id })
        assertEquals(
            listOf(SubtitleOrigin.BuiltIn to 1, SubtitleOrigin.AddOn to 2, SubtitleOrigin.Added to 1),
            subtitleOriginFilters(rows),
        )
        assertEquals(listOf("sub_en_1", "sub_es"), filterSubtitleRows(rows, SubtitleOrigin.AddOn, null, "").map { it.track.id })
        assertEquals(listOf("sub_es"), filterSubtitleRows(rows, SubtitleOrigin.AddOn, "Spanish", "").map { it.track.id })
        assertEquals(listOf("external_1"), filterSubtitleRows(rows, null, null, "movie").map { it.track.id })
    }

    @Test
    fun subtitleLanguagesComeFromNamesAndCodes() {
        val rows = subtitleRows(listOf(
            MediaTrack(id = "sub1", name = "OpenSubtitles #1 · fra", selected = false, type = "external_sub"),
            MediaTrack(id = "sub2", name = "OpenSubtitles #2 · French commentary", selected = false, type = "external_sub"),
            MediaTrack(id = "sub3", name = "OpenSubtitles #3 · English", selected = false, type = "external_sub"),
            MediaTrack(id = "sub4", name = "Commentary", selected = false, type = "external_sub"),
        ))
        assertEquals(listOf("French" to 2, "English" to 1), subtitleLanguageFilters(rows))
    }

    @Test
    fun languageFiltersPutPhoneThenCurrentLanguageFirst() {
        val rows = subtitleRows(listOf("Arabic", "Spanish", "Spanish", "Catalan", "English", "French", "French", "French").mapIndexed { i, name ->
            MediaTrack(id = "$i", name = name, selected = false)
        })
        assertEquals(
            listOf("English", "Catalan", "French", "Spanish", "Arabic"),
            subtitleLanguageFilters(rows, listOf("English", "Catalan")).map { it.first },
        )
        assertEquals("Hindi", phoneSubtitleLanguage(java.util.Locale("hi", "IN")))
    }

    @Test
    fun addedTracksNamedByIdAreLabelledFromTheirLink() {
        val tracks = listOf(
            MediaTrack(id = "3", name = "English", selected = false),
            MediaTrack(id = "https://subs.example.com/download/eng/4821937", name = "4821937", selected = true, type = "external_sub"),
            MediaTrack(id = "https://cdn.example.com/a1b2c3d4e5f6", name = "a1b2c3d4e5f6", selected = false, type = "external_sub"),
            MediaTrack(id = "https://cdn.example.com/files/Movie.2024.en.srt", name = "99887766", selected = false, type = "external_sub"),
            MediaTrack(id = "http://192.168.1.5:8080/s/77", name = "77", selected = false, type = "external_sub"),
        )
        assertEquals("English · example.com", subtitleOptionLabel(tracks[1], tracks))
        assertEquals("example.com", subtitleOptionLabel(tracks[2], tracks))
        assertEquals("Movie.2024.en", subtitleOptionLabel(tracks[3], tracks))
        assertEquals("Added", subtitleOptionLabel(tracks[4], tracks))
        assertEquals("English", subtitleRows(tracks)[1].language)
    }

    @Test
    fun repeatedLabelsAreNumberedAndStremioLinksCountAsAddOns() {
        val tracks = listOf(
            MediaTrack(id = "off", name = "Off", selected = false),
            MediaTrack(id = "https://subs5.strem.io/en/download/1001", name = "1001", selected = false, type = "external_sub"),
            MediaTrack(id = "https://subs5.strem.io/en/download/1002", name = "1002", selected = true, type = "external_sub"),
        )
        assertEquals("English · strem.io 1", subtitleOptionLabel(tracks[1], tracks))
        assertEquals("English · strem.io 2", subtitleOptionLabel(tracks[2], tracks))
        assertEquals(SubtitleOrigin.AddOn, subtitleOrigin(tracks[1]))
        assertEquals("example.co.uk", shortHost("subs.example.co.uk"))
        assertEquals("English · OpenSubtitles v3", readableSubtitleName("eng · OpenSubtitles v3"))
        assertEquals("Spanish • Forced", readableSubtitleName("Spanish • Forced"))
    }

    @Test
    fun subtitleOffsetLabelsReadAsSeconds() {
        assertEquals("Synced", subtitleOffsetLabel(0))
        assertEquals("+0.3 s", subtitleOffsetLabel(300))
        assertEquals("−1.25 s", subtitleOffsetLabel(-1250))
        assertEquals("+2 s", subtitleOffsetLabel(2000))
        assertEquals(listOf("−0.5", "−0.1", "+0.1", "+0.5"), listOf(-500L, -100L, 100L, 500L).map(::subtitleOffsetStepLabel))
    }

    @Test
    fun playerSettingsGatingTruthTable() {
        // Gating rules from RemoteSheets:
        // showSettings = !external && !image
        // showLoop = !external && !image && !isLive
        // showAddSubtitle = !external && !image && !audio

        fun isSettingsVisible(external: Boolean, image: Boolean) = !external && !image
        fun isLoopVisible(external: Boolean, image: Boolean, live: Boolean) = !external && !image && !live
        fun isAddSubtitleVisible(external: Boolean, image: Boolean, audio: Boolean) = !external && !image && !audio

        // External receiver (DLNA/Cast)
        assertFalse(isSettingsVisible(external = true, image = false))
        assertFalse(isLoopVisible(external = true, image = false, live = false))
        assertFalse(isAddSubtitleVisible(external = true, image = false, audio = false))

        // Image playback on Native
        assertFalse(isSettingsVisible(external = false, image = true))
        assertFalse(isLoopVisible(external = false, image = true, live = false))
        assertFalse(isAddSubtitleVisible(external = false, image = true, audio = false))

        // Live stream on Native
        assertTrue(isSettingsVisible(external = false, image = false))
        assertFalse(isLoopVisible(external = false, image = false, live = true))
        assertTrue(isAddSubtitleVisible(external = false, image = false, audio = false))

        // Audio stream on Native
        assertTrue(isSettingsVisible(external = false, image = false))
        assertTrue(isLoopVisible(external = false, image = false, live = false))
        assertFalse(isAddSubtitleVisible(external = false, image = false, audio = true))

        // Normal Video VOD on Native
        assertTrue(isSettingsVisible(external = false, image = false))
        assertTrue(isLoopVisible(external = false, image = false, live = false))
        assertTrue(isAddSubtitleVisible(external = false, image = false, audio = false))
    }

    @Test
    fun reportedLoopUsesASwitchAndAMissingFieldKeepsExplicitButtons() {
        assertTrue(loopEditorUsesSwitch(true))
        assertTrue(loopEditorUsesSwitch(false))
        assertFalse(loopEditorUsesSwitch(null))
    }
}
