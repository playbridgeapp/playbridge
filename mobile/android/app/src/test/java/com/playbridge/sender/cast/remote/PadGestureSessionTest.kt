package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.remote.input.PadGestureSession
import com.playbridge.sender.cast.remote.input.PadStep
import com.playbridge.sender.cast.remote.input.PadStepKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PadGestureSessionTest {
    @Test fun holdUsesTheActualCoroutineAtFiveHundredThenOneHundredFiftyMilliseconds() = runTest {
        val session = PadGestureSession(1f)
        val repeats = mutableListOf<Pair<Long, PadStepKind>>()
        val job = backgroundScope.launch { session.repeatHold { steps -> steps.forEach { repeats += testScheduler.currentTime to it.kind } } }
        session.down(0, 0f, 0f)
        assertEquals(PadStepKind.SWIPE, session.move(0, 40f, 0f).single().kind)
        runCurrent()
        advanceTimeBy(499); runCurrent()
        assertTrue(repeats.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(500L to PadStepKind.HOLD), repeats)
        advanceTimeBy(149); runCurrent()
        assertEquals(1, repeats.size)
        advanceTimeBy(1); runCurrent()
        advanceTimeBy(150); runCurrent()
        assertEquals(listOf(500L, 650L, 800L), repeats.map { it.first })
        job.cancelAndJoin()
    }

    @Test fun movementOverTwelveDpWakesAndCancelsASleepingTimerAtBothDensities() = runTest {
        for (density in listOf(2f, 3f)) {
            val session = PadGestureSession(density)
            val repeats = mutableListOf<PadStep>()
            val job = backgroundScope.launch { session.repeatHold { repeats += it } }
            val start = testScheduler.currentTime
            session.down(start, 0f, 0f)
            session.move(start, 40f * density, 0f)
            runCurrent()
            advanceTimeBy(400)
            assertTrue(session.move(start + 400, 53f * density, 0f).isEmpty())
            runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertTrue(repeats.isEmpty())
            job.cancelAndJoin()
        }
    }

    @Test fun exactlyTwelveDpKeepsTheHoldAndTwentyDpStartsItAtBothDensities() = runTest {
        for (density in listOf(2f, 3f)) {
            val session = PadGestureSession(density)
            val repeats = mutableListOf<PadStep>()
            val job = backgroundScope.launch { session.repeatHold { repeats += it } }
            val start = testScheduler.currentTime
            session.down(start, 0f, 0f)
            assertTrue(session.move(start, 19f * density, 0f).isEmpty())
            assertEquals(1, session.move(start, 20f * density, 0f).size)
            runCurrent()
            advanceTimeBy(400)
            session.move(start + 400, 32f * density, 0f)
            runCurrent()
            advanceTimeBy(99); runCurrent()
            assertTrue(repeats.isEmpty())
            advanceTimeBy(1); runCurrent()
            assertEquals(PadStepKind.HOLD, repeats.single().kind)
            job.cancelAndJoin()
        }
    }

    @Test fun aNewDragDeadlineReplacesTheSleepingDeadlineInsteadOfRepeatingEarly() = runTest {
        val session = PadGestureSession(1f)
        val repeats = mutableListOf<Long>()
        val job = backgroundScope.launch { session.repeatHold { repeats += testScheduler.currentTime } }
        session.down(0, 0f, 0f)
        session.move(0, 40f, 0f)
        runCurrent()
        advanceTimeBy(200)
        assertEquals(PadStepKind.DRAG, session.move(200, 96f, 0f).single().kind)
        runCurrent()
        advanceTimeBy(499); runCurrent()
        assertTrue(repeats.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(700L), repeats)
        job.cancelAndJoin()
    }

    @Test fun releasingWhileTheTimerSleepsAddsNoArrowAndCancelsTheRepeat() = runTest {
        val session = PadGestureSession(1f)
        val repeats = mutableListOf<PadStep>()
        val job = backgroundScope.launch { session.repeatHold { repeats += it } }
        session.down(0, 0f, 0f)
        session.move(0, 40f, 0f)
        runCurrent()
        advanceTimeBy(499)
        assertTrue(session.up(499, 40f, 0f).isEmpty())
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertTrue(repeats.isEmpty())
        job.cancelAndJoin()
    }

    @Test fun cancellingThePointerCoroutineOrSessionDuringAHoldProducesNoMoreKeys() = runTest {
        for (cancelCoroutine in listOf(false, true)) {
            val session = PadGestureSession(1f)
            val repeats = mutableListOf<PadStep>()
            val job = backgroundScope.launch { session.repeatHold { repeats += it } }
            val start = testScheduler.currentTime
            session.down(start, 0f, 0f)
            session.move(start, 40f, 0f)
            runCurrent()
            advanceTimeBy(500); runCurrent()
            assertEquals(1, repeats.size)
            if (cancelCoroutine) job.cancelAndJoin() else session.cancel()
            runCurrent()
            advanceTimeBy(1_000); runCurrent()
            assertEquals(1, repeats.size)
            session.cancel()
            job.cancelAndJoin()
        }
    }
}
