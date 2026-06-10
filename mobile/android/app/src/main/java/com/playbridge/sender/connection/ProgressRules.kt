package com.playbridge.sender.connection

/**
 * Pure decision rules for automatic watch-progress tracking — kept free of Android
 * dependencies so [PlaybackProgressTracker] behavior is unit-testable.
 */
internal object ProgressRules {

    /** Fraction of the duration that counts as "watched" (Trakt/Plex use 85–95%). */
    const val WATCHED_FRACTION = 0.90

    /** Ignore "durations" shorter than this — trailers, bogus renderer values. */
    const val MIN_TRACK_DURATION_MS = 5 * 60_000L

    /**
     * Fraction that counts as "finished" when the playlist advances past an item.
     * Looser than [WATCHED_FRACTION]: auto-advance fires at the real end, but a manual
     * "next episode" mid-episode must NOT mark the skipped episode watched.
     */
    const val ADVANCE_WATCHED_FRACTION = 0.80

    /** Position past [WATCHED_FRACTION] of a plausible duration counts as watched. */
    fun isWatched(positionMs: Long, durationMs: Long): Boolean =
        durationMs >= MIN_TRACK_DURATION_MS && positionMs >= (durationMs * WATCHED_FRACTION).toLong()

    /**
     * Whether an item the playlist just advanced past actually finished, judged by the
     * last position observed while it was current. Unknown duration → false
     * (conservative: we can't tell auto-advance from a manual skip).
     */
    fun finishedOnAdvance(lastPositionMs: Long, lastDurationMs: Long): Boolean =
        lastDurationMs >= MIN_TRACK_DURATION_MS &&
            lastPositionMs >= (lastDurationMs * ADVANCE_WATCHED_FRACTION).toLong()

    /**
     * Loose title cross-check (same spirit as TvQueueCoordinator's episode matching):
     * only veto when both sides have text and neither contains the other — absence of
     * evidence is not a mismatch.
     */
    fun titlesMatch(reported: String?, expected: String?): Boolean {
        if (reported.isNullOrBlank() || expected.isNullOrBlank()) return true
        return reported.contains(expected) || expected.contains(reported)
    }

    /**
     * Watchlist progress is forward-only: (newSeason, newEpisode) must be strictly
     * ahead of the stored pointer. Missing stored progress always counts as forward.
     */
    fun isForwardProgress(newSeason: Int, newEpisode: Int, oldSeason: Int?, oldEpisode: Int?): Boolean {
        if (oldSeason == null || oldEpisode == null) return true
        return newSeason > oldSeason || (newSeason == oldSeason && newEpisode > oldEpisode)
    }
}
