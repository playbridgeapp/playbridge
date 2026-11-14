package com.playbridge.player.preplay

/** "1:10", "12:05" or "1:02:03" for a playback position or length. Negative values read as 0:00. */
internal fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** "Season 1 · Episode 2", or null when this is not an episode. */
internal fun episodeLabel(season: Any?, episode: Any?): String? =
    if (season != null && episode != null) "Season $season · Episode $episode" else null

/** Fraction of the choice window that has passed, 0..1. [remaining] counts down from [window]. */
internal fun choiceWindowProgress(remaining: Int, window: Int): Float =
    if (window <= 0) 1f else ((window - remaining).toFloat() / window).coerceIn(0f, 1f)

/** Seconds the host counts down on the pre-play screen; also the length of the Resume / Start over choice. */
const val PREPLAY_COUNTDOWN_SECONDS = 5
