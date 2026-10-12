package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteStateReducerTest {
    @Test fun buttonPadDefaultsToGesturesAndTogglesWithoutChangingPresentation() {
        val reducer = RemoteStateReducer { 0L }
        val raw = testSnapshot().state
        reducer.observe(RemoteReceiverSnapshot(raw.copy(capabilities = nativeRemoteCapabilities(RemoteProfile.AndroidTv, raw.context, RemoteMediaKind.Video))))
        assertFalse(reducer.state.preferences.buttonPad)
        val before = reducer.state
        assertTrue(reducer.local(RemoteAction.SetButtonPad(true)).preferences.buttonPad)
        assertFalse(reducer.local(RemoteAction.SetButtonPad(false)).preferences.buttonPad)
        assertEquals(before.inputMode, reducer.state.inputMode)
        assertEquals(resolveInputPresentation(before), resolveInputPresentation(reducer.state))
        assertEquals(before.preferences.timeDisplay, reducer.state.preferences.timeDisplay)
    }
}
