package com.playbridge.sender.cast.remote.input

import com.playbridge.sender.cast.remote.RemoteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePadGesturePolicyTest {
    private class Clock { var now = 0L }
    private fun policy(clock: Clock, density: Float = 1f) =
        PadGesturePolicy.forDensity(clock = { clock.now }, density = density)

    @Test fun directionUsesTheDominantAxisAndATiePrefersHorizontal() {
        assertEquals(RemoteKey.Right.wireValue, swipe(30f, 10f))
        assertEquals(RemoteKey.Left.wireValue, swipe(-30f, 10f))
        assertEquals(RemoteKey.Down.wireValue, swipe(10f, 30f))
        assertEquals(RemoteKey.Up.wireValue, swipe(10f, -30f))
        assertEquals(RemoteKey.Right.wireValue, swipe(30f, 30f))
        assertEquals(RemoteKey.Left.wireValue, swipe(-30f, -30f))
    }

    @Test fun firstStepNeedsTwentyDpAndFurtherStepsNeedFiftySixDp() {
        val pad = policy(Clock())
        pad.down(0f, 0f)
        assertTrue(pad.move(0f, -19.9f).isEmpty())
        assertEquals(PadStep(RemoteKey.Up.wireValue, PadStepKind.SWIPE, PadDirection.Up), pad.move(0f, -20f).single())
        assertTrue(pad.move(0f, -75.9f).isEmpty())
        assertEquals(PadStep(RemoteKey.Up.wireValue, PadStepKind.DRAG, PadDirection.Up), pad.move(0f, -76f).single())
        assertTrue(pad.move(0f, -131.9f).isEmpty())
        assertEquals(RemoteKey.Up.wireValue, pad.move(0f, -132f).single().key)
    }

    @Test fun fastFortyDpSwipeEmitsExactlyOneStep() {
        val clock = Clock()
        val pad = policy(clock)
        val steps = mutableListOf<PadStep>()
        pad.down(0f, 0f)
        clock.now = 1
        steps += pad.move(40f, 0f)
        clock.now = 2
        steps += pad.up(40f, 0f)
        clock.now = 10_000
        steps += pad.tick()
        assertEquals(listOf(PadStep(RemoteKey.Right.wireValue, PadStepKind.SWIPE, PadDirection.Right)), steps)
    }

    @Test fun twentyPlusFiftySixPlusFiftySixDpDragEmitsThreeSteps() {
        val pad = policy(Clock())
        pad.down(0f, 0f)
        val steps = pad.move(20f, 0f) + pad.move(76f, 0f) + pad.move(132f, 0f)
        assertEquals(listOf(PadStepKind.SWIPE, PadStepKind.DRAG, PadStepKind.DRAG), steps.map { it.kind })
        assertTrue(steps.all { it.key == RemoteKey.Right.wireValue })
        assertTrue(pad.up(132f, 0f).isEmpty())
    }

    @Test fun oneMoveOfTwoDragIntervalsEmitsTwoSteps() {
        val pad = policy(Clock())
        pad.down(0f, 0f)
        pad.move(20f, 0f)
        assertEquals(listOf(RemoteKey.Right.wireValue, RemoteKey.Right.wireValue), pad.move(132f, 0f).map { it.key })
        assertTrue(pad.move(187.9f, 0f).isEmpty())
        assertEquals(1, pad.move(188f, 0f).size)
    }

    @Test fun threeHundredDpMoveIsCappedAtThreeWithoutAStationaryBacklog() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        pad.move(20f, 0f)
        val steps = pad.move(320f, 0f)
        assertEquals(3, steps.size)
        assertTrue(steps.all { it.kind == PadStepKind.DRAG })
        assertTrue(pad.move(320f, 0f).isEmpty())
        assertTrue(pad.up(320f, 0f).isEmpty())
        clock.now = 10_000
        assertTrue(pad.tick().isEmpty())

        pad.down(0f, 0f)
        assertTrue(pad.move(300f, 0f).size <= 3)
    }

    @Test fun rightThenDownDragChangesDirectionAtEachStep() {
        val pad = policy(Clock())
        pad.down(0f, 0f)
        assertEquals(PadDirection.Right, pad.move(20f, 0f).single().direction)
        assertEquals(PadDirection.Right, pad.move(76f, 0f).single().direction)
        assertEquals(PadDirection.Down, pad.move(76f, 56f).single().direction)
        assertEquals(PadDirection.Left, pad.move(20f, 56f).single().direction)
    }

    @Test fun diagonalDragUsesDominantAxisDistanceAndHorizontalTies() {
        val pad = policy(Clock())
        pad.down(0f, 0f)
        pad.move(20f, 0f)
        assertTrue(pad.move(60f, 40f).isEmpty())
        assertEquals(PadDirection.Right, pad.move(76f, 56f).single().direction)
    }

    @Test fun stillFingerRepeatsAtFiveHundredMsThenOneHundredFiftyMs() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        clock.now = 10
        pad.move(-40f, 0f)
        assertEquals(510L, pad.holdDeadline())
        clock.now = 509
        assertTrue(pad.tick().isEmpty())
        clock.now = 510
        assertEquals(PadStep(RemoteKey.Left.wireValue, PadStepKind.HOLD, PadDirection.Left), pad.tick().single())
        clock.now = 659
        assertTrue(pad.tick().isEmpty())
        clock.now = 660
        assertEquals(PadStepKind.HOLD, pad.tick().single().kind)
    }

    @Test fun clockJumpEmitsOneHoldStepWithoutCatchingUp() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        pad.move(20f, 0f)
        clock.now = 10_000
        assertEquals(1, pad.tick().size)
        assertEquals(10_150L, pad.holdDeadline())
        assertTrue(pad.tick().isEmpty())
        clock.now = 10_150
        assertEquals(1, pad.tick().size)
    }

    @Test fun jitterWithinTwelveDpKeepsTheHoldTimer() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        pad.move(20f, 0f)
        clock.now = 250
        assertTrue(pad.move(32f, 0f).isEmpty())
        assertEquals(500L, pad.holdDeadline())
        clock.now = 500
        assertEquals(PadStepKind.HOLD, pad.tick().single().kind)
    }

    @Test fun movementBeyondTwelveDpCancelsHoldUntilAnotherStep() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        pad.move(20f, 0f)
        clock.now = 250
        assertTrue(pad.move(32.1f, 0f).isEmpty())
        assertNull(pad.holdDeadline())
        clock.now = 500
        assertTrue(pad.tick().isEmpty())
        assertTrue(pad.move(20f, 0f).isEmpty())
        assertNull(pad.holdDeadline())
        clock.now = 700
        assertEquals(PadStepKind.DRAG, pad.move(76f, 0f).single().kind)
        clock.now = 1199
        assertTrue(pad.tick().isEmpty())
        clock.now = 1200
        assertEquals(PadStepKind.HOLD, pad.tick().single().kind)
    }

    @Test fun slowContinuousDragForOneAndAHalfSecondsNeverHoldRepeats() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        val steps = pad.move(20f, 0f).toMutableList()
        for (time in 50L..1500L step 50L) {
            clock.now = time
            steps += pad.move(20f + time / 10f, 0f)
            assertTrue(pad.tick().isEmpty())
        }
        assertEquals(3, steps.size)
        assertTrue(steps.all { it.kind == PadStepKind.SWIPE || it.kind == PadStepKind.DRAG })
    }

    @Test fun releaseAfterHoldEmitsNothingEvenWithExtraReleaseTravel() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        pad.move(20f, 0f)
        clock.now = 500
        assertEquals(PadStepKind.HOLD, pad.tick().single().kind)
        clock.now = 520
        assertTrue(pad.up(300f, 0f).isEmpty())
        assertNull(pad.holdDeadline())
        clock.now = 10_000
        assertTrue(pad.tick().isEmpty())
    }

    @Test fun tapWithinSlopAndThreeHundredMsStillSendsOk() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(10f, 10f)
        clock.now = 300
        assertEquals(PadStep(RemoteKey.Ok.wireValue, PadStepKind.TAP, null), pad.up(18f, 14f).single())
        assertTrue(pad.tick().isEmpty())
    }

    @Test fun longPressInsideSlopSendsNothing() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        clock.now = 301
        assertTrue(pad.up(2f, 2f).isEmpty())
        pad.down(0f, 0f)
        clock.now += 1000
        assertTrue(pad.tick().isEmpty())
        assertTrue(pad.up(2f, 2f).isEmpty())
    }

    @Test fun swipingBackInsideSlopDoesNotAlsoTap() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        pad.move(20f, 0f)
        clock.now = 100
        assertTrue(pad.move(5f, 0f).isEmpty())
        assertTrue(pad.up(5f, 0f).isEmpty())
    }

    @Test fun cancelAndNewDownClearDragHoldAndTapState() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        pad.move(20f, 0f)
        pad.cancel()
        clock.now = 10_000
        assertTrue(pad.tick().isEmpty())
        assertTrue(pad.move(200f, 0f).isEmpty())
        assertTrue(pad.up(200f, 0f).isEmpty())
        assertNull(pad.holdDeadline())

        pad.down(0f, 0f)
        pad.move(-40f, 0f)
        pad.down(5f, 5f)
        clock.now += 1000
        assertTrue(pad.tick().isEmpty())
        pad.down(5f, 5f)
        clock.now += 40
        assertEquals(PadStepKind.TAP, pad.up(5f, 6f).single().kind)
        pad.down(0f, 0f)
        pad.cancel()
        assertTrue(pad.up(0f, 0f).isEmpty())
    }

    @Test fun holdCapIncludesTheStepAndDragRearmsItsBudget() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        val steps = pad.move(20f, 0f).toMutableList()
        clock.now = 500
        while (pad.holdDeadline() != null) {
            steps += pad.tick()
            clock.now += 150
        }
        assertEquals(100, steps.size)
        assertTrue(pad.tick().isEmpty())
        assertEquals(PadStepKind.DRAG, pad.move(76f, 0f).single().kind)
        clock.now += 500
        assertEquals(PadStepKind.HOLD, pad.tick().single().kind)
    }

    @Test fun densityThreeConvertsSlopStepAndHoldDistanceToPixels() {
        val clock = Clock()
        val pad = policy(clock, density = 3f)
        pad.down(0f, 0f)
        assertTrue(pad.move(59.9f, 0f).isEmpty())
        assertEquals(PadStepKind.SWIPE, pad.move(60f, 0f).single().kind)
        assertTrue(pad.move(227.9f, 0f).isEmpty())
        assertEquals(PadStepKind.DRAG, pad.move(228f, 0f).single().kind)
        assertTrue(pad.move(264f, 0f).isEmpty())
        clock.now = 500
        assertEquals(PadStepKind.HOLD, pad.tick().single().kind)
        assertTrue(pad.move(264.1f, 0f).isEmpty())
        assertNull(pad.holdDeadline())
    }

    @Test fun releaseOutsideSlopDoesNotCreateAnArrowWithoutAMove() {
        val pad = policy(Clock())
        pad.down(0f, 0f)
        assertTrue(pad.up(100f, 0f).isEmpty())
        assertTrue(pad.move(200f, 0f).isEmpty())
    }

    private fun swipe(dx: Float, dy: Float): String {
        val pad = policy(Clock())
        pad.down(0f, 0f)
        return pad.move(dx, dy).single().key
    }
}
