package com.playbridge.sender.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTimelinePolicyTest {

    @Test
    fun browserVodDoesNotInheritStaleLivePlayerSettings() {
        val state = resolveRemoteTimeline(
            activeContext = "browser",
            playback = playback(durationMs = 600_000L),
            playerSettings = TvPlayerSettings(isLive = true, isSeekable = false),
        )

        assertFalse(state.isLive)
        assertTrue(state.isSeekable)
    }

    @Test
    fun browserWithUnknownDurationIsNotFalselyLiveOrSeekable() {
        val state = resolveRemoteTimeline(
            activeContext = "browser",
            playback = playback(durationMs = 0L),
            playerSettings = TvPlayerSettings(isLive = true, isSeekable = true),
        )

        assertFalse(state.isLive)
        assertFalse(state.isSeekable)
    }

    @Test
    fun nativeLivePlaybackRemainsLiveAndNotSeekable() {
        val state = resolveRemoteTimeline(
            activeContext = "player",
            playback = playback(durationMs = 0L),
            playerSettings = TvPlayerSettings(isLive = true, isSeekable = true),
        )

        assertTrue(state.isLive)
        assertFalse(state.isSeekable)
    }

    @Test
    fun nativeVodRespectsPlayerSeekability() {
        val seekable = resolveRemoteTimeline(
            activeContext = "player",
            playback = playback(durationMs = 600_000L),
            playerSettings = TvPlayerSettings(isLive = false, isSeekable = true),
        )
        val notSeekable = resolveRemoteTimeline(
            activeContext = "player",
            playback = playback(durationMs = 600_000L),
            playerSettings = TvPlayerSettings(isLive = false, isSeekable = false),
        )

        assertTrue(seekable.isSeekable)
        assertFalse(notSeekable.isSeekable)
    }

    @Test
    fun idleContextHasNoTimelineControls() {
        val state = resolveRemoteTimeline(
            activeContext = "idle",
            playback = playback(durationMs = 600_000L),
            playerSettings = TvPlayerSettings(isLive = true, isSeekable = true),
        )

        assertFalse(state.isLive)
        assertFalse(state.isSeekable)
    }

    private fun playback(durationMs: Long) = TvPlaybackStatus(
        state = "playing",
        positionMs = 30_000L,
        durationMs = durationMs,
        title = "YouTube",
    )
}
