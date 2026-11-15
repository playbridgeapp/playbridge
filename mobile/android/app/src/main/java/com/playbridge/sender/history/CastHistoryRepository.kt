package com.playbridge.sender.history

import com.playbridge.sender.data.history.CastHistoryDao
import com.playbridge.sender.data.history.CastHistoryEntity
import com.playbridge.sender.data.history.CommandHistoryStamp
import com.playbridge.sender.diagnostics.CastAttempt
import com.playbridge.shared.protocol.decodePlaylistPayloadJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A cast playing on a receiver now; [positionMs] is the receiver's last report. */
data class NowPlaying(
    val id: String,
    val positionMs: Long,
    val durationMs: Long,
    val live: Boolean,
    val paused: Boolean,
)

/** What cast history keeps on the phone. */
data class CastHistoryStorage(val casts: Int, val pictures: Int, val pictureBytes: Long)

/** The encrypted replay source store, as the history sees it. [CastReplayStore] implements it. */
interface ReplaySources {
    val sources: StateFlow<Map<String, CastReplaySource>>
    fun put(id: String, source: CastReplaySource)
    fun delete(id: String)
    fun retainOnly(ids: Set<String>)
    fun clear()
}

/** App-private picture copies. Implementations must never fetch from the network. */
interface PreviewStore {
    /** Copies the already cached picture for [key]; returns its path, or null if there is none. */
    fun save(id: String, key: String?): String?
    fun delete(id: String)
    fun retainOnly(ids: Set<String>)
    fun clear()
    /** Total size of the stored pictures. */
    fun sizeBytes(): Long
}

/** The old non-play command history, counted against the same limit. */
interface LegacyHistory {
    suspend fun stamps(): List<CommandHistoryStamp>
    suspend fun delete(id: Long)
    suspend fun deleteAll()
}

fun interface BridgedOriginPolicy { fun isBridged(pageUrl: String): Boolean }
fun interface PageOriginResolver { fun originOf(mediaUrl: String): String? }
fun interface LinkChecker { suspend fun check(url: String, headers: Map<String, String>): LinkCheckResult }

interface CastHistorySettingsSource { val settings: Flow<CastHistorySettings> }

/**
 * Remembers casts with their position. Everything that writes is serialized, and every write is
 * gated first: a disabled history, a watch party, or a bridged app's cast leaves no row, no replay
 * source, no position and no picture.
 */
