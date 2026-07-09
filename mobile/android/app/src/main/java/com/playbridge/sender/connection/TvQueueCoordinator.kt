package com.playbridge.sender.connection

import android.content.Context
import android.util.Log
import com.playbridge.sender.data.library.AddonRepository
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.player.AutoPickPrefs
import com.playbridge.shared.protocol.createQueueAddCommandJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One episode in a [TvEpisodeQueuePlan]: the addon stream ID to resolve and a ready-to-send
 *  [playbridge.PlayPayload] template (already decorated with prefs/metadata; `url` filled in
 *  by the coordinator after resolution). */
data class TvQueueEpisode(
    val streamId: String,
    val template: playbridge.PlayPayload
)

/**
 * Ordered plan for auto-advancing a series on the TV without a play-endpoint ("Hub") addon.
 * [startIndex] is the episode that was already sent as the initial play; the coordinator
 * resolves and queues the ones after it.
 */
data class TvEpisodeQueuePlan(
    val streamType: String,
    val forcedSource: String?,
    val bingeGroup: String?,
    val startIndex: Int,
    val items: List<TvQueueEpisode>
)

/** A combined tick of the signals the coordinator reacts to. */
private data class QueueSignal(
    val playlistIndex: Int?,
    val title: String?,
    val activeContext: String,
    val window: Int
)

/**
 * Keeps a small window of upcoming episodes resolved and queued on the TV for series that
 * have no play-endpoint addon (the TV can't resolve addon/debrid streams itself).
 *
 * Flow: the caller sends the current episode as a one-item playlist, then calls [start].
 * This watches the TV's reported playlist position ([ConnectionCoordinator.tvPlaylistState])
 * and, whenever fewer than `tvPrefetchWindow` episodes are queued ahead of the current one,
 * resolves the next episode (preferring the same Stremio `bingeGroup`, else the usual
 * auto-pick) and appends it with `queue_add`.
 *
 * The phone must stay connected: with a window of 1 the TV finishes the current episode plus
 * the one queued ahead if the phone drops. A larger window trades link-freshness for more
 * disconnect headroom. Episodes whose streams can't be resolved are skipped.
 */
