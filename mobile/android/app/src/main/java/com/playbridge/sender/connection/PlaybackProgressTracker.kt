package com.playbridge.sender.connection

import android.util.Log
import com.playbridge.sender.data.library.PlaybackResumeDao
import com.playbridge.sender.data.library.PlaybackResumeEntity
import com.playbridge.sender.data.library.TmdbRepository
import com.playbridge.sender.data.library.WatchlistDao
import com.playbridge.sender.data.library.WatchlistEntity
import com.playbridge.sender.data.library.WatchlistStatus
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.library.PlaylistUiState
import com.playbridge.sender.cast.TvPlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Identity of what the in-app phone player is currently showing, supplied by
 * [com.playbridge.sender.player.PlayerActivity]. Mirrors the visual_metadata the cast
 * paths attach, but sourced from the library screen that launched playback.
 */
data class InAppContent(
    val tmdbId: Int,
    val mediaType: String, // "movie" | "tv"
    val season: Int?,
    val episode: Int?,
    val title: String?,
)

/**
 * Automatic watch-progress tracking (PROGRESS_TRACKING_PLAN.md, P1).
 *
 * Consumes the playback signals the TV already pushes — `status` (position/duration),
 * `playlist_status` (per-item season/episode), `context` — plus the tmdbId every
 * library send path records in [ConnectionCoordinator.nowPlayingTvId], and writes
 * watchlist updates:
 *
 *  - **Episode watched** when position ≥ 90% of a known duration, or when the TV's
 *    playlist advances past it after it played to ≥ 80% (auto-advance — not a manual
 *    skip). Moves [WatchlistEntity.seasonProgress]/[WatchlistEntity.episodeProgress]
 *    forward-only and sets status → WATCHING.
 *  - **Movie watched** at the same threshold → status COMPLETED.
 *  - **Add-on-start** (P2): the moment identified content is playing, it's ensured in
 *    the watchlist as WATCHING (auto-add with TMDB metadata, or status promotion).
 *  - **Series completion** (P2): watching the final aired episode of an ended show
 *    flips it to COMPLETED.
 *  - **Resume points** (P1.5): throttled, content-keyed positions in `playback_resume`
 *    — written every ~10s / on pause / on skip-away, deleted once watched, pruned
 *    after ~6 months. Surfaced in the library detail screen.
 *
 * Covers native playback (TV-pushed status/playlist echo) and external receivers
 * (protocol status + the visual_metadata attached to the loaded MediaItem).
 *
 * Safety: tracks nothing without a tmdbId (browser-detected videos have none), vetoes
 * on a title mismatch (another phone's content), never regresses progress, and dedups
 * via an in-session marked set so the 1s status stream writes each fact once.
 */
