package com.playbridge.sender.history

enum class SampleState { PLAYING, PAUSED, BUFFERING, STOPPED, ERROR, IDLE }

/** One playback report, from a native TV status or an external receiver. */
data class PlaybackSample(
    val state: SampleState,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isLive: Boolean = false,
    val title: String? = null,
    val playlistIndex: Int? = null,
    val playlistCount: Int? = null,
    /** Load epoch of an external status; null for native statuses. */
    val epoch: Long? = null,
)

/** The persisted part of a tracked cast. */
data class CastProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val positionReported: Boolean = false,
    val finished: Boolean = false,
    val live: Boolean = false,
    val liveWatchedMs: Long = 0L,
    val failed: Boolean = false,
    val failureReason: String? = null,
    val playlistIndex: Int? = null,
    val playlistCount: Int? = null,
)

data class TrackerWrite(val id: String, val progress: CastProgress)

/**
 * Turns a stream of playback reports into progress for exactly one history entry.
 *
 * Attachment is by the entry id given to [begin], never by title. The reports of the cast that was
 * playing before [begin] keep arriving until the receiver says something new, so they are ignored
 * until [onSample] sees evidence of the new cast. Pure and clock-free: callers pass the time.
 */
class CastPositionTracker {
    var activeId: String? = null
        private set

    private var expectedTitle: String? = null
    private var beginEpoch: Long? = null
    private var beginAtMs = 0L
    private var baseline: PlaybackSample? = null
    private var awaitingFresh = false
    private var cleared = false

    private var lastSeen: PlaybackSample? = null
    private var progress = CastProgress()
    private var everPlayed = false
    private var lastPlayAtMs: Long? = null
    private var lastWriteAtMs: Long? = null
    private var lastWritten: CastProgress? = null
    private var lastWrittenState: SampleState? = null
    private var lastState: SampleState? = null

    /**
     * Starts tracking [id]. Returns the unsaved progress of the entry that was being tracked, which
     * the caller must persist so a quick second cast does not lose the first one's position.
     */
    fun begin(
        id: String,
        atMs: Long,
        title: String?,
        epoch: Long?,
        playlistIndex: Int?,
        playlistCount: Int?,
    ): TrackerWrite? {
        val pending = flushPending()
        activeId = id
        expectedTitle = title?.takeIf { it.isNotBlank() }
        beginEpoch = epoch
        beginAtMs = atMs
        baseline = lastSeen
        awaitingFresh = true
        cleared = false
        progress = CastProgress(playlistIndex = playlistIndex, playlistCount = playlistCount)
        everPlayed = false
        lastPlayAtMs = null
        lastWriteAtMs = null
        lastWritten = progress
        lastWrittenState = null
        lastState = null
        return pending
    }

    /**
     * The cast that is playing right now, from the latest report, or null when nothing is playing
     * or the first report of this cast has not arrived yet.
     */
    fun live(): NowPlaying? {
        val id = activeId ?: return null
        if (awaitingFresh) return null
        val state = lastState ?: return null
        if (state != SampleState.PLAYING && state != SampleState.PAUSED && state != SampleState.BUFFERING) return null
        return NowPlaying(id, progress.positionMs, progress.durationMs, progress.live, state == SampleState.PAUSED)
    }

    /** Stops tracking [id] (or whatever is tracked when null), returning its unsaved progress. */
    fun detach(id: String? = null): TrackerWrite? {
        if (id != null && id != activeId) return null
        val pending = flushPending()
        activeId = null
        return pending
    }

    /** The unsaved progress of the tracked entry, for an app or session ending. */
    fun flush(): TrackerWrite? = flushPending()

    private fun flushPending(): TrackerWrite? {
        val id = activeId ?: return null
        if (awaitingFresh || progress == lastWritten) return null
        lastWritten = progress
        return TrackerWrite(id, progress)
    }

    /** A null [sample] means the receiver report was cleared (idle, disconnected, link dropped). */
    fun onSample(sample: PlaybackSample?, atMs: Long): TrackerWrite? {
        if (sample != null) lastSeen = sample
        val id = activeId ?: return null
        if (awaitingFresh) {
            if (sample == null) { cleared = true; return null }
            if (!isFresh(sample, atMs)) return null
            awaitingFresh = false
        }
        apply(sample, atMs)
        val state = sample?.state
        val significant = state != lastWrittenState || progress.finished != lastWritten?.finished ||
            progress.failed != lastWritten?.failed || progress.live != lastWritten?.live ||
            progress.playlistIndex != lastWritten?.playlistIndex ||
            progress.positionReported != lastWritten?.positionReported ||
            state == null || state == SampleState.STOPPED || state == SampleState.IDLE ||
            state == SampleState.ERROR
        val due = lastWriteAtMs?.let { atMs - it >= WRITE_INTERVAL_MS } ?: true
        if (progress == lastWritten) return null
        if (!significant && !due) return null
        lastWriteAtMs = atMs
        lastWritten = progress
        lastWrittenState = state
        return TrackerWrite(id, progress)
    }

