package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExoPlaybackErrorPolicyTest {

    @Test
    fun `behind live window recovers at live edge`() {
        val disposition = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.BEHIND_LIVE_WINDOW,
            isLive = true,
            hasFirstFrame = true,
        )
        assertEquals(
            ExoPlaybackErrorPolicy.Disposition.Recover(ExoPlaybackErrorPolicy.RecoveryStrategy.LIVE_EDGE),
            disposition,
        )
    }

    @Test
    fun `behind live window exhausted may failover mid playback`() {
        val disposition = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.BEHIND_LIVE_WINDOW,
            isLive = true,
            hasFirstFrame = true,
            budget = ExoPlaybackErrorPolicy.AttemptBudget(
                liveEdge = ExoPlaybackErrorPolicy.MAX_LIVE_EDGE_RECOVERIES,
            ),
        )
        assertEscalate(
            disposition,
            ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
        )
        assertTrue(
            ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
                ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
                hasFirstFrame = true,
            ),
        )
    }

    @Test
    fun `live timeout recovers at live edge mid playback`() {
        val disposition = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.TIMEOUT,
            isLive = true,
            hasFirstFrame = true,
            positionMs = 60_000L,
            durationMs = 0L,
        )
        assertEquals(
            ExoPlaybackErrorPolicy.Disposition.Recover(ExoPlaybackErrorPolicy.RecoveryStrategy.LIVE_EDGE),
            disposition,
        )
    }

    @Test
    fun `vod timeout near end is treated as ended`() {
        val disposition = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.TIMEOUT,
            isLive = false,
            hasFirstFrame = true,
            positionMs = 96_000L,
            durationMs = 100_000L,
        )
        assertTrue(disposition is ExoPlaybackErrorPolicy.Disposition.TreatAsEnded)
    }

    @Test
    fun `network error on live recovers without engine switch`() {
        val disposition = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.IO_NETWORK_CONNECTION_TIMEOUT,
            isLive = true,
            hasFirstFrame = true,
        )
        assertEquals(
            ExoPlaybackErrorPolicy.Disposition.Recover(ExoPlaybackErrorPolicy.RecoveryStrategy.LIVE_EDGE),
            disposition,
        )
    }

    @Test
    fun `network error on live may failover after recovery budget`() {
        val disposition = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.IO_NETWORK_CONNECTION_TIMEOUT,
            isLive = true,
            hasFirstFrame = true,
            budget = ExoPlaybackErrorPolicy.AttemptBudget(
                liveEdge = ExoPlaybackErrorPolicy.MAX_LIVE_EDGE_RECOVERIES,
            ),
        )
        assertEscalate(
            disposition,
            ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
        )
        assertTrue(
            ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
                ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
                hasFirstFrame = true,
            ),
        )
    }

    @Test
    fun `http 403 is terminal and never engine failover`() {
        val disposition = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.IO_BAD_HTTP_STATUS,
            isLive = false,
            hasFirstFrame = false,
            httpStatus = 403,
        )
        assertEscalate(disposition, ExoPlaybackErrorPolicy.EscalationSeverity.TERMINAL)
        assertFalse(
            ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
                ExoPlaybackErrorPolicy.EscalationSeverity.TERMINAL,
                hasFirstFrame = false,
            ),
        )
    }

    @Test
    fun `unsupported format before first frame may failover`() {
        val disposition = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.PARSING_CONTAINER_UNSUPPORTED,
            isLive = false,
            hasFirstFrame = false,
            isUnrecognizedFormat = true,
        )
        assertEscalate(disposition, ExoPlaybackErrorPolicy.EscalationSeverity.STARTUP_ENGINE_FAILOVER)
        assertTrue(
            ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
                ExoPlaybackErrorPolicy.EscalationSeverity.STARTUP_ENGINE_FAILOVER,
                hasFirstFrame = false,
            ),
        )
    }

    @Test
    fun `decoder failure after first frame may failover after recovery`() {
        val disposition = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.DECODING_FAILED,
            isLive = true,
            hasFirstFrame = true,
            budget = ExoPlaybackErrorPolicy.AttemptBudget(decode = 1),
        )
        assertEscalate(
            disposition,
            ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
        )
        assertTrue(
            ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
                ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
                hasFirstFrame = true,
            ),
        )
    }

    @Test
    fun `decoder failure before first frame may failover after one recovery`() {
        val first = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.DECODER_INIT_FAILED,
            isLive = false,
            hasFirstFrame = false,
        )
        assertEquals(
            ExoPlaybackErrorPolicy.Disposition.Recover(ExoPlaybackErrorPolicy.RecoveryStrategy.REPREPARE),
            first,
        )

        val second = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.DECODER_INIT_FAILED,
            isLive = false,
            hasFirstFrame = false,
            budget = ExoPlaybackErrorPolicy.AttemptBudget(decode = 1),
        )
        assertEscalate(
            second,
            ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
        )
        assertTrue(
            ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
                ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
                hasFirstFrame = false,
            ),
        )
    }

    @Test
    fun `audio discontinuity seeks and retries`() {
        val disposition = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.AUDIO_TRACK_WRITE_FAILED,
            isLive = false,
            hasFirstFrame = true,
        )
        assertEquals(
            ExoPlaybackErrorPolicy.Disposition.Recover(ExoPlaybackErrorPolicy.RecoveryStrategy.SEEK_RETRY),
            disposition,
        )
    }

    @Test
    fun `audio track init failure before first frame may failover after recovery budget`() {
        val first = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.AUDIO_TRACK_INIT_FAILED,
            isLive = false,
            hasFirstFrame = false,
        )
        assertEquals(
            ExoPlaybackErrorPolicy.Disposition.Recover(ExoPlaybackErrorPolicy.RecoveryStrategy.REPREPARE),
            first,
        )

        val exhausted = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.AUDIO_TRACK_INIT_FAILED,
            isLive = false,
            hasFirstFrame = false,
            budget = ExoPlaybackErrorPolicy.AttemptBudget(
                reprepare = ExoPlaybackErrorPolicy.MAX_REPREPARE_RECOVERIES,
            ),
        )
        assertEscalate(
            exhausted,
            ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
        )
        assertTrue(
            ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
                ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
                hasFirstFrame = false,
            ),
        )
    }

    @Test
    fun `audio track init failure after first frame may failover after recovery`() {
        val exhausted = classify(
            errorCode = ExoPlaybackErrorPolicy.Codes.AUDIO_TRACK_INIT_FAILED,
            isLive = false,
            hasFirstFrame = true,
            budget = ExoPlaybackErrorPolicy.AttemptBudget(
                reprepare = ExoPlaybackErrorPolicy.MAX_REPREPARE_RECOVERIES,
            ),
        )
        assertEscalate(
            exhausted,
            ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
        )
        assertTrue(
            ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
                ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
                hasFirstFrame = true,
            ),
        )
    }

    @Test
    fun `mayAutoSwitchEngine allows startup or exhausted recovery failover`() {
        assertTrue(
            ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
                ExoPlaybackErrorPolicy.EscalationSeverity.STARTUP_ENGINE_FAILOVER,
                hasFirstFrame = false,
            ),
        )
        assertFalse(
            ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
                ExoPlaybackErrorPolicy.EscalationSeverity.STARTUP_ENGINE_FAILOVER,
                hasFirstFrame = true,
            ),
        )
        assertTrue(
            ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
                ExoPlaybackErrorPolicy.EscalationSeverity.RECOVERY_EXHAUSTED_FAILOVER,
                hasFirstFrame = true,
            ),
        )
        assertFalse(
            ExoPlaybackErrorPolicy.mayAutoSwitchEngine(
                ExoPlaybackErrorPolicy.EscalationSeverity.TERMINAL,
                hasFirstFrame = false,
            ),
        )
    }

    private fun classify(
        errorCode: Int,
        isLive: Boolean,
        hasFirstFrame: Boolean,
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        httpStatus: Int? = null,
        isUnrecognizedFormat: Boolean = false,
        budget: ExoPlaybackErrorPolicy.AttemptBudget = ExoPlaybackErrorPolicy.AttemptBudget(),
    ): ExoPlaybackErrorPolicy.Disposition = ExoPlaybackErrorPolicy.classify(
        ExoPlaybackErrorPolicy.ErrorFacts(
            errorCode = errorCode,
            errorCodeName = "ERROR_$errorCode",
            isLive = isLive,
            hasFirstFrame = hasFirstFrame,
            positionMs = positionMs,
            durationMs = durationMs,
            httpStatus = httpStatus,
            isUnrecognizedFormat = isUnrecognizedFormat,
            budget = budget,
        ),
    )

    private fun assertEscalate(
        disposition: ExoPlaybackErrorPolicy.Disposition,
        severity: ExoPlaybackErrorPolicy.EscalationSeverity,
    ) {
        val escalate = disposition as ExoPlaybackErrorPolicy.Disposition.Escalate
        assertEquals(severity, escalate.severity)
    }
}
