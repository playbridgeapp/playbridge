package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.remote.RemotePlayback
import com.playbridge.sender.cast.remote.RemoteQueueItem
import com.playbridge.sender.cast.remote.RemoteProgressKey
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.connection.ProgressRules
import com.playbridge.sender.data.library.PlaybackResumeEntity
import com.playbridge.sender.data.library.WatchlistEntity
import com.playbridge.sender.data.library.WatchlistStatus

sealed interface EpisodeWatchStatus {
    data object Neutral : EpisodeWatchStatus
    data class InProgress(val progress: Float) : EpisodeWatchStatus
    data object Watched : EpisodeWatchStatus
}

/**
 * Resolves progress and watched state from existing tracking entities:
 * - [WatchlistEntity] season/episode progress pointer or completed status
 * - [PlaybackResumeEntity] cross-session resume position
 * - [RemotePlayback] live displayed position for the currently playing item
 *
 * Never infers watched from queue index alone; unknown metadata stays neutral.
 */
internal fun resolveEpisodeWatchStatus(
    item: RemoteQueueItem,
    resume: PlaybackResumeEntity? = null,
    watchlist: WatchlistEntity? = null,
    isCurrent: Boolean = false,
    currentPlayback: RemotePlayback? = null,
): EpisodeWatchStatus {
    // 1. Watchlist completed status or season/episode pointer
    if (watchlist != null) {
        if (watchlist.status == WatchlistStatus.COMPLETED.value) {
            return EpisodeWatchStatus.Watched
        }
        val targetSeason = watchlist.seasonProgress
        val targetEpisode = watchlist.episodeProgress
        val s = item.season
        val e = item.episode
        if (targetSeason != null && targetEpisode != null && s != null && e != null) {
            if (s < targetSeason) {
                return EpisodeWatchStatus.Watched
            }
            if (s == targetSeason && e < targetEpisode) {
                return EpisodeWatchStatus.Watched
            }
        }
    }

    // Current live playback is newer than the throttled cross-session resume point.
    if (isCurrent && currentPlayback != null && currentPlayback.durationMs > 0L) {
        val fraction = (currentPlayback.displayedPositionMs.toFloat() / currentPlayback.durationMs.toFloat()).coerceIn(0f, 1f)
        return when {
            fraction >= ProgressRules.WATCHED_FRACTION.toFloat() -> EpisodeWatchStatus.Watched
            fraction >= 0.01f -> EpisodeWatchStatus.InProgress(fraction)
            else -> EpisodeWatchStatus.Neutral
        }
    }

    if (resume != null && resume.durationMs > 0L) {
        val fraction = (resume.positionMs.toFloat() / resume.durationMs.toFloat()).coerceIn(0f, 1f)
        return when {
            fraction >= ProgressRules.WATCHED_FRACTION.toFloat() -> EpisodeWatchStatus.Watched
            fraction >= 0.02f -> EpisodeWatchStatus.InProgress(fraction)
            else -> EpisodeWatchStatus.Neutral
        }
    }

    // Unknown metadata stays neutral; never infer watched from index alone
    return EpisodeWatchStatus.Neutral
}

internal fun formatEpisodeBadge(season: Int?, episode: Int?, index: Int): String = when {
    season != null && episode != null -> "S$season·E$episode"
    episode != null -> "E$episode"
    else -> "${index + 1}"
}

internal fun buildResumeContentKey(tmdbId: String?, season: Int?, episode: Int?): String? {
    return RemoteProgressKey.from(RemoteQueueItem(0, "", tmdbId = tmdbId, season = season, episode = episode))?.contentKey
}

internal fun resolveEpisodeWatchStatus(item: RemoteQueueItem, state: RemoteUiState): EpisodeWatchStatus {
    val progress = buildResumeContentKey(item.tmdbId, item.season, item.episode)?.let(state.queueProgress::get)
    val queue = state.queue
    val current = if (queue?.currentItemId != null) item.itemId == queue.currentItemId else item.index == queue?.currentIndex
    return resolveEpisodeWatchStatus(item, progress?.resume, progress?.watchlist, current, state.playback)
}
