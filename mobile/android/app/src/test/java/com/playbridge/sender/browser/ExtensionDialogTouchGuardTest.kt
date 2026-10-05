package com.playbridge.sender.browser

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionDialogTouchGuardTest {
    private val overlayFlags = listOf(
        MotionEvent.FLAG_WINDOW_IS_OBSCURED,
        MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED,
        MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED,
    )

    @Test fun cleanGestureRemainsUsable() {
        val guard = ExtensionDialogTouchGuard()
        assertTrue(guard.allows(MotionEvent.ACTION_DOWN, 0))
        assertTrue(guard.allows(MotionEvent.ACTION_MOVE, 0))
        assertTrue(guard.allows(MotionEvent.ACTION_UP, 0))
    }

    @Test fun fullyAndPartiallyObscuredDownsBlockTheEntireGesture() {
        for (flags in overlayFlags) {
            val guard = ExtensionDialogTouchGuard()
            assertFalse(guard.allows(MotionEvent.ACTION_DOWN, flags))
            assertFalse(guard.allows(MotionEvent.ACTION_MOVE, 0))
            assertFalse(guard.allows(MotionEvent.ACTION_UP, 0))
        }
    }

    @Test fun overlayAppearingMidGesturePreventsAnUnobscuredUpFromActivating() {
        for (flags in overlayFlags) {
            val guard = ExtensionDialogTouchGuard()
            assertTrue(guard.allows(MotionEvent.ACTION_DOWN, 0))
            assertFalse(guard.allows(MotionEvent.ACTION_MOVE, flags))
            assertFalse(guard.allows(MotionEvent.ACTION_UP, 0))
        }
    }

    @Test fun obscuredUpAndPointerEventsAlsoReject() {
        for (action in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP)) {
            for (flags in overlayFlags) {
                val guard = ExtensionDialogTouchGuard()
                assertTrue(guard.allows(MotionEvent.ACTION_DOWN, 0))
                assertFalse(guard.allows(action, flags))
                assertFalse(guard.allows(MotionEvent.ACTION_UP, 0))
            }
        }
    }

    @Test fun cancelDoesNotReenableTheTailOfARejectedGestureButANewCleanDownDoes() {
        val guard = ExtensionDialogTouchGuard()
        assertFalse(guard.allows(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED))
        assertFalse(guard.allows(MotionEvent.ACTION_CANCEL, 0))
        assertFalse(guard.allows(MotionEvent.ACTION_UP, 0))
        assertTrue(guard.allows(MotionEvent.ACTION_DOWN, 0))
        assertTrue(guard.allows(MotionEvent.ACTION_UP, 0))
    }

    @Test fun unrelatedFlagsDoNotBlockCleanInput() {
        val guard = ExtensionDialogTouchGuard()
        assertTrue(guard.allows(MotionEvent.ACTION_DOWN, 0x100))
        assertTrue(guard.allows(MotionEvent.ACTION_UP, 0x100))
    }
}
