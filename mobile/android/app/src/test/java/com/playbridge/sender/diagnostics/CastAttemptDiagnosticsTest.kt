package com.playbridge.sender.diagnostics

import com.playbridge.sender.cast.PlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastAttemptDiagnosticsTest {
    @Test
    fun onlyPlaylistCommandsStartNativePlaybackDiagnostics() {
        assertTrue(isNativePlaybackStartCommand("""{"type":"command","action":"playlist","payload":{"items":[]}}"""))
        assertFalse(isNativePlaybackStartCommand("""{"type":"command","action":"queue_add","payload":{"items":[]}}"""))
        assertFalse(isNativePlaybackStartCommand("""{"type":"command","action":"playlist_jump","payload":{"index":1}}"""))
        assertFalse(isNativePlaybackStartCommand("""{"type":"command","action":"control","payload":{"action":"play"}}"""))
        assertFalse(isNativePlaybackStartCommand("not json"))
    }

    @Test
    fun stoppedBeforePlaybackIsFailureForVideoButNotImage() {
        val buffering = CastAttempt(
            id = "00000000-0000-0000-0000-000000000001",
            startedAtMs = 0,
            receiver = CastAttempt.ReceiverKind.DLNA,
            route = CastAttempt.RouteKind.VIA_PHONE,
            media = CastAttempt.MediaKind.VIDEO,
            outcome = CastAttempt.AttemptOutcome.BUFFERING,
            events = emptyList(),
        )
        assertTrue(classifyExternalPlayback(buffering, PlaybackState.STOPPED)!!.first == CastAttempt.AttemptOutcome.FAILED)
        assertTrue(classifyExternalPlayback(buffering, PlaybackState.STOPPED)!!.second)
        assertTrue(classifyExternalPlayback(buffering.copy(media = CastAttempt.MediaKind.IMAGE), PlaybackState.STOPPED)!!.first == CastAttempt.AttemptOutcome.STOPPED)
        assertTrue(classifyExternalPlayback(buffering.copy(outcome = CastAttempt.AttemptOutcome.PLAYING), PlaybackState.STOPPED)!!.first == CastAttempt.AttemptOutcome.STOPPED)
    }

    @Test
    fun reportContainsOnlyAllowlistedCastFacts() {
        val report = formatCastAttemptReport(
            attempt = CastAttempt(
                id = "00000000-0000-0000-0000-000000000001",
                startedAtMs = 0,
                receiver = CastAttempt.ReceiverKind.DLNA,
                route = CastAttempt.RouteKind.VIA_PHONE,
                media = CastAttempt.MediaKind.VIDEO,
                outcome = CastAttempt.AttemptOutcome.FAILED,
                events = listOf(
                    CastAttemptEvent(0, CastAttemptEvent.Kind.REQUESTED),
                    CastAttemptEvent(2200, CastAttemptEvent.Kind.DLNA_SET_URI_FAILED, httpStatus = 500, upnpCode = 501),
                ),
                upstream = UpstreamStats(playlistsOk = 2, segmentsOk = 3, segmentsFailed = 1, lastFailureStatus = 403),
            ),
            appVersion = "0.3.3",
            appCode = 6,
            sdk = 36,
        )

        assertTrue(report.contains("Receiver: DLNA"))
        assertTrue(report.contains("Route: VIA_PHONE"))
        assertTrue(report.contains("DLNA_SET_URI_FAILED HTTP 500 UPnP 501"))
        assertTrue(report.contains("segments 3 OK / 1 failed"))
        assertTrue(report.contains("Last upstream HTTP failure: 403"))
        assertFalse(report.contains("http://"))
        assertFalse(report.contains("https://"))
        assertFalse(report.contains("Cookie:"))
    }
}
