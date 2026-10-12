package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteHapticsPolicyTest {
    @Test fun allowListHonorsSystemSetting() {
        var enabled = true
        var count = 0
        val haptics = RemoteHaptics({ enabled }, { count++ })
        RemoteFeedback.entries.forEach(haptics::perform)
        assertEquals(3, count)
        enabled = false
        RemoteFeedback.entries.forEach(haptics::perform)
        assertEquals(3, count)
    }
    @Test fun boundsOnlyTickOnCrossing() {
        assertTrue(crossedSeekBound(5f, 0f, 100))
        assertTrue(crossedSeekBound(95f, 100f, 100))
        assertFalse(crossedSeekBound(0f, 0f, 100))
        assertFalse(crossedSeekBound(100f, 100f, 100))
        assertFalse(crossedSeekBound(50f, 60f, 100))
        assertFalse(crossedSeekBound(5f, 0f, 0))
    }
    @Test fun secondNonImageTapAddsNoClickAndImageDoubleTapResets() {
        val actions = mutableListOf("click") // First tap is sent immediately by the pointer handler.
        if (remoteDoubleTapAction(false) != "none") actions += remoteDoubleTapAction(false)
        assertEquals(listOf("click"), actions)
        assertEquals("reset", remoteDoubleTapAction(true))
    }
}
