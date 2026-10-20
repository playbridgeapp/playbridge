package com.playbridge.sender.cast

import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteKey
import com.playbridge.sender.cast.remote.RemoteVolumeOptimism
import com.playbridge.sender.cast.remote.RemoteVolumeDragThrottle
import com.playbridge.sender.cast.remote.volumeDragTarget
import com.playbridge.sender.cast.remote.LatestVolumeThrottle
import com.playbridge.sender.cast.remote.RemoteVolumePill
import com.playbridge.sender.cast.remote.VolumeCapabilities
import com.playbridge.sender.cast.remote.remoteVolumePill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVolumeOptimismTest {
    @Test fun repeatedStepsKeepFractionalPrecisionClampAndExpire() {
        var now = 0L
        val intent = RemoteVolumeOptimism { now }
        val report = VolumeCapabilities(reportedPercent = 50, level = 50, maximum = 100, muted = false)
        repeat(4) { intent.begin(RemoteAction.AdjustVolume(1), report, 1.0/16, true) }
        assertEquals(75, intent.display(report).reportedPercent)
        intent.begin(RemoteAction.AdjustVolume(100), report, 1.0/16, true)
        assertEquals(100, intent.display(report).reportedPercent)
        intent.begin(RemoteAction.Key(RemoteKey.Mute), report, 1.0/16, true)
        assertEquals(true, intent.display(report).muted)
        now = 2_000
        assertEquals(report, intent.display(report))
    }
    @Test fun unknownOrUnreportedLevelsAreNeverInventedAndAckWins() {
        val intent = RemoteVolumeOptimism { 1L }
        val unknown = VolumeCapabilities()
        intent.begin(RemoteAction.AdjustVolume(1), unknown, .05, true)
        assertNull(intent.display(unknown).reportedPercent)
        val report = VolumeCapabilities(reportedPercent = 0, muted = false)
        intent.begin(RemoteAction.AdjustVolume(1), report, .05, false)
        assertEquals(0, intent.display(report).reportedPercent)
        intent.begin(RemoteAction.SetVolume(75), report, .05, true)
        assertEquals(75, intent.display(report).reportedPercent)
        assertEquals(false, intent.display(report).muted)
        intent.clear()
        assertEquals(37, intent.display(report.copy(reportedPercent = 37)).reportedPercent)
    }
    @Test fun swipeMappingHasNativeStepFeelAndClamps() {
        assertEquals(56, volumeDragTarget(.5, 1.0, 1.0/16))
        assertEquals(75, volumeDragTarget(.5, 4.0, 1.0/16))
        assertEquals(100, volumeDragTarget(.5, 20.0, .05))
        assertEquals(0, volumeDragTarget(.5, -20.0, .05))
    }
    @Test fun latestAbsoluteHasTrailingFinalAndRelativeRemainderIsPaced() {
        val throttle = RemoteVolumeDragThrottle()
        throttle.absolute(20)
        assertEquals(RemoteAction.SetVolume(20), throttle.next(0))
        repeat(20) { throttle.absolute(21 + it) }
        assertNull(throttle.next(124))
        assertEquals(RemoteAction.SetVolume(40), throttle.next(125))
        assertFalse(throttle.pending)
        throttle.relative(3); throttle.relative(-1)
        assertNull(throttle.next(249))
        val steps = listOf(250L, 375L, 500L, 625L).map { (throttle.next(it) as RemoteAction.AdjustVolume).steps }
        assertEquals(listOf(1, 1, 1, -1), steps)
        assertFalse(throttle.pending)
        throttle.relative(20); throttle.absolute(90)
        assertEquals(RemoteAction.SetVolume(90), throttle.next(750))
        assertFalse(throttle.pending)
        throttle.absolute(90); throttle.clear()
        assertNull(throttle.next(1000))
    }
    @Test fun pillShowsLevelMutedOrUnknownOnlyForExactLevelRoutes() {
        assertEquals(RemoteVolumePill(45, false, exact = true), remoteVolumePill(true, false, false, 45, false))
        assertEquals("45", remoteVolumePill(true, false, false, 45, null).text)
        assertEquals("Muted", remoteVolumePill(true, false, false, 45, true).text)
        assertEquals("—", remoteVolumePill(true, false, false, null, null).text)
        assertEquals(100, remoteVolumePill(true, false, false, 140, false).percent)
        // Relative-only (e.g. Roku), whole-TV control and app fallback still get the pill, step-only.
        assertEquals(RemoteVolumePill(45, false, exact = false), remoteVolumePill(false, false, false, 45, false))
        assertEquals(false, remoteVolumePill(true, true, false, 45, false).exact)
        assertEquals(false, remoteVolumePill(true, false, true, 45, false).exact)
        assertEquals("", remoteVolumePill(false, false, false, null, null).text)
    }
    @Test fun sliderThrottleSendsLatestValueWithTrailingFinal() {
        val throttle = LatestVolumeThrottle()
        throttle.offer(20)
        assertEquals(20, throttle.poll(0))
        repeat(20) { throttle.offer(21 + it) }
        assertNull(throttle.poll(124))
        assertEquals(1L, throttle.waitMs(124))
        assertEquals(40, throttle.poll(125))
        assertFalse(throttle.hasPending)
        // Release right after a send: the final value still goes out once the interval passes.
        throttle.offer(55)
        assertTrue(throttle.hasPending)
        assertNull(throttle.poll(200))
        assertEquals(55, throttle.poll(250))
        throttle.offer(-5)
        assertEquals(0, throttle.poll(400))
        throttle.offer(90); throttle.clear()
        assertNull(throttle.poll(1_000))
    }
}
