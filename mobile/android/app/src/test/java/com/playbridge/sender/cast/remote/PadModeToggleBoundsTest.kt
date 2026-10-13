package com.playbridge.sender.cast.remote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.playbridge.sender.cast.remote.ui.padModeToggleBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadModeToggleBoundsTest {
    @Test fun ltrExcludesTheEntireFortyEightDpTargetAtAnEightDpInset() {
        val bounds = padModeToggleBounds(320f, 1f, false)
        assertEquals(Rect(8f, 8f, 56f, 56f), bounds)
        assertTrue(bounds.contains(Offset(8f, 8f)))
        assertTrue(bounds.contains(Offset(55.9f, 55.9f)))
        assertFalse(bounds.contains(Offset(7.9f, 8f)))
        assertFalse(bounds.contains(Offset(56f, 32f)))
        assertFalse(bounds.contains(Offset(32f, 56f)))
    }

    @Test fun rtlMirrorsTheExcludedCornerWithoutChangingItsInsetOrSize() {
        assertEquals(Rect(264f, 8f, 312f, 56f), padModeToggleBounds(320f, 1f, true))
    }

    @Test fun densityScalesTheTargetAndInsetTogetherInBothDirections() {
        assertEquals(Rect(24f, 24f, 168f, 168f), padModeToggleBounds(960f, 3f, false))
        assertEquals(Rect(792f, 24f, 936f, 168f), padModeToggleBounds(960f, 3f, true))
    }
}
