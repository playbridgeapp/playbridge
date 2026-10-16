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
            error = userMessage(message),
        ))
        return null
    }

    companion object {
        // Only interpret Exo's structured policy reason, never arbitrary exception/URL text.
        private val httpFailure = Regex("^ERROR_CODE_IO_BAD_HTTP_STATUS: http_([1-5][0-9]{2})$")

        fun userMessage(message: String): String {
            val status = httpFailure.matchEntire(message)?.groupValues?.get(1)
            return "The media couldn't be loaded" + (status?.let { " (HTTP $it)" } ?: "")
        }
    }
}
