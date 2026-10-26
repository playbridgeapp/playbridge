package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteStateReducerTest {
    @Test fun volumeIntentReconcilesOnlyOnVolumeReportExpiresAndClearsWhenReportsDisappear() {
        var now = 0L
        val reducer = RemoteStateReducer { now }
        val descriptor = com.playbridge.sender.connection.ReceiverDescriptor(1, emptyMap(),
            com.playbridge.sender.connection.ReceiverVolume("system", setOf("relative_step", "absolute", "level_report", "mute_report"), step = .0625))
        val metadata = com.playbridge.sender.connection.ReceiverProtocolState(descriptor,
            com.playbridge.sender.connection.ReceiverVolumeState("system", .5, false))
        fun snapshot(protocol: com.playbridge.sender.connection.ReceiverProtocolState) = testSnapshot().let {
            it.copy(state = resolveReceiverRemotePolicy(it.state.copy(receiverProtocol = protocol), emptyList(), emptySet()))
        }
        val reported = snapshot(metadata)
        reducer.observe(reported)
        reducer.begin(RemoteAction.AdjustVolume(1))
        assertEquals(56, reducer.state.capabilities.volume.reportedPercent)
        reducer.observe(reported) // A playback/other status is not a volume acknowledgement.
        assertEquals(56, reducer.state.capabilities.volume.reportedPercent)
        val ack = metadata.copy(volume = metadata.volume!!.copy(level = .58), volumeRevision = 1)
        reducer.observe(snapshot(ack))
        assertEquals(58, reducer.state.capabilities.volume.reportedPercent)
        assertFalse(reducer.state.pending.volume)
        reducer.begin(RemoteAction.AdjustVolume(1))
        now = 2_000
        assertEquals(58, reducer.expire().capabilities.volume.reportedPercent)
        reducer.begin(RemoteAction.SetVolume(80))
        val fixed = ack.copy(descriptor = descriptor.copy(volume = descriptor.volume!!.copy(operations = setOf("relative_step"))))
        reducer.observe(snapshot(fixed))
        assertEquals(null, reducer.state.capabilities.volume.reportedPercent)
        assertFalse(reducer.state.pending.volume)
    }

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

    @Test fun upNextStaysOpenWhenTheQueueMovesOnButOtherSheetsClose() {
        val reducer = RemoteStateReducer { 0L }
        val first = testSnapshot()
        reducer.observe(first)
        reducer.local(RemoteAction.OpenSheet(RemoteSheet.Queue))
        val next = first.state.copy(
            playback = first.state.playback!!.copy(title = "Next"),
            queue = first.state.queue!!.copy(currentIndex = 8),
        )
        reducer.observe(first.copy(state = next))
        assertEquals(RemoteSheet.Queue, reducer.state.sheet)

        reducer.local(RemoteAction.OpenSheet(RemoteSheet.Subtitles))
        reducer.observe(first)
        assertEquals(null, reducer.state.sheet)
    }
}
