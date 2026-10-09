package com.playbridge.sender.cast.googlecast

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustSessionJsonTest {
    @Test
    fun buildsDlnaConnectTargetFromDescriptionLocationAndOptionalNetworkHandle() {
        val target = buildDlnaTargetJson("http://192.0.2.10/device.xml", -4_294_967_296L)
        assertEquals("dlna", target.getString("protocol"))
        assertEquals("http://192.0.2.10/device.xml", target.getString("location"))
        assertEquals(-4_294_967_296L, target.getLong("network_handle"))
        assertFalse(buildDlnaTargetJson("http://192.0.2.10/device.xml", null).has("network_handle"))
    }

    @Test
    fun buildsBufferedResumeLoadFieldsAsSecondsForRust() {
        val fields = buildDlnaLoadFields(
            url = "http://192.0.2.2/movie.mp4",
            contentType = "video/mp4",
            title = "Movie",
            startSeconds = 120.0,
            durationMs = 7_200_000L,
            streamType = "BUFFERED",
            isScreenMirror = false,
            fallbackUrl = null,
            fallbackContentType = null,
        )

        assertEquals(120.0, fields.getDouble("start_seconds"), 0.0)
        assertEquals(7200.0, fields.getDouble("duration_seconds"), 0.0)
        assertEquals("BUFFERED", fields.getString("stream_type"))
        assertFalse(fields.getBoolean("is_screen_mirror"))
        assertFalse(fields.has("fallback_url"))
    }

    @Test
    fun buildsLateMediaFactsAndOmitsUnknownValues() {
        val facts = buildMediaFactsFields(isLive = true, durationMs = 12_500L)
        assertTrue(facts.getBoolean("is_live"))
        assertEquals(12.5, facts.getDouble("duration_seconds"), 0.0)
        assertTrue(buildMediaFactsFields(isLive = null, durationMs = null).length() == 0)
    }

    @Test
    fun readsVolumeSupportFromConnectedCapabilities() {
        assertEquals(
            false,
            parseRustVolumeSupport(
                JSONObject().put("capabilities", JSONObject().put("volume", false)),
            ),
        )
        assertEquals(
            true,
            parseRustVolumeSupport(
                JSONObject().put("capabilities", JSONObject().put("volume", true)),
            ),
        )
        assertNull(parseRustVolumeSupport(JSONObject().put("capabilities", JSONObject())))
    }

    @Test
    fun parsesDlnaLiveStatusAndConnectedVolumeCapabilityWithoutNativeLibrary() {
        val status = parseRustPlaybackStatus(
            JSONObject()
                .put("state", "playing")
                .put("position_seconds", 12.5)
                .put("duration_seconds", 0.0)
                .put("is_live", true)
                .put("volume_supported", false),
        )

        assertEquals("playing", status.state)
        assertEquals(12.5, status.positionSeconds, 0.0)
        assertTrue(status.isLive)
        assertTrue(status.hasLiveField)
        assertEquals(false, status.volumeSupported)
    }

    @Test
    fun detectsOldNativeStatusThatCannotConfirmDlnaSessionContract() {
        val status = parseRustPlaybackStatus(
            JSONObject().put("state", "stopped").put("duration_seconds", 0.0),
        )

        assertFalse(status.hasLiveField)
        assertFalse(status.isLive)
        assertNull(status.volumeSupported)
    }

    @Test
    fun absentOptionalVolumeValueRetainsConnectedCapability() {
        val status = parseRustPlaybackStatus(
            JSONObject().put("state", "paused").put("is_live", false),
            previousVolumeSupported = true,
        )

        assertEquals(true, status.volumeSupported)
    }
}
