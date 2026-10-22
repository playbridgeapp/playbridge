package com.playbridge.player.player

import com.playbridge.shared.player.PlayerChapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteTimelineTest {
    @Test
    fun `timeline sorts, bounds and maps open-ended segments to null`() {
        val json = Json.parseToJsonElement(
            buildTimelineJson(
                playbackId = "p1",
                currentItemId = "i1",
                chapters = listOf(
                    PlayerChapter(600_000L, "  The Heist  "),
                    PlayerChapter(0L, "Opening"),
                    PlayerChapter(-5L, "bad"),
                    PlayerChapter(1_200_000L, "   "),
                ),
                segments = listOf(
                    SkipSegment("outro", 5_400_000L, SkipSegmentFetcher.OPEN_ENDED_MS),
                    SkipSegment("intro", 30_000L, 95_000L),
                    SkipSegment("ad", 1L, 2L),
                    SkipSegment("recap", 50L, 40L),
                ),
            ),
        ).jsonObject
        assertEquals("timeline", json["type"]!!.jsonPrimitive.content)
        assertEquals("p1", json["playbackId"]!!.jsonPrimitive.content)
        assertEquals("i1", json["currentItemId"]!!.jsonPrimitive.content)

        val chapters = json["chapters"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(0L, 600_000L, 1_200_000L), chapters.map { it["startMs"]!!.jsonPrimitive.long })
        assertEquals("Opening", chapters[0]["title"]!!.jsonPrimitive.content)
        assertEquals("The Heist", chapters[1]["title"]!!.jsonPrimitive.content)
        assertFalse(chapters[2].containsKey("title"))

        val segments = json["segments"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("intro", "outro"), segments.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(95_000L, segments[0]["endMs"]!!.jsonPrimitive.long)
        assertEquals(JsonNull, segments[1]["endMs"])
    }

    @Test
    fun `timeline caps counts and title length`() {
        val longTitle = "x".repeat(TIMELINE_MAX_TITLE_LENGTH + 20)
        val json = Json.parseToJsonElement(
            buildTimelineJson(
                playbackId = null,
                currentItemId = null,
                chapters = (0 until 300).map { PlayerChapter(it * 1_000L, longTitle) },
                segments = (0 until 20).map { SkipSegment("intro", it * 10L, it * 10L + 5L) },
            ),
        ).jsonObject
        assertFalse(json.containsKey("playbackId"))
        val chapters = json["chapters"]!!.jsonArray
        assertEquals(TIMELINE_MAX_CHAPTERS, chapters.size)
        assertEquals(
            TIMELINE_MAX_TITLE_LENGTH,
            chapters[0].jsonObject["title"]!!.jsonPrimitive.content.length,
        )
        assertEquals(TIMELINE_MAX_SEGMENTS, json["segments"]!!.jsonArray.size)
    }

    @Test
    fun `buffered stays between position and duration and is absent when live`() {
        assertEquals(40_000L, reportedBufferedMs(40_000L, 10_000L, 100_000L))
        assertEquals(10_000L, reportedBufferedMs(2_000L, 10_000L, 100_000L))
        assertEquals(100_000L, reportedBufferedMs(150_000L, 10_000L, 100_000L))
        assertNull(reportedBufferedMs(40_000L, 10_000L, 0L))
    }

    @Test
    fun `normalizedChapters filters negatives, sorts by startMs and normalizes titles`() {
        val result = normalizedChapters(
            listOf(
                PlayerChapter(120_000L, "  Act II  "),
                PlayerChapter(-1L, "Negative"),
                PlayerChapter(0L, "   "),
            ),
        )
        assertEquals(
            listOf(
                PlayerChapter(0L, null),
                PlayerChapter(120_000L, "Act II"),
            ),
            result,
        )
    }
}
