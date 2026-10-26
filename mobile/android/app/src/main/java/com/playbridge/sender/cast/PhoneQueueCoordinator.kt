package com.playbridge.sender.cast

import android.util.Log
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.connection.LocalCastItem
import com.playbridge.sender.connection.LocalCastPackager
import com.playbridge.sender.connection.QueueCommandResult
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.library.PlaylistUiState
import com.playbridge.shared.protocol.createPlaylistCommandJson
import com.playbridge.shared.protocol.createQueueAddCommandJson
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One entry of the phone's list, as Up next shows it. */
data class PhoneQueueItem(
    val id: String,
    val title: String,
    val durationMs: Long,
    val uri: String,
    val kind: MediaKind,
    val mime: String?,
    val artist: String? = null,
    /** How long it plays: its length, or a photo's slideshow time; 0 when unknown. */
    val playMs: Long = durationMs,
)

/** Where the phone's list is, for the slideshow controls, Up next and the mini player. */
data class PhoneQueueState(
    val active: Boolean = false,
    /** Index of the current item in the phone's list. */
    val position: Int = 0,
    val count: Int = 0,
    val items: List<PhoneQueueItem> = emptyList(),
    /** What the list was started from, e.g. "All songs" or an album; null when unknown. */
    val source: String? = null,
) {
    val current: PhoneQueueItem? get() = items.getOrNull(position)
    /** Items after the current one. */
    val remaining: Int get() = (count - position - 1).coerceAtLeast(0)
}

/**
 * Plays a list of phone files of any length on a PlayBridge TV. The phone keeps the whole
 * list; the TV only holds a window of it ([PhoneQueueWindow]), which this coordinator keeps
 * in step as items play and as the list is edited:
 *
 * - The TV's `playlist_status` (it moved on, or someone edited its queue) and edits on the
 *   phone wake a worker, which waits briefly so quick skips settle, then sends removes,
 *   adds (at most 50 per message) and moves, each tied to this playback and confirmed.
 * - Edits made on the TV are taken in, unless the phone has its own edit waiting, which wins.
 * - Anything else starting on the TV ends the list.
 *
 * Every entry gets an item id the TV keeps, so the phone can tell its items apart. A receiver
 * that ignores sender item ids gets the first [PhoneQueueWindow.TV_LIMIT] items at once instead.
 */
