package com.playbridge.player.player

/**
 * Pure classification for ExoPlayer playback failures in the multi-process TV renderer.
 *
 * Policy (Nuvio-inspired):
 * 1. Recover in-engine whenever possible (live edge, re-prepare, seek retry).
 * 2. Escalate engine failover only for hard capability/format failures **before** first frame.
 * 3. After first frame, never recommend engine switch for rebuffer / network / live glitches.
 */
internal object ExoPlaybackErrorPolicy {

    /** Media3 [androidx.media3.common.PlaybackException] error codes used by classification. */
    object Codes {
        const val UNSPECIFIED = 1000
        const val REMOTE_ERROR = 1001
        const val BEHIND_LIVE_WINDOW = 1002
        const val TIMEOUT = 1003
        const val FAILED_RUNTIME_CHECK = 1004

        const val IO_UNSPECIFIED = 2000
        const val IO_NETWORK_CONNECTION_FAILED = 2001
        const val IO_NETWORK_CONNECTION_TIMEOUT = 2002
        const val IO_INVALID_HTTP_CONTENT_TYPE = 2003
        const val IO_BAD_HTTP_STATUS = 2004
        const val IO_FILE_NOT_FOUND = 2005
        const val IO_NO_PERMISSION = 2006
        const val IO_CLEARTEXT_NOT_PERMITTED = 2007
        const val IO_READ_POSITION_OUT_OF_RANGE = 2008

        const val PARSING_CONTAINER_MALFORMED = 3001
        const val PARSING_MANIFEST_MALFORMED = 3002
        const val PARSING_CONTAINER_UNSUPPORTED = 3003
        const val PARSING_MANIFEST_UNSUPPORTED = 3004

        const val DECODER_INIT_FAILED = 4001
        const val DECODER_QUERY_FAILED = 4002
        const val DECODING_FAILED = 4003
        const val DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4004
        const val DECODING_FORMAT_UNSUPPORTED = 4005

        const val AUDIO_TRACK_INIT_FAILED = 5001
        const val AUDIO_TRACK_WRITE_FAILED = 5002
    }

    const val MAX_LIVE_EDGE_RECOVERIES = 5
    const val MAX_REPREPARE_RECOVERIES = 3
    const val MAX_AUDIO_DISCONTINUITY_RECOVERIES = 3
    const val MAX_DECODE_RECOVERIES = 1
    const val VOD_END_TIMEOUT_WINDOW_MS = 5_000L

    data class AttemptBudget(
        val liveEdge: Int = 0,
        val reprepare: Int = 0,
        val audioDiscontinuity: Int = 0,
        val decode: Int = 0,
    )

    enum class RecoveryStrategy {
        /** Seek to the live edge and re-prepare (live only). */
        LIVE_EDGE,
        /** Stop + prepare, optionally seeking back to the last position (VOD). */
        REPREPARE,
        /** Seek to current position and re-prepare (audio discontinuity). */
        SEEK_RETRY,
    }

    /**
     * How the host should treat an error that the renderer could not (or should not) recover.
     *
     * - [STARTUP_ENGINE_FAILOVER]: hard capability/format failure before first frame → switch engines once.
     * - [TERMINAL]: give up on this engine for this item; do **not** switch after playback has started.
     */
    enum class EscalationSeverity {
        STARTUP_ENGINE_FAILOVER,
        TERMINAL,
    }

    sealed class Disposition {
        data class Recover(val strategy: RecoveryStrategy) : Disposition()
        data class TreatAsEnded(val reason: String) : Disposition()
        data class Escalate(val severity: EscalationSeverity, val reason: String) : Disposition()
    }

    data class ErrorFacts(
        val errorCode: Int,
        val errorCodeName: String,
        val isLive: Boolean,
        val hasFirstFrame: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val causeClassSimpleName: String? = null,
        val message: String? = null,
        val httpStatus: Int? = null,
        val isUnrecognizedFormat: Boolean = false,
        val budget: AttemptBudget = AttemptBudget(),
    )

