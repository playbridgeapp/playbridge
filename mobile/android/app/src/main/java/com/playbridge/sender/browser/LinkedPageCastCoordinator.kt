package com.playbridge.sender.browser

import android.util.Log
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.connection.ConnectionMerge
import com.playbridge.sender.connection.ConnectionStore
import com.playbridge.sender.connection.ExternalQueueCoordinator
import com.playbridge.sender.connection.TvQueueCoordinator
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.model.TvDevice
import com.playbridge.shared.network.MediaNetworkPolicy
import com.playbridge.shared.protocol.createContextQueryJson
import com.playbridge.shared.protocol.createPlaylistCommandJson
import com.playbridge.shared.protocol.createPlaylistJumpCommandJson
import com.playbridge.shared.protocol.createQueueAddCommandJson
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import playbridge.PlayPayload
import playbridge.PlaylistPayload
import playbridge.SubtitleResource

data class LinkedPageCastUiState(
    val active: Boolean = false,
    val controllerName: String? = null,
)

data class LinkedPageCastOpenRequest(
    val bridgeRequestId: String,
    val sessionId: String,
    val origin: String,
    val tabId: Int,
    val navigationGeneration: Long,
    val items: List<LinkedPageCastItem>,
    val startIndex: Int,
    val playlistMetadata: playbridge.VisualMetadata?,
    val skipPreplay: Boolean,
    val requestedPrivateOrigins: Set<String>,
) {
    fun withPrivateOriginPermission(origins: Collection<String>): LinkedPageCastOpenRequest = copy(
        items = items.map { item ->
            item.copy(payload = item.payload.copy(allowed_private_origins = origins.toList()))
        },
    )
}

data class LinkedPageCastItem(
    val id: String,
    val payload: PlayPayload,
)

internal fun linkedQueueDemand(
    prefetchWindow: Int,
    currentIndex: Int,
    totalCount: Int,
    requestPending: Boolean,
    endOfList: Boolean,
    awaitingPlaylistEcho: Boolean,
): Int {
    if (requestPending || endOfList || awaitingPlaylistEcho) return 0
    val queuedAhead = (totalCount - currentIndex - 1).coerceAtLeast(0)
    return (prefetchWindow.coerceIn(1, 10) - queuedAhead).coerceAtLeast(0)
}

internal enum class LinkedSupplyDisposition { ACCEPT, ALREADY_ACCEPTED, STALE }

internal fun linkedSupplyDisposition(
    requestId: String?,
    pendingRequestId: String?,
    lastAcceptedRequestId: String?,
): LinkedSupplyDisposition = when {
    requestId != null && requestId == lastAcceptedRequestId ->
        LinkedSupplyDisposition.ALREADY_ACCEPTED
    requestId != null && requestId == pendingRequestId -> LinkedSupplyDisposition.ACCEPT
    else -> LinkedSupplyDisposition.STALE
}

internal fun pageRequestSuperseded(
    requestTabId: Int,
    requestNavigationGeneration: Long,
    navigationTabId: Int,
    navigationGeneration: Long,
): Boolean = requestTabId == navigationTabId &&
    navigationGeneration > requestNavigationGeneration

private const val LINKED_SESSION_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1_000L
private const val LINKED_SESSION_MAX_LIFETIME_MILLIS = 2 * 60 * 60 * 1_000L

internal fun linkedSessionExpired(
    nowMillis: Long,
    lastActivityAtMillis: Long,
    createdAtMillis: Long,
): Boolean = nowMillis - lastActivityAtMillis > LINKED_SESSION_IDLE_TIMEOUT_MILLIS ||
    nowMillis - createdAtMillis > LINKED_SESSION_MAX_LIFETIME_MILLIS

