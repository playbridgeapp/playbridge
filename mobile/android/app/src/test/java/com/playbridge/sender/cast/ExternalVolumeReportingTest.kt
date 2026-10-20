package com.playbridge.sender.cast

import com.playbridge.sender.cast.googlecast.parseRustPlaybackStatus
import com.playbridge.sender.cast.remote.Support
import com.playbridge.sender.cast.remote.VolumeScope
import com.playbridge.sender.cast.remote.externalRemoteCapabilities
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalVolumeReportingTest {
    private val volume = setOf(Capability.VOLUME)
    @Test fun castDlnaAndWebExposeNormalizedLevelsAndTheirActualScope() {
        for ((kind, scope) in listOf(TargetKind.GOOGLE_CAST to VolumeScope.ReceiverDevice,
            TargetKind.DLNA to VolumeScope.Renderer, TargetKind.WEB_BROWSER to VolumeScope.Player)) {
            val caps = externalRemoteCapabilities(kind, volume, true, 0.37, false).volume
            assertEquals(37, caps.level)
            assertEquals(100, caps.maximum)
            assertEquals(37, caps.reportedPercent)
            assertEquals(false, caps.muted)
            assertEquals(scope, caps.scope)
            assertEquals(Support.Supported, caps.absolute)
        }
    }
    @Test fun fixedOutputAndUnavailableValuesNeverInventALevelOrMute() {
        val fixed = externalRemoteCapabilities(TargetKind.GOOGLE_CAST, volume, false, .5, true).volume
        assertNull(fixed.level)
        assertNull(fixed.muted)
        assertEquals(Support.Unsupported, fixed.adjust)
        for (invalid in listOf(null, -1.0, 1.1, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(externalRemoteCapabilities(TargetKind.DLNA, volume, true, invalid).volume.level)
        }
        assertNull(externalRemoteCapabilities(TargetKind.GOOGLE_CAST, volume, true, 0.0).volume.muted)
    }
    @Test fun rokuRemainsRelativeOnlyEvenIfNumericVolumeIsUnsupported() {
        val caps = externalRemoteCapabilities(TargetKind.ROKU, volume, false, .5, true).volume
        assertEquals(Support.Supported, caps.adjust)
        assertEquals(Support.Unsupported, caps.absolute)
        assertNull(caps.level)
        assertNull(caps.maximum)
        assertNull(caps.muted)
    }
    @Test fun rustStatusParsesOptionalFieldsAndMuteIndependently() {
        val status = parseRustPlaybackStatus(JSONObject("""{"volume_supported":true,"volume_level":0.0,"muted":false}"""))
        assertEquals(0.0, status.volumeLevel!!, 0.0)
        assertEquals(false, status.muted)
        val absent = parseRustPlaybackStatus(JSONObject("{}"))
        assertNull(absent.volumeLevel)
        assertNull(absent.muted)
        val fixed = parseRustPlaybackStatus(JSONObject("""{"volume_supported":false,"volume_level":0.3,"muted":true}"""))
        assertNull(fixed.volumeLevel)
        assertNull(fixed.muted)
        assertNull(parseRustPlaybackStatus(JSONObject("""{"volume_level":"0.3","muted":"true"}""")).volumeLevel)
    }
    @Test fun notificationStartsAtMeasuredLevelAndOnlyMakesSupportedKnownRoutesAbsolute() {
        assertEquals(CastNotificationVolume(CastNotificationVolumeControl.Absolute, 37), castNotificationVolume(true, true, 37))
        assertEquals(CastNotificationVolumeControl.Relative, castNotificationVolume(true, false, 37).control)
        assertEquals(CastNotificationVolume(CastNotificationVolumeControl.Relative, 0), castNotificationVolume(true, true, null))
        assertEquals(CastNotificationVolumeControl.Fixed, castNotificationVolume(false, true, null).control)
        assertEquals(100, castNotificationVolume(true, true, 150).initialPercent)
    }
}
