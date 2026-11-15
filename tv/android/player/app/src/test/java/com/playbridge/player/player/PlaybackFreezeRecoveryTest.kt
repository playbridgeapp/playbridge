package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFreezeRecoveryTest {
    @Test fun slowGrowingCacheFor40SecondsNeverTriggersReload() {
        val recovery = PlaybackFreezeRecovery()
        recovery.markFirstFrame()

        var bufferedEnd = 10_000L
        for (sec in 0..45) {
            val decision = recovery.sample(
                nowMs = sec * 1_000L,
                playbackRequested = true,
                hasFirstFrame = true,
                positionMs = 10_000L,
                bufferedEndMs = bufferedEnd,
                durationMs = 120_000L,
            )
            assertEquals(PlaybackFreezeRecovery.Decision.None, decision)
            bufferedEnd += 250L // Slow network continuously filling cache
        }
        assertEquals(0, recovery.totalRecoveries)
    }

    @Test fun flatCacheWhileBufferingReloadsAtLastGoodPositionAfter15Seconds() {
        val recovery = PlaybackFreezeRecovery()
        recovery.markFirstFrame()

        assertEquals(
            PlaybackFreezeRecovery.Decision.None,
            recovery.sample(
                nowMs = 0L,
                playbackRequested = true,
                hasFirstFrame = true,
                positionMs = 24_000L,
                bufferedEndMs = 28_000L,
                durationMs = 120_000L,
            ),
        )
        // Advance slightly to establish 25_000ms as last good position, then freeze flat.
        assertEquals(
            PlaybackFreezeRecovery.Decision.None,
            recovery.sample(
                nowMs = 1_000L,
                playbackRequested = true,
                hasFirstFrame = true,
                positionMs = 25_000L,
                bufferedEndMs = 28_000L,
                durationMs = 120_000L,
            ),
        )

        for (sec in 2..16) {
            val decision = recovery.sample(
                nowMs = sec * 1_000L,
                playbackRequested = true,
                hasFirstFrame = true,
                positionMs = 25_000L,
                bufferedEndMs = 28_000L,
                durationMs = 120_000L,
            )
            assertEquals("Expected no reload at t=${sec}s", PlaybackFreezeRecovery.Decision.None, decision)
        }

        val reload = recovery.sample(
            nowMs = 17_000L,
            playbackRequested = true,
            hasFirstFrame = true,
            positionMs = 25_000L,
            bufferedEndMs = 28_000L,
            durationMs = 120_000L,
        )
        assertEquals(
            PlaybackFreezeRecovery.Decision.Reload(
                resumePositionMs = 25_000L,
                attempt = 1,
                withoutProgressAttempt = 1,
            ),
            reload,
        )
    }

    @Test fun silentFreezeWithoutBufferingStatusAlsoReloadsAfter15Seconds() {
        val recovery = PlaybackFreezeRecovery()
        recovery.markFirstFrame()

        recovery.sample(
            nowMs = 0L,
            playbackRequested = true,
            hasFirstFrame = true,
            positionMs = 12_000L,
            bufferedEndMs = 12_000L,
            durationMs = 120_000L,
        )
        for (sec in 1..15) {
            assertEquals(
                PlaybackFreezeRecovery.Decision.None,
                recovery.sample(
                    nowMs = sec * 1_000L,
                    playbackRequested = true,
                    hasFirstFrame = true,
                    positionMs = 12_000L,
                    bufferedEndMs = 12_000L,
                    durationMs = 120_000L,
                ),
            )
        }
        val decision = recovery.sample(
            nowMs = 16_000L,
            playbackRequested = true,
            hasFirstFrame = true,
            positionMs = 12_000L,
            bufferedEndMs = 12_000L,
            durationMs = 120_000L,
        )
        assertTrue(decision is PlaybackFreezeRecovery.Decision.Reload)
    }

    @Test fun naturalEndAndClearedItemsNeverTriggerFifteenSecondSilentFreezeReload() {
        // 1. Even before EVENT_ENDED fires, sitting flat at the buffered finite end must not reload.
        val nearEnd = PlaybackFreezeRecovery()
        nearEnd.markFirstFrame()
        for (sec in 0..30) {
            assertEquals(
                "Flat position/cache at natural end triggered reload at t=${sec}s",
                PlaybackFreezeRecovery.Decision.None,
                nearEnd.sample(
                    nowMs = sec * 1_000L,
                    playbackRequested = true,
                    hasFirstFrame = true,
                    positionMs = 59_500L,
                    bufferedEndMs = 60_000L,
                    durationMs = 60_000L,
                ),
            )
        }
        assertEquals(0, nearEnd.totalRecoveries)

        // 2. Once EVENT_ENDED or TogetherPlayerCommand.Clear marks the item ended, neither
        //    subsequent flat-sample ticks (even if state briefly looked "playing") nor late
        //    recoverable errors can trigger a reload. Resetting for a new item re-enables recovery.
        val ended = PlaybackFreezeRecovery()
        ended.markFirstFrame()
        ended.sample(
            nowMs = 0L,
            playbackRequested = true,
            hasFirstFrame = true,
            positionMs = 20_000L,
            bufferedEndMs = 20_000L,
            durationMs = 60_000L,
        )
        ended.sample(
            nowMs = 14_000L,
            playbackRequested = true,
            hasFirstFrame = true,
            positionMs = 20_000L,
            bufferedEndMs = 20_000L,
            durationMs = 60_000L,
        )
        ended.markEnded()
        for (sec in 15..35) {
            assertEquals(
                PlaybackFreezeRecovery.Decision.None,
                ended.sample(
                    nowMs = sec * 1_000L,
                    playbackRequested = true,
                    hasFirstFrame = true,
                    positionMs = 20_000L,
                    bufferedEndMs = 20_000L,
                    durationMs = 60_000L,
                ),
            )
        }
        assertEquals(
            PlaybackFreezeRecovery.Decision.None,
            ended.evaluateRecoverableError("mpv_playback_failed", playbackRequested = true, positionMs = 20_000L),
        )
        assertEquals(0, ended.totalRecoveries)

        ended.resetForNewItem()
        ended.markFirstFrame()
        assertTrue(
            ended.evaluateRecoverableError("mpv_playback_failed", playbackRequested = true, positionMs = 5_000L)
                is PlaybackFreezeRecovery.Decision.Reload,
        )
    }

    @Test fun stopsAfterTwoReloadsWithout2SecondsForwardProgress() {
        val recovery = PlaybackFreezeRecovery()
        recovery.markFirstFrame()

        val r1 = recovery.evaluateRecoverableError(
            errorCode = "mpv_playback_failed",
            playbackRequested = true,
            positionMs = 30_000L,
        )
        assertEquals(PlaybackFreezeRecovery.Decision.Reload(30_000L, 1, 1), r1)

        // Keyframe landing 3s BEFORE 30_000ms must NOT count as forward progress.
        recovery.sample(
            nowMs = 20_000L,
            playbackRequested = true,
            hasFirstFrame = true,
            positionMs = 27_000L,
            bufferedEndMs = 27_500L,
        )
        recovery.sample(
            nowMs = 21_000L,
            playbackRequested = true,
            hasFirstFrame = true,
            positionMs = 27_500L,
            bufferedEndMs = 27_500L,
        )
        assertEquals(1, recovery.recoveriesWithoutProgress)

        val r2 = recovery.evaluateRecoverableError(
            errorCode = "mpv_http_503",
            playbackRequested = true,
            positionMs = 27_500L,
        )
        assertEquals(PlaybackFreezeRecovery.Decision.Reload(30_000L, 2, 2), r2)

        val fail = recovery.evaluateRecoverableError(
            errorCode = "mpv_load_failed",
            playbackRequested = true,
            positionMs = 0L,
        )
        assertEquals(PlaybackFreezeRecovery.Decision.FailTerminal(totalAttempts = 2, withoutProgressAttempts = 2), fail)
    }

    @Test fun hardCapAllowsAtMostSixTotalReloadsAcrossItemAndPreservesAcrossPauseAndSeek() {
        val recovery = PlaybackFreezeRecovery()
        recovery.markFirstFrame()

        var pos = 10_000L
        var now = 0L
        for (attempt in 1..6) {
            val reload = recovery.evaluateRecoverableError(
                errorCode = "mpv_playback_failed",
                playbackRequested = true,
                positionMs = pos,
            )
            assertEquals(
                PlaybackFreezeRecovery.Decision.Reload(pos, attempt, 1),
                reload,
            )

            // Simulate pause + play + seek after attempt 3: totalRecoveries must NOT reset.
            if (attempt == 3) {
                recovery.sample(nowMs = now + 500L, playbackRequested = false, isPaused = true, positionMs = pos, bufferedEndMs = pos)
                pos += 10_000L
                recovery.resetForPositionChange(pos)
                assertEquals(3, recovery.totalRecoveries)
            } else {
                // Advance >= 2s forward after reload so withoutProgress resets.
                recovery.sample(nowMs = now + 1_000L, playbackRequested = true, positionMs = pos, bufferedEndMs = pos)
                pos += 2_500L
                recovery.sample(nowMs = now + 2_000L, playbackRequested = true, positionMs = pos, bufferedEndMs = pos)
                assertEquals(0, recovery.recoveriesWithoutProgress)
            }
            now += 5_000L
        }

        val seventh = recovery.evaluateRecoverableError(
            errorCode = "mpv_playback_failed",
            playbackRequested = true,
            positionMs = pos,
        )
        assertEquals(PlaybackFreezeRecovery.Decision.FailTerminal(totalAttempts = 6, withoutProgressAttempts = 0), seventh)

        // Starting a new item resets the total cap cleanly.
        recovery.resetForNewItem()
        recovery.markFirstFrame()
        assertEquals(
            PlaybackFreezeRecovery.Decision.Reload(5_000L, attempt = 1, withoutProgressAttempt = 1),
            recovery.evaluateRecoverableError("mpv_playback_failed", playbackRequested = true, positionMs = 5_000L),
        )
    }

    @Test fun userPausePartyPauseSeekingLivePreBufferingAndStillWatchingNeverTriggerRecovery() {
        val recovery = PlaybackFreezeRecovery()
        recovery.markFirstFrame()

        fun assertNeverReloads(label: String, call: (Long) -> PlaybackFreezeRecovery.Decision) {
            for (sec in 0..30) {
                assertEquals("$label triggered at t=${sec}s", PlaybackFreezeRecovery.Decision.None, call(sec * 1_000L))
            }
        }

        assertNeverReloads("user_pause") { t ->
            recovery.sample(t, playbackRequested = false, isPaused = true, positionMs = 15_000L, bufferedEndMs = 15_000L)
        }
        assertNeverReloads("party_pause") { t ->
            recovery.sample(t, playbackRequested = false, isPaused = false, positionMs = 15_000L, bufferedEndMs = 15_000L)
        }
        assertNeverReloads("seeking") { t ->
            recovery.sample(t, playbackRequested = true, isSeeking = true, positionMs = 15_000L, bufferedEndMs = 15_000L)
        }
        assertNeverReloads("live_stream") { t ->
            recovery.sample(t, playbackRequested = true, isLive = true, positionMs = 15_000L, bufferedEndMs = 15_000L)
        }
        assertNeverReloads("pre_buffering") { t ->
            recovery.sample(t, playbackRequested = true, isPreBuffering = true, positionMs = 15_000L, bufferedEndMs = 15_000L)
        }
        assertNeverReloads("still_watching") { t ->
            recovery.sample(t, playbackRequested = true, isStillWatchingPrompting = true, positionMs = 15_000L, bufferedEndMs = 15_000L)
        }
        assertEquals(0, recovery.totalRecoveries)
    }

    @Test fun nonRecoverableErrorsAndInitialLoadErrorsReturnNone() {
        val recovery = PlaybackFreezeRecovery()
        // Before first frame has ever rendered, allow normal startup failover.
        assertEquals(
            PlaybackFreezeRecovery.Decision.None,
            recovery.evaluateRecoverableError("mpv_load_failed", playbackRequested = true, positionMs = 0L),
        )

        recovery.markFirstFrame()
        for (terminalCode in listOf("mpv_unsupported_format", "mpv_decoder_failure", "mpv_http_403", "mpv_http_404")) {
            assertEquals(
                PlaybackFreezeRecovery.Decision.None,
                recovery.evaluateRecoverableError(terminalCode, playbackRequested = true, positionMs = 20_000L),
            )
        }
    }
}