class CastHistoryRepository(
    private val dao: CastHistoryDao,
    private val legacy: LegacyHistory,
    private val replay: ReplaySources,
    private val previews: PreviewStore,
    private val config: CastHistorySettingsSource,
    private val bridged: BridgedOriginPolicy,
    private val originResolver: PageOriginResolver,
    private val linkChecker: LinkChecker,
    private val attempts: () -> List<CastAttempt>,
    private val partyActive: () -> Boolean,
    private val clock: () -> Long,
    dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher),
) {
    private val mutex = Mutex()
    private val tracker = CastPositionTracker()
    private val trackerLock = Any()
    private var hint: Hint? = null

    private data class Hint(val pageUrl: String?, val pageInitiated: Boolean, val atMs: Long)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    private val _nowPlayingId = MutableStateFlow<String?>(null)

    /** The cast playing on a receiver right now, with its live position. Changes about once a second. */
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    /** Only the id of [nowPlaying]; changes when playback starts or stops, so the list can watch it cheaply. */
    val nowPlayingId: StateFlow<String?> = _nowPlayingId.asStateFlow()

    private val _checking = MutableStateFlow<Set<String>>(emptySet())

    /** Ids whose link is being checked right now. */
    val checking: StateFlow<Set<String>> = _checking.asStateFlow()

    /** Newest first. Does not include the old non-play command history. */
    val entries: Flow<List<CastHistoryEntry>> =
        combine(dao.observeAll(), replay.sources) { rows, sources ->
            rows.map { it.toEntry(canReplay = it.id in sources, mediaKind = sources[it.id]?.mediaKind) }
        }

    init {
        scope.launch {
            config.settings.distinctUntilChanged().collect { cfg ->
                mutex.withLock { reconcile(cfg) }
            }
        }
    }

    // ---- recording ------------------------------------------------------------------------

    /**
     * A website is about to start a cast from [pageUrl]. Used by the next [onCastStarted] within a
     * few seconds, then forgotten.
     */
    fun noteOrigin(pageUrl: String?, pageInitiated: Boolean = true) {
        synchronized(trackerLock) { hint = Hint(pageUrl, pageInitiated, clock()) }
    }

    /**
     * Call for every cast that starts, on any thread. Detaches the previous entry synchronously so
     * its reports can never be written to the new one, then records (or skips) in order.
     */
    fun onCastStarted(start: CastStart) {
        val merged: CastOrigin
        val pending: TrackerWrite?
        synchronized(trackerLock) {
            val h = hint?.takeIf { start.startedAtMs - it.atMs in 0..HINT_TTL_MS }
            hint = null
            merged = start.origin.copy(
                pageUrl = start.origin.pageUrl ?: h?.pageUrl,
                pageInitiated = start.origin.pageInitiated || h?.pageInitiated == true,
            )
            pending = tracker.begin(
                start.id, start.startedAtMs, start.title ?: start.source?.title, start.epoch,
                start.playlistIndex, start.playlistCount,
            )
            publishNowPlaying(null)
        }
        scope.launch {
            mutex.withLock {
                pending?.let { persist(it) }
                recordStart(start.copy(origin = merged))
            }
        }
    }

    /** Feeds a playback report; a null sample means the receiver's report was cleared. */
    fun onPlaybackSample(sample: PlaybackSample?, atMs: Long = clock()) {
        val write: TrackerWrite?
        synchronized(trackerLock) {
            write = tracker.onSample(sample, atMs)
            publishNowPlaying(tracker.live())
        }
        if (write == null) return
        scope.launch { mutex.withLock { persist(write) } }
    }

    private fun publishNowPlaying(live: NowPlaying?) {
        _nowPlaying.value = live
        _nowPlayingId.value = live?.id
    }

    /** Saves any unsaved position, e.g. when the app is going away. */
    fun flush() {
        val write = synchronized(trackerLock) { tracker.flush() } ?: return
        scope.launch { mutex.withLock { persist(write) } }
    }

    /**
     * Replaces the replay source of an entry that already exists (native sends later learn the
     * pre-proxy URL). Never creates one: a cast that was gated out stays out.
     */
    fun replaceReplaySource(id: String, source: CastReplaySource) {
        scope.launch {
            mutex.withLock {
                if (dao.get(id) == null) return@withLock
                replay.put(id, source)
                val row = dao.get(id)!!
                dao.upsert(row.copy(expiresAtMs = linkExpiresAtMs(source.playingUrl(row.playlistIndex), row.startedAtMs)))
            }
        }
    }

    internal suspend fun recordStart(start: CastStart) {
        val cfg = config.settings.first()
        val skip = gate(cfg, start.origin, bridged, partyActive())
        if (skip != null) {
            synchronized(trackerLock) { tracker.detach(start.id) }
            return
        }
        if (dao.get(start.id) != null) return
        val pageUrl = start.origin.pageUrl ?: start.source?.url?.let(originResolver::originOf)
        val source = start.source
        dao.upsert(
            CastHistoryEntity(
                id = start.id,
                title = (start.title ?: source?.title)?.takeIf { it.isNotBlank() },
                host = hostOf(pageUrl) ?: hostOf(source?.url),
                pageUrl = pageUrl,
                receiverName = start.receiverName,
                receiverKind = start.receiverKind,
                startedAtMs = start.startedAtMs,
                positionMs = 0, durationMs = 0, positionReported = false,
                finished = false, live = false, liveWatchedMs = 0,
                failed = !start.sent,
                failureReason = if (start.sent) null else CastPositionTracker.FAIL_NOT_SENT,
                playlistIndex = start.playlistIndex, playlistCount = start.playlistCount,
                previewPath = null,
                expiresAtMs = source?.let { linkExpiresAtMs(it.playingUrl(start.playlistIndex), start.startedAtMs) },
                linkCheck = null, linkCheckedAtMs = null,
            ),
        )
        if (source != null) replay.put(start.id, source)
        if (cfg.keepPreviews) {
            previews.save(start.id, start.previewKey ?: source?.url)?.let { dao.setPreview(start.id, it) }
        }
        prune(cfg.limit)
    }

    private suspend fun persist(write: TrackerWrite) {
        val before = dao.get(write.id) ?: return
        val cfg = config.settings.first()
        val p = write.progress
        // Without "remember position" nothing about where you were is stored.
        val keep = cfg.rememberPosition
        dao.updateProgress(
            id = write.id,
            positionMs = if (keep) p.positionMs else 0,
            durationMs = if (keep) p.durationMs else 0,
            positionReported = keep && p.positionReported,
            finished = p.finished,
            live = p.live,
            liveWatchedMs = if (keep) p.liveWatchedMs else 0,
            failed = p.failed,
            failureReason = p.failureReason,
            playlistIndex = p.playlistIndex,
            playlistCount = p.playlistCount,
        )
        // A playlist moved to another item: its link, and so its expiry, is a different one.
        if (p.playlistIndex != before.playlistIndex) {
            replay.sources.value[write.id]?.takeIf { it.playlistPayloadJson != null }?.let { source ->
                dao.resetLink(write.id, linkExpiresAtMs(source.playingUrl(p.playlistIndex), before.startedAtMs))
            }
        }
    }

    /**
     * A receiver reported a failure sentence. When it says the link was refused or is gone, the
     * cast being tracked is marked expired. Nothing else about the message is kept.
     */
    fun onReceiverFailure(message: String?) {
        if (!shouldMarkExpiredFromFailure(message)) return
        val id = synchronized(trackerLock) { tracker.activeId } ?: return
        scope.launch {
            mutex.withLock {
                if (dao.get(id) != null) dao.setLinkCheck(id, LinkCheckResult.Expired.name, clock())
            }
        }
    }

    // ---- pruning --------------------------------------------------------------------------

    private data class Slot(val atMs: Long, val id: String?, val legacyId: Long?)

    private suspend fun slots(): List<Slot> =
        dao.all().map { Slot(it.startedAtMs, it.id, null) } +
            legacy.stamps().map { Slot(it.timestamp, null, it.id) }

    /** How many entries (new and old together) lowering the limit to [newLimit] would delete. */
    suspend fun wouldRemove(newLimit: Int): Int = mutex.withLock {
        (slots().size - CastHistorySettings.coerceLimit(newLimit)).coerceAtLeast(0)
    }

    private suspend fun prune(limit: Int) {
        val all = slots()
        if (all.size <= limit) return
        all.sortedByDescending { it.atMs }.drop(limit).forEach { slot ->
            slot.id?.let { removeEntry(it) }
            slot.legacyId?.let { legacy.delete(it) }
        }
    }

    private suspend fun removeEntry(id: String) {
        synchronized(trackerLock) { tracker.detach(id); publishNowPlaying(tracker.live()) }
        dao.delete(id)
        replay.delete(id)
        previews.delete(id)
    }

    private suspend fun reconcile(cfg: CastHistorySettings) {
        if (!cfg.keepPreviews) {
            previews.clear()
            dao.clearPreviewPaths()
        }
        if (!cfg.enabled) return
        // Replay sources made before history existed become entries; any left over are orphans.
        val known = attempts().associateBy { it.id }
        replay.sources.value.forEach { (id, source) ->
            if (dao.get(id) != null) return@forEach
            val attempt = known[id] ?: return@forEach
            dao.upsert(
                CastHistoryEntity(
                    id = id, title = source.title?.takeIf { it.isNotBlank() }, host = hostOf(source.url),
                    pageUrl = null, receiverName = null, receiverKind = attempt.receiver.name,
                    startedAtMs = attempt.startedAtMs, positionMs = 0, durationMs = 0,
                    positionReported = false, finished = false, live = false, liveWatchedMs = 0,
                    failed = attempt.outcome == CastAttempt.AttemptOutcome.FAILED, failureReason = null,
                    playlistIndex = null, playlistCount = null, previewPath = null,
                    expiresAtMs = linkExpiresAtMs(source.url, attempt.startedAtMs),
                    linkCheck = null, linkCheckedAtMs = null,
                ),
            )
        }
        prune(cfg.limit)
        val ids = dao.all().map { it.id }.toSet()
        replay.retainOnly(ids)
        previews.retainOnly(ids)
    }

    // ---- actions --------------------------------------------------------------------------

    /** What is kept, for Settings: casts (new and old), pictures, and the pictures' size. */
    suspend fun storage(): CastHistoryStorage = mutex.withLock {
        val rows = dao.all()
        CastHistoryStorage(
            casts = rows.size + legacy.stamps().size,
            pictures = rows.count { it.previewPath != null },
            pictureBytes = previews.sizeBytes(),
        )
    }

    /** Deletes every stored picture; the casts stay and fall back to a plain tile. */
    suspend fun removePictures() = mutex.withLock {
        previews.clear()
        dao.clearPreviewPaths()
    }

    suspend fun delete(id: String) = mutex.withLock { removeEntry(id) }

    suspend fun deleteMany(ids: Collection<String>) = mutex.withLock { ids.forEach { removeEntry(it) } }

    /** Removes every remembered cast (and old entry), its replay source and its picture. */
    suspend fun clearAll() = mutex.withLock {
        synchronized(trackerLock) { tracker.detach(); publishNowPlaying(null) }
        dao.clear()
        legacy.deleteAll()
        replay.clear()
        previews.clear()
    }

    /**
     * What to cast to play [id] again. With [resume] the start position and item are where it left
     * off, but only when remembering position is on and the entry is not finished or live.
     */
    suspend fun replayPlan(id: String, resume: Boolean): CastHistoryReplay? = mutex.withLock {
        val row = dao.get(id) ?: return@withLock null
        val source = replay.sources.value[id] ?: return@withLock null
        val cfg = config.settings.first()
        val canResume = resume && cfg.rememberPosition && row.positionReported && !row.finished &&
            !row.live && row.positionMs >= CastHistoryEntry.MIN_RESUME_MS
        val originalStart = source.playlistPayloadJson?.let { decodePlaylistPayloadJson(it)?.start_index } ?: 0
        CastHistoryReplay(
            source = source,
            startPositionMs = if (canResume) row.positionMs else 0L,
            startIndex = if (resume && cfg.rememberPosition && !row.finished) {
                (row.playlistIndex ?: originalStart)
            } else originalStart,
        )
    }

    /**
     * Probes the stored link and remembers the answer. [force] is the person asking ("Check again");
     * without it nothing runs unless the "check links" setting is on. Returns null when nothing was
     * checked. Playlist entries are checked on the item that is playing.
     */
    suspend fun checkLink(id: String, force: Boolean = false): LinkCheckResult? {
        val target = mutex.withLock {
            if (!force && !config.settings.first().checkLinks) return null
            val row = dao.get(id) ?: return null
            replay.sources.value[id]?.let { it.playingUrl(row.playlistIndex) to it.headers }
        } ?: return null
        _checking.update { it + id }
        val result = try {
            linkChecker.check(target.first, target.second)
        } finally {
            _checking.update { it - id }
        }
        mutex.withLock {
            if (dao.get(id) != null) dao.setLinkCheck(id, result.name, clock())
        }
        return result
    }

    /**
     * Called when the history screen opens: checks the few casts [selectLinksToCheck] picks, only
     * when the setting is on. Playlists are checked on the playing item.
     */
    suspend fun checkLinksOnOpen(): Int {
        val candidates = mutex.withLock {
            if (!config.settings.first().checkLinks) return 0
            val sources = replay.sources.value
            dao.all().mapNotNull { row ->
                val source = sources[row.id] ?: return@mapNotNull null
                LinkCheckCandidate(
                    id = row.id, url = source.playingUrl(row.playlistIndex), finished = row.finished,
                    savedAtMs = row.startedAtMs, expiresAtMs = row.expiresAtMs,
                    lastCheckResult = row.linkCheck?.let { runCatching { LinkCheckResult.valueOf(it) }.getOrNull() },
                    lastCheckAtMs = row.linkCheckedAtMs,
                )
            }
        }
        val picked = selectLinksToCheck(candidates, clock())
        picked.forEach { checkLink(it.id) }
        return picked.size
    }

    internal companion object {
        const val HINT_TTL_MS = 10_000L
        private val WEBSITE_DETECTIONS = setOf("page_cast", "linked_page")

        /** Why a cast must not be remembered, or null if it may be. Fails closed for websites. */
        fun gate(
            cfg: CastHistorySettings,
            origin: CastOrigin,
            bridged: BridgedOriginPolicy,
            party: Boolean,
        ): String? {
            if (party) return "watch_party"
            if (!cfg.enabled) return "disabled"
            if (cfg.excludeBridgedApps) {
                val page = origin.pageUrl
                if (page != null && bridged.isBridged(page)) return "bridged_app"
                val website = origin.pageInitiated || origin.detectedBy?.lowercase() in WEBSITE_DETECTIONS
                if (page == null && website) return "unknown_website_origin"
            }
            return null
        }
    }
}
