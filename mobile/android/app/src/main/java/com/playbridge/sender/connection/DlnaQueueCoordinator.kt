package com.playbridge.sender.connection

import android.content.Context
import android.util.Log
import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.data.library.AddonRepository
import com.playbridge.sender.player.AutoPickPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phone-driven episode auto-advance for DLNA renderers.
 *
 * DLNA has no queue: the renderer plays exactly one URI. So unlike
 * [TvQueueCoordinator] (which pre-loads the native receiver's queue with `queue_add`),
 * this watches the renderer's polled playback status ([CastSessionManager.dlnaStatus])
 * and, when the current episode reaches its end, resolves the next episode's stream
 * (bingeGroup-consistent, via [EpisodeStreamResolver]) and hands the renderer the new
 * URI. Resolving lazily also keeps debrid links fresh.
 *
 * The plan is abandoned when: the DLNA target is cleared, the user explicitly stops or
 * casts something else ([CastSessionManager.dlnaInterrupts]), playback is stopped
 * mid-episode from the TV's own remote, an advance fails to start playing within a
 * timeout, or the season ends.
 *
 * Requires the phone alive throughout — guaranteed by CastSessionService while a DLNA
 * target is selected.
 */
class DlnaQueueCoordinator(
    private val context: Context,
    private val addonRepository: AddonRepository,
    private val castSessionManager: CastSessionManager,
    private val scope: CoroutineScope,
) {
    private val TAG = "DlnaQueueCoordinator"
    private val mutex = Mutex()

    private enum class Phase {
        /** No plan. */
        IDLE,

        /** An episode is playing; watching position/duration for the end. */
        WATCHING,

        /** Next episode's URI was (or is being) sent; waiting for playback to start. */
        ADVANCING,
    }

    private var plan: TvEpisodeQueuePlan? = null
    private var currentIndex = 0
    private var phase = Phase.IDLE
    private var autoPick = AutoPickPrefs("Auto", null, "", emptySet())

    /** Last position/duration seen while WATCHING — used to classify a STOPPED status. */
    private var lastPos = 0L
    private var lastDur = 0L
    private var advanceStartedAt = 0L

    init {
        // Target gone → plan gone.
        scope.launch {
            castSessionManager.activeDlnaTarget.collect { if (it == null) stop() }
        }
        // User stop / user cast supersedes the plan.
        scope.launch {
            castSessionManager.dlnaInterrupts.collect { stop() }
        }
        // The advance state machine, fed by the ~1s DLNA status poll.
        scope.launch {
            castSessionManager.dlnaStatus.collect { onStatus(it) }
        }
    }

    /**
     * Begin managing auto-advance for [newPlan], loading its start episode on the
     * renderer. The start episode's template must carry a resolved (non-empty) url;
     * later episodes may have empty urls and are resolved lazily by streamId.
     */
    fun start(newPlan: TvEpisodeQueuePlan) {
        scope.launch {
            val startTemplate = newPlan.items.getOrNull(newPlan.startIndex)?.template ?: return@launch
            val startUrl = startTemplate.url.takeIf { it.isNotEmpty() }
                ?: EpisodeStreamResolver.resolveBest(
                    addonRepository, newPlan, newPlan.startIndex, AutoPickPrefs.fromContext(context),
                )
            if (startUrl.isNullOrEmpty()) {
                Log.w(TAG, "No stream for start episode ${newPlan.startIndex}; not starting plan")
                return@launch
            }
            mutex.withLock {
                plan = newPlan
                currentIndex = newPlan.startIndex
                phase = Phase.ADVANCING // waiting for the start episode to begin playing
                lastPos = 0L
                lastDur = 0L
                advanceStartedAt = System.currentTimeMillis()
                autoPick = AutoPickPrefs.fromContext(context)
            }
            Log.d(TAG, "Started: ${newPlan.items.size} episodes, start=${newPlan.startIndex}, bingeGroup=${newPlan.bingeGroup}")
            castSessionManager.playOnDlnaFromQueue(
                MediaItem(
                    url = startUrl,
                    headers = startTemplate.headers,
                    title = startTemplate.title,
                    startPositionMs = startTemplate.start_position_ms ?: 0L,
                    visualMetadata = startTemplate.visual_metadata,
                ),
            )
        }
    }

    fun stop() {
        scope.launch { mutex.withLock { clearLocked() } }
    }

    private fun clearLocked() {
        if (plan != null) Log.d(TAG, "Plan cleared (was at episode index $currentIndex)")
        plan = null
        currentIndex = 0
        phase = Phase.IDLE
        lastPos = 0L
        lastDur = 0L
    }

    private suspend fun onStatus(st: PlaybackStatus?) {
        if (st == null) return
        val launchAdvance = mutex.withLock {
            if (plan == null) return
            when (phase) {
                Phase.IDLE -> false

                Phase.WATCHING -> {
                    if (st.isLive) return // nothing to advance over
                    if (st.state == PlaybackState.PLAYING || st.state == PlaybackState.PAUSED) {
                        if (st.durationMs > 0) lastDur = st.durationMs
                        if (st.positionMs > 0) lastPos = st.positionMs
                    }
                    val knownDuration = lastDur > MIN_DURATION_MS
                    val nearEnd = knownDuration && lastPos >= lastDur - END_WINDOW_MS
                    val endedWhilePlaying = st.state == PlaybackState.PLAYING &&
                        knownDuration && st.positionMs >= lastDur - PLAYING_END_MS
                    val stopped = st.state == PlaybackState.STOPPED
                    when {
                        // Stopped well before the end and not by us → TV-side stop; abandon.
                        stopped && knownDuration && !nearEnd -> {
                            Log.d(TAG, "Stopped mid-episode (pos=$lastPos/$lastDur) — abandoning plan")
                            clearLocked()
                            false
                        }
                        endedWhilePlaying || (stopped && nearEnd) -> {
                            phase = Phase.ADVANCING
                            advanceStartedAt = System.currentTimeMillis()
                            true
                        }
                        else -> false
                    }
                }

                Phase.ADVANCING -> {
                    if (st.state == PlaybackState.PLAYING && st.positionMs in 0..START_CONFIRM_MS) {
                        // Next episode is rolling — back to watching.
                        phase = Phase.WATCHING
                        lastPos = 0L
                        lastDur = if (st.durationMs > 0) st.durationMs else 0L
                    } else if (System.currentTimeMillis() - advanceStartedAt > ADVANCE_TIMEOUT_MS) {
                        Log.w(TAG, "Advance timed out — abandoning plan")
                        clearLocked()
                    }
                    false
                }
            }
        }
        if (launchAdvance) scope.launch { advance() }
    }

    /** Resolve & load the next playable episode after [currentIndex]; skip unresolvable ones. */
    private suspend fun advance() {
        val (p, fromIndex, picks) = mutex.withLock {
            Triple(plan ?: return, currentIndex, autoPick)
        }
        var next = fromIndex + 1
        while (next <= p.items.lastIndex) {
            val tmpl = p.items[next].template
            val url = tmpl.url.takeIf { it.isNotEmpty() }
                ?: EpisodeStreamResolver.resolveBest(addonRepository, p, next, picks)
            if (!url.isNullOrEmpty()) {
                val stillCurrent = mutex.withLock {
                    if (plan !== p) return
                    currentIndex = next
                    advanceStartedAt = System.currentTimeMillis() // resolution took time; re-arm timeout
                    true
                }
                if (stillCurrent) {
                    Log.d(TAG, "Advancing to episode index $next: ${tmpl.title}")
                    castSessionManager.playOnDlnaFromQueue(
                        MediaItem(
                            url = url,
                            headers = tmpl.headers,
                            title = tmpl.title,
                            visualMetadata = tmpl.visual_metadata,
                        ),
                    )
                }
                return
            }
            Log.w(TAG, "No stream resolved for episode index $next; skipping")
            next++
        }
        Log.d(TAG, "Season finished — clearing plan")
        mutex.withLock { if (plan === p) clearLocked() }
    }

    companion object {
        /** Ignore "durations" shorter than this — bogus values from picky renderers. */
        private const val MIN_DURATION_MS = 60_000L

        /** A STOPPED this close to the known end counts as the episode finishing. */
        private const val END_WINDOW_MS = 30_000L

        /** While PLAYING, a position this close to the end triggers the advance. */
        private const val PLAYING_END_MS = 1_500L

        /** Playback within this position confirms the next episode actually started. */
        private const val START_CONFIRM_MS = 120_000L

        /** Give resolution + the renderer this long to start the next episode. */
        private const val ADVANCE_TIMEOUT_MS = 60_000L
    }
}
