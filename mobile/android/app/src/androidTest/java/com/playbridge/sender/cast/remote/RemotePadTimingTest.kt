package com.playbridge.sender.cast.remote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.ui.RemoteGesturePad
import com.playbridge.sender.cast.remote.ui.padModeToggleBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemotePadTimingTest {
    @get:Rule val compose = createComposeRule()
    private val keys = mutableListOf<String>()
    private val enabled = mutableStateOf(true)
    private val shown = mutableStateOf(true)
    private val padDensity = mutableStateOf(2f)
    private val direction = mutableStateOf(LayoutDirection.Ltr)

    private fun mount() {
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(
                LocalDensity provides Density(padDensity.value),
                LocalLayoutDirection provides direction.value,
                LocalRemoteMotionPolicy provides RemoteMotionPolicy(0f, true, true),
                LocalRemotePreferences provides RemotePreferences(hapticsEnabled = false),
            ) {
                Box(Modifier.size(320.dp)) {
                    if (shown.value) RemoteGesturePad(enabled.value, keys::add, excludeModeToggle = true)
                }
            }
        } }
        compose.onNodeWithTag("remote.gesturePad").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
    }

    private fun advance(ms: Long) = compose.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)

    private fun startHold() {
        compose.onNodeWithTag("remote.gesturePad").performTouchInput {
            down(center)
            moveTo(center + Offset(40f * padDensity.value, 0f), delayMillis = 0)
        }
        advance(0) // Run the queued hold coroutine before advancing its deadline.
        assertEquals(listOf("dpad_right"), keys)
    }

    private fun change(value: MutableState<Boolean>) {
        compose.runOnUiThread { value.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test fun firstHoldIsAtFiveHundredThenRepeatsEveryOneHundredFiftyMilliseconds() {
        mount()
        startHold()
        advance(499)
        assertEquals(1, keys.size)
        advance(1)
        assertEquals(2, keys.size)
        advance(149)
        assertEquals(2, keys.size)
        advance(1)
        assertEquals(3, keys.size)
        advance(150)
        assertEquals(List(4) { "dpad_right" }, keys)
        compose.onNodeWithTag("remote.gesturePad").performTouchInput { up() }
        advance(1_000)
        assertEquals(4, keys.size)
    }

    @Test fun movingOverTwelveDpCancelsTheSleepingTimer() {
        mount()
        startHold()
        advance(400)
        compose.onNodeWithTag("remote.gesturePad").performTouchInput {
            // Align pointer timestamps with the virtual timer; avoid another drag step.
            advanceEventTime(400)
            moveTo(center + Offset(53f * padDensity.value, 0f), delayMillis = 0)
        }
        advance(0)
        advance(1_000)
        assertEquals(listOf("dpad_right"), keys)
        compose.onNodeWithTag("remote.gesturePad").performTouchInput { up() }
    }

    @Test fun disablingThePadDuringHoldCancelsAllFurtherKeys() {
        mount()
        startHold()
        advance(500)
        assertEquals(2, keys.size)
        change(enabled)
        advance(1_000)
        assertEquals(2, keys.size)
        compose.onNodeWithTag("remote.gesturePad").performTouchInput { up() }
    }

    @Test fun removingThePadDuringHoldCancelsAllFurtherKeys() {
        mount()
        startHold()
        advance(500)
        assertEquals(2, keys.size)
        change(shown)
        compose.onNodeWithTag("remote.gesturePad").assertDoesNotExist()
        advance(1_000)
        assertEquals(2, keys.size)
    }

    @Test fun densityChangeCancelsOldHoldAndPreservesDpThresholds() {
        mount()
        startHold()
        advance(200)
        compose.runOnUiThread { padDensity.value = 3f }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        advance(1_000)
        assertEquals(1, keys.size)
        val pad = compose.onNodeWithTag("remote.gesturePad")
        pad.performTouchInput { up() }
        keys.clear()
        pad.performTouchInput {
            down(center)
            moveTo(center + Offset(19f * padDensity.value, 0f), delayMillis = 0)
        }
        advance(0)
        assertTrue(keys.isEmpty())
        pad.performTouchInput { moveTo(center + Offset(20f * padDensity.value, 0f), delayMillis = 0) }
        advance(0)
        assertEquals(listOf("dpad_right"), keys)
        advance(499)
        assertEquals(1, keys.size)
        advance(1)
        assertEquals(2, keys.size)
        pad.performTouchInput { up() }
    }

    @Test fun toggleBoundsNeverStartAGestureEvenWhenDraggedOutOrHeldInRtl() {
        mount()
        for (rtl in listOf(false, true)) {
            compose.runOnUiThread { direction.value = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val pad = compose.onNodeWithTag("remote.gesturePad")
            val bounds = padModeToggleBounds(pad.fetchSemanticsNode().boundsInRoot.width, padDensity.value, rtl)
            pad.performTouchInput { down(bounds.center) }
            advance(1_000)
            pad.performTouchInput { moveTo(center, delayMillis = 0) }
            advance(1_000)
            pad.performTouchInput { up() }
            advance(0)
            assertTrue(keys.isEmpty())
        }
    }
}
