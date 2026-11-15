package com.playbridge.sender.browser

import android.os.SystemClock
import android.util.Log
import com.playbridge.sender.together.PartyMediaSupport
import com.playbridge.sender.together.PartyQueueItem
import com.playbridge.sender.together.PartyQueueUpdate
import com.playbridge.sender.together.PhoneTogether
import com.playbridge.shared.together.TogetherPolicy
import com.playbridge.shared.together.TogetherSource
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import playbridge.VisualMetadata

/** Playlist item entry surfaced back to the webpage in `statechange` events. */
internal data class SinkPlaylistItem(
    val linkedId: String,
    val title: String?,
)

/** Snapshot pushed from a [LinkedSessionSink] to [LinkedSessionEngine]. */
internal data class SinkPlaybackState(
    val state: String,
    val positionMs: Long,
    val durationMs: Long,
    val title: String?,
    val currentIndex: Int,
    val totalCount: Int,
    val items: List<SinkPlaylistItem>,
    val awaitingPlaylistEcho: Boolean,
)

internal data class SinkFailure(
    val code: String,
    val endSessionReason: String? = null,
    val suppressPendingClear: Boolean = false,
)

internal sealed interface SinkOpenOutcome {
    data object Ok : SinkOpenOutcome
    data class Failed(val failure: SinkFailure) : SinkOpenOutcome
}

internal sealed interface SinkBatchOutcome {
    data object Ok : SinkBatchOutcome
    data class Failed(val failure: SinkFailure) : SinkBatchOutcome
}

internal interface LinkedSinkHost {
    fun runWithLock(block: suspend () -> Unit)
    fun emitSnapshot(snapshot: SinkPlaybackState, evaluateDemand: Boolean)
    fun finish(reason: String)
    fun nowMillis(): Long
}

/**
 * Target-specific backend for a linked page-cast session (native TV over WebSocket or
 * Watch Party via [PhoneTogether]). Protocol parsing, consent/document binding, demand
 * idempotency, limits, and timeouts stay in [LinkedSessionEngine].
 */
internal interface LinkedSessionSink {
    val controllerName: String?
    val showTvUiState: Boolean
    val knownIds: Set<String>

    suspend fun prepareOpen(request: LinkedPageCastOpenRequest): SinkFailure?
    suspend fun start(
        request: LinkedPageCastOpenRequest,
        host: LinkedSinkHost,
        sessionScope: CoroutineScope,
    ): SinkOpenOutcome

    suspend fun validateAvailable(): SinkFailure?
    suspend fun append(items: List<LinkedPageCastItem>): SinkBatchOutcome
    suspend fun supply(items: List<LinkedPageCastItem>, endOfList: Boolean): SinkBatchOutcome
    suspend fun replace(
        items: List<LinkedPageCastItem>,
        startIndex: Int,
        metadata: VisualMetadata?,
        skipPreplay: Boolean,
    ): SinkOpenOutcome

    suspend fun jump(index: Int): SinkFailure?
    fun currentSnapshot(): SinkPlaybackState
    fun isDemandSuppressed(): Boolean
    suspend fun onHeartbeatTick(nowMillis: Long) {}
    fun close() {}
}

/** Narrow Watch Party interface so [WatchPartyLinkedSessionSink] runs in pure JVM tests. */
internal interface PartyPlaybackGateway {
    val partyState: StateFlow<PhoneTogether.State>
    val partyTargetSelected: StateFlow<Boolean>
    val queueUpdates: SharedFlow<PartyQueueUpdate>

    fun currentParty(): PhoneTogether.Party?
    fun isInParty(): Boolean
    fun isPartySelected(): Boolean
    fun evaluateUrl(url: String): PartyMediaSupport.PartyUrlCheck
    suspend fun playLinkedInParty(
        item: PartyQueueItem,
        queueItems: List<PartyQueueItem> = emptyList(),
        replaceQueueIds: Set<String> = emptySet(),
    ): PhoneTogether.LinkedPlayResult

    suspend fun enqueueLinkedItems(items: List<PartyQueueItem>): PhoneTogether.LinkedEnqueueResult
    suspend fun playQueuedItemNow(queueItemId: String): Boolean
    suspend fun replayLinkedItem(item: PartyQueueItem): PhoneTogether.LinkedPlayResult
}

internal object PhoneTogetherGateway : PartyPlaybackGateway {
    override val partyState: StateFlow<PhoneTogether.State> get() = PhoneTogether.state
    override val partyTargetSelected: StateFlow<Boolean> get() = PhoneTogether.partyTargetSelected
    override val queueUpdates: SharedFlow<PartyQueueUpdate> get() = PhoneTogether.queueUpdates

    override fun currentParty(): PhoneTogether.Party? =
        (PhoneTogether.state.value as? PhoneTogether.State.InParty)?.party

    override fun isInParty(): Boolean = PhoneTogether.isInParty()

    override fun isPartySelected(): Boolean = PhoneTogether.isPartySelected()

    override fun evaluateUrl(url: String): PartyMediaSupport.PartyUrlCheck =
        PartyMediaSupport.evaluatePartyUrl(url)

    override suspend fun playLinkedInParty(
        item: PartyQueueItem,
        queueItems: List<PartyQueueItem>,
        replaceQueueIds: Set<String>,
    ): PhoneTogether.LinkedPlayResult = withContext(Dispatchers.Main.immediate) {
        PhoneTogether.playLinkedInParty(item, queueItems, replaceQueueIds)
    }

    override suspend fun enqueueLinkedItems(items: List<PartyQueueItem>): PhoneTogether.LinkedEnqueueResult =
        withContext(Dispatchers.Main.immediate) {
            PhoneTogether.enqueueLinkedItems(items)
        }

