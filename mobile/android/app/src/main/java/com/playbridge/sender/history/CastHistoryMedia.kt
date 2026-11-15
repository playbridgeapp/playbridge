package com.playbridge.sender.history

import com.playbridge.sender.cast.DetectedVideo
import com.playbridge.shared.protocol.decodePlaylistPayloadJson

/** What the existing cast paths need to play a history entry again. */
data class ReplayMedia(
    /** The media; a playlist carries its items and a `playlist://` sentinel URL. */
    val video: DetectedVideo,
    /** Subtitle links of a single item, passed the way the cast sheet passes them. */
    val subtitleUrls: List<String>,
    /** The same subtitles as detected items, for opening the cast sheet. */
    val subtitleVideos: List<DetectedVideo>,
)

/**
 * Turns a stored replay plan into the media the cast paths take. The saved position and playlist
 * item ride on [DetectedVideo.startPositionMs] and [DetectedVideo.startIndex]; the cast paths for
 * PlayBridge TVs and external receivers read them from there. [seek] false drops the position
 * (the target cannot start part-way) but keeps the playlist item.
 */
fun CastHistoryReplay.toReplayMedia(id: String, startedAtMs: Long, seek: Boolean = true): ReplayMedia {
    val position = if (seek) startPositionMs else 0L
    val items = source.playlistPayloadJson?.let { decodePlaylistPayloadJson(it)?.items }
    val video = DetectedVideo(
        url = if (items != null) "playlist://history-$id" else source.url,
        title = source.title,
        contentType = source.contentType,
        headers = source.headers,
        mediaKind = source.mediaKind,
        detectedBy = "history",
        timestamp = startedAtMs,
        isPlayable = if (items != null) true else null,
        // The stored items may still carry the position of the first cast; only this plan's counts.
        playlistPayload = items?.map { it.copy(start_position_ms = null) },
        startPositionMs = position,
        startIndex = if (items != null) startIndex.coerceIn(0, items.lastIndex) else 0,
    )
    val subtitleUrls = if (items == null) source.subtitles else emptyList()
    return ReplayMedia(
        video = video,
        subtitleUrls = subtitleUrls,
        subtitleVideos = subtitleUrls.map {
            DetectedVideo(url = it, mediaKind = "subtitle", detectedBy = "history", timestamp = startedAtMs)
        },
    )
}
