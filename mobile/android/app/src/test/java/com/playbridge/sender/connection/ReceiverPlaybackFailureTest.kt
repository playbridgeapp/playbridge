package com.playbridge.sender.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverPlaybackFailureTest {
    @Test fun reasonSurvivesIdleWhilePlaybackSnapshotsClear() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        messages.emit("""{"type":"context","active":"player"}""")
        messages.emit("""{"type":"status","state":"buffering","playbackId":"current","position":0,"duration":0}""")
        messages.emit("""{"type":"status","state":"error","playbackId":"current","error":"  Couldn't load\n the video (HTTP 403)  ","position":0,"duration":0}""")
        runCurrent()
        assertEquals("Couldn't load the video (HTTP 403)", coordinator.playbackFailure.value?.message)
        assertEquals("error", coordinator.tvPlayback.value?.state)
        messages.emit("""{"type":"context","active":"idle"}""")
        runCurrent()
        assertEquals("idle", coordinator.tvActiveContext.value)
        assertNull(coordinator.tvPlayback.value)
        assertEquals("Couldn't load the video (HTTP 403)", coordinator.playbackFailure.value?.message)
        coordinator.startLocalPlaybackSession()
        assertNull(coordinator.playbackFailure.value)
    }

    @Test fun rejectedPlaylistShowsAFixedSentenceAndOtherResultsDoNot() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val playlists = mutableSetOf("cast-1", "cast-2")
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope, takePlaybackRequest = playlists::remove) { 0L }
        runCurrent()
        messages.emit("""{"type":"command_result","requestId":"queue-1","ok":false,"error":"queue_full"}""")
        messages.emit("""{"type":"command_result","requestId":"cast-1","ok":true}""")
        runCurrent()
        assertNull(coordinator.playbackFailure.value)

        messages.emit("""{"type":"command_result","requestId":"cast-2","ok":false,"error":"unsupported"}""")
        runCurrent()
        assertEquals(
            "The TV couldn't open its player. Open PlayBridge on the TV and try again.",
            coordinator.playbackFailure.value?.message,
        )
        coordinator.dismissPlaybackFailure()
        // A repeated answer for the same request doesn't bring the notice back.
        messages.emit("""{"type":"command_result","requestId":"cast-2","ok":false,"error":"unsupported"}""")
        runCurrent()
        assertNull(coordinator.playbackFailure.value)
    }

    @Test fun rejectionCodesMapToFixedSentences() {
        assertEquals("The TV couldn't read this cast. Try casting it again.", ReceiverPlaybackFailures.rejectionMessage("invalid_command"))
        assertEquals("The TV is busy. Try again in a moment.", ReceiverPlaybackFailures.rejectionMessage("queue_full"))
        assertEquals("The TV didn't start playback. Try casting it again.", ReceiverPlaybackFailures.rejectionMessage("http://secret"))
        assertEquals("The TV didn't start playback. Try casting it again.", ReceiverPlaybackFailures.rejectionMessage(null))
    }

    @Test fun missingNullBlankAndNonStringReasonsUseGenericMessageWithoutRejectingStatus() = runTest {
        for (value in listOf(null, "null", "\" \\n \\t \"", "403", "{\"message\":\"no\"}", "true")) {
            val messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
            val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
            runCurrent()
            val field = value?.let { ",\"error\":$it" }.orEmpty()
            messages.emit("""{"type":"status","state":"error","position":0,"duration":0$field}""")
            runCurrent()
            assertEquals("Playback failed on the TV", coordinator.playbackFailure.value?.message)
            assertEquals("error", coordinator.tvPlayback.value?.state)
        }
    }

    @Test fun oldPlaybackErrorCannotChangeCurrentPlaybackOrNoticeEvenAfterIdle() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        messages.emit("""{"type":"context","active":"player"}""")
        messages.emit("""{"type":"playlist_status","playbackId":"new","currentIndex":0,"totalCount":0,"items":[]}""")
        messages.emit("""{"type":"status","state":"playing","playbackId":"new","position":10,"duration":100}""")
        messages.emit("""{"type":"status","state":"error","playbackId":"old","error":"Old failure","position":0,"duration":0}""")
        runCurrent()
        assertEquals("playing", coordinator.tvPlayback.value?.state)
        assertNull(coordinator.playbackFailure.value)
        messages.emit("""{"type":"context","active":"idle"}""")
        messages.emit("""{"type":"status","state":"error","playbackId":"old","error":"Old failure","position":0,"duration":0}""")
        runCurrent()
        assertNull(coordinator.playbackFailure.value)
    }

    @Test fun successfulPlaybackNewIdentityAndSessionResetClearFailure() {
        val state = ReceiverPlaybackFailures()
        assertTrue(state.status("error", "one", "Failed"))
        state.status("playing", "one", null)
        assertNull(state.notice.value)
        state.status("error", "one", "Failed")
        state.playlist("two")
        assertNull(state.notice.value)
        assertFalse(state.status("error", "one", "Old"))
        state.status("error", "two", "Failed")
        state.reset()
        assertNull(state.notice.value)
        assertTrue(state.status("error", "three", "New receiver"))
    }

    @Test fun dismissSurvivesRepeatedStatusUntilRecoveryOrNewPlayback() {
        val state = ReceiverPlaybackFailures()
        state.status("error", "one", "Failed")
        state.dismiss()
        assertNull(state.notice.value)
        state.status("error", "one", "Failed")
        assertNull(state.notice.value)
        state.status("buffering", "one", null)
        state.status("error", "one", "Failed")
        assertNull(state.notice.value)
        state.status("playing", "one", null)
        state.status("error", "one", "Failed")
        assertEquals("Failed", state.notice.value?.message)
        state.dismiss()
        state.playlist("two")
        state.status("error", "two", "Failed")
        assertEquals("Failed", state.notice.value?.message)
    }

    @Test fun coordinatorExposesDismissWithoutClearingPlaybackState() = runTest {
        val messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
        val coordinator = ConnectionCoordinator(messages, null, backgroundScope) { 0L }
        runCurrent()
        messages.emit("""{"type":"status","state":"error","error":"Failed","position":0,"duration":0}""")
        runCurrent()
        coordinator.dismissPlaybackFailure()
        assertNull(coordinator.playbackFailure.value)
        assertEquals("error", coordinator.tvPlayback.value?.state)
    }

    @Test fun reasonIsOnePlainLineAndCappedWithoutSplittingEmoji() {
        assertEquals("<b>Failed</b> https://example.test", ReceiverPlaybackFailures.message("<b>Failed</b>\u2028\t https://example.test"))
        val bounded = ReceiverPlaybackFailures.message("😀".repeat(600))
        assertEquals(512, bounded.codePointCount(0, bounded.length))
        assertFalse(bounded.contains('\n'))
        assertEquals("Playback failed on the TV", ReceiverPlaybackFailures.message("\u0085\u2028"))
    }

    @Test fun retiredSocketBufferedErrorIsDroppedAtConsumption() = runTest {
        val bus = WebSocketMessageBus()
        val coordinator = ConnectionCoordinator(bus.messages, null, backgroundScope) { 0L }
        var current = true
        runCurrent()
        bus.publish("""{"type":"status","state":"error","error":"Retired receiver","position":0,"duration":0}""") { current }
        current = false
        runCurrent()
        assertNull(coordinator.playbackFailure.value)
        assertNull(coordinator.tvPlayback.value)
    }
}
