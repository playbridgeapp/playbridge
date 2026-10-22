package com.playbridge.player.player

import android.view.KeyEvent
import com.playbridge.player.ui.player.ActiveOverlay
import com.playbridge.player.ui.player.PlayerControlsState
import com.playbridge.shared.player.PlayerChapter

/**
 * Grace window at the start of a chapter: pressing "Previous chapter" within this
 * many ms of the current chapter's start jumps to the chapter before it; past this
 * threshold it restarts the current chapter. Matches `desktop/lib/chapter_navigation.dart`.
 */
internal const val CHAPTER_RESTART_THRESHOLD_MS = 5_000L

/**
 * Minimum distance ahead for a "Next chapter" target so a press right before a
 * boundary advances past it instead of landing on the boundary playback is about to cross.
 * Matches `desktop/lib/chapter_navigation.dart`.
 */
internal const val CHAPTER_LEAD_MS = 1_000L

internal const val LAST_CHAPTER_OSD = "Last chapter"

internal data class ChapterTarget(
    val number: Int,
    val chapter: PlayerChapter,
)

internal data class SegmentFractionRange(
    val startFraction: Float,
    val endFraction: Float,
)

internal enum class ChapterKeyAction {
    NONE,
    NEXT_CHAPTER,
    PREV_CHAPTER,
    SEEK_FORWARD_FALLBACK,
    SEEK_BACKWARD_FALLBACK,
}

/**
 * Picks the chapter to jump to from [positionMs].
 *
 * Requires at least two chapters (a single chapter from 0:00 is not navigable) and
 * assumes [chapters] is sorted by `startMs` ascending (use [normalizedChapters]).
 */
internal fun chapterJumpTarget(
    chapters: List<PlayerChapter>,
    positionMs: Long,
    forward: Boolean,
): ChapterTarget? {
    if (chapters.size < 2) return null
    val pos = positionMs.coerceAtLeast(0L)
    if (forward) {
        val index = chapters.indexOfFirst { it.startMs > pos + CHAPTER_LEAD_MS }
        return if (index < 0) null else ChapterTarget(index + 1, chapters[index])
    }
    val current = chapters.indexOfLast { it.startMs <= pos }
    if (current < 0) {
        return ChapterTarget(1, chapters.first())
    }
    val intoChapter = pos - chapters[current].startMs
    val index = if (intoChapter > CHAPTER_RESTART_THRESHOLD_MS) {
        current
    } else {
        (current - 1).coerceIn(0, chapters.lastIndex)
    }
    return ChapterTarget(index + 1, chapters[index])
}

/**
 * Short OSD/seek-bar label for a chapter jump: `"Chapter 3 · The Heist"` or `"Chapter 3"`.
 */
internal fun chapterLabel(target: ChapterTarget): String {
    val title = timelineChapterTitle(target.chapter.title)
        ?.takeUnless { isGenericChapterTitle(it, target.number) }
    return if (title != null) {
        "Chapter ${target.number} · $title"
    } else {
        "Chapter ${target.number}"
    }
}

/**
 * Muxers often name chapters "Chapter 2" / "Chapter 02"; repeating that after the
 * number would read "Chapter 2 · Chapter 2".
 */
internal fun isGenericChapterTitle(title: String, number: Int): Boolean {
    val parts = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    return parts.size == 2 && parts[0] == "chapter" && parts[1].toIntOrNull() == number
}

/**
 * Caption for the chapter containing [positionMs], or `null` when before the first
 * chapter or when no chapters are present. Single-chapter files return only the
 * normalized title (or `null` if untitled).
 */
internal fun chapterCaptionAt(
    chapters: List<PlayerChapter>,
    positionMs: Long,
): String? {
    val index = chapters.indexOfLast { it.startMs <= positionMs }
    if (index < 0) return null
    val chapter = chapters[index]
    if (chapters.size < 2) {
        return timelineChapterTitle(chapter.title)
    }
    return chapterLabel(ChapterTarget(index + 1, chapter))
}

internal fun skipSegmentCaptionLabel(type: String): String = when (type) {
    "intro" -> "Intro"
    "recap" -> "Recap"
    "outro" -> "Credits"
    "preview" -> "Preview"
    else -> type.replaceFirstChar(Char::uppercaseChar)
}

internal fun activeSkipSegmentAt(
    segments: List<SkipSegment>,
    positionMs: Long,
    durationMs: Long,
): SkipSegment? = segments.firstOrNull { segment ->
    val openEnded = segment.endMs == SkipSegmentFetcher.OPEN_ENDED_MS
    positionMs >= segment.startMs &&
        if (openEnded) {
            durationMs <= 0L || positionMs <= durationMs
        } else {
            positionMs < segment.endMs
        }
}

/**
 * Caption for the seek bar at [positionMs]: an active skip segment wins (`Intro`,
 * `Recap`, `Credits`, `Preview`), otherwise falls back to [chapterCaptionAt].
 */
