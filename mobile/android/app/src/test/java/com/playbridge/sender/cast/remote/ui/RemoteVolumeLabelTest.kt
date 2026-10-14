package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.remote.VolumeCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteVolumeLabelTest {
    @Test fun nativeLevelAndMuteShareOneLineForBothScopes() {
        assertEquals(RemoteVolumeLabel("TV volume 5/100 · Unmuted", "TV volume 5 of 100, unmuted"),
            label("TV volume", 5, 100, false))
        assertEquals(RemoteVolumeLabel("App volume 40/100 · Muted", "App volume 40 of 100, muted"),
            label("App volume", 40, 100, true))
    }

    @Test fun levelWithoutMuteHasNoMuteSuffix() {
        assertEquals(RemoteVolumeLabel("App volume 40/100", "App volume 40 of 100"),
            label("App volume", 40, 100, null))
        assertEquals(RemoteVolumeLabel("TV volume 0/100", "TV volume 0 of 100"),
            label("TV volume", 0, 100, null))
    }

    @Test fun muteWithoutLevelIsOneLabelWithoutAFabricatedValue() {
        assertEquals(RemoteVolumeLabel("TV volume · Muted", "TV volume, muted"),
            label("TV volume", null, 100, true))
        assertEquals(RemoteVolumeLabel("App volume · Unmuted", "App volume, unmuted"),
            label("App volume", null, null, false))
    }

    @Test fun noReportedValuesMeansNoLabelEvenForAppVolume() {
        assertNull(label("App volume", null, null, null))
        assertNull(label("TV volume", null, 100, null))
    }

    @Test fun incompleteOrInvalidMaximumCannotCreateALevelLabel() {
        for (maximum in listOf(null, 0, -1)) {
            assertNull(label("TV volume", 5, maximum, null))
            assertEquals(RemoteVolumeLabel("App volume · Muted", "App volume, muted"),
                label("App volume", 5, maximum, true))
        }
    }

    @Test fun legacyLevelDisplayAndVisibilityGateArePreserved() {
        assertEquals("5/100", legacyLabel(5, 100, null)?.text)
        assertEquals("5/100", legacyLabel(5, 100, false)?.text)
        assertEquals(RemoteVolumeLabel("5/100 · Muted", "TV volume 5 of 100, muted"),
            legacyLabel(5, 100, true))
        assertNull(legacyLabel(null, 100, true))
        assertNull(legacyLabel(5, 100, true, enabled = false))
    }

    private fun label(title: String, level: Int?, maximum: Int?, muted: Boolean?) = remoteVolumeLabel(
        title, VolumeCapabilities(level = level, maximum = maximum, muted = muted),
        nativeVolume = true, legacyVolumeEnabled = false,
    )

    private fun legacyLabel(level: Int?, maximum: Int?, muted: Boolean?, enabled: Boolean = true) = remoteVolumeLabel(
        "TV volume", VolumeCapabilities(level = level, maximum = maximum, muted = muted),
        nativeVolume = false, legacyVolumeEnabled = enabled,
    )
}
