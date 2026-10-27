package com.playbridge.sender.cast

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DetectorSubtitleTextTest {
    private val vtt = "\uFEFFWEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"

    private fun message(text: String = vtt, format: String = "vtt", mime: String = "text/plain") = JsonObject(mapOf(
        "type" to JsonPrimitive("video_detected"),
        "url" to JsonPrimitive("playbridge-subtitle:fixture.$format"),
        "mediaKind" to JsonPrimitive("subtitle"),
        "contentType" to JsonPrimitive(mime),
        "subtitleText" to JsonPrimitive(text),
        "subtitleFormat" to JsonPrimitive(format),
        "subtitleLabel" to JsonPrimitive("English"),
    ))

    @Test
    fun acceptsVttAndSrtAndRetainsTrackLabel() {
        val subtitle = detectorSubtitleText(message())!!
        assertEquals(vtt, subtitle.text)
        assertEquals("vtt", subtitle.format)
        assertEquals("text/vtt", subtitle.mime)
        assertEquals("English", subtitle.label)
        assertEquals("application/x-subrip", detectorSubtitleText(message(srt, "srt"))!!.mime)
        assertEquals(subtitle.hash, detectorSubtitleText(message())!!.hash)
    }

    @Test
    fun acceptsDeclaredSubtitleTypesWithoutSignature() {
        assertNotNull(detectorSubtitleText(message("captions", "vtt", "text/vtt; charset=utf-8")))
        assertNotNull(detectorSubtitleText(message("captions", "srt", "application/x-subrip")))
    }

    @Test
    fun rejectsOtherKindsMalformedFieldsAndVideoMime() {
        assertNull(detectorSubtitleText(JsonObject(message() + ("mediaKind" to JsonPrimitive("video")))))
        assertNull(detectorSubtitleText(JsonObject(message() + ("subtitleText" to JsonObject(emptyMap())))))
        assertNull(detectorSubtitleText(message(mime = "video/mp4")))
        assertNull(detectorSubtitleText(message("unrelated text")))
        assertNull(detectorSubtitleText(message(format = "srt")))
        assertNull(detectorSubtitleText(message(format = "ass")))
        assertNull(detectorSubtitleText(message("")))
    }

    @Test
    fun capsUtf8BytesAndDefaultsLabel() {
        assertNull(detectorSubtitleText(message("WEBVTT\n" + "a".repeat(MAX_DETECTED_SUBTITLE_BYTES))))
        assertNull(detectorSubtitleText(message("WEBVTT\n" + "é".repeat(MAX_DETECTED_SUBTITLE_BYTES / 2))))
        assertNotNull(detectorSubtitleText(message("WEBVTT\n" + "a".repeat(MAX_DETECTED_SUBTITLE_BYTES - 7))))
        assertEquals("Subtitles", detectorSubtitleText(JsonObject(message() - "subtitleLabel"))!!.label)
    }

    @Test
    fun publishedDetectionUsesHttpAndDoesNotRetainTextOrPageHeaders() {
        val original = JsonObject(message() + ("headers" to JsonObject(mapOf("Referer" to JsonPrimitive("https://page.example/")))))
        val subtitle = detectorSubtitleText(original)!!
        val published = publishedSubtitleMessage(original, subtitle, "http://192.0.2.1:8080/local.vtt")
        assertEquals(JsonPrimitive("http://192.0.2.1:8080/local.vtt"), published["url"])
        assertEquals(JsonPrimitive("English"), published["title"])
        assertEquals(JsonPrimitive("text/vtt"), published["contentType"])
        assertFalse(published.containsKey("subtitleText"))
        assertFalse(published.containsKey("headers"))
    }
}
