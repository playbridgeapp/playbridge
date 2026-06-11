package com.playbridge.sender.connection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressRulesTest {

    // ── isWatched ───────────────────────────────────────────────────────────

    @Test
    fun watchedAtNinetyPercent() {
        val dur = 40 * 60_000L // 40 min episode
        assertTrue(ProgressRules.isWatched(positionMs = (dur * 0.90).toLong(), durationMs = dur))
        assertTrue(ProgressRules.isWatched(positionMs = dur, durationMs = dur))
    }

    @Test
    fun notWatchedBelowThreshold() {
        val dur = 40 * 60_000L
        assertFalse(ProgressRules.isWatched(positionMs = (dur * 0.89).toLong(), durationMs = dur))
        assertFalse(ProgressRules.isWatched(positionMs = 0L, durationMs = dur))
    }

    @Test
    fun shortOrUnknownDurationsNeverCountAsWatched() {
        // Trailers / bogus renderer durations below the 5-minute floor.
        assertFalse(ProgressRules.isWatched(positionMs = 4 * 60_000L, durationMs = 4 * 60_000L))
        assertFalse(ProgressRules.isWatched(positionMs = 1_000L, durationMs = 0L))
        assertFalse(ProgressRules.isWatched(positionMs = 1_000L, durationMs = -1L))
    }

    // ── finishedOnAdvance ───────────────────────────────────────────────────

    @Test
    fun autoAdvanceAtEndCountsAsFinished() {
        val dur = 40 * 60_000L
        assertTrue(ProgressRules.finishedOnAdvance(lastPositionMs = dur - 2_000L, lastDurationMs = dur))
        assertTrue(ProgressRules.finishedOnAdvance(lastPositionMs = (dur * 0.80).toLong(), lastDurationMs = dur))
    }

    @Test
    fun manualSkipMidEpisodeIsNotFinished() {
        val dur = 40 * 60_000L
        assertFalse(ProgressRules.finishedOnAdvance(lastPositionMs = (dur * 0.30).toLong(), lastDurationMs = dur))
        assertFalse(ProgressRules.finishedOnAdvance(lastPositionMs = (dur * 0.79).toLong(), lastDurationMs = dur))
    }

    @Test
    fun unknownDurationOnAdvanceIsConservativelyNotFinished() {
        assertFalse(ProgressRules.finishedOnAdvance(lastPositionMs = 30 * 60_000L, lastDurationMs = 0L))
    }

    // ── titlesMatch ─────────────────────────────────────────────────────────

    @Test
    fun missingTitlesAreNotAMismatch() {
        assertTrue(ProgressRules.titlesMatch(null, "The Boys S1E1"))
        assertTrue(ProgressRules.titlesMatch("The Boys S1E1", null))
        assertTrue(ProgressRules.titlesMatch("", ""))
    }

    @Test
    fun containmentEitherWayMatches() {
        assertTrue(ProgressRules.titlesMatch("The Boys S1E1 - The Name of the Game", "The Boys S1E1"))
        assertTrue(ProgressRules.titlesMatch("The Boys S1E1", "The Boys S1E1 - The Name of the Game"))
    }

    @Test
    fun differentTitlesVeto() {
        assertFalse(ProgressRules.titlesMatch("Some Other Movie", "The Boys S1E1"))
    }

    // ── isForwardProgress ───────────────────────────────────────────────────

    @Test
    fun noStoredProgressIsForward() {
        assertTrue(ProgressRules.isForwardProgress(1, 1, null, null))
        assertTrue(ProgressRules.isForwardProgress(1, 1, 1, null))
    }

    @Test
    fun laterEpisodeAndSeasonAreForward() {
        assertTrue(ProgressRules.isForwardProgress(1, 2, 1, 1))   // next episode
        assertTrue(ProgressRules.isForwardProgress(2, 1, 1, 8))   // next season
    }

    @Test
    fun sameOrEarlierNeverRegresses() {
        assertFalse(ProgressRules.isForwardProgress(1, 1, 1, 1))  // same episode (rewatch)
        assertFalse(ProgressRules.isForwardProgress(1, 1, 1, 5))  // jumped back
        assertFalse(ProgressRules.isForwardProgress(1, 9, 2, 1))  // earlier season
    }
}
