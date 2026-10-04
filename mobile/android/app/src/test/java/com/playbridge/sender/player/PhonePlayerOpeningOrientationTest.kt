package com.playbridge.sender.player

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePlayerOpeningOrientationTest {
    @Test fun `accepts only the three explicit wire strings`() {
        assertEquals(PhonePlayerOpeningOrientation.AUTOMATIC, PhonePlayerOpeningOrientation.parse("auto"))
        assertEquals(PhonePlayerOpeningOrientation.PORTRAIT, PhonePlayerOpeningOrientation.parse("portrait"))
        assertEquals(PhonePlayerOpeningOrientation.LANDSCAPE, PhonePlayerOpeningOrientation.parse("landscape"))
        listOf(null, true, 1, "Landscape", "sideways", listOf("portrait")).forEach { assertNull(PhonePlayerOpeningOrientation.parse(it)) }
    }
    @Test fun `explicit opening choices cannot be overridden by video aspect ratio`() {
        assertFalse(PhonePlayerOpeningOrientation.LANDSCAPE.automatic)
        assertFalse(PhonePlayerOpeningOrientation.PORTRAIT.automatic)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, PhonePlayerOpeningOrientation.LANDSCAPE.requestedOrientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, PhonePlayerOpeningOrientation.PORTRAIT.requestedOrientation)
    }
    @Test fun `automatic retains the existing landscape default and aspect adaptation`() {
        assertTrue(PhonePlayerOpeningOrientation.AUTOMATIC.automatic)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, PhonePlayerOpeningOrientation.AUTOMATIC.requestedOrientation)
    }
}
