package com.playbridge.sender.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastPositionTrackerTest {
    private fun playing(pos: Long, dur: Long = 100_000, title: String? = null, epoch: Long? = null) =
        PlaybackSample(SampleState.PLAYING, pos, dur, title = title, epoch = epoch)

    private fun tracker(id: String = "a", title: String? = null, epoch: Long? = null) =
        CastPositionTracker().also { it.begin(id, 0, title, epoch, null, null) }

    @Test
    fun writesFirstReportThenThrottlesUntilTheIntervalPasses() {
        val t = tracker()
        assertEquals(5_000L, t.onSample(playing(5_000), 1_000)!!.progress.positionMs)
        assertNull(t.onSample(playing(6_000), 2_000))
        assertEquals(16_000L, t.onSample(playing(16_000), 12_000)!!.progress.positionMs)
    }

    @Test
    fun pauseWritesImmediatelyAndStopKeepsTheLastRealPosition() {
        val t = tracker()
        t.onSample(playing(40_000), 1_000)
        val paused = t.onSample(PlaybackSample(SampleState.PAUSED, 41_000, 100_000), 2_000)!!
        assertEquals(41_000L, paused.progress.positionMs)
        assertNull(t.onSample(PlaybackSample(SampleState.PAUSED, 45_000, 100_000), 3_000)) // throttled
        val stopped = t.onSample(PlaybackSample(SampleState.STOPPED, 0, 0), 4_000)!!
        assertEquals(45_000L, stopped.progress.positionMs)
        assertEquals(100_000L, stopped.progress.durationMs)
        assertFalse(stopped.progress.finished)
        assertFalse(stopped.progress.failed)
    }

    @Test
    fun reachingTheEndMarksFinishedAndAStopAfterwardsDoesNotUndoIt() {
        val t = tracker()
        val end = t.onSample(playing(96_000), 1_000)!!
        assertTrue(end.progress.finished)
        // The receiver's reset to zero changes nothing worth saving, and never reaches the database.
        assertNull(t.onSample(PlaybackSample(SampleState.STOPPED, 0, 0), 2_000))
        assertNull(t.flush())
    }

    @Test
    fun endRuleIsFivePercentOrTenSecondsOnLongMedia() {
        assertTrue(CastPositionTracker.reachedEnd(95_000, 100_000))
        assertFalse(CastPositionTracker.reachedEnd(55_000, 59_000))
        assertTrue(CastPositionTracker.reachedEnd(7_195_000, 7_200_000))
        assertTrue(CastPositionTracker.reachedEnd(91_000, 100_000)) // under 10 s left on a 100 s video
        assertFalse(CastPositionTracker.reachedEnd(89_000, 100_000))
        assertFalse(CastPositionTracker.reachedEnd(27_000, 30_000)) // short clips only use the 95% rule
        assertFalse(CastPositionTracker.reachedEnd(6_000_000, 7_200_000))
        assertFalse(CastPositionTracker.reachedEnd(0, 0))
    }

    @Test
    fun reportsOfThePreviousCastAreIgnoredUntilTheNewOneShowsUp() {
        val t = CastPositionTracker()
        t.begin("a", 0, "Movie A", null, null, null)
        t.onSample(playing(90_000, 100_000, "Movie A"), 1_000)
        t.begin("b", 5_000, "Movie B", null, null, null)
        // The receiver still reports A for a moment.
        assertNull(t.onSample(playing(91_000, 100_000, "Movie A"), 6_000))
        assertNull(t.onSample(playing(92_000, 100_000, "Movie A"), 20_000))
        val write = t.onSample(playing(1_000, 50_000, "Movie B"), 21_000)!!
        assertEquals("b", write.id)
        assertEquals(1_000L, write.progress.positionMs)
        assertEquals(50_000L, write.progress.durationMs)
    }

    @Test
    fun withoutAKnownTitleAnUnchangedReportIsStaleUntilTheGracePeriod() {
        val t = CastPositionTracker()
        t.begin("a", 0, null, null, null, null)
        t.onSample(playing(30_000), 1_000)
        t.begin("b", 2_000, null, null, null, null)
        assertNull(t.onSample(playing(31_000), 3_000))
        assertNotNull(t.onSample(playing(1_000), 2_000 + CastPositionTracker.STALE_GRACE_MS))
    }

    @Test
    fun aReportForAnotherTitleIsNeverAttributedEvenAfterTheGracePeriod() {
        val t = tracker(title = "Wanted")
        assertNull(t.onSample(playing(5_000, title = "Something else"), 60_000))
    }

    @Test
    fun aClearedReportMakesTheNextOneFresh() {
        val t = CastPositionTracker()
        t.begin("a", 0, null, null, null, null)
        t.onSample(playing(30_000), 1_000)
        t.begin("b", 2_000, null, null, null, null)
        assertNull(t.onSample(null, 2_500))
        assertEquals("b", t.onSample(playing(30_000), 3_000)!!.id)
    }

    @Test
    fun beginHandsBackTheUnsavedProgressOfThePreviousEntry() {
        val t = tracker("a")
        t.onSample(playing(5_000), 1_000)
        t.onSample(playing(6_000), 2_000) // throttled, so unsaved
        val pending = t.begin("b", 3_000, null, null, null, null)!!
        assertEquals("a", pending.id)
        assertEquals(6_000L, pending.progress.positionMs)
    }

    @Test
    fun externalReportsAreMatchedByLoadEpoch() {
        val t = tracker(epoch = 3)
        assertNull(t.onSample(playing(9_000, epoch = 2), 1_000))
        assertEquals(1_000L, t.onSample(playing(1_000, epoch = 3), 2_000)!!.progress.positionMs)
    }

    @Test
    fun liveStreamsCountTimeWatchedAndStoreNoPosition() {
        val t = tracker()
        fun live(at: Long) = t.onSample(PlaybackSample(SampleState.PLAYING, 7_000, 0, isLive = true), at)
        live(0)
        live(5_000)
        val write = live(10_000)
        val p = (write ?: t.flush())!!.progress
        assertTrue(p.live)
        assertEquals(10_000L, p.liveWatchedMs)
        assertEquals(0L, p.positionMs)
        assertFalse(p.finished)
    }

    @Test
    fun aLongGapBetweenLiveReportsIsNotCountedAsWatching() {
        val t = tracker()
        t.onSample(PlaybackSample(SampleState.PLAYING, 1_000, 0, isLive = true), 0)
        val p = t.onSample(PlaybackSample(SampleState.PLAYING, 1_000, 0, isLive = true), 3_600_000)!!.progress
        assertEquals(CastPositionTracker.MAX_LIVE_GAP_MS, p.liveWatchedMs)
    }

    @Test
    fun aReceiverThatNeverReportsPositionIsNeverMarkedAsReporting() {
        val t = tracker()
        assertNull(t.onSample(PlaybackSample(SampleState.PLAYING, 0, 0), 1_000))
        assertNull(t.onSample(PlaybackSample(SampleState.PAUSED, 0, 0), 2_000))
        assertNull(t.onSample(PlaybackSample(SampleState.STOPPED, 0, 0), 3_000))
        assertNull(t.flush())
    }

    @Test
    fun aNewPlaylistItemStartsOverAndOnlyTheLastItemCanFinishTheEntry() {
        val t = CastPositionTracker().also { it.begin("a", 0, null, null, 0, 3) }
        fun item(index: Int, pos: Long) =
            PlaybackSample(SampleState.PLAYING, pos, 100_000, playlistIndex = index, playlistCount = 3)
        val first = t.onSample(item(0, 99_000), 1_000)!!.progress
        assertFalse(first.finished) // more items follow
        val second = t.onSample(item(1, 4_000), 2_000)!!.progress
        assertEquals(1, second.playlistIndex)
        assertEquals(4_000L, second.positionMs)
        assertTrue(t.onSample(item(2, 99_000), 3_000)!!.progress.finished)
    }

    @Test
    fun stoppingBeforeAnythingPlayedIsAFailureAndPlayingClearsIt() {
        val t = tracker()
        val failed = t.onSample(PlaybackSample(SampleState.STOPPED, 0, 0), 1_000)!!.progress
        assertTrue(failed.failed)
        assertEquals(CastPositionTracker.FAIL_STOPPED_EARLY, failed.failureReason)
        val recovered = t.onSample(playing(1_000), 2_000)!!.progress
        assertFalse(recovered.failed)
    }

    @Test
    fun anErrorReportIsAFailure() {
        val t = tracker()
        val p = t.onSample(PlaybackSample(SampleState.ERROR), 1_000)!!.progress
        assertTrue(p.failed)
        assertEquals(CastPositionTracker.FAIL_RECEIVER_ERROR, p.failureReason)
    }

    @Test
    fun aClearedReportAfterPlayingFlushesWithoutFailingOrRegressing() {
        val t = tracker()
        t.onSample(playing(30_000), 1_000)
        t.onSample(playing(35_000), 2_000) // throttled
        val write = t.onSample(null, 3_000)!!
        assertEquals(35_000L, write.progress.positionMs)
        assertFalse(write.progress.failed)
    }

    @Test
    fun nothingIsWrittenWhenNoEntryIsTracked() {
        val t = CastPositionTracker()
        assertNull(t.onSample(playing(1_000), 1))
        t.begin("a", 0, null, null, null, null)
        t.detach("a")
        assertNull(t.onSample(playing(1_000), 2))
        assertNull(t.activeId)
    }
}
