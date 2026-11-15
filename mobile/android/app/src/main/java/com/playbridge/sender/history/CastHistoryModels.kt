package com.playbridge.sender.history

import com.playbridge.sender.data.history.CastHistoryEntity
import com.playbridge.shared.protocol.decodePlaylistPayloadJson
import java.net.URI

/** What the history list should say about an entry. */
enum class CastHistoryStatus {
    /** The receiver reported failure, or stopped before anything played. */
    FAILED,
    /** A live stream: no position, only time watched. */
    LIVE,
    FINISHED,
    /** A position worth resuming from. */
    IN_PROGRESS,
    /** Playback was reported but never got meaningfully into the media. */
    NOT_STARTED,
    /** The receiver never reported a position; do not claim any progress. */
    PROGRESS_UNKNOWN,
}

/** User choices for cast history. Defaults are the first-run values. */
data class CastHistorySettings(
    val enabled: Boolean = true,
    val limit: Int = DEFAULT_LIMIT,
    val rememberPosition: Boolean = true,
    val keepPreviews: Boolean = true,
    /** Casts started by a bridged app or website are not remembered at all. */
    val excludeBridgedApps: Boolean = true,
    /** Probe a remembered link before offering it again. */
    val checkLinks: Boolean = false,
) {
    companion object {
        const val DEFAULT_LIMIT = 50
        val LIMITS = listOf(25, 50, 100, 200, 500)

        /** The nearest allowed limit, so a stray stored or imported value cannot grow the table. */
        fun coerceLimit(value: Int): Int = LIMITS.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_LIMIT
    }
}

/** A cast as the history screen shows it. */
data class CastHistoryEntry(
    val id: String,
    val title: String?,
    val host: String?,
    val pageUrl: String?,
    val receiverName: String?,
    val receiverKind: String,
    val startedAtMs: Long,
    val positionMs: Long,
    val durationMs: Long,
    val positionReported: Boolean,
    val finished: Boolean,
    val live: Boolean,
    val liveWatchedMs: Long,
    val failed: Boolean,
    val failureReason: String?,
    val playlistIndex: Int?,
    val playlistCount: Int?,
    val previewPath: String?,
    val expiresAtMs: Long?,
    val linkCheck: LinkCheckResult?,
    val linkCheckedAtMs: Long?,
    /** A replay source is still stored, so Play again and Resume can work. */
    val canReplay: Boolean,
    /** The wire media kind of the replay source ("video", "audio", ...), when it is still stored. */
    val mediaKind: String? = null,
) {
    val status: CastHistoryStatus
        get() = when {
            failed -> CastHistoryStatus.FAILED
            live -> CastHistoryStatus.LIVE
            finished -> CastHistoryStatus.FINISHED
            !positionReported -> CastHistoryStatus.PROGRESS_UNKNOWN
            positionMs >= MIN_RESUME_MS -> CastHistoryStatus.IN_PROGRESS
            else -> CastHistoryStatus.NOT_STARTED
        }

    /** 0..1 progress through the current item, or null when it would be a guess. */
    val progressFraction: Float?
        get() = if (!positionReported || live || durationMs <= 0) null
        else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** What to say about this cast's link: the one place the stored facts become a [LinkStatus]. */
    fun linkStatus(nowMs: Long, checking: Boolean = false): LinkStatus =
        linkStatus(nowMs, expiresAtMs, linkCheck, linkCheckedAtMs, checking)

    companion object {
        const val MIN_RESUME_MS = 10_000L
    }
}

internal fun CastHistoryEntity.toEntry(canReplay: Boolean, mediaKind: String? = null) = CastHistoryEntry(
    id = id, title = title, host = host, pageUrl = pageUrl, receiverName = receiverName,
    receiverKind = receiverKind, startedAtMs = startedAtMs, positionMs = positionMs,
    durationMs = durationMs, positionReported = positionReported, finished = finished, live = live,
    liveWatchedMs = liveWatchedMs, failed = failed, failureReason = failureReason,
    playlistIndex = playlistIndex, playlistCount = playlistCount, previewPath = previewPath,
    expiresAtMs = expiresAtMs, linkCheck = linkCheck?.let { runCatching { LinkCheckResult.valueOf(it) }.getOrNull() },
    linkCheckedAtMs = linkCheckedAtMs, canReplay = canReplay, mediaKind = mediaKind,
)

/** How to replay an entry: the stored source plus where to start. */
data class CastHistoryReplay(
    val source: CastReplaySource,
    /** 0 unless resuming. Applies to the item at [startIndex]. */
    val startPositionMs: Long,
    val startIndex: Int,
)

/** Where a cast came from, as far as the caller knows. */
data class CastOrigin(
    val pageUrl: String? = null,
    /** The payload's `detected_by`; `page_cast` and `linked_page` mean a website started the cast. */
    val detectedBy: String? = null,
    /** True when a website, not the user's own cast sheet, asked for this cast. */
    val pageInitiated: Boolean = false,
)

/** Everything the history needs to know when a cast starts. */
data class CastStart(
    val id: String,
    val startedAtMs: Long,
    val receiverKind: String,
    val receiverName: String?,
    val source: CastReplaySource?,
    val title: String?,
    val playlistIndex: Int?,
    val playlistCount: Int?,
    val sent: Boolean,
    val origin: CastOrigin = CastOrigin(),
    /** `loadEpoch` of an external load, so only its own statuses are attributed to this cast. */
    val epoch: Long? = null,
    /** The key under which the cast sheet cached a picture, if known. */
    val previewKey: String? = null,
)

internal fun hostOf(url: String?): String? =
    url?.let { runCatching { URI(it).host?.lowercase() }.getOrNull() }?.takeIf { it.isNotBlank() }

/**
 * The link that is playing: the item at [index] of a playlist source, else the source's own URL.
 * Used only to read an expiry from it; the URL is not stored anywhere new.
 */
internal fun CastReplaySource.playingUrl(index: Int?): String {
    val payload = playlistPayloadJson ?: return url
    val items = decodePlaylistPayloadJson(payload)?.items ?: return url
    return items.getOrNull(index ?: 0)?.url?.takeIf { it.isNotBlank() } ?: url
}