class TvQueueCoordinator(
    private val context: Context,
    private val webSocketClient: WebSocketClient,
    private val addonRepository: AddonRepository,
    private val connectionCoordinator: ConnectionCoordinator,
    private val settingsRepository: SettingsRepository,
    private val subtitleService: com.playbridge.sender.data.library.StremioSubtitleService,
    private val scope: CoroutineScope
) {
    private val TAG = "TvQueueCoordinator"
    private val mutex = Mutex()

    private var plan: TvEpisodeQueuePlan? = null

    /** Episode index at each TV queue position (position 0 == the first queued item). */
    private val queuedEpisodeIndices = mutableListOf<Int>()
    /** Next episode index we will attempt to resolve & enqueue. */
    private var nextToResolve = 0
    /** Episode currently playing on the TV (forward-only); drives how far ahead we buffer. */
    private var currentEpisodeIndex = 0
    private var window = 1
    private var autoPick = AutoPickPrefs("Auto", null, "", emptySet())
    /** Bumped on every start()/stop()/re-attach so a slow re-attach can't clobber a newer session. */
    @Volatile private var epoch = 0
    @Volatile private var reattaching = false
    /**
     * Serialises [topUp] so concurrent signal ticks / start()+signal races cannot claim the same
     * [nextToResolve] and both `queue_add` the same episode before either advances the pointer.
     * Held only as a mutual-exclusion flag — network I/O still runs outside [mutex].
     */
    private val topUpGate = Mutex()

    init {
        // Single long-lived watcher. While a plan is active it keeps the buffer full; while idle
        // it re-attaches to a series the TV is already playing (e.g. after the app was reopened),
        // using the resolution context the TV echoes back in playlist_status.
        scope.launch {
            combine(
                connectionCoordinator.tvPlaylistState,
                connectionCoordinator.tvPlayback,
                connectionCoordinator.tvActiveContext,
                settingsRepository.tvPrefetchWindow
            ) { pl, pb, ctx, win -> QueueSignal(pl?.currentIndex, pb?.title, ctx, win) }
                .collect { sig -> onSignal(sig) }
        }
    }

    /** Begin managing the TV queue for [newPlan]. The initial play for [TvEpisodeQueuePlan.startIndex]
     *  must already have been sent (as a one-item playlist) before calling this. */
    fun start(newPlan: TvEpisodeQueuePlan) {
        scope.launch {
            mutex.withLock {
                epoch++
                plan = newPlan
                queuedEpisodeIndices.clear()
                queuedEpisodeIndices.add(newPlan.startIndex)
                nextToResolve = newPlan.startIndex + 1
                currentEpisodeIndex = newPlan.startIndex
                autoPick = AutoPickPrefs.fromContext(context)
            }
            Log.d(TAG, "Started: ${newPlan.items.size} episodes, start=${newPlan.startIndex}, bingeGroup=${newPlan.bingeGroup}")
            topUp()
        }
    }

    fun stop() {
        // Bump epoch immediately so an in-flight topUp (outside the mutex during resolve)
        // drops its result even before the coroutine below acquires the lock — critical on
        // app Exit where finishAndRemoveTask may leave the process alive briefly.
        epoch++
        scope.launch { mutex.withLock { clearLocked() } }
    }

    private suspend fun onSignal(sig: QueueSignal) {
        if (sig.activeContext == "idle") {
            mutex.withLock { clearLocked() }
            return
        }
        val p = mutex.withLock { window = sig.window; plan }
        if (p == null) {
            maybeReattach(sig)
            return
        }
        // Track the current episode from whichever signal is freshest (status carries the title
        // on every tick), forward-only so a stale update can't make us under-buffer.
        //
        // Also re-sync the already-queued set from the TV's playlist_status after a reconnect:
        // episodes we thought failed to send may already be on the TV, and the TV may have
        // advanced while we were offline. Extending queuedEpisodeIndices / nextToResolve from
        // the echo prevents re-queue_add of the same S/E (duplicates).
        mutex.withLock {
            val fromTitle = sig.title?.takeIf { it.isNotBlank() }?.let { matchEpisodeByTitle(p, it) } ?: -1
            val fromPlaylist = sig.playlistIndex?.let { queuedEpisodeIndices.getOrNull(it) } ?: -1
            val derived = maxOf(fromTitle, fromPlaylist)
            if (derived > currentEpisodeIndex) currentEpisodeIndex = derived
            mergeTvPlaylistEcho(p)
        }
        topUp()
    }

    /**
     * Align [queuedEpisodeIndices] / [nextToResolve] with the TV's current
     * [ConnectionCoordinator.tvPlaylistState] echo so a reconnect doesn't re-send episodes
     * that already landed on the receiver. Order is TV playlist order (see [QueueBookkeeping]).
     */
    private fun mergeTvPlaylistEcho(p: TvEpisodeQueuePlan) {
        val pl = connectionCoordinator.tvPlaylistState.value ?: return
        if (pl.items.isEmpty()) return
        fun episodeIndexOf(season: Int?, episode: Int?): Int {
            if (season == null || episode == null) return -1
            return p.items.indexOfFirst {
                val vm = it.template.visual_metadata
                vm?.season == season && vm.episode == episode
            }
        }
        val echoed = pl.items.mapNotNull { episodeIndexOf(it.season, it.episode).takeIf { i -> i >= 0 } }
        val merged = QueueBookkeeping.mergeQueuedEpisodeIndices(queuedEpisodeIndices, echoed)
        if (merged == queuedEpisodeIndices) return
        queuedEpisodeIndices.clear()
        queuedEpisodeIndices.addAll(merged)
        nextToResolve = QueueBookkeeping.nextToResolveAfter(merged, nextToResolve)
        Log.d(TAG, "Merged TV playlist echo: alreadyQueued=$queuedEpisodeIndices, nextToResolve=$nextToResolve")
    }

    /** Find the plan episode whose title the TV is currently reporting (exact, then loose match). */
    private fun matchEpisodeByTitle(p: TvEpisodeQueuePlan, title: String): Int {
        val exact = p.items.indexOfFirst { it.template.title == title }
        if (exact >= 0) return exact
        // Boundary-aware prefix match: the TV reports the template title plus a suffix
        // (" (n/m)"), so prefix+boundary covers the real case. A plain contains() matched
        // "Show S1E1" inside "Show S1E10 …" when episode titles are blank, deriving the
        // wrong current episode.
        fun boundaryMatch(candidate: String?): Boolean {
            if (candidate.isNullOrBlank()) return false
            val longer = if (title.length >= candidate.length) title else candidate
            val shorter = if (title.length >= candidate.length) candidate else title
            if (!longer.startsWith(shorter)) return false
            return longer.length == shorter.length || !longer[shorter.length].isLetterOrDigit()
        }
        return p.items.indexOfFirst { boundaryMatch(it.template.title) }
    }

    private fun clearLocked() {
        epoch++
        plan = null
        queuedEpisodeIndices.clear()
        nextToResolve = 0
        currentEpisodeIndex = 0
    }

    /** One unit of top-up work, snapshotted under the lock. */
    private data class TopUpWork(
        val plan: TvEpisodeQueuePlan,
        val index: Int,
        val epoch: Int,
        val picks: AutoPickPrefs,
    )

    /**
     * Resolve & enqueue forward until at least [window] episodes are queued ahead of the
     * current one.
     *
     * Stream resolution + subtitle fetching are network I/O and deliberately run OUTSIDE
     * the plan [mutex]: holding that lock across the fetch would block stop()/start()/every
     * signal tick for the duration of the slowest addon (a user switching series would
     * visibly stall). The epoch/plan re-check on commit discards results that a supersession
     * made stale — the same pattern DlnaQueueCoordinator.advance() uses.
     *
     * Only one [topUp] runs at a time ([topUpGate]): playlist_status / status ticks fire
     * frequently and previously each could enter this loop, both snapshot the same
     * [nextToResolve], both resolve, and both send `queue_add` before either advances the
     * pointer — which produced duplicate episodes on the TV for series casts.
     */
    private suspend fun topUp() {
        // Serialize callers (start + frequent playlist_status ticks). withLock — not tryLock —
        // so a start() that arrives mid-resolve still runs once the previous topUp exits and
        // can fill the new plan's window; tryLock would drop that kick and wait for a signal.
        topUpGate.withLock {
            while (true) {
                val work = mutex.withLock {
                    val p = plan ?: return@withLock null
                    // Don't claim indices while the socket is down — a disconnect mid-binge used
                    // to resolve+fail-send in a loop, and a delivered-but-unacked queue_add would
                    // be retried after reconnect as a duplicate (TV-side dedup is the backstop).
                    if (!webSocketClient.isConnected()) return@withLock null
                    val ahead = queuedEpisodeIndices.count { it > currentEpisodeIndex }
                    if (ahead >= window || nextToResolve > p.items.lastIndex) return@withLock null
                    // Claim the index immediately so a superseding start()/re-attach that races
                    // the resolve still sees nextToResolve past this work item; send failure
                    // rolls the claim back below.
                    val index = nextToResolve
                    nextToResolve = index + 1
                    TopUpWork(p, index, epoch, autoPick)
                } ?: break

                // Slow part — no plan mutex held.
                val url = resolveBest(work.plan, work.index, work.picks)
                val payload = if (url != null) {
                    val tmpl = work.plan.items[work.index].template
                    val subs = fetchSubtitlesFor(tmpl, url)
                    tmpl.copy(url = url, subtitles = (tmpl.subtitles + subs).distinct())
                } else {
                    null
                }

                val stop = mutex.withLock {
                    // Superseded (stop/start/re-attach) while resolving — drop the result.
                    // nextToResolve was already advanced by the claim (or reset by the superseder).
                    if (epoch != work.epoch || plan !== work.plan) return@withLock true
                    if (payload != null) {
                        if (!webSocketClient.send(createQueueAddCommandJson(payload))) {
                            // Send failed (socket dropped) — re-claim so the next tick retries.
                            // Serial topUp means nothing past this claim is in-flight.
                            if (nextToResolve > work.index) nextToResolve = work.index
                            Log.w(TAG, "queue_add failed for episode index ${work.index}; will retry")
                            return@withLock true
                        }
                        queuedEpisodeIndices.add(work.index)
                        val ahead = queuedEpisodeIndices.count { it > currentEpisodeIndex }
                        Log.d(TAG, "Queued episode index ${work.index} (current=$currentEpisodeIndex, $ahead ahead)")
                    } else {
                        Log.w(TAG, "No stream resolved for episode index ${work.index}; skipping")
                    }
                    false
                }
                if (stop) break
            }
        }
    }

    /** Preferred-language addon subtitles for an episode (from its template's visual_metadata). */
    private suspend fun fetchSubtitlesFor(tmpl: playbridge.PlayPayload, resolvedUrl: String?): List<String> {
        val sendSubtitles = runCatching { settingsRepository.sendSubtitlesToTv.first() }.getOrDefault(true)
        if (!sendSubtitles) return emptyList()
        val pref = runCatching { settingsRepository.preferredSubtitleLang.first() }.getOrDefault("")
        val vm = tmpl.visual_metadata
        return subtitleService.getAllSubtitleUrls(
            vm?.imdb_id, vm?.season, vm?.episode, pref,
            videoRelease = subtitleService.filenameFromUrl(resolvedUrl)
        )
    }

    private suspend fun resolveBest(p: TvEpisodeQueuePlan, index: Int, picks: AutoPickPrefs): String? =
        EpisodeStreamResolver.resolveBest(addonRepository, p, index, picks)

    /**
     * When idle, if the TV is already playing a series whose `playlist_status` carries the echoed
     * resolution context (imdbId + per-item season/episode), rebuild the plan from it — re-fetching
     * the episode list by imdbId — and resume queueing. No phone-side persistence required.
     */
    private fun maybeReattach(sig: QueueSignal) {
        if (reattaching || plan != null || sig.activeContext != "player") return
        val pl = connectionCoordinator.tvPlaylistState.value ?: return
        val items = pl.items
        if (items.isEmpty()) return
        val imdbId = items.firstNotNullOfOrNull { it.imdbId } ?: return
        // bingeGroup is set only by our lazy-queue path — its presence is what tells us this is
        // a phone-managed series to resume, not a Hub playlist or any other TV content.
        val bingeGroup = items.firstNotNullOfOrNull { it.bingeGroup } ?: return
        if (items.none { it.season != null && it.episode != null }) return

        reattaching = true
        val myEpoch = epoch
        scope.launch {
            try {
                doReattach(pl, imdbId, bingeGroup, myEpoch)
            } catch (e: Exception) {
                Log.w(TAG, "Re-attach failed: ${e.message}")
            } finally {
                reattaching = false
            }
        }
    }

    private suspend fun doReattach(
        pl: com.playbridge.sender.library.PlaylistUiState,
        imdbId: String,
        bingeGroup: String?,
        myEpoch: Int
    ) {
        // Re-attach only supports imdb-based series (the only thing we lazy-queue), so the stream
        // type is always "series"; source pinning isn't echoed, so resolve across all addons.
        val streamType = "series"
        val forcedSource: String? = null
        val meta = addonRepository.fetchMetaWithSource(streamType, imdbId, forcedSource)?.first ?: return
        val videos = meta.videos
            .filter { it.season != null && it.episode != null && it.season > 0 }
            .distinctBy { Pair(it.season, it.episode) }
            .sortedWith(compareBy({ it.season }, { it.episode }))
        if (videos.size <= 1) return

        val planItems = videos.map { vid ->
            TvQueueEpisode(
                streamId = "$imdbId:${vid.season}:${vid.episode}",
                template = playbridge.PlayPayload(
                    url = "",
                    title = "${meta.name} S${vid.season}E${vid.episode}${if (vid.title.isNotBlank()) " - ${vid.title}" else ""}",
                    content_type = "series",
                    detected_by = "library",
                    binge_group = bingeGroup,
                    visual_metadata = playbridge.VisualMetadata(
                        title = meta.name,
                        season = vid.season,
                        episode = vid.episode,
                        imdb_id = imdbId
                    )
                )
            )
        }
        fun episodeIndexOf(season: Int?, episode: Int?) =
            videos.indexOfFirst { it.season == season && it.episode == episode }

        val queued = pl.items.mapNotNull { episodeIndexOf(it.season, it.episode).takeIf { i -> i >= 0 } }
            .distinct().sorted()
        if (queued.isEmpty()) return
        val current = pl.items.getOrNull(pl.currentIndex)
            ?.let { episodeIndexOf(it.season, it.episode) }?.takeIf { it >= 0 } ?: queued.first()

        mutex.withLock {
            // Abort if a user-initiated start() (or another re-attach) superseded us during the fetch.
            if (epoch != myEpoch || plan != null) return
            plan = TvEpisodeQueuePlan(streamType, forcedSource, bingeGroup, current, planItems)
            queuedEpisodeIndices.clear()
            queuedEpisodeIndices.addAll(queued)
            nextToResolve = queued.last() + 1
            currentEpisodeIndex = current
            autoPick = AutoPickPrefs.fromContext(context)
        }
        Log.d(TAG, "Re-attached: $imdbId, current=$current, alreadyQueued=$queued")
        topUp()
    }
}
