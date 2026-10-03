package com.playbridge.sender.cast

import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastSessionReadinessTest {
    @Test
    fun `idle DLNA and Roku selections can accept their first stream`() {
        for (kind in listOf(TargetKind.DLNA, TargetKind.ROKU)) {
            assertTrue(session(kind, PlaybackState.IDLE).isReadyForPlayback)
        }
    }

    @Test
    fun `stateless receivers remain ready after stopping and clearing media`() {
        for (kind in listOf(TargetKind.DLNA, TargetKind.ROKU)) {
            assertTrue(session(kind, PlaybackState.STOPPED).isReadyForPlayback)
        }
    }

    @Test
    fun `selection alone never bypasses a stateful receiver handshake`() {
        for (kind in listOf(TargetKind.GOOGLE_CAST, TargetKind.WEB_BROWSER, TargetKind.NATIVE)) {
            assertFalse(session(kind, PlaybackState.IDLE).isReadyForPlayback)
        }
        assertTrue(session(TargetKind.GOOGLE_CAST, PlaybackState.STOPPED).isReadyForPlayback)
    }

    @Test
    fun `buffering a stateless stream does not disconnect its destination`() {
        for (kind in listOf(TargetKind.DLNA, TargetKind.ROKU)) {
            assertTrue(session(kind, PlaybackState.BUFFERING).isReadyForPlayback)
        }
        for (kind in listOf(TargetKind.GOOGLE_CAST, TargetKind.WEB_BROWSER, TargetKind.NATIVE)) {
            assertFalse(session(kind, PlaybackState.BUFFERING).isReadyForPlayback)
        }
    }

    @Test
    fun `failed sessions are not ready`() {
        for (kind in TargetKind.entries) {
            assertFalse(session(kind, PlaybackState.ERROR).isReadyForPlayback)
        }
    }

    @Test
    fun `playing and paused receivers remain ready`() {
        for (kind in TargetKind.entries) {
            assertTrue(session(kind, PlaybackState.PLAYING).isReadyForPlayback)
            assertTrue(session(kind, PlaybackState.PAUSED).isReadyForPlayback)
        }
    }

    @Test
    fun `cleared or incomplete selections are not receiver destinations`() {
        assertFalse(CastSessionState().isReadyForPlayback)
        assertFalse(CastSessionState(phase = SessionPhase.SELECTED).isReadyForPlayback)
        assertFalse(
            CastSessionState(phase = SessionPhase.SELECTED, targetKind = TargetKind.DLNA)
                .isReadyForPlayback,
        )
    }

    private fun session(kind: TargetKind, state: PlaybackState): CastSessionState {
        val protocol = when (kind) {
            TargetKind.DLNA -> CastProtocol.DLNA
            TargetKind.ROKU -> CastProtocol.ROKU
            TargetKind.GOOGLE_CAST -> CastProtocol.GOOGLE_CAST
            TargetKind.WEB_BROWSER -> CastProtocol.WEB_BROWSER
            TargetKind.NATIVE -> CastProtocol.PLAYBRIDGE
        }
        val status = PlaybackStatus(state)
        return CastSessionState(
            phase = externalSessionPhase(kind, status, mediaTitle = null),
            endpointKey = EndpointKey(protocol, "receiver"),
            targetKind = kind,
            playback = status,
        )
    }
}