    fun classify(facts: ErrorFacts): Disposition {
        // Live edge fell outside the available window — rejoin live; never switch engines.
        if (facts.errorCode == Codes.BEHIND_LIVE_WINDOW) {
            return if (facts.budget.liveEdge < MAX_LIVE_EDGE_RECOVERIES) {
                Disposition.Recover(RecoveryStrategy.LIVE_EDGE)
            } else {
                Disposition.Escalate(EscalationSeverity.TERMINAL, "behind_live_window_exhausted")
            }
        }

        // Stuck-player timeout near VOD end → treat as clean EOS (same as STATE_ENDED).
        if (facts.errorCode == Codes.TIMEOUT && isNearVodEnd(facts)) {
            return Disposition.TreatAsEnded("timeout_near_end")
        }

        // Live / mid-stream stall timeout → rejoin live edge or re-prepare; never engine-hop mid-play.
        if (facts.errorCode == Codes.TIMEOUT) {
            return if (facts.isLive) {
                recoverOrEscalate(
                    attempts = facts.budget.liveEdge,
                    max = MAX_LIVE_EDGE_RECOVERIES,
                    strategy = RecoveryStrategy.LIVE_EDGE,
                    exhaustedReason = "live_timeout_exhausted",
                )
            } else {
                recoverOrEscalate(
                    attempts = facts.budget.reprepare,
                    max = MAX_REPREPARE_RECOVERIES,
                    strategy = RecoveryStrategy.REPREPARE,
                    exhaustedReason = "timeout_exhausted",
                )
            }
        }

        if (facts.isUnrecognizedFormat ||
            facts.errorCode == Codes.PARSING_CONTAINER_UNSUPPORTED ||
            facts.errorCode == Codes.PARSING_MANIFEST_UNSUPPORTED
        ) {
            return Disposition.Escalate(
                severity = if (facts.hasFirstFrame) {
                    EscalationSeverity.TERMINAL
                } else {
                    EscalationSeverity.STARTUP_ENGINE_FAILOVER
                },
                reason = "unsupported_format",
            )
        }

        val isAudioDiscontinuity =
            facts.errorCode == Codes.AUDIO_TRACK_WRITE_FAILED ||
                facts.causeClassSimpleName == "UnexpectedDiscontinuityException"
        if (isAudioDiscontinuity) {
            return recoverOrEscalate(
                attempts = facts.budget.audioDiscontinuity,
                max = MAX_AUDIO_DISCONTINUITY_RECOVERIES,
                strategy = RecoveryStrategy.SEEK_RETRY,
                exhaustedReason = "audio_discontinuity_exhausted",
            )
        }

        if (facts.errorCode == Codes.AUDIO_TRACK_INIT_FAILED) {
            return recoverOrEscalate(
                attempts = facts.budget.reprepare,
                max = MAX_REPREPARE_RECOVERIES,
                strategy = RecoveryStrategy.REPREPARE,
                exhaustedReason = "audio_track_init_exhausted",
            )
        }

        val isDecoderFailure =
            facts.errorCode == Codes.DECODER_INIT_FAILED ||
                facts.errorCode == Codes.DECODING_FAILED ||
                facts.errorCode == Codes.DECODER_QUERY_FAILED ||
                facts.errorCode == Codes.DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
                facts.errorCode == Codes.DECODING_FORMAT_UNSUPPORTED ||
                facts.causeClassSimpleName == "DecoderInitializationException" ||
                facts.causeClassSimpleName == "MediaCodecVideoDecoderException"

        if (isDecoderFailure) {
            // One in-engine re-prepare (and host learns compatibility flags before escalate).
            if (facts.budget.decode < MAX_DECODE_RECOVERIES) {
                return Disposition.Recover(RecoveryStrategy.REPREPARE)
            }
            return Disposition.Escalate(
                severity = if (facts.hasFirstFrame) {
                    EscalationSeverity.TERMINAL
                } else {
                    EscalationSeverity.STARTUP_ENGINE_FAILOVER
                },
                reason = "decoder_failure",
            )
        }

        if (isNetworkOrIoError(facts)) {
            if (isNonRetryableHttp(facts.httpStatus)) {
                return Disposition.Escalate(EscalationSeverity.TERMINAL, "http_${facts.httpStatus}")
            }
            // Live network blips: rejoin live edge; VOD: re-prepare at position.
            return if (facts.isLive) {
                recoverOrEscalate(
                    attempts = facts.budget.liveEdge,
                    max = MAX_LIVE_EDGE_RECOVERIES,
                    strategy = RecoveryStrategy.LIVE_EDGE,
                    exhaustedReason = "live_network_exhausted",
                )
            } else {
                recoverOrEscalate(
                    attempts = facts.budget.reprepare,
                    max = MAX_REPREPARE_RECOVERIES,
                    strategy = RecoveryStrategy.REPREPARE,
                    exhaustedReason = "network_exhausted",
                )
            }
        }

        if (facts.errorCode == Codes.PARSING_CONTAINER_MALFORMED ||
            facts.errorCode == Codes.PARSING_MANIFEST_MALFORMED
        ) {
            return if (facts.isLive) {
                recoverOrEscalate(
                    attempts = facts.budget.liveEdge,
                    max = MAX_LIVE_EDGE_RECOVERIES,
                    strategy = RecoveryStrategy.LIVE_EDGE,
                    exhaustedReason = "live_malformed_exhausted",
                )
            } else {
                recoverOrEscalate(
                    attempts = facts.budget.reprepare,
                    max = MAX_REPREPARE_RECOVERIES,
                    strategy = RecoveryStrategy.REPREPARE,
                    exhaustedReason = "malformed_exhausted",
                )
            }
        }

        // Unknown / unspecified: try one re-prepare, then terminal (or startup failover only if
        // we never painted a frame — last chance for exotic codec paths).
        if (facts.budget.reprepare < 1) {
            return Disposition.Recover(RecoveryStrategy.REPREPARE)
        }
        return Disposition.Escalate(
            severity = if (facts.hasFirstFrame) {
                EscalationSeverity.TERMINAL
            } else {
                EscalationSeverity.STARTUP_ENGINE_FAILOVER
            },
            reason = "unhandled_${facts.errorCodeName}",
        )
    }

