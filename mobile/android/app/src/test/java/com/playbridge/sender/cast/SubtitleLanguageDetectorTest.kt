package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleLanguageDetectorTest {
    @Test
    fun acceptsClearResultOnly() {
        assertEquals("fr", likelySubtitleLanguageCode(listOf("fr" to 0.89f, "en" to 0.08f)))
        assertNull(likelySubtitleLanguageCode(listOf("fr" to 0.68f, "en" to 0.20f)))
        assertNull(likelySubtitleLanguageCode(listOf("fr" to 0.76f, "en" to 0.68f)))
        assertNull(likelySubtitleLanguageCode(listOf("und" to 0.90f)))
        assertNull(likelySubtitleLanguageCode(listOf("und" to 0.90f, "fr" to 0.80f)))
    }
}
