package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.TvChapter
import com.playbridge.sender.cast.TvSkipSegment
import com.playbridge.sender.cast.parseTvTimeline
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSeekTimelineTest {
    private val chapters = listOf(RemoteChapter(0L, "Opening"), RemoteChapter(600_000L, "The Heist"), RemoteChapter(1_800_000L))
    private val segments = listOf(RemoteSegment("intro", 30_000L, 95_000L), RemoteSegment("outro", 5_400_000L, null))

    @Test fun `envelope is flat at both ends and full in the middle`() {
        val wavelength = 40f
        assertEquals(0f, remoteWaveEnvelope(0f, 400f, wavelength))
        assertEquals(0f, remoteWaveEnvelope(400f, 400f, wavelength))
        assertEquals(1f, remoteWaveEnvelope(200f, 400f, wavelength), 0.0001f)
        // Inside the 1.5-wavelength taper the wave is lower than full height.
        assertTrue(remoteWaveEnvelope(380f, 400f, wavelength) < 0.5f)
    }

    @Test fun `caption prefers the segment then the numbered chapter`() {
        assertEquals("Intro", timelineCaption(chapters, segments, 60_000L, 6_000_000L))
        assertEquals("Chapter 2 · The Heist", timelineCaption(chapters, segments, 700_000L, 6_000_000L))
        assertEquals("Chapter 3", timelineCaption(chapters, segments, 2_000_000L, 6_000_000L))
        assertEquals("Credits", timelineCaption(chapters, segments, 5_900_000L, 6_000_000L))
        assertNull(timelineCaption(emptyList(), emptyList(), 10L, 6_000_000L))
    }

    @Test fun `a single chapter shows only its title`() {
        assertEquals("Movie", timelineCaption(listOf(RemoteChapter(0L, "Movie")), emptyList(), 10L, 100_000L))
    }

    @Test fun `scrub snaps to a nearby chapter or segment end only`() {
        val tolerance = 5_000L
        assertEquals(95_000L, snapToTimeline(97_000L, 6_000_000L, chapters, segments, tolerance))
        assertEquals(600_000L, snapToTimeline(596_000L, 6_000_000L, chapters, segments, tolerance))
        assertEquals(300_000L, snapToTimeline(300_000L, 6_000_000L, chapters, segments, tolerance))
        // Chapter 0 and open-ended segment ends are not magnets.
        assertEquals(1_000L, snapToTimeline(1_000L, 6_000_000L, chapters, segments, tolerance))
    }

    @Test fun `snap tolerance scales with length within bounds`() {
        assertEquals(1_500L, snapToleranceMs(60_000L))
        assertEquals(45_000L, snapToleranceMs(6_000_000L))
    }

    @Test fun `timeline parse keeps valid sorted entries and open-ended segments`() {
        val json = JSONObject("""
            {"type":"timeline","playbackId":"p1","currentItemId":"i1",
             "chapters":[{"startMs":312000},{"startMs":0,"title":"  Opening  "},{"startMs":-5},{"title":"x"}],
             "segments":[{"type":"outro","startMs":5400000,"endMs":null},{"type":"intro","startMs":30000,"endMs":95000},
                         {"type":"ad","startMs":1,"endMs":2},{"type":"recap","startMs":50,"endMs":40}]}
        """.trimIndent())
        val timeline = parseTvTimeline(json)
        assertEquals("p1", timeline.playbackId)
        assertEquals("i1", timeline.currentItemId)
        assertEquals(listOf(TvChapter(0L, "Opening"), TvChapter(312_000L)), timeline.chapters)
        assertEquals(listOf(TvSkipSegment("intro", 30_000L, 95_000L), TvSkipSegment("outro", 5_400_000L, null)), timeline.segments)
    }

    @Test fun `timeline parse tolerates missing lists`() {
        val timeline = parseTvTimeline(JSONObject("""{"type":"timeline"}"""))
        assertTrue(timeline.chapters.isEmpty() && timeline.segments.isEmpty())
        assertNull(timeline.playbackId)
    }
}
