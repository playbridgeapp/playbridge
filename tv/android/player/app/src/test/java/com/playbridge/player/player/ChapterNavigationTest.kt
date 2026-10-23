package com.playbridge.player.player

import android.view.KeyEvent
import com.playbridge.player.ui.player.ActiveOverlay
import com.playbridge.player.ui.player.PlaybackCapabilities
import com.playbridge.player.ui.player.PlayerControlsState
import com.playbridge.player.ui.player.formatDuration
import com.playbridge.shared.player.PlayerChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ChapterNavigationTest {
    private val sampleChapters = listOf(
        PlayerChapter(0L, "Cold Open"),
        PlayerChapter(60_000L, "Main Title"),
        PlayerChapter(300_000L, "   "),
        PlayerChapter(600_000L, null),
    )

    @Test
    fun `chapterJumpTarget returns null for empty or single-chapter lists`() {
        assertNull(chapterJumpTarget(emptyList(), 10_000L, forward = true))
        assertNull(chapterJumpTarget(emptyList(), 10_000L, forward = false))
        val single = listOf(PlayerChapter(0L, "Only Chapter"))
        assertNull(chapterJumpTarget(single, 10_000L, forward = true))
        assertNull(chapterJumpTarget(single, 10_000L, forward = false))
    }

    @Test
    fun `forward jump advances to next chapter and skips boundary within 1s lead`() {
        // At 0s -> chapter 2 (60s)
        assertEquals(
            ChapterTarget(2, sampleChapters[1]),
            chapterJumpTarget(sampleChapters, 0L, forward = true),
        )
        // At 58.999s (> 1s before 60s) -> chapter 2 (60s)
        assertEquals(
            ChapterTarget(2, sampleChapters[1]),
            chapterJumpTarget(sampleChapters, 58_999L, forward = true),
        )
        // At 59.000s (exactly 1s before 60s) -> skips 60s and lands on chapter 3 (300s)
        assertEquals(
            ChapterTarget(3, sampleChapters[2]),
            chapterJumpTarget(sampleChapters, 59_000L, forward = true),
        )
        // At 59.500s (0.5s before 60s) -> skips 60s and lands on chapter 3 (300s)
        assertEquals(
            ChapterTarget(3, sampleChapters[2]),
            chapterJumpTarget(sampleChapters, 59_500L, forward = true),
        )
        // In last chapter (at 600s or later) -> null
        assertNull(chapterJumpTarget(sampleChapters, 600_000L, forward = true))
        assertNull(chapterJumpTarget(sampleChapters, 750_000L, forward = true))
    }

    @Test
    fun `backward jump restarts after 5s threshold or steps to previous chapter within 5s`() {
        // Within first 5s of chapter 2 (60s..65s inclusive) -> goes to chapter 1 (0s)
        assertEquals(
            ChapterTarget(1, sampleChapters[0]),
            chapterJumpTarget(sampleChapters, 60_000L, forward = false),
        )
        assertEquals(
            ChapterTarget(1, sampleChapters[0]),
            chapterJumpTarget(sampleChapters, 65_000L, forward = false),
        )
        // Past 5s into chapter 2 (65.001s) -> restarts chapter 2 (60s)
        assertEquals(
            ChapterTarget(2, sampleChapters[1]),
            chapterJumpTarget(sampleChapters, 65_001L, forward = false),
        )
        // Within first 5s of chapter 1 -> clamps to chapter 1
        assertEquals(
            ChapterTarget(1, sampleChapters[0]),
            chapterJumpTarget(sampleChapters, 2_000L, forward = false),
        )
        // Past 5s into chapter 1 -> restarts chapter 1
        assertEquals(
            ChapterTarget(1, sampleChapters[0]),
            chapterJumpTarget(sampleChapters, 12_000L, forward = false),
        )
    }

    @Test
    fun `backward jump before nonzero first chapter lands on chapter 1`() {
        val offsetChapters = listOf(
            PlayerChapter(15_000L, "Prologue"),
            PlayerChapter(90_000L, "Act I"),
        )
        assertEquals(
            ChapterTarget(1, offsetChapters[0]),
            chapterJumpTarget(offsetChapters, 5_000L, forward = false),
        )
    }

    @Test
    fun `chapterLabel and chapterCaptionAt format titled and untitled chapters`() {
        assertEquals(
            "Chapter 1 · Cold Open",
            chapterLabel(ChapterTarget(1, sampleChapters[0])),
        )
        assertEquals(
            "Chapter 3",
            chapterLabel(ChapterTarget(3, sampleChapters[2])),
        )
        assertEquals(
            "Chapter 4",
            chapterLabel(ChapterTarget(4, sampleChapters[3])),
        )

        assertEquals("Chapter 2 · Main Title", chapterCaptionAt(sampleChapters, 120_000L))
        assertEquals("Chapter 3", chapterCaptionAt(sampleChapters, 300_000L))
        assertNull(chapterCaptionAt(emptyList(), 50_000L))

        val singleTitled = listOf(PlayerChapter(0L, "  Feature Film  "))
        assertEquals("Feature Film", chapterCaptionAt(singleTitled, 10_000L))
        val singleUntitled = listOf(PlayerChapter(0L, "   "))
        assertNull(chapterCaptionAt(singleUntitled, 10_000L))

        // Muxer placeholder titles are not repeated after the number.
        assertEquals("Chapter 2", chapterLabel(ChapterTarget(2, PlayerChapter(0L, "Chapter 2"))))
        assertEquals("Chapter 2", chapterLabel(ChapterTarget(2, PlayerChapter(0L, "chapter 02"))))
        assertEquals("Chapter 2 · Chapter 3", chapterLabel(ChapterTarget(2, PlayerChapter(0L, "Chapter 3"))))
    }

    @Test
    fun `timelineCaption prioritizes active skip segment over chapter and handles open-ended segments`() {
        val segments = listOf(
            SkipSegment("recap", 0L, 20_000L),
            SkipSegment("intro", 60_000L, 90_000L),
            SkipSegment("preview", 500_000L, 530_000L),
            SkipSegment("outro", 580_000L, SkipSegmentFetcher.OPEN_ENDED_MS),
        )
        val durationMs = 650_000L

        // Active segments win over chapter caption
        assertEquals("Recap", timelineCaption(sampleChapters, segments, 10_000L, durationMs))
        assertEquals("Intro", timelineCaption(sampleChapters, segments, 60_000L, durationMs))
        assertEquals("Preview", timelineCaption(sampleChapters, segments, 510_000L, durationMs))
        assertEquals("Credits", timelineCaption(sampleChapters, segments, 610_000L, durationMs))

        // At exact end of bounded intro (90s), segment is over and chapter caption takes over
        assertEquals(
            "Chapter 2 · Main Title",
            timelineCaption(sampleChapters, segments, 90_000L, durationMs),
        )
        // Between recap and intro (30s) -> chapter 1 caption
        assertEquals(
            "Chapter 1 · Cold Open",
            timelineCaption(sampleChapters, segments, 30_000L, durationMs),
        )
    }

    @Test
    fun `chapterFractions filters out start at 0 or past duration and requires at least two chapters`() {
        val chapters = listOf(
            PlayerChapter(0L, "Start"),
            PlayerChapter(250_000L, "Quarter"),
            PlayerChapter(500_000L, "Half"),
            PlayerChapter(1_000_000L, "At End"),
            PlayerChapter(1_200_000L, "Past End"),
        )
        assertEquals(
            listOf(0.25f, 0.5f),
            chapterFractions(chapters, 1_000_000L),
        )
        assertTrue(chapterFractions(listOf(PlayerChapter(500_000L, "Only")), 1_000_000L).isEmpty())
        assertTrue(chapterFractions(chapters, 0L).isEmpty())
    }

    @Test
    fun `segmentFractions clamps ranges and maps open-ended segments to end of duration`() {
        val segments = listOf(
            SkipSegment("intro", 100_000L, 250_000L),
            SkipSegment("outro", 800_000L, SkipSegmentFetcher.OPEN_ENDED_MS),
            SkipSegment("recap", 950_000L, 900_000L), // degenerate
            SkipSegment("preview", 1_100_000L, 1_200_000L), // past duration
        )
        val ranges = segmentFractions(segments, 1_000_000L)
        assertEquals(
            listOf(
                SegmentFractionRange(0.1f, 0.25f),
                SegmentFractionRange(0.8f, 1.0f),
            ),
            ranges,
        )
        assertTrue(segmentFractions(segments, 0L).isEmpty())
    }

    @Test
    fun `resolveChapterKeyAction routes dedicated keys and skip fallbacks while leaving D-pad Up and Down untouched`() {
        val withChapters = PlayerControlsState(chapters = sampleChapters)
        val withoutChapters = PlayerControlsState(chapters = emptyList())

        // Channel Up/Down & Page Up/Down
        assertEquals(
            ChapterKeyAction.NEXT_CHAPTER,
            resolveChapterKeyAction(KeyEvent.KEYCODE_CHANNEL_UP, withChapters),
        )
        assertEquals(
            ChapterKeyAction.NEXT_CHAPTER,
            resolveChapterKeyAction(KeyEvent.KEYCODE_PAGE_DOWN, withChapters),
        )
        assertEquals(
            ChapterKeyAction.PREV_CHAPTER,
            resolveChapterKeyAction(KeyEvent.KEYCODE_CHANNEL_DOWN, withChapters),
        )
        assertEquals(
            ChapterKeyAction.PREV_CHAPTER,
            resolveChapterKeyAction(KeyEvent.KEYCODE_PAGE_UP, withChapters),
        )
        assertEquals(
            ChapterKeyAction.NONE,
            resolveChapterKeyAction(KeyEvent.KEYCODE_CHANNEL_UP, withoutChapters),
        )
        assertEquals(
            ChapterKeyAction.NONE,
            resolveChapterKeyAction(KeyEvent.KEYCODE_PAGE_UP, withoutChapters),
        )

        // Media Skip Forward/Backward: jump chapters when >= 2, fall back to +/-10s seek when < 2
        assertEquals(
            ChapterKeyAction.NEXT_CHAPTER,
            resolveChapterKeyAction(KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, withChapters),
        )
        assertEquals(
            ChapterKeyAction.PREV_CHAPTER,
            resolveChapterKeyAction(KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, withChapters),
        )
        assertEquals(
            ChapterKeyAction.SEEK_FORWARD_FALLBACK,
            resolveChapterKeyAction(KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, withoutChapters),
        )
        assertEquals(
            ChapterKeyAction.SEEK_BACKWARD_FALLBACK,
            resolveChapterKeyAction(KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, withoutChapters),
        )

        // Media Next/Previous stay playlist actions (never chapter jumps)
        assertEquals(
            ChapterKeyAction.NONE,
            resolveChapterKeyAction(KeyEvent.KEYCODE_MEDIA_NEXT, withChapters),
        )
        assertEquals(
            ChapterKeyAction.NONE,
            resolveChapterKeyAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS, withChapters),
        )

        // D-pad Up/Down never route to chapter jumps (always preserve volume / focus behavior)
        val seekStripWithChapters = withChapters.copy(isVisible = true, isFullControlsVisible = false)
        assertEquals(
            ChapterKeyAction.NONE,
            resolveChapterKeyAction(KeyEvent.KEYCODE_DPAD_UP, seekStripWithChapters),
        )
        assertEquals(
            ChapterKeyAction.NONE,
            resolveChapterKeyAction(KeyEvent.KEYCODE_DPAD_DOWN, seekStripWithChapters),
        )
    }

    @Test
    fun `buildChapterPickerRows formats labels, start times, and marks current chapter`() {
        val chapters = listOf(
            PlayerChapter(0L, "Cold Open"),
            PlayerChapter(65_000L, "Chapter 02"),
            PlayerChapter(3_723_000L, "Finale"),
        )
        val rows = buildChapterPickerRows(chapters, positionMs = 120_000L)
        assertEquals(
            listOf(
                ChapterPickerRow(
                    index = 0,
                    number = 1,
                    label = "Chapter 1 · Cold Open",
                    startMs = 0L,
                    formattedStartTime = "00:00",
                    isCurrent = false,
                ),
                ChapterPickerRow(
                    index = 1,
                    number = 2,
                    label = "Chapter 2",
                    startMs = 65_000L,
                    formattedStartTime = "01:05",
                    isCurrent = true,
                ),
                ChapterPickerRow(
                    index = 2,
                    number = 3,
                    label = "Chapter 3 · Finale",
                    startMs = 3_723_000L,
                    formattedStartTime = "1:02:03",
                    isCurrent = false,
                ),
            ),
            rows,
        )

        // Before a non-zero first chapter, no row is marked current
        val offsetChapters = listOf(
            PlayerChapter(15_000L, "Prologue"),
            PlayerChapter(90_000L, "Act I"),
        )
        assertEquals(-1, currentChapterIndex(offsetChapters, 5_000L))
        assertTrue(buildChapterPickerRows(offsetChapters, 5_000L).none { it.isCurrent })
        assertEquals(-1, currentChapterIndex(emptyList(), 0L))
    }

    @Test
    fun `chapter keys are suppressed during transitions, overlays, live streams, and non-seekable media`() {
        val base = PlayerControlsState(
            isVisible = true,
            isFullControlsVisible = false,
            chapters = sampleChapters,
        )
        assertFalse(canJumpChapters(base.copy(playbackTransitionMessage = "Preparing…")))
        assertFalse(canJumpChapters(base.copy(activeOverlay = ActiveOverlay.SETTINGS)))
        assertFalse(canJumpChapters(base.copy(activeOverlay = ActiveOverlay.CHAPTERS)))
        assertFalse(canJumpChapters(base.copy(capabilities = PlaybackCapabilities(isLive = true))))
        assertFalse(canJumpChapters(base.copy(capabilities = PlaybackCapabilities(isSeekable = false))))
        assertFalse(canJumpChapters(base, isStillWatchingPrompting = true))
        assertFalse(canJumpChapters(base, isImageMedia = true))
        assertTrue(canJumpChapters(base))
    }

    @Test
    fun `formatDuration uses Locale ROOT ASCII digits`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("05:09", formatDuration(309_000L))
            assertEquals("1:02:03", formatDuration(3_723_000L))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