internal fun timelineCaption(
    chapters: List<PlayerChapter>,
    segments: List<SkipSegment>,
    positionMs: Long,
    durationMs: Long,
): String? {
    activeSkipSegmentAt(segments, positionMs, durationMs)?.let {
        return skipSegmentCaptionLabel(it.type)
    }
    return chapterCaptionAt(chapters, positionMs)
}

/**
 * Fractional positions `(0f, 1f)` along the seek bar where chapter gaps should be drawn.
 * Requires at least 2 chapters and a positive duration; chapters at `0L` or `>= durationMs`
 * do not produce a notch.
 */
internal fun chapterFractions(
    chapters: List<PlayerChapter>,
    durationMs: Long,
): List<Float> {
    if (chapters.size < 2 || durationMs <= 0L) return emptyList()
    return chapters.mapNotNull { chapter ->
        if (chapter.startMs in 1L until durationMs) {
            chapter.startMs.toFloat() / durationMs.toFloat()
        } else {
            null
        }
    }
}

/**
 * Clamped `[0f, 1f]` ranges along the seek bar for skip segments. Open-ended segments
 * extend to `1f` (`durationMs`).
 */
internal fun segmentFractions(
    segments: List<SkipSegment>,
    durationMs: Long,
): List<SegmentFractionRange> {
    if (durationMs <= 0L) return emptyList()
    return segments.mapNotNull { segment ->
        val start = segment.startMs.coerceIn(0L, durationMs)
        val rawEnd = if (segment.endMs == SkipSegmentFetcher.OPEN_ENDED_MS) durationMs else segment.endMs
        val end = rawEnd.coerceIn(0L, durationMs)
        if (end > start) {
            SegmentFractionRange(
                startFraction = start.toFloat() / durationMs.toFloat(),
                endFraction = end.toFloat() / durationMs.toFloat(),
            )
        } else {
            null
        }
    }
}

internal data class ChapterPickerRow(
    val index: Int,
    val number: Int,
    val label: String,
    val startMs: Long,
    val formattedStartTime: String,
    val isCurrent: Boolean,
)

/**
 * 0-based index of the chapter containing [positionMs], or `-1` when before the first
 * chapter or when [chapters] is empty.
 */
internal fun currentChapterIndex(
    chapters: List<PlayerChapter>,
    positionMs: Long,
): Int {
    if (chapters.isEmpty()) return -1
    val pos = positionMs.coerceAtLeast(0L)
    return chapters.indexOfLast { it.startMs <= pos }
}

/**
 * Builds the rows for the full-controls chapter picker overlay.
 */
internal fun buildChapterPickerRows(
    chapters: List<PlayerChapter>,
    positionMs: Long,
): List<ChapterPickerRow> {
    val activeIndex = currentChapterIndex(chapters, positionMs)
    return chapters.mapIndexed { index, chapter ->
        val number = index + 1
        ChapterPickerRow(
            index = index,
            number = number,
            label = chapterLabel(ChapterTarget(number, chapter)),
            startMs = chapter.startMs,
            formattedStartTime = com.playbridge.player.ui.player.formatDuration(chapter.startMs),
            isCurrent = index == activeIndex,
        )
    }
}

internal fun canJumpChapters(
    state: PlayerControlsState,
    isStillWatchingPrompting: Boolean = false,
    isImageMedia: Boolean = false,
): Boolean {
    if (isImageMedia || isStillWatchingPrompting) return false
    if (state.playbackTransitionMessage != null || state.prePlayMetadata != null) return false
    if (state.activeOverlay != ActiveOverlay.NONE) return false
    if (state.capabilities.isLive || !state.capabilities.isSeekable) return false
    return state.chapters.size >= 2
}

/**
 * Pure key-routing decision for chapter navigation and skip-key fallbacks.
 */
internal fun resolveChapterKeyAction(
    keyCode: Int,
    state: PlayerControlsState,
    isStillWatchingPrompting: Boolean = false,
    isImageMedia: Boolean = false,
): ChapterKeyAction {
    if (isImageMedia || isStillWatchingPrompting) return ChapterKeyAction.NONE
    if (state.playbackTransitionMessage != null || state.prePlayMetadata != null) return ChapterKeyAction.NONE
    if (state.activeOverlay != ActiveOverlay.NONE) return ChapterKeyAction.NONE
    if (state.capabilities.isLive || !state.capabilities.isSeekable) return ChapterKeyAction.NONE

    val hasMultipleChapters = state.chapters.size >= 2
    return when (keyCode) {
        KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_PAGE_DOWN ->
            if (hasMultipleChapters) ChapterKeyAction.NEXT_CHAPTER else ChapterKeyAction.NONE

        KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_PAGE_UP ->
            if (hasMultipleChapters) ChapterKeyAction.PREV_CHAPTER else ChapterKeyAction.NONE

        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD ->
            if (hasMultipleChapters) ChapterKeyAction.NEXT_CHAPTER else ChapterKeyAction.SEEK_FORWARD_FALLBACK

        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD ->
            if (hasMultipleChapters) ChapterKeyAction.PREV_CHAPTER else ChapterKeyAction.SEEK_BACKWARD_FALLBACK

        else -> ChapterKeyAction.NONE
    }
}
