package com.playbridge.player.player

import com.playbridge.shared.player.PlayerChapter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val TIMELINE_MAX_CHAPTERS = 256
internal const val TIMELINE_MAX_SEGMENTS = 16
internal const val TIMELINE_MAX_TITLE_LENGTH = 200
private val TIMELINE_SEGMENT_TYPES = setOf("intro", "recap", "outro", "preview")

/**
 * The `timeline` receiver frame: chapter markers and skip segments for the current
 * item. Hand-written JSON (not a generated protobuf model); bounds match `Timeline`
 * in protocol/asyncapi.yaml and the desktop receiver.
 */
internal fun buildTimelineJson(
    playbackId: String?,
    currentItemId: String?,
    chapters: List<PlayerChapter>,
    segments: List<SkipSegment>,
): String {
    val sortedChapters = chapters.filter { it.startMs >= 0L }.sortedBy { it.startMs }
    val sortedSegments = segments
        .filter {
            it.type in TIMELINE_SEGMENT_TYPES &&
                it.startMs >= 0L &&
                (it.endMs == SkipSegmentFetcher.OPEN_ENDED_MS || it.endMs > it.startMs)
        }
        .sortedBy { it.startMs }
    return buildJsonObject {
        put("type", "timeline")
        if (playbackId != null) put("playbackId", playbackId)
        if (currentItemId != null) put("currentItemId", currentItemId)
        put("chapters", buildJsonArray {
            sortedChapters.take(TIMELINE_MAX_CHAPTERS).forEach { chapter ->
                add(buildJsonObject {
                    put("startMs", chapter.startMs)
                    timelineChapterTitle(chapter.title)?.let { put("title", it) }
                })
            }
        })
        put("segments", buildJsonArray {
            sortedSegments.take(TIMELINE_MAX_SEGMENTS).forEach { segment ->
                add(buildJsonObject {
                    put("type", segment.type)
                    put("startMs", segment.startMs)
                    if (segment.endMs == SkipSegmentFetcher.OPEN_ENDED_MS) {
                        put("endMs", JsonNull)
                    } else {
                        put("endMs", segment.endMs)
                    }
                })
            }
        })
    }.toString()
}

/** Trimmed title capped at [TIMELINE_MAX_TITLE_LENGTH] code points; blank is dropped. */
private fun timelineChapterTitle(raw: String?): String? {
    val title = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (title.codePointCount(0, title.length) <= TIMELINE_MAX_TITLE_LENGTH) return title
    return title.substring(0, title.offsetByCodePoints(0, TIMELINE_MAX_TITLE_LENGTH)).trimEnd()
}

/** Buffered position for `status.buffered`: kept within [positionMs, durationMs]. */
internal fun reportedBufferedMs(bufferedMs: Long, positionMs: Long, durationMs: Long): Long? {
    if (durationMs <= 0L) return null
    val floor = positionMs.coerceIn(0L, durationMs)
    return bufferedMs.coerceIn(floor, durationMs)
}
