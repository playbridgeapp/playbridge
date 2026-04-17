package com.playbridge.player.stremio

import android.util.Log
import com.playbridge.player.logging.FileLogger
import com.playbridge.protocol.SeriesContext
import com.playbridge.protocol.SeriesEpisodeRef
import com.playbridge.player.stremio.ScoredStremioStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SeriesNavigator"

/**
 * Tracks series playback state and resolves fresh stream URLs for prev/next navigation.
 *
 * Constructed once per playback session from the [SeriesContext] delivered in the initial
 * PlayPayload. [qualityPreference] comes from PlayPayload.defaultVideoQuality and is passed
 * separately since SeriesContext doesn't carry it.
 *
 * Two navigation modes:
 *  - **List mode** (preferred): [SeriesContext.allEpisodes] is non-null — navigation is
 *    index-based over the flat episode list. Season boundaries are transparent; the list
 *    is assumed sorted by (season, episode).
 *  - **Optimistic mode**: [SeriesContext.allEpisodes] is null — episode number is simply
 *    incremented/decremented. [hasNext] always returns true in this mode; a null result
 *    from [resolveNext] signals the addon returned nothing (end of series).
 */
class SeriesNavigator(
    val context: SeriesContext,
    /** Mirrors PlayPayload.defaultVideoQuality — "2160p", "1080p", "720p", or null for best. */
    val qualityPreference: String? = null,
    val contentType: String = "series" // "movie" or "series"
) {

    // ── Concurrency guard ─────────────────────────────────────────────────────

    /**
     * Serialises all resolution calls so that rapid Next/Prev taps or a user picker
     * tap racing with auto-advance never run two network requests concurrently.
     * Each suspend resolution function acquires this lock for its entire duration,
     * so a second caller always observes the already-advanced [currentIndex].
     */
    private val mutex = Mutex()

    // ── State ──────────────────────────────────────────────────────────────────

    /** Flat episode list sorted by (season, episode), or null when not provided. */
    val episodeList: List<SeriesEpisodeRef>? =
        if (contentType == "series") context.allEpisodes?.sortedWith(compareBy({ it.season }, { it.episode }))
        else null

    /** Series title for display. */
    val seriesTitle: String?
        get() = context.seriesTitle

    /**
     * Release-group / source hint for the currently playing stream.
     * Seeded by [updateSourceHint] once the player starts, then updated automatically
     * after each successful [resolveNext] / [resolvePrev] / [resolveAndAdvanceToIndex].
     * Passed to [StremioClient] so it can prefer the same torrent source for subsequent
     * episodes (season pack of same release group > other sources at same quality).
     */
    var currentSourceHint: String? = null
        private set

    /**
     * Current index within [episodeList] (list mode), or null (optimistic mode).
     * Advanced/rewound by navigation calls.
     */
    var currentIndex: Int? = episodeList
        ?.indexOfFirst { it.season == context.season && it.episode == context.episode }
        ?.takeIf { it >= 0 }
        private set

    /** Current season — kept in sync with currentIndex in list mode. */
    var currentSeason: Int = context.season
        private set

    /** Current episode — kept in sync with currentIndex in list mode. */
    var currentEpisode: Int = context.episode
        private set

    /** Episode title for the current position, if known. */
    val currentEpisodeTitle: String?
        get() = episodeList?.getOrNull(currentIndex ?: -1)?.title ?: context.episodeTitle

    init {
        Log.d(TAG, "SeriesNavigator created: ${context.seriesTitle} " +
            "S${context.season}E${context.episode} " +
            "mode=${if (episodeList != null) "list(${episodeList.size} eps)" else "optimistic"} " +
            "currentIndex=$currentIndex qualityPref=$qualityPreference " +
            "addons=${context.addonBaseUrls.size}")
    }

    // ── Source hint ───────────────────────────────────────────────────────────

    /**
     * Seed or update the source hint from the stream that is currently playing.
     * Call this from ExoPlayerActivity once the player starts (or after a new episode
     * begins), passing the stream URL and stream name/title if known.
     *
     * The hint is also updated automatically after every successful [resolveNext],
     * [resolvePrev], or [resolveAndAdvanceToIndex].
     */
    fun updateSourceHint(url: String?, name: String? = null, title: String? = null) {
        val hint = extractSourceHint(url, name, title)
        if (hint != null && hint != currentSourceHint) {
            Log.d(TAG, "Source hint updated: $currentSourceHint → $hint")
            currentSourceHint = hint
        }
    }

    // ── Navigation state queries ───────────────────────────────────────────────

    /**
     * True if there is a known next episode.
     * Always true in optimistic mode (we try and see if the addon returns streams).
     */
    fun hasNext(): Boolean {
        val idx = currentIndex ?: return true // optimistic: always try
        return idx < (episodeList?.lastIndex ?: 0)
    }

    /**
     * True if there is a known previous episode.
     * In optimistic mode, false only if we're at S01E01.
     */
    fun hasPrev(): Boolean {
        val idx = currentIndex ?: return currentSeason > 1 || currentEpisode > 1
        return idx > 0
    }

    /**
     * Metadata for the next episode without resolving a stream URL.
     * Useful for showing "Up Next" UI before the user reaches end-of-episode.
     */
    fun peekNext(): SeriesEpisodeRef? {
        val idx = currentIndex
        return if (idx != null) {
            episodeList?.getOrNull(idx + 1)
        } else {
            // Optimistic: simple increment; assume <100 episodes per season
            SeriesEpisodeRef(season = currentSeason, episode = currentEpisode + 1)
        }
    }

    /**
     * Metadata for the previous episode without resolving a stream URL.
     */
    fun peekPrev(): SeriesEpisodeRef? {
        val idx = currentIndex
        return if (idx != null) {
            episodeList?.getOrNull(idx - 1)
        } else {
            when {
                currentEpisode > 1 -> SeriesEpisodeRef(season = currentSeason, episode = currentEpisode - 1)
                currentSeason > 1  -> null // We don't know the last episode of the previous season
                else               -> null // S01E01 — nowhere to go
            }
        }
    }

    // ── Resolution ────────────────────────────────────────────────────────────

    /**
     * Resolve all available streams for the current content.
     * Useful for showing a "Stream Selection" dialog to the user.
     */
    suspend fun resolveCurrentStreams(): List<ScoredStremioStream> = mutex.withLock {
        Log.d(TAG, "resolveCurrentStreams: resolving $contentType ${context.imdbId}")
        StremioClient.resolveStreamsByContentId(
            addonBaseUrls          = context.addonBaseUrls,
            addonNames             = context.addonNames,
            contentId              = context.imdbId,
            contentType            = contentType,
            season                 = if (contentType == "series") currentSeason else null,
            episode                = if (contentType == "series") currentEpisode else null,
            qualityPreference      = qualityPreference,
            sourceHint             = currentSourceHint,
            preferredAddonBaseUrl  = context.preferredAddonBaseUrl,
            preferredAddonName     = context.preferredAddonName
        )
    }

    /**
     * Resolve a fresh stream URL for the next episode.
     *
     * On success, advances internal state so subsequent calls move one episode further.
     * Returns null if:
     *  - Already at the last episode (list mode)
     *  - The addon returns no streams (optimistic mode — likely end of series)
     */
    suspend fun resolveNext(): ResolvedStremioStream? = mutex.withLock {
        if (!hasNext()) {
            FileLogger.i(TAG, "resolveNext: already at last known episode")
            return@withLock null
        }
        val nextRef = peekNext() ?: return@withLock null

        FileLogger.i(TAG, "Navigating to NEXT episode: S${nextRef.season}E${nextRef.episode}")
        val stream = StremioClient.resolveEpisode(
            addonBaseUrls          = context.addonBaseUrls,
            addonNames             = context.addonNames,
            imdbId                 = context.imdbId,
            season                 = nextRef.season,
            episode                = nextRef.episode,
            qualityPref            = qualityPreference,
            hint                   = currentSourceHint,
            prefUrl                = context.preferredAddonBaseUrl,
            prefName               = context.preferredAddonName
        )

        if (stream != null) {
            jumpTo(nextRef)
            updateSourceHint(stream.url, stream.name, stream.title)
            stream
        } else {
            FileLogger.w(TAG, "resolveNext: no streams for S${nextRef.season}E${nextRef.episode} — likely series end")
            null
        }
    }

    /**
     * Resolve a fresh stream URL for the previous episode.
     *
     * On success, rewinds internal state. Returns null if at the first episode or addon
     * returns nothing.
     */
    suspend fun resolvePrev(): ResolvedStremioStream? = mutex.withLock {
        if (!hasPrev()) {
            FileLogger.i(TAG, "resolvePrev: already at first episode")
            return@withLock null
        }
        val prevRef = peekPrev() ?: return@withLock null

        FileLogger.i(TAG, "Navigating to PREVIOUS episode: S${prevRef.season}E${prevRef.episode}")
        val stream = StremioClient.resolveEpisode(
            addonBaseUrls          = context.addonBaseUrls,
            addonNames             = context.addonNames,
            imdbId                 = context.imdbId,
            season                 = prevRef.season,
            episode                = prevRef.episode,
            qualityPref            = qualityPreference,
            hint                   = currentSourceHint,
            prefUrl                = context.preferredAddonBaseUrl,
            prefName               = context.preferredAddonName
        )

        if (stream != null) {
            jumpTo(prevRef)
            updateSourceHint(stream.url, stream.name, stream.title)
            stream
        } else {
            FileLogger.w(TAG, "resolvePrev: no streams for S${prevRef.season}E${prevRef.episode}")
            null
        }
    }

    /**
     * Resolve a fresh stream URL for a specific episode index (list mode only).
     *
     * On success, jumps the internal state to this index. Returns null if index is out of bounds
     * or addon returns nothing.
     */
    suspend fun resolveAndAdvanceToIndex(index: Int): ResolvedStremioStream? = mutex.withLock {
        val targetRef = episodeList?.getOrNull(index) ?: return@withLock null

        FileLogger.i(TAG, "Jumping to episode index $index: S${targetRef.season}E${targetRef.episode}")
        val stream = StremioClient.resolveEpisode(
            addonBaseUrls          = context.addonBaseUrls,
            addonNames             = context.addonNames,
            imdbId                 = context.imdbId,
            season                 = targetRef.season,
            episode                = targetRef.episode,
            qualityPref            = qualityPreference,
            hint                   = currentSourceHint,
            prefUrl                = context.preferredAddonBaseUrl,
            prefName               = context.preferredAddonName
        )

        if (stream != null) {
            jumpTo(targetRef)
            updateSourceHint(stream.url, stream.name, stream.title)
            stream
        } else {
            FileLogger.w(TAG, "resolveAndAdvanceToIndex: no streams for S${targetRef.season}E${targetRef.episode}")
            null
        }
    }

    // ── State mutation (private) ───────────────────────────────────────────────

    /**
     * Move internal state to the given episode ref.
     *
     * In list mode the new [currentIndex] is derived by looking up [ref] in [episodeList]
     * rather than blindly doing ±1. This prevents index drift when concurrent calls are
     * serialised by [mutex] — each call observes the already-updated state and calculates
     * the correct position independently of call order.
     */
    private fun jumpTo(ref: SeriesEpisodeRef) {
        currentSeason  = ref.season
        currentEpisode = ref.episode
        currentIndex   = episodeList?.indexOfFirst {
            it.season == ref.season && it.episode == ref.episode
        }?.takeIf { it >= 0 }
        FileLogger.i(TAG, "Playlist item picked → S${currentSeason}E${currentEpisode} (index=$currentIndex)")
    }
}
