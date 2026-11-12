package com.playbridge.player.ui.home

import com.playbridge.player.data.PlaybackHistoryItem
import com.playbridge.player.player.resumePosition
import com.playbridge.player.server.WebSocketServer
import com.playbridge.player.ui.player.durationText
import com.playbridge.player.ui.player.timeLeftText

/** Pure logic behind Home, the bookmark actions panel and the pairing card. */

/** The server waits this long for the phone to confirm a pairing code (see WebSocketServer). */
internal const val PAIRING_WINDOW_MS = 60_000L

internal fun resumePositionForHistoryItem(item: PlaybackHistoryItem): Long? =
    resumePosition(item.position, item.duration)

internal fun historyThumbnailCacheKey(item: PlaybackHistoryItem): String =
    "${item.thumbnailUrl}#${item.thumbnailRevision}"

/** Bookmarks are the favourited history entries, most recently played first. */
internal fun bookmarksOf(history: List<PlaybackHistoryItem>): List<PlaybackHistoryItem> =
    history.filter(PlaybackHistoryItem::isFavorite).sortedByDescending { it.timestamp }

/** 6:42, or 1:05:09 once an hour is reached. */
internal fun formatClock(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Fraction played, only when playback would resume (otherwise the tile shows no bar). */
internal fun bookmarkProgress(item: PlaybackHistoryItem): Float? {
    val resume = resumePositionForHistoryItem(item) ?: return null
    return (resume.toFloat() / item.duration).coerceIn(0f, 1f)
}

/** Line under a tile: "Resume at 6:42 · 8 min left", "From the start · 10 min", or null when unknown. */
internal fun bookmarkTileLine(item: PlaybackHistoryItem): String? {
    val resume = resumePositionForHistoryItem(item)
    return when {
        resume != null -> "Resume at ${formatClock(resume)} · ${timeLeftText(item.duration, resume)}"
        item.duration > 0L -> "From the start · ${durationText(item.duration)}"
        else -> null
    }
}

/** Line in the actions panel: "You're at 6:42 · 8 min left", "10 min", or null when unknown. */
internal fun bookmarkPanelLine(item: PlaybackHistoryItem): String? {
    val resume = resumePositionForHistoryItem(item)
    return when {
        resume != null -> "You're at ${formatClock(resume)} · ${timeLeftText(item.duration, resume)}"
        item.duration > 0L -> durationText(item.duration)
        else -> null
    }
}

/** Accessibility label for a tile. */
internal fun bookmarkDescription(item: PlaybackHistoryItem): String {
    val title = item.title ?: "Untitled"
    val resume = resumePositionForHistoryItem(item)
    return if (resume != null) "$title, ${timeLeftText(item.duration, resume)}" else title
}

/** First-run Home: nothing paired yet and nothing bookmarked. */
internal fun isFirstRun(pairedCount: Int, bookmarkCount: Int): Boolean =
    pairedCount == 0 && bookmarkCount == 0

internal fun connectedPhonesLabel(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "1 phone connected"
    else -> "$count phones connected"
}

internal enum class ReadyTone { Ok, Pending, Problem }

internal data class ReadyStatus(val text: String, val tone: ReadyTone)

/** "Is this TV ready?" in plain language, from the real server state. */
internal fun readyStatus(state: WebSocketServer.ConnectionState): ReadyStatus = when (state) {
    is WebSocketServer.ConnectionState.Connected, is WebSocketServer.ConnectionState.Running ->
        ReadyStatus("Ready to cast", ReadyTone.Ok)
    is WebSocketServer.ConnectionState.Starting -> ReadyStatus("Getting ready…", ReadyTone.Pending)
    is WebSocketServer.ConnectionState.Error -> ReadyStatus("Not ready: ${state.message}", ReadyTone.Problem)
    is WebSocketServer.ConnectionState.Stopped -> ReadyStatus("Not running", ReadyTone.Problem)
}

/** "482 915". Codes that are not 6 digits are shown as they are. */
internal fun formatPairingCode(code: String): String =
    if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code

/** Seconds left of the pairing window, rounded up; 0 once it has expired. */
internal fun pairingSecondsLeft(elapsedMs: Long, windowMs: Long = PAIRING_WINDOW_MS): Int =
    (((windowMs - elapsedMs.coerceAtLeast(0L)) + 999L) / 1000L).coerceAtLeast(0L).toInt()

internal fun pairingFractionLeft(elapsedMs: Long, windowMs: Long = PAIRING_WINDOW_MS): Float =
    ((windowMs - elapsedMs.coerceAtLeast(0L)).toFloat() / windowMs).coerceIn(0f, 1f)

internal fun expiresInText(secondsLeft: Int): String =
    if (secondsLeft == 1) "Expires in 1 second" else "Expires in $secondsLeft seconds"
