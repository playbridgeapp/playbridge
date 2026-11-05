package com.playbridge.sender.cast

import com.playbridge.sender.player.PhoneTrackLanguages
import com.playbridge.sender.player.languageSpellings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLanguagesTest {
    @Test
    fun `settings codes in either form normalise to one`() {
        assertEquals("es", normalizeLanguage("spa"))
        assertEquals("es", normalizeLanguage("es"))
        assertEquals("fr", normalizeLanguage("fre"))
        assertEquals("de", normalizeLanguage("ger"))
        assertEquals("en", normalizeLanguage("en-US"))
        assertNull(normalizeLanguage(""))
        assertNull(normalizeLanguage("multi"))
        assertNull(normalizeLanguage("und"))
    }

    @Test
    fun `languages are read from titles and file names`() {
        assertEquals("en", languageInTitle("English (SDH)"))
        assertEquals("es", languageInTitle("spa"))
        assertEquals("en", languageInTitle("en"))
        assertNull(languageInTitle("Get it on"))
        assertEquals("en", languageInFileName("https://cdn.test/movie.en.vtt?token=1"))
        assertEquals("de", languageInFileName("https://cdn.test/show_s01e02_ger.srt"))
        assertEquals("fr", languageInFileName("https://cdn.test/subs/french.vtt"))
        assertNull(languageInFileName("https://cdn.test/6aecb0f105c24a.vtt"))
    }

    private fun sub(url: String, timestamp: Long, code: String? = null, title: String? = null) =
        DetectedVideo(url = url, timestamp = timestamp, title = title).apply { subtitleLanguageCode = code }

    @Test
    fun `preferred language sorts first, then the phone's language`() {
        val polish = sub("https://cdn.test/a.vtt", 3, code = "pl")
        val english = sub("https://cdn.test/b.vtt", 2, code = "en")
        val spanish = sub("https://cdn.test/c.vtt", 1, code = "es")
        assertEquals(listOf(spanish, english, polish), rankSubtitles(listOf(polish, english, spanish), "spa", "en"))
        assertEquals(listOf(english, polish, spanish), rankSubtitles(listOf(polish, english, spanish), "", "en"))
    }

    @Test
    fun `the plain subtitle is picked over forced and SDH ones`() {
        val forced = sub("https://cdn.test/en.forced.vtt", 3, code = "en")
        val sdh = sub("https://cdn.test/x.vtt", 2, code = "en", title = "English SDH")
        val plain = sub("https://cdn.test/y.vtt", 1, code = "en")
        val ranked = rankSubtitles(listOf(forced, sdh, plain), "en", "en")
        assertEquals(plain, preferredSubtitlePick(ranked, "en"))
        assertEquals(forced, preferredSubtitlePick(listOf(forced), "en"))
        assertNull(preferredSubtitlePick(ranked, ""))
        assertNull(preferredSubtitlePick(ranked, "fre"))
    }

    @Test
    fun `phone player languages follow the settings`() {
        val auto = PhoneTrackLanguages.of(audioSetting = "", subtitleSetting = "", chosenSubtitles = false, deviceLanguage = "en")
        assertEquals("en", auto.audio)
        assertNull(auto.subtitles)
        assertTrue(auto.subtitlesOffUnlessPreferred)

        val preferred = PhoneTrackLanguages.of(audioSetting = "ja", subtitleSetting = "spa", chosenSubtitles = false, deviceLanguage = "en")
        assertEquals("ja", preferred.audio)
        assertEquals("es", preferred.subtitles)

        val chosen = PhoneTrackLanguages.of(audioSetting = "multi", subtitleSetting = "spa", chosenSubtitles = true, deviceLanguage = "en")
        assertNull(chosen.audio)
        assertNull(chosen.subtitles)
        assertFalse(chosen.subtitlesOffUnlessPreferred)
    }

    @Test
    fun `mpv gets every spelling of a language`() {
        assertEquals(listOf("en", "eng"), languageSpellings("en"))
        assertEquals(listOf("fr", "fra", "fre"), languageSpellings("fr"))
    }
}