    /**
     * Whether the host may auto-switch engines for this escalated failure.
     * Engine switches are restricted to startup (no first frame) + [STARTUP_ENGINE_FAILOVER].
     */
    fun mayAutoSwitchEngine(
        severity: EscalationSeverity,
        hasFirstFrame: Boolean,
    ): Boolean = !hasFirstFrame && severity == EscalationSeverity.STARTUP_ENGINE_FAILOVER

    fun severityWireValue(severity: EscalationSeverity): String = when (severity) {
        EscalationSeverity.STARTUP_ENGINE_FAILOVER -> RendererProtocol.ERROR_SEVERITY_STARTUP_FAILOVER
        EscalationSeverity.TERMINAL -> RendererProtocol.ERROR_SEVERITY_TERMINAL
    }

    fun parseSeverity(raw: String?): EscalationSeverity? = when (raw) {
        RendererProtocol.ERROR_SEVERITY_STARTUP_FAILOVER -> EscalationSeverity.STARTUP_ENGINE_FAILOVER
        RendererProtocol.ERROR_SEVERITY_TERMINAL -> EscalationSeverity.TERMINAL
        else -> null
    }

    private fun recoverOrEscalate(
        attempts: Int,
        max: Int,
        strategy: RecoveryStrategy,
        exhaustedReason: String,
    ): Disposition {
        if (attempts < max) return Disposition.Recover(strategy)
        // Exhausted recoveries are terminal: switching engines does not fix a dead CDN/live edge.
        // Only decoder/format paths escalate as STARTUP_ENGINE_FAILOVER (handled separately).
        return Disposition.Escalate(EscalationSeverity.TERMINAL, exhaustedReason)
    }

    private fun isNearVodEnd(facts: ErrorFacts): Boolean {
        if (facts.isLive) return false
        val duration = facts.durationMs
        if (duration <= 0L) return false
        return facts.positionMs >= duration - VOD_END_TIMEOUT_WINDOW_MS
    }

    private fun isNetworkOrIoError(facts: ErrorFacts): Boolean {
        if (facts.errorCode in Codes.IO_UNSPECIFIED..Codes.IO_READ_POSITION_OUT_OF_RANGE) {
            // IO_BAD_HTTP_STATUS handled via httpStatus for retryability.
            return true
        }
        val cause = facts.causeClassSimpleName.orEmpty()
        if (cause == "HttpDataSourceException" ||
            cause == "InvalidResponseCodeException" ||
            cause == "UnknownHostException" ||
            cause == "SocketTimeoutException" ||
            cause == "PlaylistStuckException"
        ) {
            return true
        }
        val message = facts.message.orEmpty()
        return message.contains("timed out", ignoreCase = true) ||
            message.contains("Unable to connect", ignoreCase = true)
    }

    private fun isNonRetryableHttp(status: Int?): Boolean = when (status) {
        400, 401, 403, 404, 410 -> true
        else -> false
    }
}