    override suspend fun playQueuedItemNow(queueItemId: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            PhoneTogether.playQueuedItemNow(queueItemId)
        }

    override suspend fun replayLinkedItem(item: PartyQueueItem): PhoneTogether.LinkedPlayResult =
        withContext(Dispatchers.Main.immediate) {
            PhoneTogether.replayLinkedItem(item)
        }
}

/**
 * Watch Party sink tracking which Up next [PartyQueueItem]s belong to the active website
 * session, translating party state transitions & queue mutations into linked events, and
 * handling Ask policy, host-only permission loss, user queue edits/clears, and URL checks.
 */
internal class WatchPartyLinkedSessionSink(
    private val gateway: PartyPlaybackGateway,
    private val elapsedClock: () -> Long = SystemClock::elapsedRealtime,
) : LinkedSessionSink {

    private data class SessionEntry(
        val linkedId: String,
        val queueItem: PartyQueueItem,
    )

    override val controllerName: String? = null
    override val showTvUiState: Boolean = false

    private val played = mutableListOf<SessionEntry>()
    private var current: SessionEntry? = null
    private val upcoming = mutableListOf<SessionEntry>()
    private val upcomingByQueueId = linkedMapOf<String, SessionEntry>()
    private val admittedIds = linkedSetOf<String>()

    private var openingRoomId: String? = null
    private var latestParty: PhoneTogether.Party? = null
    private var awaitingVideoEcho: Boolean = false
    private var hasSeenRoomVideo: Boolean = false
    private var askOnlyMode: Boolean = false
    private var queueClearedByUser: Boolean = false
    private var host: LinkedSinkHost? = null

    override val knownIds: Set<String>
        get() = admittedIds.toSet()

    override suspend fun prepareOpen(request: LinkedPageCastOpenRequest): SinkFailure? {
        val party = gateway.currentParty() ?: return SinkFailure("receiver_stopped")
        if (!gateway.isPartySelected()) return SinkFailure("receiver_changed")
        if (party.locked || (party.policy == TogetherPolicy.OnlyHost && !party.host)) {
            return SinkFailure("not_allowed")
        }
        val selected = request.items.getOrNull(request.startIndex)
            ?: return SinkFailure("invalid_request")
        if (gateway.evaluateUrl(selected.payload.url).rejection != null) {
            return SinkFailure("unsupported_target")
        }
        return null
    }

    override suspend fun start(
        request: LinkedPageCastOpenRequest,
        host: LinkedSinkHost,
        sessionScope: CoroutineScope,
    ): SinkOpenOutcome {
        val party = gateway.currentParty()
            ?: return SinkOpenOutcome.Failed(SinkFailure("receiver_stopped"))
        if (!gateway.isPartySelected()) {
            return SinkOpenOutcome.Failed(SinkFailure("receiver_changed"))
        }
        val selected = request.items[request.startIndex]
        val firstEntry = SessionEntry(selected.id, toPartyQueueItem(selected))
        val askPolicyNow = !party.canChangeVideo
        val candidateRest = if (askPolicyNow) {
            emptyList()
        } else {
            request.items
                .drop(request.startIndex + 1)
                .filter { gateway.evaluateUrl(it.payload.url).rejection == null }
                .map { SessionEntry(it.id, toPartyQueueItem(it)) }
        }
        val playResult = gateway.playLinkedInParty(
            item = firstEntry.queueItem,
            queueItems = candidateRest.map { it.queueItem },
        )
        when (playResult) {
            is PhoneTogether.LinkedPlayResult.Error ->
                return SinkOpenOutcome.Failed(SinkFailure(playResult.code))
            is PhoneTogether.LinkedPlayResult.Ok -> {
                this.host = host
                openingRoomId = party.roomId
                latestParty = party
                played.clear()
                current = firstEntry
                upcoming.clear()
                upcomingByQueueId.clear()
                admittedIds.clear()
                admittedIds += firstEntry.linkedId
                askOnlyMode = playResult.askOnly
                queueClearedByUser = false
                if (!playResult.askOnly) {
                    val acceptedSet = playResult.queuedIds.toHashSet()
                    candidateRest.filterTo(upcoming) { it.queueItem.id in acceptedSet }
                    upcoming.forEach { entry ->
                        upcomingByQueueId[entry.queueItem.id] = entry
                        admittedIds += entry.linkedId
                    }
                }
                awaitingVideoEcho = true
                hasSeenRoomVideo = false
                startObservers(sessionScope)
                return SinkOpenOutcome.Ok
            }
        }
    }

    override suspend fun validateAvailable(): SinkFailure? {
        val party = gateway.currentParty()
        if (party == null || (openingRoomId != null && party.roomId != openingRoomId)) {
            return SinkFailure("receiver_stopped", endSessionReason = "receiver_stopped")
        }
        if (!gateway.isPartySelected()) {
            return SinkFailure("receiver_changed", endSessionReason = "receiver_changed")
        }
        if (isPermissionLostForGood(party)) {
            return SinkFailure("not_allowed", endSessionReason = "not_allowed")
        }
        return null
    }

    override suspend fun append(items: List<LinkedPageCastItem>): SinkBatchOutcome {
        checkOperationPermission()?.let { return SinkBatchOutcome.Failed(it) }
        val entries = filterValidEntries(items)
        if (entries.isEmpty()) {
            return SinkBatchOutcome.Failed(SinkFailure("unsupported_target"))
        }
        return when (val result = gateway.enqueueLinkedItems(entries.map { it.queueItem })) {
            is PhoneTogether.LinkedEnqueueResult.Error ->
                SinkBatchOutcome.Failed(SinkFailure(result.code))
            is PhoneTogether.LinkedEnqueueResult.Ok -> {
                val addedSet = result.addedItems.mapTo(hashSetOf()) { it.id }
                entries.filter { it.queueItem.id in addedSet }.forEach { entry ->
                    upcoming.add(entry)
                    upcomingByQueueId[entry.queueItem.id] = entry
                    admittedIds += entry.linkedId
                }
                SinkBatchOutcome.Ok
            }
        }
    }

    override suspend fun supply(items: List<LinkedPageCastItem>, endOfList: Boolean): SinkBatchOutcome {
        checkOperationPermission()?.let { return SinkBatchOutcome.Failed(it) }
        val entries = filterValidEntries(items)
        if (entries.isEmpty()) {
            return SinkBatchOutcome.Failed(
                SinkFailure("unsupported_target", suppressPendingClear = true),
            )
        }
        return when (val result = gateway.enqueueLinkedItems(entries.map { it.queueItem })) {
            is PhoneTogether.LinkedEnqueueResult.Error ->
                SinkBatchOutcome.Failed(SinkFailure(result.code))
            is PhoneTogether.LinkedEnqueueResult.Ok -> {
                val addedSet = result.addedItems.mapTo(hashSetOf()) { it.id }
                entries.filter { it.queueItem.id in addedSet }.forEach { entry ->
                    upcoming.add(entry)
                    upcomingByQueueId[entry.queueItem.id] = entry
                    admittedIds += entry.linkedId
                }
                SinkBatchOutcome.Ok
            }
        }
    }

    override suspend fun replace(
        items: List<LinkedPageCastItem>,
        startIndex: Int,
        metadata: VisualMetadata?,
        skipPreplay: Boolean,
    ): SinkOpenOutcome {
        val party = gateway.currentParty()
            ?: return SinkOpenOutcome.Failed(SinkFailure("receiver_stopped", endSessionReason = "receiver_stopped"))
        if (!gateway.isPartySelected()) {
            return SinkOpenOutcome.Failed(SinkFailure("receiver_changed", endSessionReason = "receiver_changed"))
        }
        if (isPermissionLostForGood(party)) {
            return SinkOpenOutcome.Failed(SinkFailure("not_allowed", endSessionReason = "not_allowed"))
        }
        if (party.locked) {
            return SinkOpenOutcome.Failed(SinkFailure("not_allowed"))
        }
        if (!party.canChangeVideo && items.size > 1) {
            return SinkOpenOutcome.Failed(SinkFailure("unsupported_queue"))
        }
        val selected = items[startIndex]
        if (gateway.evaluateUrl(selected.payload.url).rejection != null) {
            return SinkOpenOutcome.Failed(SinkFailure("unsupported_target"))
        }
        val firstEntry = SessionEntry(selected.id, toPartyQueueItem(selected))
        val candidateRest = if (!party.canChangeVideo) {
            emptyList()
        } else {
            items
                .drop(startIndex + 1)
                .filter { gateway.evaluateUrl(it.payload.url).rejection == null }
                .map { SessionEntry(it.id, toPartyQueueItem(it)) }
        }
        val playResult = gateway.playLinkedInParty(
            item = firstEntry.queueItem,
            queueItems = candidateRest.map { it.queueItem },
            replaceQueueIds = upcomingByQueueId.keys.toSet(),
        )
        return when (playResult) {
            is PhoneTogether.LinkedPlayResult.Error ->
                SinkOpenOutcome.Failed(SinkFailure(playResult.code))
            is PhoneTogether.LinkedPlayResult.Ok -> {
                played.clear()
                current = firstEntry
                upcoming.clear()
                upcomingByQueueId.clear()
                admittedIds.clear()
                admittedIds += firstEntry.linkedId
                askOnlyMode = playResult.askOnly
                queueClearedByUser = false
                if (!playResult.askOnly) {
                    val acceptedSet = playResult.queuedIds.toHashSet()
                    candidateRest.filterTo(upcoming) { it.queueItem.id in acceptedSet }
                    upcoming.forEach { entry ->
                        upcomingByQueueId[entry.queueItem.id] = entry
                        admittedIds += entry.linkedId
                    }
                }
                awaitingVideoEcho = true
                hasSeenRoomVideo = false
                SinkOpenOutcome.Ok
            }
        }
    }

    override suspend fun jump(index: Int): SinkFailure? {
        val party = gateway.currentParty()
            ?: return SinkFailure("receiver_stopped", endSessionReason = "receiver_stopped")
        if (!gateway.isPartySelected()) {
            return SinkFailure("receiver_changed", endSessionReason = "receiver_changed")
        }
        if (isPermissionLostForGood(party)) {
            return SinkFailure("not_allowed", endSessionReason = "not_allowed")
        }
        if (party.locked) return SinkFailure("not_allowed")
        val ordered = orderedEntries()
        val target = ordered.getOrNull(index) ?: return SinkFailure("invalid_request")
        val currentIdx = currentIndexIn(ordered)
        if (!party.canChangeVideo) {
            if (index != currentIdx) return SinkFailure("unsupported_queue")
            return when (val res = gateway.replayLinkedItem(target.queueItem)) {
                is PhoneTogether.LinkedPlayResult.Error -> SinkFailure(res.code)
                is PhoneTogether.LinkedPlayResult.Ok -> {
                    awaitingVideoEcho = true
                    null
                }
            }
        }
        val queueId = target.queueItem.id
        if (queueId in upcomingByQueueId) {
            if (!gateway.playQueuedItemNow(queueId)) {
                return SinkFailure("connect_failed")
            }
            val promoted = upcomingByQueueId.remove(queueId) ?: target
            upcoming.removeAll { it.queueItem.id == queueId }
            current?.let { played.add(it) }
            current = promoted
            awaitingVideoEcho = true
            return null
        }
        return when (val res = gateway.replayLinkedItem(target.queueItem)) {
            is PhoneTogether.LinkedPlayResult.Error -> SinkFailure(res.code)
            is PhoneTogether.LinkedPlayResult.Ok -> {
                if (target != current) {
                    played.removeAll { it.queueItem.id == target.queueItem.id }
                    current?.let { played.add(it) }
                    current = target
                }
                awaitingVideoEcho = true
                null
            }
        }
    }

    override fun currentSnapshot(): SinkPlaybackState {
        val party = latestParty ?: gateway.currentParty()
        val ordered = orderedEntries()
        val activeEntry = current
        val currentIdx = currentIndexIn(ordered)
        val roomItem = party?.item
        val state = when {
            awaitingVideoEcho -> "connecting"
            roomItem != null -> if (party.playing) "playing" else "paused"
            hasSeenRoomVideo && upcoming.isEmpty() -> "ended"
            hasSeenRoomVideo -> "connecting"
            else -> "connecting"
        }
        val positionMs = if (!awaitingVideoEcho && roomItem != null) {
            party.positionNow(elapsedClock())
        } else {
            0L
        }
        val durationMs = if (!awaitingVideoEcho && roomItem != null) {
            party.durationMs ?: 0L
        } else {
            0L
        }
        val title = if (!awaitingVideoEcho && roomItem != null) {
            roomItem.title
        } else {
            activeEntry?.queueItem?.title
        }
        return SinkPlaybackState(
            state = state,
            positionMs = positionMs,
            durationMs = durationMs,
            title = title,
            currentIndex = currentIdx,
            totalCount = ordered.size,
            items = ordered.map { SinkPlaylistItem(it.linkedId, it.queueItem.title) },
            awaitingPlaylistEcho = false,
        )
    }

    override fun isDemandSuppressed(): Boolean = askOnlyMode || queueClearedByUser

    private fun startObservers(sessionScope: CoroutineScope) {
        val hostRef = host ?: return
        sessionScope.launch {
            gateway.partyState.collect { state ->
                hostRef.runWithLock {
                    handlePartyState(state, hostRef)
                }
            }
        }
        sessionScope.launch {
            gateway.partyTargetSelected.collect { selected ->
                if (!selected) {
                    hostRef.runWithLock {
                        hostRef.finish("receiver_changed")
                    }
                }
            }
        }
        sessionScope.launch {
            gateway.queueUpdates.collect { update ->
                hostRef.runWithLock {
                    handleQueueUpdate(update, hostRef)
                }
            }
        }
        sessionScope.launch {
            while (isActive) {
                delay(LinkedPageCastCoordinator.STATE_EVENT_INTERVAL_MILLIS)
                hostRef.runWithLock {
                    val party = latestParty
                    if (party != null && party.item != null && party.playing && !awaitingVideoEcho) {
                        hostRef.emitSnapshot(currentSnapshot(), evaluateDemand = false)
                    }
                }
            }
        }
    }

    private fun handlePartyState(state: PhoneTogether.State, hostRef: LinkedSinkHost) {
        when (state) {
            is PhoneTogether.State.Idle,
            is PhoneTogether.State.Ended,
            -> {
                hostRef.finish("receiver_stopped")
                return
            }
            is PhoneTogether.State.InParty -> {
                val party = state.party
                if (openingRoomId != null && party.roomId != openingRoomId) {
                    hostRef.finish("receiver_stopped")
                    return
                }
                if (isPermissionLostForGood(party)) {
                    hostRef.finish("not_allowed")
                    return
                }
                val previousAskMode = askOnlyMode
                latestParty = party
                askOnlyMode = !party.canChangeVideo
                val roomItem = party.item
                val activeItem = current?.queueItem
                if (roomItem != null) {
                    if (awaitingVideoEcho && activeItem != null && matchesPartyItem(roomItem, activeItem)) {
                        awaitingVideoEcho = false
                        hasSeenRoomVideo = true
                    } else if (!awaitingVideoEcho) {
                        hasSeenRoomVideo = true
                    }
                }
                val permissionUpgraded = previousAskMode && !askOnlyMode && !queueClearedByUser
                hostRef.emitSnapshot(currentSnapshot(), evaluateDemand = permissionUpgraded)
            }
            else -> Unit
        }
    }

    private fun handleQueueUpdate(update: PartyQueueUpdate, hostRef: LinkedSinkHost) {
        when (update) {
            is PartyQueueUpdate.ManualPlay -> {
                hostRef.finish("superseded")
            }
            is PartyQueueUpdate.LoadedFromQueue -> {
                val promoted = upcomingByQueueId.remove(update.item.id)
                if (promoted != null) {
                    current?.let { played.add(it) }
                    current = promoted
                    awaitingVideoEcho = true
                    syncUpcomingFromSnapshot(update.queueSnapshot)
                    hostRef.emitSnapshot(currentSnapshot(), evaluateDemand = !isDemandSuppressed())
                } else {
                    syncUpcomingFromSnapshot(update.queueSnapshot)
                    hostRef.emitSnapshot(currentSnapshot(), evaluateDemand = false)
                }
            }
            is PartyQueueUpdate.Removed -> {
                if (upcomingByQueueId.remove(update.removedId) != null) {
                    syncUpcomingFromSnapshot(update.queueSnapshot)
                    hostRef.emitSnapshot(currentSnapshot(), evaluateDemand = false)
                }
            }
            is PartyQueueUpdate.Reordered -> {
                val beforeOrder = upcoming.map { it.queueItem.id }
                syncUpcomingFromSnapshot(update.queueSnapshot)
                if (upcoming.map { it.queueItem.id } != beforeOrder) {
                    hostRef.emitSnapshot(currentSnapshot(), evaluateDemand = false)
                }
            }
            is PartyQueueUpdate.Cleared -> {
                val hadSessionItems = upcoming.isNotEmpty()
                upcoming.clear()
                upcomingByQueueId.clear()
                queueClearedByUser = true
                if (hadSessionItems || current != null) {
                    hostRef.emitSnapshot(currentSnapshot(), evaluateDemand = false)
                }
            }
            is PartyQueueUpdate.Enqueued,
            is PartyQueueUpdate.ReplacedSessionQueue,
            -> {
                syncUpcomingFromSnapshot(update.queueSnapshot)
            }
        }
    }

    private fun syncUpcomingFromSnapshot(snapshot: List<PartyQueueItem>) {
        val ordered = snapshot.mapNotNull { upcomingByQueueId[it.id] }
        upcoming.clear()
        upcoming.addAll(ordered)
        val liveIds = ordered.mapTo(hashSetOf()) { it.queueItem.id }
        upcomingByQueueId.keys.retainAll(liveIds)
    }

    private fun orderedEntries(): List<SessionEntry> = buildList {
        addAll(played)
        current?.let(::add)
        addAll(upcoming)
    }

    private fun currentIndexIn(ordered: List<SessionEntry>): Int {
        val activeEntry = current ?: return 0
        val idx = ordered.indexOfFirst { it.linkedId == activeEntry.linkedId && it.queueItem.id == activeEntry.queueItem.id }
        return if (idx >= 0) idx else 0
    }

    private fun checkOperationPermission(): SinkFailure? {
        val party = gateway.currentParty()
            ?: return SinkFailure("receiver_stopped", endSessionReason = "receiver_stopped")
        if (!gateway.isPartySelected()) {
            return SinkFailure("receiver_changed", endSessionReason = "receiver_changed")
        }
        if (isPermissionLostForGood(party)) {
            return SinkFailure("not_allowed", endSessionReason = "not_allowed")
        }
        if (party.locked) return SinkFailure("not_allowed")
        if (!party.canChangeVideo) return SinkFailure("unsupported_queue")
        return null
    }

    private fun filterValidEntries(items: List<LinkedPageCastItem>): List<SessionEntry> =
        items
            .filter { gateway.evaluateUrl(it.payload.url).rejection == null }
            .map { SessionEntry(it.id, toPartyQueueItem(it)) }

    private fun toPartyQueueItem(item: LinkedPageCastItem): PartyQueueItem =
        PartyMediaSupport.itemOf(item.payload)

    private fun matchesPartyItem(roomItem: com.playbridge.shared.together.TogetherItem, queueItem: PartyQueueItem): Boolean {
        val sourceUrl = (roomItem.source as? TogetherSource.Url)?.url
        return sourceUrl == queueItem.url && roomItem.title == queueItem.title
    }

    private fun isPermissionLostForGood(party: PhoneTogether.Party): Boolean =
        !party.host && party.policy == TogetherPolicy.OnlyHost
}