class PhoneQueueCoordinator(
    private val packager: LocalCastPackager,
    private val webSocketClient: WebSocketClient,
    private val connectionCoordinator: ConnectionCoordinator,
    private val castSessionManager: CastSessionManager,
    private val scope: CoroutineScope,
) {
    private class Entry(val id: String, val item: LocalCastItem) {
        val view = PhoneQueueItem(
            id, item.title ?: "Phone file", item.durationMs, item.uri, item.kind, item.mime,
            artist = item.artist?.takeIf { it.isNotBlank() },
            playMs = item.durationMs.takeIf { it > 0 } ?: item.displayDurationMs ?: 0L,
        )
    }

    private val mutex = Mutex()
    private var source: String? = null
    private var entries: MutableList<Entry> = mutableListOf()
    private val byId = HashMap<String, Entry>()
    private var cursor = 0
    /** Ids the TV has confirmed it holds (or held). */
    private val sent = HashSet<String>()
    private var playbackId: String? = null
    private var latest: PlaylistUiState? = null
    /** A phone edit is waiting to be sent: the phone's order wins over the TV's this round. */
    private var dirty = false
    /** Skip TV reports older than our last confirmed change. */
    private var awaitRevision = -1L
    /** An item being jumped to: window updates wait until the TV is on it. */
    private var jumpingTo: String? = null
    /** Ids removed from the phone's list that the TV still holds. */
    private val pendingRemoves = LinkedHashSet<String>()
    private var itemsChanged = true
    private var generation = 0L
    private var attachJob: Job? = null

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(PhoneQueueState())
    val state: StateFlow<PhoneQueueState> = mutableState.asStateFlow()

    /** Up next's Save as collection: the app opens the collection picker with these. */
    private val mutableSaveRequests = kotlinx.coroutines.flow.MutableSharedFlow<List<PhoneQueueItem>>(extraBufferCapacity = 1)
    val saveRequests: kotlinx.coroutines.flow.SharedFlow<List<PhoneQueueItem>> = mutableSaveRequests

    fun requestSave() {
        mutableSaveRequests.tryEmit(mutableState.value.items)
    }

    init {
        scope.launch { connectionCoordinator.tvPlaylistState.collect { onStatus(it) } }
        scope.launch {
            for (signal in wake) {
                delay(SETTLE_MS)
                runCatching { reconcile() }.onFailure { Log.w(TAG, "Queue update failed: ${it.message}") }
            }
        }
    }

    /** True while the TV is playing this phone list. */
    val isActive: Boolean get() = mutableState.value.active

    /** Starts [items] on the TV from the first one. False if it could not be sent. */
    suspend fun start(items: List<LocalCastItem>, source: String? = null): Boolean {
        if (items.isEmpty()) return false
        val list = items.map { Entry(UUID.randomUUID().toString(), it) }
        val session = mutex.withLock {
            clearLocked()
            this.source = source
            entries = list.toMutableList()
            list.forEach { byId[it.id] = it }
            itemsChanged = true
            ++generation
        }
        return sendFrom(0, session)
    }

    /** Inserts [items] right after the current one. */
    suspend fun playNext(items: List<LocalCastItem>) {
        mutex.withLock {
            val added = items.map { Entry(UUID.randomUUID().toString(), it) }
            entries.addAll(cursor + 1, added)
            added.forEach { byId[it.id] = it }
            dirty = true
            itemsChanged = true
            publishLocked()
        }
        wake.trySend(Unit)
    }

    /** Appends [items] to the end of the list. */
    suspend fun addToEnd(items: List<LocalCastItem>) {
        mutex.withLock {
            val added = items.map { Entry(UUID.randomUUID().toString(), it) }
            entries.addAll(added)
            added.forEach { byId[it.id] = it }
            dirty = true
            itemsChanged = true
            publishLocked()
        }
        wake.trySend(Unit)
    }

    /** Moves [delta] items through the list (slideshow previous and next). */
    suspend fun step(delta: Int): Boolean = jumpTo(mutex.withLock { cursor } + delta)

    /**
     * Plays entry [index] of the list. In the TV's window it is a plain jump; otherwise the
     * entry is added to the TV's queue and jumped to, in the same playback, and the window
     * then rebuilds around it.
     */
    suspend fun jumpTo(index: Int): Boolean {
        val (entry, onTv, pid, session) = mutex.withLock {
            val entry = entries.getOrNull(index) ?: return false
            val pid = playbackId ?: return false
            Jump(entry, latest?.items?.any { it.itemId == entry.id } == true, pid, generation)
        }
        // Until the TV is on the new item, a window update would see it as far ahead of the old
        // current item and remove it again.
        // It ends when the TV reports the new item (in reconcile), or after a few seconds.
        // An unconfirmed jump (null) may still happen: a receiver can take a while to confirm
        // (the desktop confirms once the item has opened), so it keeps the guard for longer.
        mutex.withLock { jumpingTo = entry.id }
        var outcome: Boolean? = false
        try {
            outcome = jumpInto(entry, onTv, pid, session)
        } finally {
            if (outcome == false) mutex.withLock { if (jumpingTo == entry.id) jumpingTo = null }
            wake.trySend(Unit)
        }
        if (outcome != false) scope.launch {
            delay(if (outcome == true) JUMP_SETTLE_MS else JUMP_UNCONFIRMED_MS)
            mutex.withLock { if (jumpingTo == entry.id) jumpingTo = null }
            wake.trySend(Unit)
        }
        return outcome == true
    }

    /** True once the TV confirms the jump, false if it refused, null if it didn't answer in time. */
    private suspend fun jumpInto(entry: Entry, onTv: Boolean, pid: String, session: Long): Boolean? {
        var unconfirmedAdd = false
        if (!onTv) {
            val payload = packager.packageItems(listOf(entry.item), listOf(entry.id))?.firstOrNull() ?: return false
            val requestId = UUID.randomUUID().toString()
            val added = connectionCoordinator.sendConfirmedQueueCommand(
                requestId, createQueueAddCommandJson(listOf(payload), pid, requestId), CONFIRM_MS,
            )
            if (added?.ok == false) return false
            mutex.withLock {
                if (session != generation) return false
                if (added != null) sent += entry.id
            }
            // Not confirmed in time, but the TV may well hold it: try the jump anyway.
            unconfirmedAdd = added == null
        }
        val jumped = command("playlist_jump", pid) { put("itemId", entry.id) } ?: return null
        if (jumped.ok && unconfirmedAdd) mutex.withLock { if (session == generation) sent += entry.id }
        return jumped.ok
    }

    // Up next and the Remote call these from UI that may close straight away (the sheet
    // dismisses on tap), so the work runs in this coordinator's scope, not the caller's.
    fun playItem(id: String) {
        scope.launch {
            val index = mutex.withLock { entries.indexOfFirst { it.id == id } }
            if (index >= 0 && !jumpTo(index)) Log.w(TAG, "Couldn't play queue item $index")
        }
    }
    fun removeItem(id: String) { scope.launch { remove(id) } }
    fun moveItem(id: String, toIndex: Int) { scope.launch { move(id, toIndex) } }
    fun clearQueue() { scope.launch { clearUpcoming() } }

    /** Removes entry [id] (not the current one) from the list, and from the TV if it holds it. */
    suspend fun remove(id: String) {
        mutex.withLock {
            val index = entries.indexOfFirst { it.id == id }
            if (index < 0 || index == cursor) return
            entries.removeAt(index)
            byId.remove(id)
            if (index < cursor) cursor--
            if (id in sent) pendingRemoves += id
            dirty = true
            itemsChanged = true
            publishLocked()
        }
        wake.trySend(Unit)
    }

    /** Moves entry [id] to [toIndex] in the list (after the current one). */
    suspend fun move(id: String, toIndex: Int) {
        mutex.withLock {
            val from = entries.indexOfFirst { it.id == id }
            if (from < 0 || from == cursor) return
            val entry = entries.removeAt(from)
            val target = toIndex.coerceIn(cursor + 1, entries.size)
            entries.add(target, entry)
            dirty = true
            itemsChanged = true
            publishLocked()
        }
        wake.trySend(Unit)
    }

    /** Clear queue: drops everything after the current item, which keeps playing. */
    suspend fun clearUpcoming() {
        mutex.withLock {
            val upcoming = entries.subList(cursor + 1, entries.size)
            upcoming.forEach { byId.remove(it.id); if (it.id in sent) pendingRemoves += it.id }
            upcoming.clear()
            dirty = true
            itemsChanged = true
            publishLocked()
        }
        wake.trySend(Unit)
    }

    /** Forgets the list; the TV keeps playing what it holds. */
    suspend fun stop() = mutex.withLock { clearLocked() }

    // ---- Sending ---------------------------------------------------------------------------

    /** Starts a new TV playlist with entry [index] and waits to see the TV take it. */
    private suspend fun sendFrom(index: Int, session: Long): Boolean {
        val entry = mutex.withLock { entries.getOrNull(index) } ?: return false
        val payload = packager.packageItems(listOf(entry.item), listOf(entry.id))?.firstOrNull() ?: return false
        runCatching {
            org.koin.core.context.GlobalContext.get()
                .get<com.playbridge.sender.browser.LinkedPageCastCoordinator>()
                .supersedeIfActive()
        }
        if (!webSocketClient.send(createPlaylistCommandJson(playbridge.PlaylistPayload(items = listOf(payload))))) return false
        castSessionManager.notifyNativePlaybackStarted()
        mutex.withLock {
            if (session != generation) return true
            cursor = index
            sent += entry.id
            publishLocked(active = true)
        }
        attachJob?.cancel()
        attachJob = scope.launch { awaitAttach(entry.id, session) }
        return true
    }

    /**
     * A receiver that keeps sender item ids reports the first item under its id. One that
     * doesn't never will: give it the rest of the list (up to its limit) in one go instead.
     */
    private suspend fun awaitAttach(firstId: String, session: Long) {
        delay(ATTACH_TIMEOUT_MS)
        val fallback = mutex.withLock {
            if (session != generation || playbackId != null) return
            val status = latest ?: connectionCoordinator.tvPlaylistState.value
            val pid = status?.playbackId ?: return
            val rest = entries.drop(cursor + 1).take(PhoneQueueWindow.TV_LIMIT - 1).map { it.item }
            attachJob = null // This job: clearing must not cancel it.
            clearLocked()
            pid to rest
        }
        Log.i(TAG, "Receiver did not keep item ids for $firstId; sending ${fallback.second.size} items at once")
        val payloads = packager.packageItems(fallback.second) ?: return
        for (chunk in payloads.chunked(APPEND_LIMIT)) {
            if (!webSocketClient.send(createQueueAddCommandJson(chunk, fallback.first))) break
        }
    }

    private suspend fun onStatus(status: PlaylistUiState?) {
        if (status == null) return
        val changed = mutex.withLock {
            if (entries.isEmpty()) return
            val ids = status.items.mapNotNull { it.itemId }
            when {
                playbackId == null -> {
                    // The new playback is ours once the TV lists one of our items.
                    if (ids.none { it in byId }) return
                    playbackId = status.playbackId
                }
                status.playbackId != playbackId -> {
                    Log.i(TAG, "Another playback started on the TV; the phone list ends")
                    clearLocked()
                    return
                }
            }
            latest = status
            true
        }
        if (changed) wake.trySend(Unit)
    }

    private suspend fun reconcile() {
        val work = mutex.withLock {
            val status = latest ?: return
            val pid = playbackId ?: return
            if (status.queueRevision < awaitRevision) return
            val current = status.currentItemId ?: return
            // A jump is under way; update once the TV reports it is on the new item.
            jumpingTo?.let { target -> if (current != target) return else jumpingTo = null }
            val tv = status.items.mapNotNull { it.itemId }
            val at = entries.indexOfFirst { it.id == current }
            if (at < 0) return // Something another controller added is playing; wait for ours.
            cursor = at
            if (!dirty) {
                val adopted = PhoneQueueWindow.adopt(entries.map { it.id }, cursor, sent, tv, current)
                if (adopted.size != entries.size || adopted.indices.any { adopted[it] != entries[it].id }) {
                    val kept = adopted.toHashSet()
                    entries.filter { it.id !in kept }.forEach { byId.remove(it.id); sent.remove(it.id) }
                    entries = adopted.mapNotNull(byId::get).toMutableList()
                    cursor = entries.indexOfFirst { it.id == current }
                    itemsChanged = true
                }
            }
            dirty = false
            publishLocked()
            val onTv = tv.toHashSet()
            pendingRemoves.retainAll(onTv)
            val window = PhoneQueueWindow.plan(entries.map { it.id }, cursor, tv, current)
            val plan = window.copy(removes = (pendingRemoves + window.removes).distinct())
            if (plan.isEmpty) return
            Triple(plan, pid, generation)
        }
        val (plan, pid, session) = work
        if (!apply(plan, pid, session)) {
            // Not confirmed: try again shortly with whatever the TV reports by then.
            scope.launch { delay(RETRY_MS); wake.trySend(Unit) }
        }
    }

    /** Sends [plan]: removes first, then adds, then moves. False if a step went unconfirmed. */
    private suspend fun apply(plan: WindowPlan, pid: String, session: Long): Boolean {
        suspend fun ok(result: QueueCommandResult?): Boolean {
            if (result == null) return false
            if (result.error == "stale_playback" || result.error == "no_active_playback") {
                mutex.withLock { if (session == generation) clearLocked() }
                return false
            }
            result.queueRevision?.let { revision -> mutex.withLock { if (session == generation) awaitRevision = revision } }
            return result.ok
        }
        if (plan.removes.isNotEmpty()) {
            val result = command("queue_remove", pid) {
                put("itemIds", buildJsonArray { plan.removes.forEach { add(JsonPrimitive(it)) } })
            }
            if (!ok(result)) return false
            mutex.withLock {
                sent.removeAll(plan.removes.toSet())
                pendingRemoves.removeAll(plan.removes.toSet())
            }
        }
        if (plan.adds.isNotEmpty()) {
            val toAdd = mutex.withLock { plan.adds.mapNotNull(byId::get) }
            val payloads = packager.packageItems(toAdd.map { it.item }, toAdd.map { it.id }) ?: return false
            for (chunk in payloads.chunked(APPEND_LIMIT)) {
                val requestId = UUID.randomUUID().toString()
                val result = connectionCoordinator.sendConfirmedQueueCommand(
                    requestId, createQueueAddCommandJson(chunk, pid, requestId), CONFIRM_MS,
                )
                if (!ok(result)) return false
                mutex.withLock { chunk.mapNotNull { it.item_id }.forEach(sent::add) }
            }
        }
        for ((id, before) in plan.moves) {
            val result = command("queue_move", pid) {
                put("itemId", id)
                put("beforeItemId", before)
            }
            if (!ok(result)) return false
        }
        return true
    }

    private suspend fun command(
        action: String,
        pid: String,
        payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): QueueCommandResult? {
        val requestId = UUID.randomUUID().toString()
        val json = buildJsonObject {
            put("type", "command")
            put("action", action)
            put("requestId", requestId)
            put("payload", buildJsonObject { payload(); put("ifPlaybackId", pid) })
        }.toString()
        return connectionCoordinator.sendConfirmedQueueCommand(requestId, json, CONFIRM_MS)
    }

    private fun clearLocked() {
        attachJob?.cancel()
        attachJob = null
        entries = mutableListOf()
        byId.clear()
        sent.clear()
        cursor = 0
        playbackId = null
        latest = null
        dirty = false
        awaitRevision = -1L
        pendingRemoves.clear()
        jumpingTo = null
        itemsChanged = true
        generation++
        mutableState.value = PhoneQueueState()
    }

    private fun publishLocked(active: Boolean = mutableState.value.active) {
        val items = if (itemsChanged) entries.map { it.view } else mutableState.value.items
        itemsChanged = false
        mutableState.value = PhoneQueueState(
            active = active && entries.isNotEmpty(), position = cursor, count = entries.size, items = items,
            source = source,
        )
    }

    private data class Jump(val entry: Entry, val onTv: Boolean, val playbackId: String, val session: Long)

    private companion object {
        const val TAG = "PhoneQueueCoordinator"
        /** Let quick skips settle before updating the TV. */
        const val SETTLE_MS = 600L
        const val RETRY_MS = 2_000L
        const val CONFIRM_MS = 8_000L
        const val ATTACH_TIMEOUT_MS = 8_000L
        /** How long window updates wait for the TV to report a jump. */
        const val JUMP_SETTLE_MS = 5_000L
        const val JUMP_UNCONFIRMED_MS = 15_000L
        /** PlayBridge TVs reject larger `queue_add` messages. */
        const val APPEND_LIMIT = 50
    }
}
