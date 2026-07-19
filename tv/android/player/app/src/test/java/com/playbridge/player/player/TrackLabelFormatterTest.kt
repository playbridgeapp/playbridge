package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackLabelFormatterTest {
    @Test
    fun genericNormalLabelDoesNotHideLanguage() {
        assertEquals(
            "English",
            buildSubtitleTrackLabel("Normal", "eng", "Subtitle 1"),
        )
    }

    @Test
    fun usefulTrackQualifierIsPreserved() {
        assertEquals(
            "English • Forced",
            buildSubtitleTrackLabel("Forced", "en", "Subtitle 1"),
        )
    }

    @Test
    fun duplicatedLanguageLabelIsCollapsed() {
        assertEquals(
            "Japanese",
            buildSubtitleTrackLabel("Japanese", "jpn", "Subtitle 1"),
        )
    }

    @Test
    fun missingMetadataUsesFallback() {
        assertEquals(
            "Subtitle 3",
            buildSubtitleTrackLabel("Normal", null, "Subtitle 3"),
        )
    }
}
