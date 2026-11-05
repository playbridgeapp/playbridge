package com.playbridge.sender.player

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOpeningTest {
    @Test
    fun `fullscreen is the default and opens in landscape`() {
        assertEquals(PhoneLandscapeOpen.FULLSCREEN, PhoneLandscapeOpen.fromId(null))
        assertEquals(PhoneLandscapeOpen.FULLSCREEN, PhoneLandscapeOpen.fromId("sideways"))
        val opening = playerOpening(null, PhoneLandscapeOpen.FULLSCREEN)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, opening.requestedOrientation)
        assertFalse(opening.followPhone)
        assertTrue(opening.automatic)
    }

    @Test
    fun `portrait first follows the phone`() {
        assertEquals(PhoneLandscapeOpen.PORTRAIT_FIRST, PhoneLandscapeOpen.fromId("portrait"))
        val opening = playerOpening(null, PhoneLandscapeOpen.PORTRAIT_FIRST)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, opening.requestedOrientation)
        assertTrue(opening.followPhone)
        // Vertical videos still turn the screen to portrait.
        assertTrue(opening.automatic)
    }

    @Test
    fun `a website's orientation wins over the setting`() {
        val landscape = playerOpening(PhonePlayerOpeningOrientation.LANDSCAPE, PhoneLandscapeOpen.PORTRAIT_FIRST)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, landscape.requestedOrientation)
        assertFalse(landscape.followPhone)
        assertFalse(landscape.automatic)

        val portrait = playerOpening(PhonePlayerOpeningOrientation.PORTRAIT, PhoneLandscapeOpen.FULLSCREEN)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, portrait.requestedOrientation)
        assertFalse(portrait.followPhone)
    }
}
