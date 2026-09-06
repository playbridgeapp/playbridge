package com.playbridge.sender.data.iptv
import androidx.core.net.toUri

import android.content.Context
import android.net.Uri
import android.util.Log
import com.playbridge.shared.player.IptvChannel
import com.playbridge.shared.player.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Owns IPTV playlist storage: add/edit/delete, (re)parse + cache channels, and probe
 * channel reachability. See IPTV_PLAN.md §4 / §6. Koin singleton.
 */
class IptvRepository(
    private val context: Context,
    private val playlistDao: IptvPlaylistDao,
    private val channelDao: IptvChannelDao,
    httpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "IptvRepository"
        private const val PROBE_CONCURRENCY = 8
        private const val PROBE_TIMEOUT_SEC = 6L
        /** Don't re-probe channels checked more recently than this unless forced. */
        const val PROBE_FRESHNESS_MS = 30 * 60 * 1000L
    }

    // Short-timeout client dedicated to probes so a stalled channel can't hang the pool.
    private val probeClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    fun observePlaylists() = playlistDao.observeAll()
    fun observeChannels(playlistId: Long) = channelDao.observeForPlaylist(playlistId)

    fun observeGroups(playlistId: Long) = channelDao.observeGroups(playlistId)

    /** SQL handles group/order; Kotlin preserves literal Unicode case-insensitive search. */
    fun observeChannelsFiltered(
        playlistId: Long,
        query: String,
        group: String?,
        activeFirst: Boolean,
    ) = channelDao.observeFiltered(playlistId, group, activeFirst)
        .map { channels -> IptvSearchRules.filter(channels, query) }
        .flowOn(Dispatchers.Default)

    /** Add a playlist and immediately parse + cache its channels. Returns the new id. */
    suspend fun addPlaylist(name: String, source: String, sourceType: String): Long {
        val now = System.currentTimeMillis()
        val id = playlistDao.insert(
            IptvPlaylistEntity(
                name = name.trim(),
                source = source.trim(),
                sourceType = sourceType,
                addedAt = now,
                updatedAt = now,
                channelCount = 0,
            ),
        )
        runCatching { refresh(id) }
            .onFailure { Log.w(TAG, "Initial parse failed for playlist $id", it) }
        return id
    }

    suspend fun editPlaylist(id: Long, name: String, source: String, sourceType: String) {
        val existing = playlistDao.getById(id) ?: return
        val sourceChanged = existing.source != source.trim() || existing.sourceType != sourceType
        playlistDao.update(
            existing.copy(name = name.trim(), source = source.trim(), sourceType = sourceType),
        )
        if (sourceChanged) runCatching { refresh(id) }
    }

    suspend fun deletePlaylist(playlist: IptvPlaylistEntity) = playlistDao.delete(playlist)

    /** Re-read the source, re-parse, and atomically replace cached channels. */
    suspend fun refresh(id: Long) {
        val playlist = playlistDao.getById(id) ?: return
        val channels = parseSource(playlist) ?: emptyList()
        val entities = channels.map { it.toEntity(id) }
        channelDao.replaceForPlaylist(id, entities)
        playlistDao.markRefreshed(id, System.currentTimeMillis(), entities.size)
    }

    private suspend fun parseSource(playlist: IptvPlaylistEntity): List<IptvChannel>? =
        when (playlist.sourceType) {
            IptvSourceType.URL -> M3uParser.fetchChannels(playlist.source, null)
            IptvSourceType.FILE -> withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(playlist.source.toUri())
                        ?.bufferedReader()?.use { it.readText() }
                }.getOrNull()?.let { M3uParser.parseM3uText(it) }
            }
            else -> null
        }

    /**
     * Probe each channel's URL for reachability and record status + latency. Bounded
     * concurrency; cancellable; skips channels probed within [PROBE_FRESHNESS_MS] unless
     * [force]. [onProgress] receives (done, total).
     */
    suspend fun probe(
        playlistId: Long,
        force: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val all = channelDao.getForPlaylist(playlistId)
        val now = System.currentTimeMillis()
        val toProbe = if (force) all else all.filter {
            it.probedAt == null || now - it.probedAt > PROBE_FRESHNESS_MS
        }
        val total = toProbe.size
        if (total == 0) {
            onProgress(0, 0)
            return
        }
        val semaphore = Semaphore(PROBE_CONCURRENCY)
        var done = 0
        coroutineScope {
            toProbe.forEach { channel ->
                launch {
                    semaphore.withPermit {
                        val result = probeOne(channel)
                        channelDao.updateProbe(
                            channel.id, result.first, result.second, System.currentTimeMillis(),
                        )
                    }
                    synchronized(semaphore) { done++; onProgress(done, total) }
                }
            }
        }
    }

    /** @return (status, latencyMs?) for one channel. */
    private suspend fun probeOne(channel: IptvChannelEntity): Pair<String, Int?> =
        withContext(Dispatchers.IO) {
            val headers = decodeHeaders(channel.headersJson)
            val builder = Request.Builder()
                .url(channel.url)
                .header("Range", "bytes=0-0")
            headers.forEach { (k, v) -> builder.header(k, v) }
            val start = System.currentTimeMillis()
            try {
                probeClient.newCall(builder.get().build()).execute().use { resp ->
                    val latency = (System.currentTimeMillis() - start).toInt()
                    // 2xx and 206 (partial) are good; 3xx are auto-followed.
                    if (resp.isSuccessful || resp.code == 206) {
                        IptvProbeStatus.ACTIVE to latency
                    } else {
                        IptvProbeStatus.DEAD to null
                    }
                }
            } catch (e: Exception) {
                IptvProbeStatus.DEAD to null
            }
        }
}

private fun IptvChannel.toEntity(playlistId: Long) = IptvChannelEntity(
    playlistId = playlistId,
    name = name,
    url = url,
    logo = logo,
    groupTitle = groupTitle,
    tvgId = tvgId,
    orderIndex = order,
    headersJson = encodeHeaders(headers),
)

private val headerSerializer = MapSerializer(String.serializer(), String.serializer())

internal fun encodeHeaders(headers: Map<String, String>): String? {
    if (headers.isEmpty()) return null
    return Json.encodeToString(headerSerializer, headers)
}

internal fun decodeHeaders(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching { Json.decodeFromString(headerSerializer, json) }.getOrDefault(emptyMap())
}