    private fun isFresh(s: PlaybackSample, atMs: Long): Boolean {
        val expected = expectedTitle
        if (expected != null && s.title != null && !s.title.equals(expected, ignoreCase = true)) return false
        val epoch = beginEpoch
        if (epoch != null && s.epoch != null) return s.epoch == epoch
        val base = baseline
        return cleared || base == null ||
            s.title != base.title || s.durationMs != base.durationMs ||
            (base.state in IDLE_STATES && s.state !in IDLE_STATES) ||
            atMs - beginAtMs >= STALE_GRACE_MS
    }

    private fun apply(s: PlaybackSample?, atMs: Long) {
        var p = progress
        val state = s?.state
        if (s != null && s.playlistIndex != null) {
            if (p.playlistIndex != null && p.playlistIndex != s.playlistIndex) {
                // A new item: its position and length are its own.
                p = p.copy(positionMs = 0, durationMs = 0, finished = false)
                lastPlayAtMs = null
            }
            p = p.copy(playlistIndex = s.playlistIndex, playlistCount = s.playlistCount ?: p.playlistCount)
        }
        if (s != null) {
            val playingLike = s.state == SampleState.PLAYING || s.state == SampleState.PAUSED
            if (playingLike) everPlayed = true
            val live = p.live || s.isLive ||
                (playingLike && s.durationMs <= 0 && s.positionMs > 0)
            p = p.copy(live = live)
            if (live) {
                var watched = p.liveWatchedMs
                val last = lastPlayAtMs
                if (s.state == SampleState.PLAYING && last != null) {
                    watched += (atMs - last).coerceIn(0, MAX_LIVE_GAP_MS)
                }
                p = p.copy(liveWatchedMs = watched)
            }
            lastPlayAtMs = if (s.state == SampleState.PLAYING) atMs else null
            when (s.state) {
                SampleState.PLAYING, SampleState.PAUSED, SampleState.BUFFERING -> {
                    if (!live && (s.positionMs > 0 || s.durationMs > 0)) {
                        p = p.copy(
                            positionMs = s.positionMs.coerceAtLeast(0),
                            durationMs = if (s.durationMs > 0) s.durationMs else p.durationMs,
                            positionReported = p.positionReported || playingLike,
                        )
                    }
                    if (s.state == SampleState.PLAYING) p = p.copy(failed = false, failureReason = null)
                }
                SampleState.STOPPED, SampleState.IDLE -> {
                    // Receivers reset the position when they stop; keep the last real one.
                    if (s.durationMs > 0 && p.durationMs == 0L) p = p.copy(durationMs = s.durationMs)
                    if (s.state == SampleState.STOPPED && !everPlayed && !p.positionReported) {
                        p = p.copy(failed = true, failureReason = FAIL_STOPPED_EARLY)
                    }
                }
                SampleState.ERROR -> p = p.copy(failed = true, failureReason = FAIL_RECEIVER_ERROR)
            }
        }
        if (state != SampleState.BUFFERING) {
            p = p.copy(finished = isFinished(p))
        }
        lastState = state
        progress = p
    }

    private fun isFinished(p: CastProgress): Boolean {
        if (p.live || !p.positionReported || p.durationMs <= 0) return false
        val idx = p.playlistIndex
        val count = p.playlistCount
        if (idx != null && count != null && idx < count - 1) return false
        return reachedEnd(p.positionMs, p.durationMs)
    }

    companion object {
        const val WRITE_INTERVAL_MS = 10_000L
        const val STALE_GRACE_MS = 8_000L
        const val MAX_LIVE_GAP_MS = 15_000L
        const val FAIL_STOPPED_EARLY = "stopped_before_playback"
        const val FAIL_RECEIVER_ERROR = "receiver_error"
        const val FAIL_NOT_SENT = "not_sent"
        private val IDLE_STATES = setOf(SampleState.STOPPED, SampleState.IDLE, SampleState.ERROR)

        /** Within 5% of the end, or under 10 seconds left on anything a minute or longer. */
        fun reachedEnd(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && (positionMs * 100 >= durationMs * 95 ||
                (durationMs >= 60_000 && durationMs - positionMs < 10_000))
    }
}
