package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAvailabilityTest {
    @Test fun nativeInputRoutesMatchReceiverProfilesAndMedia() {
        for (image in listOf(false, true)) {
            assertEquals(NativeRemoteInputAvailability(true, true), nativeRemoteInputAvailability(listOf("exo", "mpv"), emptySet(), image))
            assertEquals(NativeRemoteInputAvailability(false, image), nativeRemoteInputAvailability(listOf("internal_mpv"), setOf("subtitle_resource_add_v1"), image))
            for (players in listOf(listOf("avplayer", "mpv"), listOf("internal_mpv"), emptyList())) {
                val input = nativeRemoteInputAvailability(players, emptySet(), image)
                assertEquals(NativeRemoteInputAvailability(false, false), input)
                for (context in listOf("player", "browser", "idle")) {
                    val nav = remoteNavAvailability(context, false, input.remote, false, true)
                    assertFalse(nav.back)
                    assertFalse(nav.home)
                    assertFalse(nav.keys)
                }
            }
            for (context in listOf("player", "browser", "idle")) {
                val desktop = nativeRemoteInputAvailability(listOf("internal_mpv"), setOf("subtitle_resource_add_v1"), image)
                assertEquals(RemoteNavAvailability(false, false, false, true), remoteNavAvailability(context, false, desktop.remote, true, true))
            }
        }
    }

    @Test fun navRoutesAreHonestAcrossContextsAndConnection() {
        assertEquals(RemoteNavAvailability(true, false, false, true), remoteNavAvailability("player", false, true, true, true))
        assertEquals(RemoteNavAvailability(true, true, true, true), remoteNavAvailability("browser", false, true, true, true))
        assertEquals(RemoteNavAvailability(false, false, false, true), remoteNavAvailability("idle", false, true, true, true))
        assertEquals(RemoteNavAvailability(true, true, false, true), remoteNavAvailability("idle", true, true, true, true))
        assertEquals(RemoteNavAvailability(false, false, false, false), remoteNavAvailability("player", true, false, false, true))
        for (context in listOf("player", "browser", "idle")) {
            assertEquals(RemoteNavAvailability(false, false, false, false), remoteNavAvailability(context, false, true, true, false))
        }
        assertTrue(nativeRemoteVolumeSupported(listOf("exo", "mpv"), emptySet()))
        assertFalse(nativeRemoteVolumeSupported(listOf("avplayer", "mpv"), emptySet()))
        assertFalse(nativeRemoteVolumeSupported(listOf("internal_mpv"), emptySet()))
        assertTrue(nativeRemoteVolumeSupported(listOf("internal_mpv"), setOf("subtitle_resource_add_v1")))
    }
    @Test fun transportUsesValidNativeNeighboursAndNeverBrowser() {
        fun transport(context: String = "player", external: Boolean = false, seekable: Boolean = true, live: Boolean = false, image: Boolean = false, indices: List<Int> = listOf(3, 8, 9), index: Int = 8) = remoteTransportAvailability(context, external, seekable, live, image, indices, index)
        assertEquals(RemoteTransportAvailability(true, true, 3, 9), transport())
        assertNull(transport(index = 3).previous)
        assertNull(transport(index = 9).next)
        assertNull(transport(index = 5).previous)
        assertNull(transport(index = 5).next)
        assertNull(transport(indices = listOf(8)).previous)
        assertNull(transport(indices = listOf(8)).next)
        assertNull(transport(external = true).previous)
        assertNull(transport(external = true).next)
        assertFalse(transport(live = true).seek)
        assertFalse(transport(image = true).seek)
        assertFalse(transport(seekable = false).seek)
        assertFalse(transport(context = "browser").visible)
        assertFalse(transport(context = "idle").visible)
    }
    @Test fun externalIdleAndPlayerInference() {
        for (state in listOf(null, "idle", "stopped", "error")) assertEquals("idle", externalRemoteContext(state))
        for (state in listOf("playing", "paused", "buffering")) assertEquals("player", externalRemoteContext(state))
    }
}
