package com.playbridge.sender.connection

import android.util.Log
import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.remote.RemoteQueueItem
import com.playbridge.sender.cast.remote.RemoteQueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Advances a user-supplied playlist on receivers that expose one media item at a time. */
class ExternalQueueCoordinator(
    private val castSessionManager: CastSessionManager,
    private val scope: CoroutineScope,
) {
    private val TAG = "ExternalQueueCoordinator"
    private val mutex = Mutex()

    private enum class Phase {
        /** No plan. */
        IDLE,

        /** An item is playing; watching position/duration for the end. */
        WATCHING,

        /** Next item's URI was (or is being) sent; waiting for playback to start. */
        ADVANCING,
    }

    private val _remoteQueue = MutableStateFlow<RemoteQueueState?>(null)
    val remoteQueue: StateFlow<RemoteQueueState?> = _remoteQueue.asStateFlow()
    private fun publishQueueLocked() {
        val current = plan
        _remoteQueue.value = current?.let {
            RemoteQueueState(currentIndex = currentIndex, phoneOwned = true, items = it.items.mapIndexed { index, entry ->
                val metadata = entry.visual_metadata
                RemoteQueueItem(index, entry.title.orEmpty(), season = metadata?.season, episode = metadata?.episode, imdbId = metadata?.imdb_id, bingeGroup = entry.binge_group, tmdbId = metadata?.tmdb_id)
            })
        }
    }
    private data class Playlist(val items: List<playbridge.PlayPayload>, val startIndex: Int)
    private var plan: Playlist? = null
    private var currentIndex = 0
    private var phase = Phase.IDLE

    /** Last position/duration seen while WATCHING — used to classify a STOPPED status. */
    private var lastPos = 0L
    private var lastDur = 0L
    private var advanceStartedAt = 0L

    init {
        // Target gone → plan gone.
        scope.launch {
            castSessionManager.activeExternalDevice.collect { if (it == null) stop() }
        }
        // User stop / user cast supersedes the plan.
        scope.launch {
            castSessionManager.externalInterrupts.collect { stop() }
        }
        // The advance state machine, fed by the active protocol's status stream.
        scope.launch {
            castSessionManager.externalStatus.collect { onStatus(it) }
        }
    }

    /**
     * Begin managing auto-advance for [items], loading [startIndex] on the renderer.
     * Every item must already contain its playable URL.
     */
    fun start(items: List<playbridge.PlayPayload>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        val newPlan = Playlist(items.toList(), startIndex.coerceIn(items.indices))
        scope.launch {
            val startTemplate = newPlan.items.getOrNull(newPlan.startIndex) ?: return@launch
            val startUrl = startTemplate.url
            if (startUrl.isNullOrEmpty()) {
                Log.w(TAG, "No stream for start item ${newPlan.startIndex}; not starting plan")
                return@launch
            }
            mutex.withLock {
                plan = newPlan
                currentIndex = newPlan.startIndex
                phase = Phase.ADVANCING // waiting for the start item to begin playing
                lastPos = 0L
                lastDur = 0L
                advanceStartedAt = System.currentTimeMillis()
                publishQueueLocked()
            }
            Log.d(TAG, "Started: ${newPlan.items.size} items, start=${newPlan.startIndex}")
            castSessionManager.playOnExternalFromQueue(
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
        if (plan != null) Log.d(TAG, "Plan cleared (was at item index $currentIndex)")
        plan = null
        currentIndex = 0
        phase = Phase.IDLE
        publishQueueLocked()
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
                    val stopped = st.state == PlaybackState.STOPPED || st.state == PlaybackState.IDLE
                    when {
                        // Stopped well before the end and not by us → TV-side stop; abandon.
                        stopped && knownDuration && !nearEnd -> {
                            Log.d(TAG, "Stopped mid-item (pos=$lastPos/$lastDur) — abandoning plan")
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
                        // Next item is rolling — back to watching.
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

    /** Load the next playable item after [currentIndex]; skip empty URLs. */
    private suspend fun advance() {
        val (p, fromIndex) = mutex.withLock {
            Pair(plan ?: return, currentIndex)
        }
        var next = fromIndex + 1
        while (next <= p.items.lastIndex) {
            val tmpl = p.items[next]
            val url = tmpl.url
            if (!url.isNullOrEmpty()) {
                val stillCurrent = mutex.withLock {
                    if (plan !== p) return
                    currentIndex = next
                    publishQueueLocked()
                    advanceStartedAt = System.currentTimeMillis() // resolution took time; re-arm timeout
                    true
                }
                if (stillCurrent) {
                    Log.d(TAG, "Advancing to item index $next: ${tmpl.title}")
                    castSessionManager.playOnExternalFromQueue(
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
            Log.w(TAG, "No stream resolved for item index $next; skipping")
            next++
        }
        Log.d(TAG, "Playlist finished — clearing plan")
        mutex.withLock { if (plan === p) clearLocked() }
    }

    companion object {
        /** Ignore "durations" shorter than this — bogus values from picky renderers. */
        private const val MIN_DURATION_MS = 60_000L

        /** A STOPPED this close to the known end counts as the item finishing. */
        private const val END_WINDOW_MS = 30_000L

        /** While PLAYING, a position this close to the end triggers the advance. */
        private const val PLAYING_END_MS = 1_500L

        /** Playback within this position confirms the next item actually started. */
        private const val START_CONFIRM_MS = 120_000L

        /** Give the renderer this long to start the next item. */
        private const val ADVANCE_TIMEOUT_MS = 60_000L
    }
}
