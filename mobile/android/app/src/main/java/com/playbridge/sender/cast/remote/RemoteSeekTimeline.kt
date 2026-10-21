package com.playbridge.sender.cast.remote

import kotlin.math.abs

/**
 * Shared seek-bar geometry and timeline rules. Android and iOS draw the same wave from
 * these numbers: one soft wave on the played part that eases into a round thumb, flat
 * when paused, still with a shimmer while buffering.
 */
internal object RemoteSeekStyle {
    const val WAVELENGTH_DP = 44f
    const val AMPLITUDE_DP = 3f
    const val STROKE_DP = 3.5f
    const val TRACK_DP = 3f
    const val SEGMENT_DP = 7f
    const val CHAPTER_GAP_DP = 3f
    const val THUMB_RADIUS_DP = 6.5f
    const val THUMB_HELD_RADIUS_DP = 10f
    /** The wave drifts one wavelength in this time while playing. */
    const val DRIFT_MS = 4_000
    const val GROW_MS = 800
    const val FLATTEN_MS = 550
    /** The wave fades to flat over this many wavelengths before the thumb. */
    const val TAPER_WAVELENGTHS = 1.5f
}

/**
 * Height multiplier (0..1) at [x] for a wave drawn on 0..[thumbX]: half a wavelength of
 * ease-in from the start and [RemoteSeekStyle.TAPER_WAVELENGTHS] of ease-out into the thumb.
 */
internal fun remoteWaveEnvelope(x: Float, thumbX: Float, wavelength: Float): Float {
    if (x <= 0f || x >= thumbX || wavelength <= 0f) return 0f
    fun smooth(t: Float): Float { val c = t.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }
    val start = smooth(x / (wavelength * 0.5f))
    val end = smooth((thumbX - x) / (wavelength * RemoteSeekStyle.TAPER_WAVELENGTHS))
    return start * end
}

internal fun remoteSegmentLabel(type: String): String = when (type) {
    "intro" -> "Intro"
    "recap" -> "Recap"
    "outro" -> "Credits"
    "preview" -> "Preview"
    else -> type.replaceFirstChar(Char::uppercaseChar)
}

/** The segment containing [positionMs], if any; open-ended segments run to [durationMs]. */
internal fun segmentAt(segments: List<RemoteSegment>, positionMs: Long, durationMs: Long): RemoteSegment? =
    segments.firstOrNull { positionMs >= it.startMs && positionMs < (it.endMs ?: durationMs) }

/** 1-based number and chapter that [positionMs] falls in, or null before the first or with none. */
internal fun chapterAt(chapters: List<RemoteChapter>, positionMs: Long): Pair<Int, RemoteChapter>? {
    val index = chapters.indexOfLast { it.startMs <= positionMs }
    return if (index < 0) null else (index + 1) to chapters[index]
}

/** Bubble caption under the scrub time: the segment wins, then the chapter. */
internal fun timelineCaption(
    chapters: List<RemoteChapter>, segments: List<RemoteSegment>, positionMs: Long, durationMs: Long,
): String? {
    segmentAt(segments, positionMs, durationMs)?.let { return remoteSegmentLabel(it.type) }
    val (number, chapter) = chapterAt(chapters, positionMs) ?: return null
    if (chapters.size < 2) return chapter.title
    return chapter.title?.let { "Chapter $number · $it" } ?: "Chapter $number"
}

/**
 * Scrub magnet: a target within [toleranceMs] of a chapter start or a segment end is
 * pulled onto it, so landing just after an intro or on a chapter is easy. Returns
 * [targetMs] unchanged when nothing is near.
 */
internal fun snapToTimeline(
    targetMs: Long, durationMs: Long, chapters: List<RemoteChapter>, segments: List<RemoteSegment>, toleranceMs: Long,
): Long {
    if (toleranceMs <= 0L) return targetMs
    val anchors = buildList {
        chapters.forEach { if (it.startMs > 0L) add(it.startMs) }
        segments.forEach { segment -> segment.endMs?.let { if (it < durationMs) add(it) } }
    }
    val nearest = anchors.minByOrNull { abs(it - targetMs) } ?: return targetMs
    return if (abs(nearest - targetMs) <= toleranceMs) nearest else targetMs
}

/** About 1.2% of the media, so the magnet scales with length but stays small. */
internal fun snapToleranceMs(durationMs: Long): Long = (durationMs * 0.012).toLong().coerceIn(1_500L, 45_000L)
