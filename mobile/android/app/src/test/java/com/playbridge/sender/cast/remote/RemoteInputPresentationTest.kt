package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputPresentationTest {
    private fun state(context: RemoteContext = RemoteContext.Player, kind: RemoteMediaKind = RemoteMediaKind.Video,
        profile: RemoteProfile = RemoteProfile.AndroidTv) = testSnapshot(context, kind).state.copy(
        receiver = testConfiguration(profile).receiver, capabilities = nativeRemoteCapabilities(profile, context, kind),
    )

    @Test fun videoAndAudioHaveNoPadUnlessTheTvIsWholeDevice() {
        for (kind in listOf(RemoteMediaKind.Video, RemoteMediaKind.Audio)) {
            val raw = state(kind = kind)
            assertEquals(RemoteInputPresentation.Absent, resolveInputPresentation(raw))
            assertFalse(RemoteInputMode.Pad in resolveRemoteLayout(raw, RemoteViewport()).modes)
            val whole = raw.copy(capabilities = raw.capabilities.copy(keyScope = ControlScope.WholeDevice))
            val layout = resolveRemoteLayout(whole, RemoteViewport())
            assertEquals(RemoteInputPresentation.Full, layout.inputPresentation)
            assertEquals(RemoteInputMode.Pad, layout.selectedMode)
            assertFalse(RemoteInputMode.Pointer in layout.modes)
        }
    }
    @Test fun browserAndImagesUseFullPointerAndIdleUsesContent() {
        for (raw in listOf(state(RemoteContext.Browser), state(kind = RemoteMediaKind.Image),
            state(kind = RemoteMediaKind.Image, profile = RemoteProfile.Desktop))) {
            val layout = resolveRemoteLayout(raw, RemoteViewport())
            assertEquals(RemoteInputPresentation.Full, layout.inputPresentation)
            assertEquals(RemoteInputMode.Pointer, layout.selectedMode)
            assertFalse(RemoteInputMode.Pad in layout.modes)
            assertFalse(RemoteInputMode.Keyboard in layout.modes)
        }
        val browser = state(RemoteContext.Browser)
        val both = browser.copy(capabilities = browser.capabilities.copy(keyScope = ControlScope.WholeDevice))
        val switched = resolveRemoteLayout(both, RemoteViewport())
        assertEquals(listOf(RemoteInputMode.Pad, RemoteInputMode.Pointer), switched.modes)
        assertEquals(RemoteInputMode.Pointer, switched.selectedMode)
        val idle = resolveRemoteLayout(state(RemoteContext.Idle), RemoteViewport())
        assertEquals(RemoteInputPresentation.Full, idle.inputPresentation)
        assertEquals(RemoteInputMode.Context, idle.selectedMode)
        assertFalse(RemoteInputMode.Pad in idle.modes)
        val pad = state(RemoteContext.Idle).copy(capabilities = state().capabilities.copy(keyScope = ControlScope.WholeDevice))
        val idlePad = resolveRemoteLayout(pad, RemoteViewport())
        assertEquals(RemoteInputMode.Pad, idlePad.selectedMode)
        assertFalse(RemoteInputMode.Context in idlePad.modes)
    }
    @Test fun unsupportedNativeAndExternalPlayersHaveNoBlankInput() {
        for (profile in listOf(RemoteProfile.AppleTv, RemoteProfile.Desktop, RemoteProfile.Cli, RemoteProfile.Unknown, RemoteProfile.External)) {
            assertEquals(RemoteInputPresentation.Absent, resolveInputPresentation(state(profile = profile)))
        }
        val roku = state(profile = RemoteProfile.External).copy(capabilities = state().capabilities.copy(keyScope = ControlScope.WholeDevice))
        assertEquals(RemoteInputPresentation.Full, resolveInputPresentation(roku))
        assertEquals(RemoteInputMode.Pad, resolveRemoteLayout(roku, RemoteViewport()).selectedMode)
    }
    @Test fun absentPresentationOverridesTheLegacyGeometryInputDefault() {
        val layout = RemoteAdaptiveLayout.resolve(RemoteViewport(800, 420),
            RemoteAdaptiveSections(info = true, seek = true, inputPresentation = RemoteInputPresentation.Absent))
        assertEquals(0f, layout.minimumInputHeightDp, 0f)
        assertEquals(0f, layout.inputHeightDp, 0f)
        assertEquals(2, layout.columns)
        assertEquals(RemoteColumnSplit.InfoAndQueue, layout.split)
        assertFalse(RemoteSection.Input in layout.sectionOrder)
    }

    @Test fun localStopCannotReusePlayerOnlyKeysAsAnIdlePad() {
        val reducer = RemoteStateReducer { 0L }
        reducer.observe(RemoteReceiverSnapshot(state()))
        val idle = reducer.stopped()
        assertTrue(idle.capabilities.keys.isNotEmpty())
        assertEquals(ControlScope.PlayBridgeActivity, idle.capabilities.keyScope)
        val layout = resolveRemoteLayout(idle, RemoteViewport())
        assertEquals(listOf(RemoteInputMode.Context), layout.modes)
        assertEquals(RemoteInputMode.Context, layout.selectedMode)
    }

}