class PlaybackProgressTracker(
    private val watchlistDao: WatchlistDao,
    private val resumeDao: PlaybackResumeDao,
    private val tmdb: TmdbRepository,
    settingsRepository: SettingsRepository,
    private val connectionCoordinator: ConnectionCoordinator,
    private val castSessionManager: com.playbridge.sender.cast.CastSessionManager,
    private val scope: CoroutineScope,
) {
    private val TAG = "ProgressTracker"

    private val enabled = settingsRepository.trackWatchProgress
        .stateIn(scope, SharingStarted.Eagerly, true)
    private val autoAdd = settingsRepository.autoAddToWatching
        .stateIn(scope, SharingStarted.Eagerly, true)

    /** Keys ("tmdbId:s:e" / "movie:tmdbId") already written this session. */
    private val markedKeys = mutableSetOf<String>()

    /** Content keys whose watchlist row was already ensured (add-on-start) this session. */
    private val ensuredKeys = mutableSetOf<String>()
    private var lastPlaylistIndex: Int? = null
    private var sessionTmdbId: Int? = null

    /** Last position/duration observed for the *current* playlist item — consulted on
     *  advance to distinguish "finished" from "user skipped ahead". */
    private var lastObservedPositionMs = 0L
    private var lastObservedDurationMs = 0L

    /**
     * Resume-write throttle state, one per transport leg. Keeping this per-leg matters:
     * the native, DLNA and in-app collectors run in the same scope, and a shared throttle
     * would let one leg's tick suppress (or misattribute) another leg's pause-triggered
     * resume write.
     */
    private class ResumeThrottle {
        var lastSavedKey: String? = null
        var lastSavedPosMs = 0L
        var lastState: String? = null

        fun reset() {
            lastSavedKey = null
            lastSavedPosMs = 0L
            lastState = null
        }
    }

    private val nativeThrottle = ResumeThrottle()
    private val dlnaThrottle = ResumeThrottle()
    private val inAppThrottle = ResumeThrottle()
    private val writeMutex = Mutex()

    /**
     * Freshness gate: the watched threshold may only fire after the session has been
     * observed *below* it at least once. Protects against any stale status (e.g. the
     * previous content's near-end position arriving paired with a new identity)
     * instantly "watching" content the user just started.
     */
    private var thresholdArmed = false

    /** DLNA leg state (native and DLNA sessions are mutually exclusive). */
    private var dlnaItem: PlayingItem? = null
    private var dlnaLastObservedPosMs = 0L
    private var dlnaLastObservedDurMs = 0L
    private var dlnaThresholdArmed = false

    /** What's playing, resolved enough to key storage by content. */
    private data class PlayingItem(
        val tmdbId: Int,
        val mediaType: String, // "movie" | "tv"
        val season: Int?,
        val episode: Int?,
        val title: String?,
    ) {
        val key: String
            get() = if (mediaType == "tv") "tmdb:$tmdbId:$season:$episode" else "tmdb:$tmdbId"
    }

    private data class Tick(
        val playback: TvPlaybackStatus?,
        val playlist: PlaylistUiState?,
        val tmdbId: Int?,
        val nowPlayingSeason: Int?,
        val context: String,
    )

    init {
        // Drop stale resume points (default retention ~6 months).
        scope.launch {
            runCatching { resumeDao.prune(System.currentTimeMillis() - RESUME_RETENTION_MS) }
        }
        scope.launch {
            combine(
                connectionCoordinator.tvPlayback,
                connectionCoordinator.tvPlaylistState,
                connectionCoordinator.nowPlayingTvId,
                connectionCoordinator.nowPlayingSeason,
                connectionCoordinator.tvActiveContext,
            ) { pb, pl, id, season, ctx -> Tick(pb, pl, id, season, ctx) }
                .collect { onTick(it) }
        }
        // External-receiver leg: there is no native playlist echo — identity comes from the
        // visual_metadata the phone attached to the loaded MediaItem.
        scope.launch {
            combine(
                castSessionManager.externalStatus,
                castSessionManager.externalNowPlayingMeta,
                castSessionManager.activeExternalDevice,
            ) { status, meta, target -> Triple(status, meta, target) }
                .collect { (status, meta, target) -> onDlnaTick(status, meta, target != null) }
        }
    }

    private suspend fun onTick(t: Tick) {
        if (!enabled.value) return
        if (t.context == "idle") {
            resetSession()
            return
        }
        val tmdbId = t.tmdbId ?: return // no identity (e.g. browser video) — track nothing
        if (tmdbId <= 0) return
        if (tmdbId != sessionTmdbId) {
            resetSession()
            sessionTmdbId = tmdbId
        }

        val items = t.playlist?.items.orEmpty()
        val index = t.playlist?.currentIndex ?: 0

        // 1. Advance: the TV moved past an item. Mark it watched only if the last
        //    position we saw while it was current was near its end — auto-advance
        //    qualifies, a manual "next episode" mid-episode does not (that one keeps
        //    a resume point at where it was left instead).
        val prev = lastPlaylistIndex
        if (prev != null && index != prev) {
            val passed = items.getOrNull(prev)
            val (s, e) = episodeOf(passed?.season, passed?.episode, t, prev)
            if (s != null && e != null) {
                val item = PlayingItem(tmdbId, "tv", s, e, passed?.title)
                if (index > prev &&
                    ProgressRules.finishedOnAdvance(lastObservedPositionMs, lastObservedDurationMs)
                ) {
                    markEpisodeWatched(tmdbId, s, e)
                } else if (lastObservedPositionMs >= MIN_RESUME_POSITION_MS && lastObservedDurationMs > 0) {
                    saveResume(item, lastObservedPositionMs, lastObservedDurationMs, nativeThrottle)
                }
            }
            lastObservedPositionMs = 0L
            lastObservedDurationMs = 0L
        }
        lastPlaylistIndex = index

        // 2. Current item: resume position + watched threshold.
        val pb = t.playback ?: return
        val current = items.getOrNull(index)
        if (!ProgressRules.titlesMatch(pb.title, current?.title)) return
        if (pb.positionMs > 0) lastObservedPositionMs = pb.positionMs
        if (pb.durationMs > 0) lastObservedDurationMs = pb.durationMs

        val (season, episode) = episodeOf(current?.season, current?.episode, t, index)
        val item = when {
            season != null && episode != null ->
                PlayingItem(tmdbId, "tv", season, episode, current?.title ?: pb.title)
            // Series whose playlist echo lacks s/e and no fallback — can't identify; skip.
            t.nowPlayingSeason != null -> return
            else -> PlayingItem(tmdbId, "movie", null, null, pb.title)
        }

        // Playback is reporting → user started watching: ensure a Watching row exists.
        // Skip-ahead catch-up may only fire for the episode the user explicitly sent
        // (it matches the send-time pointer) — NEVER for items the playlist advanced to
        // mid-session (those are judged by the advance rules above; catching up "past"
        // a manually-skipped episode would mark it watched and delete its resume point),
        // and never for a stale echo of the previous content.
        val userStartedThis = item.mediaType != "tv" ||
            (item.season == t.nowPlayingSeason &&
                item.episode == connectionCoordinator.nowPlayingEpisodeStart.value)
        ensureTracked(item, allowCatchUp = userStartedThis)

        if (ProgressRules.isWatched(pb.positionMs, pb.durationMs)) {
            if (!thresholdArmed) return // stale near-end status from before this session
            if (item.mediaType == "tv") markEpisodeWatched(tmdbId, item.season!!, item.episode!!)
            else markMovieWatched(tmdbId)
        } else {
            thresholdArmed = true // seen genuinely-unwatched playback; threshold may fire now
            maybeSaveResume(item, pb.positionMs, pb.durationMs, pb.state, nativeThrottle)
        }
    }

    // ── DLNA leg ────────────────────────────────────────────────────────────

    private suspend fun onDlnaTick(
        status: com.playbridge.sender.cast.PlaybackStatus?,
        meta: playbridge.VisualMetadata?,
        targetActive: Boolean,
    ) {
        if (!enabled.value) return
        if (!targetActive || meta == null) {
            // Session over (explicit stop / target lost) — flush a final resume point for
            // whatever was playing, mirroring the in-app player's close flush.
            val last = dlnaItem
            if (last != null &&
                dlnaLastObservedPosMs >= MIN_RESUME_POSITION_MS && dlnaLastObservedDurMs > 0 &&
                !ProgressRules.isWatched(dlnaLastObservedPosMs, dlnaLastObservedDurMs)
            ) {
                saveResume(last, dlnaLastObservedPosMs, dlnaLastObservedDurMs, dlnaThrottle)
            }
            if (!targetActive && dlnaItem != null) resetSession()
            dlnaItem = null
            dlnaLastObservedPosMs = 0L
            dlnaLastObservedDurMs = 0L
            dlnaThresholdArmed = false
            return
        }
        val tmdbId = meta.tmdb_id?.toIntOrNull()?.takeIf { it > 0 } ?: return
        val item = if (meta.season != null && meta.episode != null) {
            PlayingItem(tmdbId, "tv", meta.season, meta.episode, meta.episode_title ?: meta.title)
        } else {
            PlayingItem(tmdbId, "movie", null, null, meta.title)
        }

        // Item changed (queue advance or a new cast) — judge the previous one, exactly
        // like the native playlist-advance rule.
        val prevItem = dlnaItem
        if (prevItem != null && prevItem.key != item.key) {
            if (advancedForward(prevItem, item) &&
                ProgressRules.finishedOnAdvance(dlnaLastObservedPosMs, dlnaLastObservedDurMs)
            ) {
                markEpisodeWatched(prevItem.tmdbId, prevItem.season!!, prevItem.episode!!)
            } else if (dlnaLastObservedPosMs >= MIN_RESUME_POSITION_MS && dlnaLastObservedDurMs > 0) {
                saveResume(prevItem, dlnaLastObservedPosMs, dlnaLastObservedDurMs, dlnaThrottle)
            }
            dlnaLastObservedPosMs = 0L
            dlnaLastObservedDurMs = 0L
            dlnaThresholdArmed = false // new item — must see it unwatched before marking
        }
        dlnaItem = item

        val st = status ?: return
        if (st.isLive) return
        if (st.positionMs > 0) dlnaLastObservedPosMs = st.positionMs
        if (st.durationMs > 0) dlnaLastObservedDurMs = st.durationMs

        // Catch-up only for the item the user cast (fresh session or a show switch) —
        // queue advances within a show must not imply earlier episodes were seen.
        // NOTE: deliberately more conservative than the native path (which matches the
        // send-time pointer): on DLNA a manual cast of a LATER episode of the same show
        // mid-session is indistinguishable from a queue advance, so it won't catch up.
        // Missing a catch-up is recoverable; marking a skipped episode watched is not.
        ensureTracked(item, allowCatchUp = prevItem == null || prevItem.tmdbId != item.tmdbId)

        if (ProgressRules.isWatched(st.positionMs, st.durationMs)) {
            if (!dlnaThresholdArmed) return // stale poll from the previous item
            if (item.mediaType == "tv") markEpisodeWatched(item.tmdbId, item.season!!, item.episode!!)
            else markMovieWatched(item.tmdbId)
        } else {
            dlnaThresholdArmed = true
            val stateKey = when (st.state) {
                com.playbridge.sender.cast.PlaybackState.PAUSED -> "paused"
                com.playbridge.sender.cast.PlaybackState.PLAYING -> "playing"
                else -> st.state.name.lowercase()
            }
            maybeSaveResume(item, st.positionMs, st.durationMs, stateKey, dlnaThrottle)
        }
    }

    // ── In-app phone player leg ─────────────────────────────────────────────
    //
    // The in-app player ([PlayerActivity]) is a third, mutually-exclusive transport.
    // It can't be observed via a flow (separate Activity), so it pushes ticks here.
    // The rules are identical to the DLNA leg: per-item state, advance judging,
    // threshold arming, and throttled resume writes.

    private var inAppItem: PlayingItem? = null
    private var inAppLastObservedPosMs = 0L
    private var inAppLastObservedDurMs = 0L
    private var inAppThresholdArmed = false

    /** Feed a position update from the in-app phone player. */
    fun reportInAppProgress(content: InAppContent, positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        scope.launch { onInAppTick(content, positionMs, durationMs, isPlaying) }
    }

    /** The in-app player closed — flush a final resume point and clear the leg. */
    fun reportInAppStopped() {
        scope.launch {
            val item = inAppItem
            if (item != null &&
                inAppLastObservedPosMs >= MIN_RESUME_POSITION_MS && inAppLastObservedDurMs > 0 &&
                !ProgressRules.isWatched(inAppLastObservedPosMs, inAppLastObservedDurMs)
            ) {
                saveResume(item, inAppLastObservedPosMs, inAppLastObservedDurMs, inAppThrottle)
            }
            inAppItem = null
            inAppLastObservedPosMs = 0L
            inAppLastObservedDurMs = 0L
            inAppThresholdArmed = false
        }
    }

    private suspend fun onInAppTick(
        content: InAppContent,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
    ) {
        if (!enabled.value) return
        val tmdbId = content.tmdbId.takeIf { it > 0 } ?: return
        // TV item with no season/episode can't be keyed — skip rather than fabricate.
        if (content.mediaType == "tv" && (content.season == null || content.episode == null)) return
        val item = PlayingItem(tmdbId, content.mediaType, content.season, content.episode, content.title)

        // Item changed (episode advance or a new launch) — judge the previous one exactly
        // like the native playlist-advance / DLNA item-change rule.
        val prevItem = inAppItem
        if (prevItem != null && prevItem.key != item.key) {
            if (advancedForward(prevItem, item) &&
                ProgressRules.finishedOnAdvance(inAppLastObservedPosMs, inAppLastObservedDurMs)
            ) {
                markEpisodeWatched(prevItem.tmdbId, prevItem.season!!, prevItem.episode!!)
            } else if (inAppLastObservedPosMs >= MIN_RESUME_POSITION_MS && inAppLastObservedDurMs > 0) {
                saveResume(prevItem, inAppLastObservedPosMs, inAppLastObservedDurMs, inAppThrottle)
            }
            inAppLastObservedPosMs = 0L
            inAppLastObservedDurMs = 0L
            inAppThresholdArmed = false // new item — must see it unwatched before marking
        }
        inAppItem = item

        if (positionMs > 0) inAppLastObservedPosMs = positionMs
        if (durationMs > 0) inAppLastObservedDurMs = durationMs

        // Catch-up only for the episode this player session was launched on — episode
        // advances inside the player must not imply earlier episodes were seen.
        // NOTE: same conservative trade-off as the DLNA call site above — jumping to a
        // later episode of the same show within one player session won't catch up.
        ensureTracked(item, allowCatchUp = prevItem == null || prevItem.tmdbId != item.tmdbId)

        if (ProgressRules.isWatched(positionMs, durationMs)) {
            if (!inAppThresholdArmed) return // stale near-end position before this session armed
            if (item.mediaType == "tv") markEpisodeWatched(item.tmdbId, item.season!!, item.episode!!)
            else markMovieWatched(item.tmdbId)
        } else {
            inAppThresholdArmed = true
            maybeSaveResume(item, positionMs, durationMs, if (isPlaying) "playing" else "paused", inAppThrottle)
        }
    }

    // ── Resume positions ────────────────────────────────────────────────────

    /** Throttled persist: on item change, every ≥10s of movement, or on pause. */
    private suspend fun maybeSaveResume(
        item: PlayingItem,
        positionMs: Long,
        durationMs: Long,
        state: String?,
        throttle: ResumeThrottle,
    ) {
        val pausedNow = state == "paused" && throttle.lastState != "paused"
        throttle.lastState = state
        if (positionMs < MIN_RESUME_POSITION_MS || durationMs <= 0) return
        val keyChanged = item.key != throttle.lastSavedKey
        val moved = kotlin.math.abs(positionMs - throttle.lastSavedPosMs) >= RESUME_SAVE_INTERVAL_MS
        if (!keyChanged && !moved && !pausedNow) return
        saveResume(item, positionMs, durationMs, throttle)
    }

    private suspend fun saveResume(
        item: PlayingItem,
        positionMs: Long,
        durationMs: Long,
        throttle: ResumeThrottle,
    ) {
        throttle.lastSavedKey = item.key
        throttle.lastSavedPosMs = positionMs
        runCatching {
            resumeDao.upsert(
                PlaybackResumeEntity(
                    contentKey = item.key,
                    tmdbId = item.tmdbId,
                    mediaType = item.mediaType,
                    season = item.season,
                    episode = item.episode,
                    title = item.title,
                    positionMs = positionMs,
                    durationMs = durationMs,
                ),
            )
        }.onFailure { Log.w(TAG, "Resume save failed: ${it.message}") }
    }

    /**
     * Season/episode for a playlist slot. Prefer the TV's echoed values (library queues
     * always carry per-item season/episode in `visual_metadata`). The send-time pointer
     * ([ConnectionCoordinator.nowPlayingEpisodeStart]) only describes the episode the
     * session *started* on, so it's trusted for the start slot alone (index 0).
     *
     * Extrapolating `start + index` across later slots is season-unaware: once a queue
     * crosses a season finale — or carries a stray leading item from a prior cast — the
     * offset runs into bogus episode numbers and corrupts the forward-only progress
     * pointer. Later slots without an echo therefore return null, and the caller skips
     * them rather than writing fabricated progress.
     */
    private fun episodeOf(
        echoedSeason: Int?,
        echoedEpisode: Int?,
        t: Tick,
        index: Int,
    ): Pair<Int?, Int?> {
        if (echoedSeason != null && echoedEpisode != null) return echoedSeason to echoedEpisode
        val season = t.nowPlayingSeason ?: return null to null
        if (index != 0) return null to null
        return season to connectionCoordinator.nowPlayingEpisodeStart.value
    }

    /**
     * True when [next] is a later episode of the same show as [prev] — the only kind of
     * item change that can mean "the previous episode finished and playback advanced".
     * Backward jumps, show switches and tv↔movie changes are user navigation: the
     * previous item keeps a resume point instead of being marked watched.
     */
    private fun advancedForward(prev: PlayingItem, next: PlayingItem): Boolean =
        prev.mediaType == "tv" && next.mediaType == "tv" &&
            prev.tmdbId == next.tmdbId &&
            prev.season != null && prev.episode != null &&
            next.season != null && next.episode != null &&
            ProgressRules.isForwardProgress(next.season, next.episode, prev.season, prev.episode)

    private fun resetSession() {
        markedKeys.clear()
        ensuredKeys.clear()
        lastPlaylistIndex = null
        sessionTmdbId = null
        lastObservedPositionMs = 0L
        lastObservedDurationMs = 0L
        nativeThrottle.reset()
        dlnaThrottle.reset()
        thresholdArmed = false
        dlnaThresholdArmed = false
    }

    /**
     * Add-on-start: the moment identified content is actually playing, make sure it's
     * in the watchlist as Watching — a new row (auto-add, TMDB metadata) or a status
     * promotion from Plan-to-Watch/On-Hold/Dropped. Watching/Completed rows are left
     * alone (rewatches must not demote Completed).
     *
     * Skip-ahead catch-up ([allowCatchUp]): *deliberately starting* S/E implies
     * everything before it has been seen — the progress pointer moves (forward-only) to
     * the episode *before* the one being started, so starting S1E3 marks E1/E2 watched
     * and starting S3E1 marks seasons 1–2 watched. Rewatching an earlier episode never
     * regresses the pointer. Callers pass allowCatchUp=true ONLY for the episode the
     * user explicitly chose to play — never for items playback advanced to mid-session,
     * where catching up would mark a manually-skipped episode watched and delete the
     * resume point the advance rules just saved for it.
     */
    private suspend fun ensureTracked(item: PlayingItem, allowCatchUp: Boolean) {
        if (!ensuredKeys.add(item.key)) return
        writeMutex.withLock {
            val now = System.currentTimeMillis()
            val row = watchlistDao.getByIdSync(item.tmdbId)
                ?: autoAddEntity(item.tmdbId, item.mediaType)
                ?: return
            if (row.status != WatchlistStatus.WATCHING.value &&
                row.status != WatchlistStatus.COMPLETED.value
            ) {
                watchlistDao.updateStatus(
                    item.tmdbId, WatchlistStatus.WATCHING.value, row.startedAt ?: now, null,
                )
                Log.i(TAG, "Playback started — status → Watching: tmdb=${item.tmdbId}")
            }

            // Skip-ahead catch-up (series only; no-op when starting S1E1).
            val season = item.season
            val episode = item.episode
            if (allowCatchUp && item.mediaType == "tv" && season != null && episode != null &&
                (episode > 1 || season > 1)
            ) {
                val catchUpEpisode = episode - 1 // (s, 0) = previous seasons done, this one untouched
                if (ProgressRules.isForwardProgress(season, catchUpEpisode, row.seasonProgress, row.episodeProgress)) {
                    watchlistDao.updateProgress(item.tmdbId, season, catchUpEpisode)
                    // Their resume points are moot now — they're implied watched.
                    runCatching { resumeDao.deleteEpisodesUpTo(item.tmdbId, season, catchUpEpisode) }
                    Log.i(TAG, "Skip-ahead: tmdb=${item.tmdbId} caught up to S${season}E$catchUpEpisode (started E$episode)")
                }
            }
        }
    }

    private suspend fun markEpisodeWatched(tmdbId: Int, season: Int, episode: Int) {
        // Watched → never offer to resume it again. Runs BEFORE the dedup: a rewatch in
        // the same session re-creates resume points that still need cleaning at 90%.
        runCatching { resumeDao.deleteByKey("tmdb:$tmdbId:$season:$episode") }
        if (!markedKeys.add("$tmdbId:$season:$episode")) return
        writeMutex.withLock {
            val existing = watchlistDao.getByIdSync(tmdbId)
                ?: autoAddEntity(tmdbId, mediaType = "tv")
                ?: return
            if (ProgressRules.isForwardProgress(season, episode, existing.seasonProgress, existing.episodeProgress)) {
                watchlistDao.updateProgress(tmdbId, season, episode)
                Log.i(TAG, "Marked watched: tmdb=$tmdbId S${season}E$episode")
            }
            // Pull non-active rows into Watching; never demote Completed (rewatch).
            val now = System.currentTimeMillis()
            when (existing.status) {
                WatchlistStatus.WATCHING.value ->
                    if (existing.startedAt == null) {
                        watchlistDao.updateStatus(tmdbId, existing.status, now, existing.completedAt)
                    }
                WatchlistStatus.COMPLETED.value -> Unit
                else -> watchlistDao.updateStatus(
                    tmdbId, WatchlistStatus.WATCHING.value, existing.startedAt ?: now, null,
                )
            }
        }
        maybeCompleteSeries(tmdbId, season, episode)
    }

    /**
     * Series completion: if the episode just watched is the last aired episode of the
     * final season (and nothing further is scheduled), flip the show to Completed —
     * mirroring what [markMovieWatched] does for movies.
     */
    private fun maybeCompleteSeries(tmdbId: Int, season: Int, episode: Int) {
        if (!tmdb.isConfigured()) return
        scope.launch {
            runCatching {
                val details = tmdb.getTvDetails(tmdbId) ?: return@launch
                if (details.nextEpisodeToAir != null) return@launch // still airing
                val lastSeason = details.seasons
                    .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
                    .maxByOrNull { it.seasonNumber } ?: return@launch
                if (season < lastSeason.seasonNumber) return@launch
                if (season == lastSeason.seasonNumber && episode < lastSeason.episodeCount) return@launch
                writeMutex.withLock {
                    val existing = watchlistDao.getByIdSync(tmdbId) ?: return@launch
                    if (existing.status != WatchlistStatus.COMPLETED.value) {
                        val now = System.currentTimeMillis()
                        watchlistDao.updateStatus(
                            tmdbId, WatchlistStatus.COMPLETED.value, existing.startedAt ?: now, now,
                        )
                        Log.i(TAG, "Series completed: tmdb=$tmdbId (S${season}E$episode was the finale)")
                    }
                }
            }.onFailure { Log.w(TAG, "Series-completion check failed: ${it.message}") }
        }
    }

    private suspend fun markMovieWatched(tmdbId: Int) {
        // Watched → never offer to resume it again. Runs BEFORE the dedup: a rewatch in
        // the same session re-creates resume points that still need cleaning at 90%.
        runCatching { resumeDao.deleteByKey("tmdb:$tmdbId") }
        if (!markedKeys.add("movie:$tmdbId")) return
        writeMutex.withLock {
            val existing = watchlistDao.getByIdSync(tmdbId)
                ?: autoAddEntity(tmdbId, mediaType = "movie")
                ?: return
            if (existing.status != WatchlistStatus.COMPLETED.value) {
                val now = System.currentTimeMillis()
                watchlistDao.updateStatus(
                    tmdbId, WatchlistStatus.COMPLETED.value, existing.startedAt ?: now, now,
                )
                Log.i(TAG, "Marked movie completed: tmdb=$tmdbId")
            }
        }
    }

    /** Insert an untracked item as WATCHING with TMDB metadata; null when disabled/unavailable. */
    private suspend fun autoAddEntity(tmdbId: Int, mediaType: String): WatchlistEntity? {
        if (!autoAdd.value || !tmdb.isConfigured()) return null
        val now = System.currentTimeMillis()
        val entity = runCatching {
            if (mediaType == "tv") {
                tmdb.getTvDetails(tmdbId)?.let {
                    WatchlistEntity(
                        tmdbId = tmdbId, mediaType = "tv", title = it.name,
                        posterUrl = it.posterUrl, year = it.year, rating = it.rating,
                        status = WatchlistStatus.WATCHING.value, startedAt = now,
                    )
                }
            } else {
                tmdb.getMovieDetails(tmdbId)?.let {
                    WatchlistEntity(
                        tmdbId = tmdbId, mediaType = "movie", title = it.title,
                        posterUrl = it.posterUrl, year = it.year, rating = it.rating,
                        status = WatchlistStatus.WATCHING.value, startedAt = now,
                    )
                }
            }
        }.getOrNull() ?: return null
        watchlistDao.insert(entity)
        Log.i(TAG, "Auto-added to watchlist: tmdb=$tmdbId ($mediaType) '${entity.title}'")
        return entity
    }

    companion object {
        /** Don't create resume points for barely-started playback. */
        private const val MIN_RESUME_POSITION_MS = 30_000L

        /** Persist at most one position write per this much playback movement. */
        private const val RESUME_SAVE_INTERVAL_MS = 10_000L

        /** Resume points older than this are pruned at startup. */
        private const val RESUME_RETENTION_MS = 180L * 24 * 60 * 60 * 1000
    }
}
