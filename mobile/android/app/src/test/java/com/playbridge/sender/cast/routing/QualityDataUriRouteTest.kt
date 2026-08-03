package com.playbridge.sender.cast.routing

import com.playbridge.sender.cast.proxy.StreamRouteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selected HLS quality packaging policy.
 */
class QualityDataUriRouteTest {

    @Test
    fun dataUriForcesViaPhoneWhenDirectRequestedWithoutHttpChild() {
        val (url, mode) = CastPreparation.resolveItemForPackaging(
            evaluatedUrl = "data:application/x-mpegurl;base64,abc",
            packagedCandidateUrl = "data:application/x-mpegurl;base64,abc",
            requestedMode = StreamRouteMode.DIRECT,
        )
        assertTrue(url.startsWith("data:"))
        assertEquals(StreamRouteMode.VIA_PHONE, mode)
    }

    @Test
    fun prefersHttpChildPlaylistWhenDirectSelectedAndNoSeparateAudio() {
        val child = "https://cdn.example/media/720.m3u8"
        val dataUri = "data:application/x-mpegurl;base64,abc"
        val (url, mode) = CastPreparation.resolveItemForPackaging(
            evaluatedUrl = child,
            packagedCandidateUrl = dataUri,
            requestedMode = StreamRouteMode.DIRECT,
            hasSeparateAudio = false,
        )
        assertEquals(child, url)
        assertEquals(StreamRouteMode.DIRECT, mode)
        assertFalse(url.startsWith("data:"))
    }

    @Test
    fun prefersHttpChildPlaylistWhenViaProxySelectedAndNoSeparateAudio() {
        val child = "https://cdn.example/media/720.m3u8"
        val dataUri = "data:application/x-mpegurl;base64,abc"
        val (url, mode) = CastPreparation.resolveItemForPackaging(
            evaluatedUrl = child,
            packagedCandidateUrl = dataUri,
            requestedMode = StreamRouteMode.VIA_PROXY,
            hasSeparateAudio = false,
        )
        assertEquals(child, url)
        assertEquals(StreamRouteMode.VIA_PROXY, mode)
        assertFalse(url.startsWith("data:"))
    }

    @Test
    fun separateAudioUsesOriginalMasterForDirect() {
        val child = "https://cdn.example/media/720.m3u8"
        val master = "https://cdn.example/master.m3u8"
        val dataUri = "data:application/x-mpegurl;base64,abc"
        val (url, mode) = CastPreparation.resolveItemForPackaging(
            evaluatedUrl = child,
            packagedCandidateUrl = dataUri,
            requestedMode = StreamRouteMode.DIRECT,
            hasSeparateAudio = true,
            originalMasterUrl = master,
        )
        assertEquals(master, url)
        assertEquals(StreamRouteMode.DIRECT, mode)
    }

    @Test
    fun separateAudioUsesOriginalMasterForViaProxy() {
        val child = "https://cdn.example/media/720.m3u8"
        val master = "https://cdn.example/master.m3u8"
        val dataUri = "data:application/x-mpegurl;base64,abc"
        val (url, mode) = CastPreparation.resolveItemForPackaging(
            evaluatedUrl = child,
            packagedCandidateUrl = dataUri,
            requestedMode = StreamRouteMode.VIA_PROXY,
            hasSeparateAudio = true,
            originalMasterUrl = master,
        )
        assertEquals(master, url)
        assertEquals(StreamRouteMode.VIA_PROXY, mode)
    }

    @Test
    fun separateAudioKeepsFilteredMasterForViaPhone() {
        val child = "https://cdn.example/media/720.m3u8"
        val dataUri = "data:application/x-mpegurl;base64,abc"
        val (url, mode) = CastPreparation.resolveItemForPackaging(
            evaluatedUrl = child,
            packagedCandidateUrl = dataUri,
            requestedMode = StreamRouteMode.VIA_PHONE,
            hasSeparateAudio = true,
        )
        assertEquals(dataUri, url)
        assertEquals(StreamRouteMode.VIA_PHONE, mode)
    }

    @Test
    fun nonDataUriUnchanged() {
        val http = "https://cdn.example/v.mp4"
        val (url, mode) = CastPreparation.resolveItemForPackaging(
            evaluatedUrl = http,
            packagedCandidateUrl = http,
            requestedMode = StreamRouteMode.DIRECT,
        )
        assertEquals(http, url)
        assertEquals(StreamRouteMode.DIRECT, mode)
    }
}
