package com.playbridge.player.player

import com.playbridge.shared.protocol.createStatusJson

/** Chooses the existing failover route, or publishes a terminal status before the host exits. */
internal class RendererFailureReporter {
    private var terminalReported = false

    fun reset() {
        terminalReported = false
    }

    fun fallbackOrReport(
        allowEngineFailover: Boolean,
        fallback: RendererKind?,
        attemptedRenderers: Set<RendererKind>,
        message: String,
        positionMs: Long,
        durationMs: Long,
        mediaKind: String,
        playbackId: String?,
        currentItemId: String?,
        broadcastStatus: (String) -> Unit,
    ): RendererKind? {
        if (terminalReported) return null
        if (allowEngineFailover && fallback != null && fallback !in attemptedRenderers) return fallback
        terminalReported = true
        broadcastStatus(createStatusJson(
            state = "error",
            position = positionMs.coerceAtLeast(0L),
            duration = durationMs.coerceAtLeast(0L),
            title = null,
            mediaKind = mediaKind,
            playbackId = playbackId,
            currentItemId = currentItemId,
            error = if (message == PlaybackStallDetector.MESSAGE) message else userMessage(message),
        ))
        return null
    }

    companion object {
        // Accept only structured policy codes and fixed sentences, never arbitrary exception text.
        private val exoReason = Regex("^ERROR_CODE_[A-Z_]+: ([a-z_0-9]+)$")
        private val httpFailure = Regex("^(?:http_|mpv_http_)([1-5][0-9]{2})$")

        fun userMessage(message: String): String {
            val reason = exoReason.matchEntire(message)?.groupValues?.get(1) ?: message
            val status = httpFailure.matchEntire(reason)?.groupValues?.get(1)?.toInt()
            return when {
                status == 401 || status == 403 -> "The link was refused (HTTP $status)"
                status == 404 || status == 410 -> "The video wasn't found (HTTP $status)"
                status != null -> "The media couldn't be loaded (HTTP $status)"
                reason in setOf("unsupported_format", "mpv_unsupported_format") -> "This video format isn't supported"
                reason in setOf("decoder_failure", "mpv_decoder_failure") -> "The TV couldn't decode this video"
                reason in setOf("network_exhausted", "live_network_exhausted", "timeout_exhausted", "live_timeout_exhausted", "mpv_network_failed") -> "The TV couldn't reach the video server"
                reason == "playback_stalled" || reason == "The video stopped loading" -> "The video stopped loading"
                else -> "The media couldn't be loaded"
            }
        }
    }
}
