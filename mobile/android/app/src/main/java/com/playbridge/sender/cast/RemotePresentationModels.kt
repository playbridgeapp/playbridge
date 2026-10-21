package com.playbridge.sender.cast



/** Live playback status synced from the TV via `status` messages. */
data class TvPlaybackStatus(
    val state: String,
    val positionMs: Long,
    val durationMs: Long,
    val title: String?,
    val mediaKind: String = "video",
    /** Absolute position the receiver has buffered up to (`status.buffered`), when reported. */
    val bufferedMs: Long? = null,
)

/** A chapter start from the media file, synced via `timeline` messages. */
data class TvChapter(val startMs: Long, val title: String? = null)

/** An intro/recap/outro/preview span; a null [endMs] runs to the end of the media. */
data class TvSkipSegment(val type: String, val startMs: Long, val endMs: Long?)

/** Chapters and skip segments for one receiver item, synced via `timeline` messages. */
data class TvTimeline(
    val playbackId: String? = null,
    val currentItemId: String? = null,
    val chapters: List<TvChapter> = emptyList(),
    val segments: List<TvSkipSegment> = emptyList(),
)

private val skipSegmentTypes = setOf("intro", "recap", "outro", "preview")

/** Bounded, sorted parse of a `timeline` frame; malformed entries are dropped, never fatal. */
internal fun parseTvTimeline(json: org.json.JSONObject): TvTimeline {
    fun id(key: String) = (json.opt(key) as? String)?.takeIf(String::isNotEmpty)
    fun ms(o: org.json.JSONObject, key: String): Long? =
        (o.opt(key) as? Number)?.toLong()?.takeIf { it >= 0L }
    val chapters = buildList {
        val arr = json.optJSONArray("chapters") ?: return@buildList
        for (i in 0 until minOf(arr.length(), 256)) {
            val o = arr.optJSONObject(i) ?: continue
            val start = ms(o, "startMs") ?: continue
            val title = (o.opt("title") as? String)?.trim()?.take(200)?.takeIf(String::isNotEmpty)
            add(TvChapter(start, title))
        }
    }.sortedBy { it.startMs }.distinctBy { it.startMs }
    val segments = buildList {
        val arr = json.optJSONArray("segments") ?: return@buildList
        for (i in 0 until minOf(arr.length(), 16)) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type").takeIf { it in skipSegmentTypes } ?: continue
            val start = ms(o, "startMs") ?: continue
            val end = if (o.isNull("endMs")) null else ms(o, "endMs") ?: continue
            if (end != null && end <= start) continue
            add(TvSkipSegment(type, start, end))
        }
    }.sortedBy { it.startMs }
    return TvTimeline(id("playbackId"), id("currentItemId"), chapters, segments)
}

/** An available audio or subtitle track on the TV, synced via `tracks` messages. */
data class MediaTrack(
    val id: String,
    val name: String,
    val selected: Boolean,
    val type: String? = null
)

internal val subtitleOffIds = setOf("off", "none", "no", "-1")

/** Use the receiver's own Off ID; Apple receivers omit the row, but accept `none`. */
internal fun subtitleOffTrack(tracks: List<MediaTrack>): MediaTrack =
    tracks.firstOrNull { it.id.lowercase() in subtitleOffIds }
        ?: MediaTrack(id = "none", name = "Off", selected = tracks.none { it.selected })

/** TV player settings synced via `player_settings` messages. */
data class TvPlayerSettings(
    val speed: Float = 1.0f,
    val scaling: String = "Fit",
    val audioBoost: Boolean = false,
    val subtitleOffsetMs: Long = 0L,
    val engine: String = "",
    val qualityMaxHeight: Int = 0,
    val currentVideoHeight: Int = 0,
    val isLive: Boolean = false,
    val isSeekable: Boolean = true,
    val speedAvailable: Boolean = true,
    val scalingAvailable: Boolean = true,
    val audioBoostAvailable: Boolean = true,
    val qualityAvailable: Boolean = false,
    val loop: Boolean? = null,
)
