package com.playbridge.sender.cast.remote.input

import com.playbridge.sender.cast.remote.RemoteKey
import com.playbridge.sender.cast.remote.RemoteRepeatPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePadGesturePolicyTest {
    private class Clock { var now = 0L }
    private fun policy(clock: Clock) = PadGesturePolicy({ clock.now })

    @Test fun directionUsesTheDominantAxisAndATiePrefersHorizontal() {
        assertEquals(RemoteKey.Right.wireValue, swipe(30f, 10f))
        assertEquals(RemoteKey.Left.wireValue, swipe(-30f, 10f))
        assertEquals(RemoteKey.Down.wireValue, swipe(10f, 30f))
        assertEquals(RemoteKey.Up.wireValue, swipe(10f, -30f))
        assertEquals(RemoteKey.Right.wireValue, swipe(30f, 30f))
        assertEquals(RemoteKey.Left.wireValue, swipe(-30f, -30f))
    }

    @Test fun movementInsideTheSlopIsNotASwipeAndASecondMoveDoesNotRepeat() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        assertTrue(pad.move(0f, -23.9f).isEmpty())
        val crossed = pad.move(0f, -24f)
        assertEquals(PadStepKind.SWIPE, crossed.single().kind)
        assertEquals(RemoteKey.Up.wireValue, crossed.single().key)
        assertTrue(pad.move(0f, -120f).isEmpty())
    }

    @Test fun tapAndSwipeAreExclusiveAndASlowPressIsNeither() {
        val clock = Clock()
        val tap = policy(clock)
        tap.down(10f, 10f)
        clock.now = 200
        val ok = tap.up(18f, 14f)
        assertEquals(PadStep(RemoteKey.Ok.wireValue, PadStepKind.TAP, null), ok.single())
        clock.now = 5_000
        assertTrue(tap.tick().isEmpty())

        val late = policy(clock)
        clock.now = 0
        late.down(0f, 0f)
        clock.now = PadGesturePolicy.TAP_TIMEOUT_MS + 1
        assertTrue(late.up(2f, 2f).isEmpty())

        val swipe = policy(clock)
        clock.now = 0
        swipe.down(0f, 0f)
        clock.now = 200
        val arrow = swipe.move(0f, -40f)
        clock.now = 400
        val released = swipe.up(0f, -50f)
        assertEquals(PadStepKind.SWIPE, arrow.single().kind)
        assertTrue(released.none { it.kind == PadStepKind.TAP })
        assertFalse(swipe.hasPendingFlick())
        assertTrue(swipe.up(0f, -80f).isEmpty())
    }

    @Test fun velocityMapsToACappedStepCount() {
        assertEquals(1, flickStepsForVelocity(1.49f))
        assertEquals(2, flickStepsForVelocity(PadGesturePolicy.FLICK_MIN_VELOCITY_PX_PER_MS))
        assertEquals(3, flickStepsForVelocity(2.7f))
        assertEquals(PadGesturePolicy.MAX_FLICK_STEPS, flickStepsForVelocity(1_000f))
    }

    @Test fun flickIsCappedAtFiveAndAClockJumpDoesNotCatchUp() {
        val clock = Clock()
        val pad = policy(clock)
        val steps = mutableListOf<PadStep>()
        pad.down(0f, 0f)
        clock.now = 16
        steps += pad.move(0f, -2_000f)
        clock.now = 32
        steps += pad.up(0f, -2_000f)
        assertEquals(PadStepKind.SWIPE, steps.first().kind)
        assertEquals(PadStepKind.FLICK, steps.last().kind)
        assertTrue(pad.hasPendingFlick())
        clock.now += 10_000
        val jumped = pad.tick()
        assertEquals(1, jumped.size)
        assertEquals(PadStepKind.FLICK, jumped.single().kind)
        assertTrue(pad.hasPendingFlick())
        while (pad.hasPendingFlick() && steps.size < 10) {
            clock.now += PadGesturePolicy.FLICK_CADENCE_MS
            steps += pad.tick()
        }
        steps += jumped
        assertEquals(PadGesturePolicy.MAX_FLICK_STEPS, steps.size)
        assertTrue(steps.drop(1).all { it.kind == PadStepKind.FLICK && it.key == RemoteKey.Up.wireValue })
        clock.now += 10_000
        assertTrue(pad.tick().isEmpty())
    }

    @Test fun holdRepeatsOnTheStageOneCadenceAndReleaseDoesNotFlick() {
        val clock = Clock()
        val pad = policy(clock)
        pad.down(0f, 0f)
        clock.now = 10
        assertEquals(RemoteKey.Left.wireValue, pad.move(-40f, 0f).single().key)
        clock.now = 10 + RemoteRepeatPolicy.INITIAL_DELAY_MS - 1
        assertTrue(pad.tick().isEmpty())
        clock.now = 10 + RemoteRepeatPolicy.INITIAL_DELAY_MS
        val first = pad.tick().single()
        assertEquals(PadStepKind.HOLD, first.kind)
        assertEquals(RemoteKey.Left.wireValue, first.key)
        clock.now = 10 + RemoteRepeatPolicy.INITIAL_DELAY_MS + RemoteRepeatPolicy.CADENCE_MS - 1
        assertTrue(pad.tick().isEmpty())
        clock.now = 10 + RemoteRepeatPolicy.INITIAL_DELAY_MS + RemoteRepeatPolicy.CADENCE_MS
        assertEquals(PadStepKind.HOLD, pad.tick().single().kind)
        clock.now += 20
        assertTrue(pad.up(-80f, 0f).isEmpty())
        assertFalse(pad.hasPendingFlick())
        clock.now += 10_000
        assertTrue(pad.tick().isEmpty())
    }

    @Test fun cancelAndANewTouchDropFlickAndHold() {
        val clock = Clock()
        val flick = policy(clock)
        flick.down(0f, 0f)
        clock.now = 16
        flick.move(80f, 0f)
        clock.now = 32
        flick.up(2_000f, 0f)
        assertTrue(flick.hasPendingFlick())
        flick.cancel()
        clock.now += 10_000
        assertFalse(flick.hasPendingFlick())
        assertTrue(flick.tick().isEmpty())

        val replaced = policy(clock)
        clock.now = 0
        replaced.down(0f, 0f)
        clock.now = 16
        replaced.move(0f, 80f)
        clock.now = 24
        replaced.up(0f, 2_000f)
        assertTrue(replaced.hasPendingFlick())
        clock.now = 30
        replaced.down(5f, 5f)
        clock.now += 10_000
        assertTrue(replaced.tick().isEmpty())
        replaced.down(5f, 5f)
        clock.now += 40
        assertEquals(PadStepKind.TAP, replaced.up(5f, 6f).single().kind)

        val held = policy(clock)
        clock.now = 0
        held.down(0f, 0f)
        clock.now = 10
        held.move(-40f, 0f)
        held.cancel()
        clock.now = 10 + RemoteRepeatPolicy.INITIAL_DELAY_MS + 5_000
        assertTrue(held.tick().isEmpty())
        assertFalse(held.isArmed())
    }

    private fun swipe(dx: Float, dy: Float): String {
        val pad = policy(Clock())
        pad.down(0f, 0f)
        return pad.move(dx, dy).single().key
    }
}
