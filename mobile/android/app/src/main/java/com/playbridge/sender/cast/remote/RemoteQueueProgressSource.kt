package com.playbridge.sender.cast.remote

import com.playbridge.sender.data.library.PlaybackResumeDao
import com.playbridge.sender.data.library.PlaybackResumeEntity
import com.playbridge.sender.data.library.WatchlistDao
import com.playbridge.sender.data.library.WatchlistEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

data class RemoteQueueProgress(val resume: PlaybackResumeEntity? = null, val watchlist: WatchlistEntity? = null)

data class RemoteProgressKey(val tmdbId: Int, val season: Int? = null, val episode: Int? = null) {
    val contentKey: String get() = if (season != null && episode != null) "tmdb:$tmdbId:$season:$episode" else "tmdb:$tmdbId"
    val mediaType: String get() = if (season != null) "tv" else "movie"

    companion object {
        fun from(item: RemoteQueueItem): RemoteProgressKey? {
            val id = item.tmdbId?.toIntOrNull()?.takeIf { it > 0 } ?: return null
            if ((item.season == null) != (item.episode == null)) return null
            if (item.season != null && (item.season < 0 || item.episode!! <= 0)) return null
            return RemoteProgressKey(id, item.season, item.episode)
        }
    }
}

internal fun RemoteQueueState?.progressKeys(): Set<RemoteProgressKey> =
    this?.items.orEmpty().mapNotNull(RemoteProgressKey::from).toSet()

fun interface RemoteQueueProgressSource {
    fun observe(keys: Set<RemoteProgressKey>): Flow<Map<String, RemoteQueueProgress>>
}

/** Reads existing tracking only. No remote selection or status tick writes progress. */
class LibraryRemoteQueueProgressSource(
    private val resumeDao: PlaybackResumeDao,
    private val watchlistDao: WatchlistDao,
) : RemoteQueueProgressSource {
    override fun observe(keys: Set<RemoteProgressKey>): Flow<Map<String, RemoteQueueProgress>> {
        if (keys.isEmpty()) return flowOf(emptyMap())
        val streams = keys.groupBy { it.tmdbId }.map { (id, content) ->
            combine(resumeDao.observeForTmdb(id), watchlistDao.getById(id)) { resumes, watchlist ->
                buildMap {
                    content.forEach { key ->
                        val resume = resumes.firstOrNull { it.contentKey == key.contentKey && it.tmdbId == id && it.mediaType == key.mediaType }
                        val tracking = watchlist?.takeIf { it.tmdbId == id && it.mediaType == key.mediaType }
                        if (resume != null || tracking != null) put(key.contentKey, RemoteQueueProgress(resume, tracking))
                    }
                }
            }
        }
        return combine(streams) { snapshots -> snapshots.flatMap { it.entries }.associate { it.toPair() } }
    }
}