/**
 * Shared protocol coordinator engine for linked page-cast sessions regardless of target sink.
 * Owns open/handle serialization, duplicate-id & size checks, `needitems` retry/idempotency,
 * `statechange` throttling, cancel-open bookkeeping, and idle/max session lifetimes.
 */
internal class LinkedSessionEngine(
    private val prefetchWindow: suspend () -> Int,
    private val postLinkedMessage: (JSONObject) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onUiStateChange: (LinkedPageCastUiState) -> Unit = {},
) {
    private data class PendingNeed(
        val requestId: String,
        val count: Int,
        var lastDispatchedAtMillis: Long,
    )

    private class Active(
        val openBridgeRequestId: String,
        val sessionId: String,
        val origin: String,
        val tabId: Int,
        val navigationGeneration: Long,
        val sink: LinkedSessionSink,
        val createdAtMillis: Long,
        var lastPageActivityAtMillis: Long,
        val allowedPrivateOrigins: MutableSet<String> = linkedSetOf(),
        var pendingNeed: PendingNeed? = null,
        var lastAcceptedNeedId: String? = null,
        var endOfList: Boolean = false,
        var lastStateStructure: Int? = null,
        var lastStateEmittedAtMillis: Long = 0,
        var sessionScope: CoroutineScope? = null,
    ) {
        fun binding() = LinkedSessionBinding(sessionId, origin, tabId, navigationGeneration)
    }

    @Volatile
    private var active: Active? = null
    private val operationMutex = Mutex()
    private val cancelledOpenRequests = linkedSetOf<String>()

    fun isActive(): Boolean = active != null

    fun activeOrigin(): String? = active?.origin

    fun allowPrivateOriginsForActive(origin: String, origins: Collection<String>): Boolean {
        val session = active?.takeIf { it.origin == origin } ?: return false
        val combined = session.allowedPrivateOrigins + origins
        val normalized = com.playbridge.shared.network.MediaNetworkPolicy.normalizePrivateOrigins(combined)
            ?: return false
        session.allowedPrivateOrigins.clear()
        session.allowedPrivateOrigins.addAll(normalized)
        return true
    }

    fun isMessageForActiveSession(message: JSONObject, origin: String): Boolean {
        val session = active ?: return false
        return linkedMessageMatchesSession(session.binding(), message, origin)
    }

    fun cancelOpen(targetBridgeRequestId: String, reason: String = "navigation") {
        synchronized(cancelledOpenRequests) {
            cancelledOpenRequests += targetBridgeRequestId
            while (cancelledOpenRequests.size > MAX_CANCELLED_OPEN_REQUESTS) {
                cancelledOpenRequests.remove(cancelledOpenRequests.first())
            }
        }
        val current = active
        if (current?.openBridgeRequestId == targetBridgeRequestId) {
            finishSession(current, reason)
        }
        result(targetBridgeRequestId, false, "session_ended")
    }

    fun isOpenCancelled(bridgeRequestId: String): Boolean = synchronized(cancelledOpenRequests) {
        bridgeRequestId in cancelledOpenRequests
    }

    fun accept(bridgeRequestId: String) = result(bridgeRequestId, true)

    fun reject(bridgeRequestId: String, error: String) = result(bridgeRequestId, false, error)

    fun supersedeIfActive() {
        active?.let { finishSession(it, "superseded") }
    }

    fun unlink(reason: String = "unlinked") {
        active?.let { finishSession(it, reason) }
    }

    fun open(request: LinkedPageCastOpenRequest, sink: LinkedSessionSink) {
        scope.launch {
            operationMutex.withLock {
                if (isOpenCancelled(request.bridgeRequestId)) return@withLock
                if (request.items.distinctBy { it.id }.size != request.items.size) {
                    result(request.bridgeRequestId, false, "invalid_request")
                    return@withLock
                }
                sink.prepareOpen(request)?.let { failure ->
                    result(request.bridgeRequestId, false, failure.code)
                    return@withLock
                }
                val previous = active
                if (previous != null) {
                    active = null
                    previous.sessionScope?.coroutineContext?.get(Job)?.cancel()
                    previous.sink.close()
                    onUiStateChange(LinkedPageCastUiState())
                    event(previous, "ended", JSONObject().put("reason", "superseded"))
                }
                val binding = request.linkedSessionBinding()
                val now = clock()
                val sessionJob = SupervisorJob(scope.coroutineContext[Job])
                val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
                val next = Active(
                    openBridgeRequestId = request.bridgeRequestId,
                    sessionId = binding.sessionId,
                    origin = binding.origin,
                    tabId = binding.tabId,
                    navigationGeneration = binding.navigationGeneration,
                    sink = sink,
                    createdAtMillis = now,
                    lastPageActivityAtMillis = now,
                    allowedPrivateOrigins = request.items.flatMapTo(linkedSetOf()) { it.payload.allowed_private_origins },
                    sessionScope = sessionScope,
                )
                active = next
                if (sink.showTvUiState) {
                    onUiStateChange(LinkedPageCastUiState(true, sink.controllerName))
                } else {
                    onUiStateChange(LinkedPageCastUiState())
                }
                val host = createHost(next)
                when (val outcome = sink.start(request, host, sessionScope)) {
                    is SinkOpenOutcome.Failed -> {
                        if (active === next) {
                            active = null
                            sessionJob.cancel()
                            sink.close()
                            onUiStateChange(LinkedPageCastUiState())
                        }
                        if (!isOpenCancelled(request.bridgeRequestId)) {
                            result(request.bridgeRequestId, false, outcome.failure.code)
                        }
                        return@withLock
                    }
                    SinkOpenOutcome.Ok -> Unit
                }
                if (isOpenCancelled(request.bridgeRequestId) || active !== next) {
                    if (active === next) {
                        active = null
                        sessionJob.cancel()
                        sink.close()
                        onUiStateChange(LinkedPageCastUiState())
                    }
                    return@withLock
                }
                startHeartbeat(next, sessionScope)
                result(request.bridgeRequestId, true)
                val snapshot = sink.currentSnapshot()
                emitState(next, snapshot)
                if (!sink.isDemandSuppressed()) {
                    maybeRequestItems(next, snapshot)
                }
            }
        }
    }

    fun handle(message: JSONObject) {
        val bridgeRequestId = message.optString("bridgeRequestId")
        val type = message.optString("type")
        val session = active
        val origin = PageCastConsentStore.normalizeOrigin(message.optString("origin"))
        if (session == null || origin == null || !linkedMessageMatchesSession(session.binding(), message, origin)) {
            result(bridgeRequestId, false, "session_ended")
            return
        }
        scope.launch {
            operationMutex.withLock {
                if (active !== session) {
                    result(bridgeRequestId, false, "session_ended")
                    return@withLock
                }
                session.sink.validateAvailable()?.let { failure ->
                    result(bridgeRequestId, false, failure.code)
                    failure.endSessionReason?.let { finishSessionLocked(session, it) }
                    return@withLock
                }
                if (message.toString().toByteArray().size > LinkedPageCastCoordinator.MAX_REQUEST_BYTES) {
                    result(bridgeRequestId, false, "resource_limit")
                    return@withLock
                }
                session.lastPageActivityAtMillis = clock()
                when (type) {
                    "linked_ping" -> result(bridgeRequestId, true)
                    "linked_replace" -> replace(session, bridgeRequestId, message.optJSONObject("payload"))
                    "linked_append" -> append(session, bridgeRequestId, message.optJSONObject("payload"))
                    "linked_supply" -> supply(session, bridgeRequestId, message.optJSONObject("payload"))
                    "linked_jump" -> jump(session, bridgeRequestId, message.optJSONObject("payload"))
                    "linked_unlink" -> {
                        result(bridgeRequestId, true)
                        finishSessionLocked(session, message.optString("reason", "unlinked"))
                    }
                    else -> result(bridgeRequestId, false, "invalid_request")
                }
            }
        }
    }

    private suspend fun replace(session: Active, bridgeRequestId: String, payload: JSONObject?) {
        val items = LinkedPageCastCoordinator.parseItems(payload?.optJSONArray("items"), session.allowedPrivateOrigins)
        val startIndex = payload?.optInt("startIndex", 0) ?: 0
        if (items == null ||
            startIndex !in items.indices ||
            items.distinctBy { it.id }.size != items.size
        ) {
            result(bridgeRequestId, false, "invalid_request")
            return
        }
        val metadata = payload?.optJSONObject("metadata")?.let {
            if (it.toString().toByteArray().size > LinkedPageCastCoordinator.MAX_METADATA_BYTES) {
                result(bridgeRequestId, false, "resource_limit")
                return
            }
            com.playbridge.shared.protocol.decodeVisualMetadataJson(it.toString())
        }
        val skipPreplayValue = payload?.opt("skipPreplay")
        val skipPreplay = if (skipPreplayValue == null || skipPreplayValue == JSONObject.NULL) {
            false
        } else {
            skipPreplayValue as? Boolean ?: run {
                result(bridgeRequestId, false, "invalid_request")
                return
            }
        }
        when (val outcome = session.sink.replace(items, startIndex, metadata, skipPreplay)) {
            is SinkOpenOutcome.Failed -> {
                result(bridgeRequestId, false, outcome.failure.code)
                outcome.failure.endSessionReason?.let { finishSessionLocked(session, it) }
            }
            SinkOpenOutcome.Ok -> {
                session.pendingNeed = null
                session.endOfList = false
                result(bridgeRequestId, true)
                val snapshot = session.sink.currentSnapshot()
                emitState(session, snapshot)
                if (!session.sink.isDemandSuppressed()) {
                    maybeRequestItems(session, snapshot)
                }
            }
        }
    }

    private suspend fun append(session: Active, bridgeRequestId: String, payload: JSONObject?) {
        val items = LinkedPageCastCoordinator.parseItems(payload?.optJSONArray("items"), session.allowedPrivateOrigins)
        val knownIds = session.sink.knownIds
        if (items == null ||
            items.distinctBy { it.id }.size != items.size ||
            items.any { it.id in knownIds }
        ) {
            result(bridgeRequestId, false, "invalid_request")
            return
        }
        if (knownIds.size + items.size > LinkedPageCastCoordinator.MAX_SESSION_ITEMS) {
            result(bridgeRequestId, false, "resource_limit")
            return
        }
        when (val outcome = session.sink.append(items)) {
            is SinkBatchOutcome.Failed -> {
                result(bridgeRequestId, false, outcome.failure.code)
                outcome.failure.endSessionReason?.let { finishSessionLocked(session, it) }
            }
            SinkBatchOutcome.Ok -> {
                result(bridgeRequestId, true)
                emitState(session, session.sink.currentSnapshot())
            }
        }
    }

    private suspend fun supply(session: Active, bridgeRequestId: String, payload: JSONObject?) {
        val pending = session.pendingNeed
        val requestId = payload?.optString("requestId")
        when (linkedSupplyDisposition(requestId, pending?.requestId, session.lastAcceptedNeedId)) {
            LinkedSupplyDisposition.ALREADY_ACCEPTED -> {
                result(bridgeRequestId, true)
                return
            }
            LinkedSupplyDisposition.STALE -> {
                logWarning("Rejected linked_supply because its request is stale")
                result(bridgeRequestId, false, "stale_request")
                return
            }
            LinkedSupplyDisposition.ACCEPT -> Unit
        }
        val acceptedPending = checkNotNull(pending)
        val acceptedPayload = checkNotNull(payload)
        val endOfList = acceptedPayload.optBoolean("endOfList", false)
        val array = acceptedPayload.optJSONArray("items") ?: JSONArray()
        if (array.length() == 0 && !endOfList) {
            result(bridgeRequestId, false, "invalid_request")
            return
        }
        if (array.length() == 0) {
            session.pendingNeed = null
            session.endOfList = true
            session.lastAcceptedNeedId = acceptedPending.requestId
            result(bridgeRequestId, true)
            return
        }
        val items = LinkedPageCastCoordinator.parseItems(array, session.allowedPrivateOrigins)
        val knownIds = session.sink.knownIds
        if (items == null ||
            items.size > acceptedPending.count ||
            items.distinctBy { it.id }.size != items.size ||
            items.any { it.id in knownIds }
        ) {
            result(bridgeRequestId, false, "invalid_request")
            return
        }
        if (knownIds.size + items.size > LinkedPageCastCoordinator.MAX_SESSION_ITEMS) {
            result(bridgeRequestId, false, "resource_limit")
            return
        }
        when (val outcome = session.sink.supply(items, endOfList)) {
            is SinkBatchOutcome.Failed -> {
                if (outcome.failure.suppressPendingClear) {
                    session.pendingNeed = null
                    session.lastAcceptedNeedId = acceptedPending.requestId
                    if (endOfList) session.endOfList = true
                }
                result(bridgeRequestId, false, outcome.failure.code)
                outcome.failure.endSessionReason?.let { finishSessionLocked(session, it) }
            }
            SinkBatchOutcome.Ok -> {
                session.pendingNeed = null
                session.endOfList = endOfList
                session.lastAcceptedNeedId = acceptedPending.requestId
                result(bridgeRequestId, true)
                val snapshot = session.sink.currentSnapshot()
                emitState(session, snapshot)
                if (!session.sink.isDemandSuppressed()) {
                    maybeRequestItems(session, snapshot)
                }
            }
        }
    }

    private suspend fun jump(session: Active, bridgeRequestId: String, payload: JSONObject?) {
        val index = payload?.optInt("index", -1) ?: -1
        val failure = session.sink.jump(index)
        if (failure != null) {
            result(bridgeRequestId, false, failure.code)
            failure.endSessionReason?.let { finishSessionLocked(session, it) }
            return
        }
        session.pendingNeed = null
        result(bridgeRequestId, true)
        val snapshot = session.sink.currentSnapshot()
        emitState(session, snapshot)
        if (!session.sink.isDemandSuppressed()) {
            maybeRequestItems(session, snapshot)
        }
    }

    private fun startHeartbeat(session: Active, sessionScope: CoroutineScope) {
        sessionScope.launch {
            while (isActive && active === session) {
                delay(LINKED_ECHO_RESYNC_MILLIS)
                operationMutex.withLock {
                    if (active !== session) return@withLock
                    val now = clock()
                    if (linkedSessionExpired(now, session.lastPageActivityAtMillis, session.createdAtMillis)) {
                        finishSessionLocked(session, "session_expired")
                    } else {
                        session.sink.onHeartbeatTick(now)
                    }
                }
            }
        }
    }

    private fun createHost(session: Active): LinkedSinkHost = object : LinkedSinkHost {
        override fun runWithLock(block: suspend () -> Unit) {
            scope.launch {
                operationMutex.withLock {
                    if (active === session) block()
                }
            }
        }

        override fun emitSnapshot(snapshot: SinkPlaybackState, evaluateDemand: Boolean) {
            if (active !== session) return
            emitState(session, snapshot)
            if (evaluateDemand && !session.sink.isDemandSuppressed()) {
                scope.launch {
                    operationMutex.withLock {
                        if (active === session && !session.sink.isDemandSuppressed()) {
                            maybeRequestItems(session, session.sink.currentSnapshot())
                        }
                    }
                }
            }
        }

        override fun finish(reason: String) {
            finishSession(session, reason)
        }

        override fun nowMillis(): Long = clock()
    }

    private suspend fun maybeRequestItems(session: Active, snapshot: SinkPlaybackState) {
        if (active !== session || session.sink.isDemandSuppressed()) return
        val now = clock()
        session.pendingNeed?.let { pending ->
            if (now - pending.lastDispatchedAtMillis < LinkedPageCastCoordinator.NEED_ITEMS_RETRY_MILLIS) return
            pending.lastDispatchedAtMillis = now
            dispatchNeedItems(session, pending, snapshot)
            return
        }
        val window = prefetchWindow().coerceIn(1, 10)
        val count = linkedQueueDemand(
            prefetchWindow = window,
            currentIndex = snapshot.currentIndex,
            totalCount = snapshot.totalCount,
            requestPending = session.pendingNeed != null,
            endOfList = session.endOfList,
            awaitingPlaylistEcho = snapshot.awaitingPlaylistEcho,
        )
        if (count <= 0) return
        val request = PendingNeed(UUID.randomUUID().toString(), count, now)
        session.pendingNeed = request
        dispatchNeedItems(session, request, snapshot)
    }

    private fun dispatchNeedItems(session: Active, request: PendingNeed, snapshot: SinkPlaybackState) {
        val lastIdx = (snapshot.totalCount - 1).coerceAtLeast(0)
        event(
            session,
            "needitems",
            JSONObject()
                .put("requestId", request.requestId)
                .put("afterIndex", lastIdx)
                .put("afterItemId", snapshot.items.getOrNull(lastIdx)?.linkedId)
                .put("count", request.count),
        )
    }

    private fun emitState(session: Active, snapshot: SinkPlaybackState) {
        val now = clock()
        val structuralState = listOf(
            snapshot.state,
            snapshot.title,
            snapshot.durationMs,
            snapshot.currentIndex,
            snapshot.totalCount,
            snapshot.items,
        ).hashCode()
        if (session.lastStateStructure == structuralState &&
            now - session.lastStateEmittedAtMillis < LinkedPageCastCoordinator.STATE_EVENT_INTERVAL_MILLIS
        ) return
        session.lastStateStructure = structuralState
        session.lastStateEmittedAtMillis = now
        val queue = JSONArray()
        snapshot.items.forEachIndexed { index, item ->
            queue.put(
                JSONObject()
                    .put("index", index)
                    .put("id", item.linkedId)
                    .put("title", item.title),
            )
        }
        event(
            session,
            "statechange",
            JSONObject()
                .put("state", snapshot.state)
                .put("positionMs", snapshot.positionMs)
                .put("durationMs", snapshot.durationMs)
                .put("title", snapshot.title)
                .put("currentIndex", snapshot.currentIndex)
                .put("totalCount", snapshot.totalCount)
                .put("items", queue),
        )
    }

    @Synchronized
    private fun finishSession(session: Active, reason: String) {
        finishSessionLocked(session, reason)
    }

    @Synchronized
    private fun finishSessionLocked(session: Active, reason: String) {
        if (active !== session) return
        active = null
        session.sessionScope?.coroutineContext?.get(Job)?.cancel()
        session.sink.close()
        onUiStateChange(LinkedPageCastUiState())
        event(session, "ended", JSONObject().put("reason", reason))
    }

    private fun result(bridgeRequestId: String, ok: Boolean, error: String? = null) {
        postLinkedMessage(
            JSONObject()
                .put("type", "linked_result")
                .put("bridgeRequestId", bridgeRequestId)
                .put("ok", ok)
                .apply { if (error != null) put("error", error) },
        )
    }

    private fun event(session: Active, name: String, detail: JSONObject) {
        postLinkedMessage(
            JSONObject()
                .put("type", "linked_event")
                .put("sessionId", session.sessionId)
                .put("event", name)
                .put("detail", detail),
        )
    }

    private fun logWarning(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    companion object {
        private const val TAG = "LinkedSessionEngine"
        private const val MAX_CANCELLED_OPEN_REQUESTS = 64
    }
}
