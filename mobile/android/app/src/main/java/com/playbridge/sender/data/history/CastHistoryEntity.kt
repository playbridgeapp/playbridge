package com.playbridge.sender.data.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One remembered cast. [id] is the same UUID as the matching [com.playbridge.sender.diagnostics.CastAttempt]
 * and the [com.playbridge.sender.history.CastReplayStore] entry, so the three can be joined without
 * ever copying a title or URL into the shareable diagnostics.
 */
@Entity(tableName = "cast_history", indices = [Index(value = ["startedAtMs"])])
data class CastHistoryEntity(
    @PrimaryKey val id: String,
    val title: String?,
    /** Host of the page the media came from, else of the media itself. */
    val host: String?,
    val pageUrl: String?,
    val receiverName: String?,
    /** A [com.playbridge.sender.diagnostics.CastAttempt.ReceiverKind] name. */
    val receiverKind: String,
    val startedAtMs: Long,
    val positionMs: Long,
    val durationMs: Long,
    /** False until a receiver actually reported a position or length. */
    val positionReported: Boolean,
    val finished: Boolean,
    val live: Boolean,
    val liveWatchedMs: Long,
    val failed: Boolean,
    val failureReason: String?,
    val playlistIndex: Int?,
    val playlistCount: Int?,
    /** App-private JPEG copy of the picture shown in the cast sheet, if any. */
    val previewPath: String?,
    /** When the stream link stops working, if the URL says so. */
    val expiresAtMs: Long?,
    /** A [com.playbridge.sender.history.LinkCheckResult] name, or null if never checked. */
    val linkCheck: String?,
    val linkCheckedAtMs: Long?,
)
