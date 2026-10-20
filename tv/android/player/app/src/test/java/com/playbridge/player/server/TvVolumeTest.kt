package com.playbridge.player.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvVolumeTest {
    @Test fun descriptorOmitsAbsoluteAndReportsForFixedOutput() {
        val fixed = buildReceiverCapabilities(emptyList(), emptyList(), volumeFixed = true).toString()
        assertTrue(fixed.contains("relative_step"))
        assertTrue(fixed.contains("mute_toggle"))
        for (operation in listOf("absolute", "level_report", "mute_report")) assertFalse(fixed.contains(operation))
        val adjustable = buildReceiverCapabilities(emptyList(), emptyList()).toString()
        for (operation in listOf("relative_step", "absolute", "mute_toggle", "level_report", "mute_report")) assertTrue(adjustable.contains(operation))
    }
    @Test fun normalizationAndNativeRoundingRespectStreamRange() {
        assertEquals(0.5, normalizedTvVolume(10, 20)!!, 0.0)
        assertEquals(1.0, normalizedTvVolume(30, 20)!!, 0.0)
        assertNull(normalizedTvVolume(10, 0))
        assertEquals(8, tvVolumeIndex(0.5, 15))
        assertEquals(0, tvVolumeIndex(0.0, 15))
        assertEquals(15, tvVolumeIndex(1.0, 15))
        for (bad in listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(tvVolumeIndex(bad, 15))
    }
    @Test fun muteIsIndependentAndResyncAlwaysHasAFrame() {
        val zero = TvVolumeState(0.0, false)
        assertEquals(Json.parseToJsonElement("""{"type":"volume_state","scope":"system","level":0.0,"muted":false}"""), Json.parseToJsonElement(zero.frame()))
        assertFalse(shouldPublishTvVolume(zero, zero))
        assertTrue(shouldPublishTvVolume(zero, zero.copy(muted = true)))
        assertFalse(shouldPublishTvVolume(zero, null))
        val publication = TvVolumePublication()
        assertEquals(zero.frame(), publication.changed(zero))
        assertNull(publication.changed(zero))
        assertEquals(zero.frame(), publication.sync(zero)) // Auth/resync bypass de-dup.
        assertNull(publication.changed(zero))
        assertFalse(Json.parseToJsonElement(TvVolumeState(null, true).frame()).jsonObject.containsKey("level"))
    }
    @Test fun absoluteCommandRequiresNumericNormalizedSystemLevel() {
        assertEquals(0.25, parseTvVolumeCommand("""{"type":"command","action":"volume","payload":{"scope":"system","level":0.25}}""")!!, 0.0)
        for (payload in listOf("""{"scope":"app","level":0.2}""", """{"scope":"system","level":"0.2"}""", """{"scope":"system","level":-1}""", """{"scope":"system","level":101}""", "{}")) {
            assertNull(parseTvVolumeCommand("""{"type":"command","action":"volume","payload":$payload}"""))
        }
    }
}