/** Owns the single page-linked playlist authority while Android owns the TV session. */
class LinkedPageCastCoordinator(
    private val webSocketClient: WebSocketClient,
    private val connectionCoordinator: ConnectionCoordinator,
    private val connectionStore: ConnectionStore,
    private val settingsRepository: SettingsRepository,
    private val tvQueueCoordinator: TvQueueCoordinator,
    private val externalQueueCoordinator: ExternalQueueCoordinator,
    private val scope: CoroutineScope,
) {
    private data class PendingNeed(
        val requestId: String,
        val count: Int,
        var lastDispatchedAtMillis: Long = System.currentTimeMillis(),
    )
    private data class Active(
        val openBridgeRequestId: String,
        val sessionId: String,
        val origin: String,
        val receiver: TvDevice,
        val ids: MutableList<String>,
        val createdAtMillis: Long = System.currentTimeMillis(),
        var lastPageActivityAtMillis: Long = System.currentTimeMillis(),
        val allowedPrivateOrigins: MutableSet<String> = linkedSetOf(),
        var pendingNeed: PendingNeed? = null,
        var lastAcceptedNeedId: String? = null,
        var endOfList: Boolean = false,
        var awaitingPlaylistEcho: Boolean = true,
        var hasSeenPlayer: Boolean = false,
        var lastStateStructure: Int? = null,
        var lastStateEmittedAtMillis: Long = 0,
    )

    @Volatile
    private var active: Active? = null
    private val operationMutex = Mutex()
    private val cancelledOpenRequests = linkedSetOf<String>()
    private val _uiState = MutableStateFlow(LinkedPageCastUiState())
    val uiState: StateFlow<LinkedPageCastUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            combine(
                connectionCoordinator.tvPlaylistState,
                connectionCoordinator.tvPlayback,
                connectionCoordinator.tvActiveContext,
            ) { playlist, playback, context -> Triple(playlist, playback, context) }
                .collect { (playlist, playback, context) ->
                    val session = active ?: return@collect
                    if (context == "player") session.hasSeenPlayer = true
                    if (context == "idle" && session.hasSeenPlayer) {
                        finish("receiver_stopped")
                        return@collect
                    }
                    if (playlist != null) {
                        if (playlist.totalCount >= session.ids.size) session.awaitingPlaylistEcho = false
                        emitState(session, playlist.currentIndex, playlist.totalCount, playlist.items.map { it.title }, playback)
                        maybeRequestItems(session, playlist.currentIndex, playlist.totalCount)
                    } else {
                        emitState(session, 0, session.ids.size, emptyList(), playback)
                    }
                }
        }
        scope.launch {
            webSocketClient.connectionState.collect { state ->
                if (state is WebSocketClient.ConnectionState.Connected && active != null) {
                    webSocketClient.send(createContextQueryJson())
                }
            }
        }
        scope.launch {
            connectionStore.tvDevice.collect { device ->
                val session = active ?: return@collect
                if (device == null || !ConnectionMerge.isSameDevice(device, session.receiver)) {
                    finish("receiver_changed")
                }
            }
        }
        scope.launch {
            while (true) {
                delay(LINKED_STATUS_RESYNC_MILLIS)
                val session = active
                if (session != null && sessionExpired(session)) {
                    finish("session_expired")
                } else if (session != null &&
                    webSocketClient.connectionState.value is WebSocketClient.ConnectionState.Connected
                ) {
                    // Receiver broadcasts are best-effort. Re-querying while a page owns the
                    // queue recovers a dropped playlist index update without polling the page.
                    webSocketClient.send(createContextQueryJson())
                }
            }
        }
    }

    fun parseOpen(message: JSONObject): LinkedPageCastOpenRequest? = runCatching {
        val bridgeRequestId = message.requiredShortString("bridgeRequestId", 128)
        val sessionId = message.requiredShortString("sessionId", 128)
        val origin = PageCastConsentStore.normalizeOrigin(message.optString("origin")) ?: return null
        val tabId = message.getInt("tabId")
        val navigationGeneration = message.getLong("navigationGeneration")
        if (tabId < 0 || navigationGeneration < 0) return null
        val payload = message.optJSONObject("payload") ?: return null
        if (payload.toString().toByteArray().size > MAX_REQUEST_BYTES) return null
        val items = parseItems(payload.optJSONArray("items"), emptySet()) ?: return null
        val startIndex = payload.optInt("startIndex", 0)
        if (startIndex !in items.indices) return null
        val metadata = payload.optJSONObject("metadata")?.let {
            if (it.toString().toByteArray().size > MAX_METADATA_BYTES) return null
            com.playbridge.shared.protocol.decodeVisualMetadataJson(it.toString())
        }
        val skipPreplayValue = payload.opt("skipPreplay")
        val skipPreplay = if (skipPreplayValue == null || skipPreplayValue == JSONObject.NULL) {
            false
        } else {
            skipPreplayValue as? Boolean ?: return null
        }
        LinkedPageCastOpenRequest(
            bridgeRequestId,
            sessionId,
            origin,
            tabId,
            navigationGeneration,
            items,
            startIndex,
            metadata,
            skipPreplay,
            parseRequestedPrivateOrigins(payload) ?: return null,
        )
    }.getOrNull()

    suspend fun requestedPrivateOrigins(request: LinkedPageCastOpenRequest): Set<String>? =
        withContext(Dispatchers.IO) {
            collectRequestedPrivateOrigins(request.items, request.requestedPrivateOrigins)
        }

    suspend fun messageRequestedPrivateOrigins(message: JSONObject): Set<String>? = withContext(Dispatchers.IO) {
        val payload = message.optJSONObject("payload") ?: return@withContext emptySet()
        val declared = parseRequestedPrivateOrigins(payload) ?: return@withContext null
        val items = parseItems(payload.optJSONArray("items"), emptySet()).orEmpty()
        collectRequestedPrivateOrigins(items, declared)
    }

    fun allowPrivateOriginsForActive(origin: String, origins: Collection<String>): Boolean {
        val session = active?.takeIf { it.origin == origin } ?: return false
        val combined = session.allowedPrivateOrigins + origins
        val normalized = MediaNetworkPolicy.normalizePrivateOrigins(combined) ?: return false
        session.allowedPrivateOrigins.clear()
        session.allowedPrivateOrigins.addAll(normalized)
        return true
    }

    fun isMessageForActiveSession(message: JSONObject, origin: String): Boolean {
        val session = active ?: return false
        return session.sessionId == message.optString("sessionId") && session.origin == origin
    }

    fun cancelOpen(targetBridgeRequestId: String, reason: String = "navigation") {
        synchronized(cancelledOpenRequests) {
            cancelledOpenRequests += targetBridgeRequestId
            while (cancelledOpenRequests.size > MAX_CANCELLED_OPEN_REQUESTS) {
                cancelledOpenRequests.remove(cancelledOpenRequests.first())
            }
        }
        if (active?.openBridgeRequestId == targetBridgeRequestId) finish(reason)
        result(targetBridgeRequestId, false, "session_ended")
    }

    fun isOpenCancelled(bridgeRequestId: String): Boolean = synchronized(cancelledOpenRequests) {
        bridgeRequestId in cancelledOpenRequests
    }

    fun open(request: LinkedPageCastOpenRequest, receiver: TvDevice) {
        scope.launch {
            operationMutex.withLock {
                if (isOpenCancelled(request.bridgeRequestId)) return@withLock
                if (webSocketClient.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
                    result(request.bridgeRequestId, false, "connect_failed")
                    return@withLock
                }
                active?.let { old -> event(old, "ended", JSONObject().put("reason", "superseded")) }
                tvQueueCoordinator.stop()
                externalQueueCoordinator.stop()
                val next = Active(
                    openBridgeRequestId = request.bridgeRequestId,
                    sessionId = request.sessionId,
                    origin = request.origin,
                    receiver = receiver,
                    ids = request.items.mapTo(mutableListOf()) { it.id },
                    allowedPrivateOrigins = request.items.flatMapTo(linkedSetOf()) { it.payload.allowed_private_origins },
                    hasSeenPlayer = connectionCoordinator.tvActiveContext.value == "player",
                )
                active = next
                _uiState.value = LinkedPageCastUiState(true, PageCastConsentStore.displayName(request.origin))
                connectionCoordinator.tvPlaylistState.value = null
                val sent = webSocketClient.send(
                    createPlaylistCommandJson(
                        PlaylistPayload(
                            items = request.items.map { it.payload },
                            start_index = request.startIndex,
                            visual_metadata = request.playlistMetadata,
                            skip_preplay = request.skipPreplay,
                        ),
                    ),
                )
                if (!sent || isOpenCancelled(request.bridgeRequestId)) {
                    active = null
                    _uiState.value = LinkedPageCastUiState()
                    if (!isOpenCancelled(request.bridgeRequestId)) {
                        result(request.bridgeRequestId, false, "connect_failed")
                    }
                    return@withLock
                }
                result(request.bridgeRequestId, true)
            }
        }
    }

    fun handle(message: JSONObject) {
        val bridgeRequestId = message.optString("bridgeRequestId")
        val sessionId = message.optString("sessionId")
        val type = message.optString("type")
        val session = active
        if (session == null || session.sessionId != sessionId) {
            result(bridgeRequestId, false, "session_ended")
            return
        }
        scope.launch {
            operationMutex.withLock {
                if (active !== session) {
                    result(bridgeRequestId, false, "session_ended")
                    return@withLock
                }
                val selectedReceiver = connectionStore.tvDevice.first()
                if (selectedReceiver == null ||
                    !ConnectionMerge.isSameDevice(selectedReceiver, session.receiver)
                ) {
                    result(bridgeRequestId, false, "receiver_changed")
                    finish("receiver_changed")
                    return@withLock
                }
                if (message.toString().toByteArray().size > MAX_REQUEST_BYTES) {
                    result(bridgeRequestId, false, "resource_limit")
                    return@withLock
                }
                session.lastPageActivityAtMillis = System.currentTimeMillis()
                when (type) {
                    "linked_ping" -> result(bridgeRequestId, true)
                    "linked_replace" -> replace(session, bridgeRequestId, message.optJSONObject("payload"))
                    "linked_append" -> append(session, bridgeRequestId, message.optJSONObject("payload"))
                    "linked_supply" -> supply(session, bridgeRequestId, message.optJSONObject("payload"))
                    "linked_jump" -> jump(session, bridgeRequestId, message.optJSONObject("payload"))
                    "linked_unlink" -> {
                        result(bridgeRequestId, true)
                        finish(message.optString("reason", "unlinked"))
                    }
                    else -> result(bridgeRequestId, false, "invalid_request")
                }
            }
        }
    }

    fun reject(bridgeRequestId: String, error: String) = result(bridgeRequestId, false, error)

    fun supersedeIfActive() {
        if (active != null) finish("superseded")
    }

    fun unlink(reason: String = "unlinked") {
        if (active != null) finish(reason)
    }

    fun activeOrigin(): String? = active?.origin

    private suspend fun replace(session: Active, bridgeRequestId: String, payload: JSONObject?) {
        val items = parseItems(payload?.optJSONArray("items"), session.allowedPrivateOrigins)
        val startIndex = payload?.optInt("startIndex", 0) ?: 0
        if (items == null || startIndex !in items.indices) {
            result(bridgeRequestId, false, "invalid_request")
            return
        }
        val metadata = payload?.optJSONObject("metadata")?.let {
            if (it.toString().toByteArray().size > MAX_METADATA_BYTES) {
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
        connectionCoordinator.tvPlaylistState.value = null
        val sent = webSocketClient.send(
            createPlaylistCommandJson(
                PlaylistPayload(
                    items = items.map { it.payload },
                    start_index = startIndex,
                    visual_metadata = metadata,
                    skip_preplay = skipPreplay,
                ),
            ),
        )
        if (!sent) {
            result(bridgeRequestId, false, "connect_failed")
            finish("queue_update_failed")
            return
        }
        session.ids.clear()
        session.ids.addAll(items.map { it.id })
        session.pendingNeed = null
        session.endOfList = false
        session.awaitingPlaylistEcho = true
        result(bridgeRequestId, true)
    }

    private suspend fun append(session: Active, bridgeRequestId: String, payload: JSONObject?) {
        val items = parseItems(payload?.optJSONArray("items"), session.allowedPrivateOrigins)
        if (items == null || items.any { it.id in session.ids }) {
            result(bridgeRequestId, false, "invalid_request")
            return
        }
        if (session.ids.size + items.size > MAX_SESSION_ITEMS) {
            result(bridgeRequestId, false, "resource_limit")
            return
        }
        if (!sendQueueItems(items)) {
            result(bridgeRequestId, false, "connect_failed")
            finish("queue_update_failed")
            return
        }
        session.ids += items.map { it.id }
        result(bridgeRequestId, true)
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
                Log.w(TAG, "Rejected linked_supply because its request is stale")
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
        val items = parseItems(array, session.allowedPrivateOrigins)
        if (items == null || items.size > acceptedPending.count || items.any { it.id in session.ids }) {
            result(bridgeRequestId, false, "invalid_request")
            return
        }
        if (session.ids.size + items.size > MAX_SESSION_ITEMS) {
            result(bridgeRequestId, false, "resource_limit")
            return
        }
        if (!sendQueueItems(items)) {
            result(bridgeRequestId, false, "connect_failed")
            finish("queue_update_failed")
            return
        }
        session.ids += items.map { it.id }
        session.pendingNeed = null
        session.endOfList = endOfList
        session.lastAcceptedNeedId = acceptedPending.requestId
        result(bridgeRequestId, true)
    }

    private fun sendQueueItems(items: List<LinkedPageCastItem>): Boolean {
        for (item in items) {
            if (!webSocketClient.send(createQueueAddCommandJson(item.payload))) return false
        }
        return true
    }

    private fun jump(session: Active, bridgeRequestId: String, payload: JSONObject?) {
        val index = payload?.optInt("index", -1) ?: -1
        if (index !in session.ids.indices) {
            result(bridgeRequestId, false, "invalid_request")
            return
        }
        session.pendingNeed = null
        val sent = webSocketClient.send(createPlaylistJumpCommandJson(index))
        result(bridgeRequestId, sent, if (sent) null else "connect_failed")
    }

    private suspend fun maybeRequestItems(session: Active, currentIndex: Int, totalCount: Int) {
        if (active !== session) return
        session.pendingNeed?.let { pending ->
            if (System.currentTimeMillis() - pending.lastDispatchedAtMillis < NEED_ITEMS_RETRY_MILLIS) return
            pending.lastDispatchedAtMillis = System.currentTimeMillis()
            dispatchNeedItems(session, pending, totalCount)
            return
        }
        val window = settingsRepository.tvPrefetchWindow.first().coerceIn(1, 10)
        val count = linkedQueueDemand(
            prefetchWindow = window,
            currentIndex = currentIndex,
            totalCount = totalCount,
            requestPending = session.pendingNeed != null,
            endOfList = session.endOfList,
            awaitingPlaylistEcho = session.awaitingPlaylistEcho,
        )
        if (count <= 0) return
        val request = PendingNeed(UUID.randomUUID().toString(), count)
        session.pendingNeed = request
        dispatchNeedItems(session, request, totalCount)
    }

    private fun dispatchNeedItems(session: Active, request: PendingNeed, totalCount: Int) {
        event(
            session,
            "needitems",
            JSONObject()
                .put("requestId", request.requestId)
                .put("afterIndex", (totalCount - 1).coerceAtLeast(0))
                .put("afterItemId", session.ids.getOrNull(totalCount - 1))
                .put("count", request.count),
        )
    }

    private fun sessionExpired(session: Active): Boolean {
        val now = System.currentTimeMillis()
        return linkedSessionExpired(now, session.lastPageActivityAtMillis, session.createdAtMillis)
    }

    private fun collectRequestedPrivateOrigins(
        items: List<LinkedPageCastItem>,
        declared: Collection<String>,
    ): Set<String>? {
        MediaNetworkPolicy.normalizePrivateOrigins(declared) ?: return null
        val origins = declared.mapNotNullTo(linkedSetOf(), MediaNetworkPolicy::privateOrigin)
        items.forEach { item ->
            sequenceOf(item.payload.url)
                .plus(item.payload.subtitles.asSequence())
                .plus(item.payload.subtitle_resources.asSequence().map(SubtitleResource::url))
                .mapNotNull(MediaNetworkPolicy::privateOrigin)
                .forEach(origins::add)
        }
        return MediaNetworkPolicy.normalizePrivateOrigins(origins)
    }

    private fun emitState(
        session: Active,
        currentIndex: Int,
        totalCount: Int,
        titles: List<String>,
        playback: com.playbridge.sender.cast.TvPlaybackStatus?,
    ) {
        val now = System.currentTimeMillis()
        val structuralState = listOf(
            playback?.state,
            playback?.title,
            playback?.durationMs,
            currentIndex,
            totalCount,
            session.ids.take(minOf(totalCount, session.ids.size)),
            titles,
        ).hashCode()
        if (session.lastStateStructure == structuralState &&
            now - session.lastStateEmittedAtMillis < STATE_EVENT_INTERVAL_MILLIS
        ) return
        session.lastStateStructure = structuralState
        session.lastStateEmittedAtMillis = now
        val queue = JSONArray()
        repeat(minOf(totalCount, session.ids.size)) { index ->
            queue.put(JSONObject().put("index", index).put("id", session.ids[index]).put("title", titles.getOrNull(index)))
        }
        event(
            session,
            "statechange",
            JSONObject()
                .put("state", playback?.state ?: "connecting")
                .put("positionMs", playback?.positionMs ?: 0L)
                .put("durationMs", playback?.durationMs ?: 0L)
                .put("title", playback?.title)
                .put("currentIndex", currentIndex)
                .put("totalCount", totalCount)
                .put("items", queue),
        )
    }

    @Synchronized
    private fun finish(reason: String) {
        val session = active ?: return
        active = null
        _uiState.value = LinkedPageCastUiState()
        event(session, "ended", JSONObject().put("reason", reason))
    }

    private fun result(bridgeRequestId: String, ok: Boolean, error: String? = null) {
        Components.postLinkedMessage(
            JSONObject()
                .put("type", "linked_result")
                .put("bridgeRequestId", bridgeRequestId)
                .put("ok", ok)
                .apply { if (error != null) put("error", error) },
        )
    }

    private fun event(session: Active, name: String, detail: JSONObject) {
        Components.postLinkedMessage(
            JSONObject()
                .put("type", "linked_event")
                .put("sessionId", session.sessionId)
                .put("event", name)
                .put("detail", detail),
        )
    }

    companion object {
        private const val TAG = "LinkedPageCast"
        private const val MAX_ITEMS = 50
        private const val MAX_SESSION_ITEMS = 200
        private const val MAX_SUBTITLES = 16
        private const val MAX_HEADERS = 16
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_METADATA_BYTES = 16 * 1024
        private const val MAX_REQUEST_BYTES = 64 * 1024
        private const val MAX_CANCELLED_OPEN_REQUESTS = 64
        private const val LINKED_STATUS_RESYNC_MILLIS = 2_000L
        private const val NEED_ITEMS_RETRY_MILLIS = 5_000L
        private const val STATE_EVENT_INTERVAL_MILLIS = 1_000L
        private val ALLOWED_HEADERS = setOf(
            "authorization", "cookie", "referer", "origin", "user-agent", "accept", "accept-language",
        )

        private fun parseRequestedPrivateOrigins(payload: JSONObject): Set<String>? {
            val values = payload.optJSONArray("privateNetworkOrigins") ?: return emptySet()
            if (values.length() > MediaNetworkPolicy.MAX_PRIVATE_ORIGINS) return null
            val origins = buildList {
                for (index in 0 until values.length()) {
                    val value = values.opt(index) as? String ?: return null
                    add(value)
                }
            }
            return MediaNetworkPolicy.normalizePrivateOrigins(origins)
        }

        private fun parseItems(
            array: JSONArray?,
            allowedPrivateOrigins: Collection<String>,
        ): List<LinkedPageCastItem>? {
            if (array == null || array.length() !in 1..MAX_ITEMS) return null
            val items = buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: return null
                    val id = obj.optString("id")
                    val url = obj.optString("url")
                    if (id.isBlank() || id.length > 128 || !MediaNetworkPolicy.isHttpUrl(url)) return null
                    val title = obj.optString("title").ifBlank { null }
                    val contentType = obj.optString("contentType").ifBlank { null }
                    if ((title?.length ?: 0) > 4_096 || (contentType?.length ?: 0) > 256) return null
                    val headers = parseHeaders(obj.optJSONObject("headers")) ?: return null
                    val subtitles = obj.optJSONArray("subtitles")?.let { values ->
                        if (values.length() > MAX_SUBTITLES) return null
                        buildList {
                            for (i in 0 until values.length()) {
                                val subtitle = values.optString(i)
                                if (!MediaNetworkPolicy.isHttpUrl(subtitle)) return null
                                add(subtitle)
                            }
                        }
                    }.orEmpty()
                    val subtitleResources = obj.optJSONArray("subtitleResources")?.let { values ->
                        if (values.length() > MAX_SUBTITLES) return null
                        buildList {
                            for (i in 0 until values.length()) {
                                val resource = values.optJSONObject(i) ?: return null
                                val subtitleUrl = resource.optString("url")
                                if (!MediaNetworkPolicy.isHttpUrl(subtitleUrl)) return null
                                val resourceHeaders = parseHeaders(resource.optJSONObject("headers")) ?: return null
                                val label = resource.optString("label").ifBlank { null }
                                val language = resource.optString("language").ifBlank { null }
                                if ((label?.length ?: 0) > 256 || (language?.length ?: 0) > 64) return null
                                add(SubtitleResource(subtitleUrl, resourceHeaders, label, language))
                            }
                        }
                    }.orEmpty()
                    if (subtitles.size + subtitleResources.size > MAX_SUBTITLES) return null
                    val metadata = obj.optJSONObject("metadata")?.let {
                        if (it.toString().toByteArray().size > MAX_METADATA_BYTES) return null
                        com.playbridge.shared.protocol.decodeVisualMetadataJson(it.toString())
                    }
                    add(
                        LinkedPageCastItem(
                            id,
                            PlayPayload(
                                url = url,
                                title = title,
                                headers = headers,
                                content_type = contentType,
                                subtitles = subtitles,
                                subtitle_resources = subtitleResources,
                                detected_by = "linked_page",
                                visual_metadata = metadata,
                                allowed_private_origins = allowedPrivateOrigins.toList(),
                            ),
                        ),
                    )
                }
            }
            return items.takeIf { it.map(LinkedPageCastItem::id).distinct().size == it.size }
        }

        private fun parseHeaders(obj: JSONObject?): Map<String, String>? {
            if (obj == null) return emptyMap()
            if (obj.length() > MAX_HEADERS) return null
            var bytes = 0
            val result = linkedMapOf<String, String>()
            val normalizedNames = mutableSetOf<String>()
            for (name in obj.keys()) {
                val lowerName = name.lowercase(Locale.ROOT)
                if (name != name.trim() || !HEADER_NAME.matches(name) || lowerName !in ALLOWED_HEADERS ||
                    !normalizedNames.add(lowerName)
                ) return null
                val value = obj.opt(name) as? String ?: return null
                if (value.any { it.code in 0..31 || it.code == 127 }) return null
                if (lowerName in setOf("origin", "referer") && !MediaNetworkPolicy.isHttpUrl(value)) return null
                bytes += name.toByteArray().size + value.toByteArray().size
                if (bytes > MAX_HEADER_BYTES) return null
                result[name] = value
            }
            return result
        }

        private val HEADER_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

        private fun JSONObject.requiredShortString(name: String, maxLength: Int): String {
            val value = getString(name)
            require(value.isNotBlank() && value.length <= maxLength)
            return value
        }
    }
}
