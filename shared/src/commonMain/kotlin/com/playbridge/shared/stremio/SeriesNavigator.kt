package com.playbridge.shared.stremio

import com.playbridge.shared.logging.logger
import com.playbridge.shared.protocol.SeriesContext
import com.playbridge.shared.protocol.SeriesEpisodeRef
import kotlin.comparisons.compareBy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SeriesNavigator"

/**
 * Tracks series playback state and resolves fresh stream URLs for prev/next navigation.
 */
class SeriesNavigator(
    val context: SeriesContext,
    /** Mirrors PlayPayload.defaultVideoQuality — "2160p", "1080p", "720p", or null for best. */
    val qualityPreference: String? = null,
    val contentType: String = "series", // "movie" or "series"
    /** Preferred release-type keys (bluray, web-dl, remux, webrip, hdtv, dvd, cam). */
    val preferredSourceTypes: List<String>? = null,
    /** Typical episode runtime in minutes — used together with [maxBitrateMbps] to estimate stream bitrate. */
    val runtimeMinutes: Int? = null,
    /** Soft cap on estimated Mbps; streams above this are deprioritized. */
    val maxBitrateMbps: Double? = null
) {

    private val mutex = Mutex()

    /** Flat episode list sorted by (season, episode), or null when not provided. */
    val episodeList: List<SeriesEpisodeRef>? = if (contentType == "series") {
        context.allEpisodes?.sortedWith(compareBy({ it.season }, { it.episode }))
    } else null

    /** Series title for display. */
    val seriesTitle: String?
        get() = context.seriesTitle

    /** Release-group / source hint for the currently playing stream. */
    var currentSourceHint: String? = null
        private set

    /**
     * Current index within [episodeList] (list mode), or null (optimistic mode).
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
        logger.d(TAG, "SeriesNavigator created: ${context.seriesTitle} " +
            "S${context.season}E${context.episode} " +
            "mode=${if (episodeList != null) "list(${episodeList.size} eps)" else "optimistic"} " +
            "currentIndex=$currentIndex qualityPref=$qualityPreference " +
            "addons=${context.addonBaseUrls.size}")
    }

    fun updateSourceHint(url: String?, name: String? = null, title: String? = null) {
        val hint = extractSourceHint(url, name, title)
        if (hint != null && hint != currentSourceHint) {
            logger.d(TAG, "Source hint updated: $currentSourceHint → $hint")
            currentSourceHint = hint
        }
    }

    fun hasNext(): Boolean {
        val idx = currentIndex ?: return true // optimistic: always try
        return idx < (episodeList?.lastIndex ?: 0)
    }

    fun hasPrev(): Boolean {
        val idx = currentIndex ?: return currentSeason > 1 || currentEpisode > 1
        return idx > 0
    }

    fun peekNext(): SeriesEpisodeRef? {
        val idx = currentIndex
        return if (idx != null) {
            episodeList?.getOrNull(idx + 1)
        } else {
            SeriesEpisodeRef(season = currentSeason, episode = currentEpisode + 1)
        }
    }

    fun peekPrev(): SeriesEpisodeRef? {
        val idx = currentIndex
        return if (idx != null) {
            episodeList?.getOrNull(idx - 1)
        } else {
            when {
                currentEpisode > 1 -> SeriesEpisodeRef(season = currentSeason, episode = currentEpisode - 1)
                currentSeason > 1  -> null
                else               -> null
            }
        }
    }

    suspend fun resolveCurrentStreams(): List<ScoredStremioStream> = mutex.withLock {
        logger.d(TAG, "resolveCurrentStreams: resolving $contentType ${context.imdbId}")
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
            preferredAddonName     = context.preferredAddonName,
            preferredSourceTypes   = preferredSourceTypes,
            runtimeMinutes         = runtimeMinutes,
            maxBitrateMbps         = maxBitrateMbps
        )
    }

    suspend fun resolveNext(): ResolvedStremioStream? = mutex.withLock {
        if (!hasNext()) {
            logger.i(TAG, "resolveNext: already at last known episode")
            return@withLock null
        }
        val nextRef = peekNext() ?: return@withLock null

        logger.i(TAG, "Navigating to NEXT episode: S${nextRef.season}E${nextRef.episode}")
        val stream = StremioClient.resolveEpisode(
            addonBaseUrls          = context.addonBaseUrls,
            addonNames             = context.addonNames,
            imdbId                 = context.imdbId,
            season                 = nextRef.season,
            episode                = nextRef.episode,
            qualityPref            = qualityPreference,
            hint                   = currentSourceHint,
            prefUrl                = context.preferredAddonBaseUrl,
            prefName               = context.preferredAddonName,
            preferredSourceTypes   = preferredSourceTypes,
            runtimeMinutes         = runtimeMinutes,
            maxBitrateMbps         = maxBitrateMbps
        )

        if (stream != null) {
            jumpTo(nextRef)
            updateSourceHint(stream.url, stream.name, stream.title)
            stream
        } else {
            logger.w(TAG, "resolveNext: no streams for S${nextRef.season}E${nextRef.episode} — likely series end")
            null
        }
    }

    suspend fun resolvePrev(): ResolvedStremioStream? = mutex.withLock {
        if (!hasPrev()) {
            logger.i(TAG, "resolvePrev: already at first episode")
            return@withLock null
        }
        val prevRef = peekPrev() ?: return@withLock null

        logger.i(TAG, "Navigating to PREVIOUS episode: S${prevRef.season}E${prevRef.episode}")
        val stream = StremioClient.resolveEpisode(
            addonBaseUrls          = context.addonBaseUrls,
            addonNames             = context.addonNames,
            imdbId                 = context.imdbId,
            season                 = prevRef.season,
            episode                = prevRef.episode,
            qualityPref            = qualityPreference,
            hint                   = currentSourceHint,
            prefUrl                = context.preferredAddonBaseUrl,
            prefName               = context.preferredAddonName,
            preferredSourceTypes   = preferredSourceTypes,
            runtimeMinutes         = runtimeMinutes,
            maxBitrateMbps         = maxBitrateMbps
        )

        if (stream != null) {
            jumpTo(prevRef)
            updateSourceHint(stream.url, stream.name, stream.title)
            stream
        } else {
            logger.w(TAG, "resolvePrev: no streams for S${prevRef.season}E${prevRef.episode}")
            null
        }
    }

    suspend fun resolveAndAdvanceToIndex(index: Int): ResolvedStremioStream? = mutex.withLock {
        val targetRef = episodeList?.getOrNull(index) ?: return@withLock null

        logger.i(TAG, "Jumping to episode index $index: S${targetRef.season}E${targetRef.episode}")
        val stream = StremioClient.resolveEpisode(
            addonBaseUrls          = context.addonBaseUrls,
            addonNames             = context.addonNames,
            imdbId                 = context.imdbId,
            season                 = targetRef.season,
            episode                = targetRef.episode,
            qualityPref            = qualityPreference,
            hint                   = currentSourceHint,
            prefUrl                = context.preferredAddonBaseUrl,
            prefName               = context.preferredAddonName,
            preferredSourceTypes   = preferredSourceTypes,
            runtimeMinutes         = runtimeMinutes,
            maxBitrateMbps         = maxBitrateMbps
        )

        if (stream != null) {
            jumpTo(targetRef)
            updateSourceHint(stream.url, stream.name, stream.title)
            stream
        } else {
            logger.w(TAG, "resolveAndAdvanceToIndex: no streams for S${targetRef.season}E${targetRef.episode}")
            null
        }
    }

    private fun jumpTo(ref: SeriesEpisodeRef) {
        currentSeason  = ref.season
        currentEpisode = ref.episode
        currentIndex   = episodeList?.indexOfFirst {
            it.season == ref.season && it.episode == ref.episode
        }?.takeIf { it >= 0 }
        logger.i(TAG, "Playlist item picked → S${currentSeason}E${currentEpisode} (index=$currentIndex)")
    }
}
