package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.remote.RemoteQueueItem
import com.playbridge.sender.cast.remote.RemoteUiState

sealed interface EpisodeWatchStatus {
    data object Neutral : EpisodeWatchStatus
    data class InProgress(val progress: Float) : EpisodeWatchStatus
    data object Watched : EpisodeWatchStatus
}

internal fun formatEpisodeBadge(season: Int?, episode: Int?, index: Int): String = when {
    season != null && episode != null -> "S$season·E$episode"
    episode != null -> "E$episode"
    else -> "${index + 1}"
}

internal fun resolveEpisodeWatchStatus(item: RemoteQueueItem, state: RemoteUiState): EpisodeWatchStatus {
    val queue = state.queue
    val current = if (queue?.currentItemId != null) item.itemId == queue.currentItemId else item.index == queue?.currentIndex
    val playback = state.playback ?: return EpisodeWatchStatus.Neutral
    if (!current || playback.durationMs <= 0) return EpisodeWatchStatus.Neutral
    val fraction = (playback.displayedPositionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
    return when {
        fraction >= 0.9f -> EpisodeWatchStatus.Watched
        fraction >= 0.01f -> EpisodeWatchStatus.InProgress(fraction)
        else -> EpisodeWatchStatus.Neutral
    }
}
