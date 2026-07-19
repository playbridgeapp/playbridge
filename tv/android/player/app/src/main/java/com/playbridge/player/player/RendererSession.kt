package com.playbridge.player.player

import java.util.concurrent.atomic.AtomicLong

internal enum class RendererKind { MPV, EXO, WEBVIEW, IMAGE }

internal enum class RendererSessionPhase { IDLE, PREPARING, READY, PLAYING, STOPPING, FAILED }

internal data class RendererSessionSnapshot(
    val sessionId: Long = 0L,
    val renderer: RendererKind? = null,
    val phase: RendererSessionPhase = RendererSessionPhase.IDLE,
    val error: String? = null,
)

/** Host-side state machine that rejects callbacks from stale renderer sessions. */
internal class RendererSessionCoordinator {
    private var snapshot = RendererSessionSnapshot()

    @Synchronized
    fun current(): RendererSessionSnapshot = snapshot

    @Synchronized
    fun begin(renderer: RendererKind): RendererSessionSnapshot {
        snapshot = RendererSessionSnapshot(sessionIds.incrementAndGet(), renderer, RendererSessionPhase.PREPARING)
        return snapshot
    }

    @Synchronized
    fun markReady(sessionId: Long): Boolean = updateIfCurrent(sessionId) {
        if (it.phase != RendererSessionPhase.PREPARING) return@updateIfCurrent false
        snapshot = it.copy(phase = RendererSessionPhase.READY, error = null)
        true
    }

    @Synchronized
    fun markFirstFrame(sessionId: Long): Boolean = updateIfCurrent(sessionId) {
        if (it.phase != RendererSessionPhase.PREPARING && it.phase != RendererSessionPhase.READY) {
            return@updateIfCurrent false
        }
        snapshot = it.copy(phase = RendererSessionPhase.PLAYING, error = null)
        true
    }

    @Synchronized
    fun canHandleEnded(sessionId: Long): Boolean =
        snapshot.sessionId == sessionId &&
            (snapshot.phase == RendererSessionPhase.READY ||
                snapshot.phase == RendererSessionPhase.PLAYING)

    @Synchronized
    fun requestStop(sessionId: Long): Boolean = updateIfCurrent(sessionId) {
        if (it.phase == RendererSessionPhase.IDLE || it.phase == RendererSessionPhase.STOPPING) {
            return@updateIfCurrent false
        }
        snapshot = it.copy(phase = RendererSessionPhase.STOPPING)
        true
    }

    @Synchronized
    fun markStopped(sessionId: Long): Boolean = updateIfCurrent(sessionId) {
        // Clear the active id as part of teardown. A late callback from the stopped renderer
        // must not be able to mutate the new idle state.
        snapshot = RendererSessionSnapshot()
        true
    }

    @Synchronized
    fun markFailed(sessionId: Long, message: String): Boolean = updateIfCurrent(sessionId) {
        snapshot = it.copy(phase = RendererSessionPhase.FAILED, error = message)
        true
    }

    private inline fun updateIfCurrent(
        sessionId: Long,
        update: (RendererSessionSnapshot) -> Boolean,
    ): Boolean {
        if (snapshot.sessionId != sessionId) return false
        return update(snapshot)
    }

    private companion object {
        // Renderer services can outlive the host Activity. A process-global, wall-clock-seeded
        // sequence prevents a recreated host from reusing an id that a warm service has already
        // accepted and would therefore reject as stale.
        val sessionIds = AtomicLong(System.currentTimeMillis() shl 16)
    }
}

/** Keeps the seek UI at its requested position until the renderer catches up. */
internal class PendingSeekTracker(
    private val confirmationToleranceMs: Long = 3_000L,
    private val timeoutMs: Long = 15_000L,
) {
    private data class PendingSeek(
        val sessionId: Long,
        val originMs: Long,
        val targetMs: Long,
        val startedAtMs: Long,
    )

    private var pending: PendingSeek? = null

    fun start(
        sessionId: Long,
        originMs: Long,
        targetMs: Long,
        nowMs: Long,
    ) {
        pending = PendingSeek(sessionId, originMs, targetMs, nowMs)
    }

    fun displayPosition(sessionId: Long, reportedPositionMs: Long, nowMs: Long): Long {
        val current = pending ?: return reportedPositionMs
        if (current.sessionId != sessionId) return reportedPositionMs

        val reachedTarget = when {
            current.targetMs > current.originMs ->
                reportedPositionMs >= current.targetMs - confirmationToleranceMs
            current.targetMs < current.originMs ->
                reportedPositionMs <= current.targetMs + confirmationToleranceMs
            else -> kotlin.math.abs(reportedPositionMs - current.targetMs) <= confirmationToleranceMs
        }
        val timedOut = nowMs - current.startedAtMs >= timeoutMs
        if (reachedTarget || timedOut) {
            pending = null
            return reportedPositionMs
        }
        return current.targetMs
    }

    fun isPending(sessionId: Long): Boolean = pending?.sessionId == sessionId

    fun clear() {
        pending = null
    }
}
