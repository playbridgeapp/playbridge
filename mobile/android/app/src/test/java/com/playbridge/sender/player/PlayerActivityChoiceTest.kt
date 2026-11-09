package com.playbridge.sender.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerActivityChoiceTest {
    @Test
    fun `keep playing keeps the player in its own task`() {
        assertEquals(PlayerActivity::class.java, playerActivityClass(PhoneLandscapeOpen.FULLSCREEN, PhoneLeaveAction.KEEP_PLAYING))
        assertEquals(PortraitFirstPlayerActivity::class.java, playerActivityClass(PhoneLandscapeOpen.PORTRAIT_FIRST, PhoneLeaveAction.KEEP_PLAYING))
    }

    @Test
    fun `pause and picture-in-picture share the app's task`() {
        for (action in listOf(PhoneLeaveAction.PAUSE, PhoneLeaveAction.PICTURE_IN_PICTURE)) {
            assertEquals(InAppPlayerActivity::class.java, playerActivityClass(PhoneLandscapeOpen.FULLSCREEN, action))
            assertEquals(InAppPortraitFirstPlayerActivity::class.java, playerActivityClass(PhoneLandscapeOpen.PORTRAIT_FIRST, action))
        }
    }

    @Test
    fun `a website's orientation skips portrait first`() {
        val website = PhonePlayerOpeningOrientation.LANDSCAPE
        assertEquals(PlayerActivity::class.java, playerActivityClass(PhoneLandscapeOpen.PORTRAIT_FIRST, PhoneLeaveAction.KEEP_PLAYING, website))
        assertEquals(InAppPlayerActivity::class.java, playerActivityClass(PhoneLandscapeOpen.PORTRAIT_FIRST, PhoneLeaveAction.PICTURE_IN_PICTURE, website))
    }

    @Test
    fun `the earlier background play switch carries over until a choice is made`() {
        assertEquals(PhoneLeaveAction.PAUSE, PhoneLeaveAction.of(null, null))
        assertEquals(PhoneLeaveAction.PAUSE, PhoneLeaveAction.of(null, false))
        assertEquals(PhoneLeaveAction.KEEP_PLAYING, PhoneLeaveAction.of(null, true))
        assertEquals(PhoneLeaveAction.PICTURE_IN_PICTURE, PhoneLeaveAction.of("pip", true))
        assertEquals(PhoneLeaveAction.PAUSE, PhoneLeaveAction.of("pause", true))
        assertEquals(PhoneLeaveAction.PAUSE, PhoneLeaveAction.of("unknown", false))
        PhoneLeaveAction.entries.forEach { assertEquals(it, PhoneLeaveAction.fromId(it.id)) }
    }
}
