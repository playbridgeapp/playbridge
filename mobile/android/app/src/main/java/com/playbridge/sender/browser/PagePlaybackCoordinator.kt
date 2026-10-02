package com.playbridge.sender.browser

import android.content.Context
import android.content.Intent
import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.SessionPhase
import com.playbridge.sender.cast.SubtitleRef
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.player.PagePlayerSession
import com.playbridge.sender.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Destination selection and phone playback use the same native state as Library. */
class PagePlaybackCoordinator(
    private val context: Context,
    private val connection: ConnectionViewModel,
    private val linked: LinkedPageCastCoordinator,
    private val scope: CoroutineScope,
) {
    private var active: LinkedPageCastOpenRequest? = null
    private var externalProgress: Job? = null
    private var createdAtMillis = 0L
    private var lastActivityAtMillis = 0L

    suspend fun destination(): JSONObject {
        val route = connection.route.value
        val device = if (route is CastSessionManager.Route.External) connection.activeExternalDevice.value
            else connection.tvDevice.first()
        val local = route is CastSessionManager.Route.ThisDevice
        val connected = when (route) {
            is CastSessionManager.Route.ThisDevice -> true
            is CastSessionManager.Route.NativeTv -> connection.connectionState.value is WebSocketClient.ConnectionState.Connected
            is CastSessionManager.Route.External -> connection.castSessionState.value.phase in setOf(SessionPhase.CONNECTED, SessionPhase.PLAYING)
        }
        return JSONObject().put("id", if (local) "this-device" else device?.endpointKey?.toString() ?: "unavailable")
            .put("name", if (local) "This device" else device?.name ?: "TV")
            .put("kind", if (local) "local" else if (route is CastSessionManager.Route.NativeTv) "native" else "external")
            .put("connected", connected)
    }

    fun handle(message: JSONObject): Boolean {
        val type = message.optString("type")
        if (type in setOf("linked_destination", "linked_choose_destination")) {
            scope.launch {
                val tab = message.optInt("tabId", -1)
                val generation = message.optLong("navigationGeneration", -1)
                if (!Components.isCurrentPageNavigation(tab, generation)) { reply(message, "session_ended"); return@launch }
                if (type == "linked_choose_destination") {
                    val payload = message.optJSONObject("payload") ?: JSONObject()
                    if (payload.has("destinationId")) {
                        if (payload.opt("destinationId") != "this-device") { reply(message, "invalid_request"); return@launch }
                        connection.selectThisDevice()
                    } else Components.playbackDevicePickerRequests.value += 1
                }
                val value = destination()
                if (Components.isCurrentPageNavigation(tab, generation)) reply(message, destination = value)
                else reply(message, "session_ended")
            }
            return true
        }
        val session = active ?: return false
        if (message.optString("sessionId") != session.sessionId) return false
        if (message.optString("origin") != session.origin || message.optInt("tabId", -1) != session.tabId ||
            message.optLong("navigationGeneration", -1) != session.navigationGeneration) {
            reply(message, "session_ended"); return true
        }
        lastActivityAtMillis = System.currentTimeMillis()
        when (type) {
            "linked_ping" -> { PagePlayerSession.ready(session.sessionId); reply(message) }
            "linked_unlink" -> { reply(message); end("unlinked") }
            "linked_supply" -> scope.launch {
                if (externalProgress != null) { reply(message, "unsupported_operation"); return@launch }
                val payload = message.optJSONObject("payload") ?: JSONObject()
                val origins = linked.messageRequestedPrivateOrigins(message)
                if (origins == null || !PageCastConsentStore.isApproved(context, session.origin) ||
                    PageCastConsentStore.unapprovedPrivateOrigins(context, session.origin, origins).isNotEmpty()) {
                    reply(message, "not_allowed"); return@launch
                }
                if (active !== session) { reply(message, "session_ended"); return@launch }
                val array = payload.optJSONArray("items") ?: JSONArray()
                val items = if (array.length() == 0 && payload.optBoolean("endOfList")) emptyList()
                    else LinkedPageCastCoordinator.parseItems(array, origins)
                if (items == null || !PagePlayerSession.supply(session.sessionId, payload.optString("requestId"), items,
                        payload.optBoolean("endOfList"))) reply(message, "stale_request") else reply(message)
            }
            else -> reply(message, "unsupported_operation")
        }
        return true
    }

    suspend fun open(request: LinkedPageCastOpenRequest) {
        val destination = destination()
        if (!Components.isCurrentPageNavigation(request.tabId, request.navigationGeneration) ||
            linked.isOpenCancelled(request.bridgeRequestId)) { reject(request, "session_ended"); return }
        if (destination.optString("id") != request.destinationId) { reject(request, "receiver_changed"); return }
        if (!destination.optBoolean("connected")) { reject(request, "connect_failed"); return }
        end("superseded")
        linked.supersedeIfActive()
        active = request
        createdAtMillis = System.currentTimeMillis(); lastActivityAtMillis = createdAtMillis
        val item = request.items[request.startIndex]
        if (destination.optString("kind") == "local") {
            PagePlayerSession.open(request) { name, detail -> if (active === request) event(name, detail) }
            val intent = Intent(context, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, item.payload.url)
                .putExtra(PlayerActivity.EXTRA_TITLE, item.payload.title)
                .putExtra(PlayerActivity.EXTRA_CONTENT_TYPE, item.payload.content_type)
                .putExtra(PlayerActivity.EXTRA_HEADERS, JSONObject(item.payload.headers).toString())
                .putExtra(PlayerActivity.EXTRA_PAGE_SESSION_ID, request.sessionId)
            try { context.startActivity(intent); replyRequest(request) }
            catch (_: Exception) { reject(request, "playback_failed"); end("playback_failed") }
        } else {
            if (request.items.size > 1) { reject(request, "unsupported_queue"); end("unsupported_queue"); return }
            val value = item.payload
            val subtitles = value.subtitles.map { SubtitleRef(it) } + value.subtitle_resources.map {
                SubtitleRef(it.url, it.label, it.language)
            }
            // Credentialed sidecars require the native PlayBridge/local path.
            if (value.subtitle_resources.any { it.headers.isNotEmpty() }) {
                reject(request, "unsupported_subtitles"); end("unsupported_subtitles"); return
            }
            val previousEpoch = connection.externalStatus.value?.loadEpoch
            if (!connection.castToSelectedExternal(MediaItem(url = value.url, headers = value.headers,
                    title = value.title, mimeType = value.content_type, subtitles = subtitles,
                    visualMetadata = value.visual_metadata, startPositionMs = value.start_position_ms ?: 0))) {
                reject(request, "playback_failed"); end("playback_failed"); return
            }
            val loadedEpoch = connection.externalStatus.value?.loadEpoch
            replyRequest(request)
            externalProgress = scope.launch {
                var epoch: Long? = loadedEpoch
                while (active === request) {
                    delay(1000)
                    if (destination().optString("id") != request.destinationId || !destination().optBoolean("connected")) {
                        end("receiver_changed"); break
                    }
                    if (!PageCastConsentStore.isApproved(context, request.origin) ||
                        linkedSessionExpired(System.currentTimeMillis(), lastActivityAtMillis, createdAtMillis)) {
                        end("session_ended"); break
                    }
                    val status = connection.externalStatus.value ?: continue
                    if (epoch == null) {
                        if (status.loadEpoch == null || status.loadEpoch == previousEpoch) continue
                        epoch = status.loadEpoch
                    }
                    if (status.loadEpoch != epoch) { end("superseded"); break }
                    event("statechange", JSONObject().put("currentIndex", request.startIndex)
                        .put("items", JSONArray(request.items.map { JSONObject().put("id", it.id) }))
                        .put("positionMs", status.positionMs).put("durationMs", status.durationMs)
                        .put("state", status.state.name.lowercase()))
                    if (status.state in setOf(PlaybackState.STOPPED, PlaybackState.ERROR)) { end("receiver_stopped"); break }
                }
            }
        }
    }

    fun cancelOpen(requestId: String) {
        if (active?.bridgeRequestId == requestId) end("user_cancelled")
    }

    fun navigation(tabId: Int, generation: Long) {
        active?.takeIf { pageRequestSuperseded(it.tabId, it.navigationGeneration, tabId, generation) }?.let { end("navigation") }
    }

    fun end(reason: String) {
        val session = active ?: return
        PagePlayerSession.flush(session.sessionId)
        event("ended", JSONObject().put("reason", reason))
        active = null
        externalProgress?.cancel(); externalProgress = null
        PagePlayerSession.unlink(session.sessionId)
    }

    private fun event(name: String, detail: JSONObject) {
        val session = active ?: return
        Components.postLinkedMessage(JSONObject().put("type", "linked_event").put("sessionId", session.sessionId)
            .put("event", name).put("detail", detail))
        if (name == "ended") { active = null; externalProgress?.cancel(); externalProgress = null }
    }

    private fun reply(message: JSONObject, error: String? = null, destination: JSONObject? = null) {
        Components.postLinkedMessage(JSONObject().put("type", "linked_result")
            .put("bridgeRequestId", message.optString("bridgeRequestId")).put("ok", error == null)
            .apply { if (error != null) put("error", error); if (destination != null) put("destination", destination) })
    }
    private fun replyRequest(request: LinkedPageCastOpenRequest) = reply(JSONObject().put("bridgeRequestId", request.bridgeRequestId))
    private fun reject(request: LinkedPageCastOpenRequest, error: String) = reply(JSONObject().put("bridgeRequestId", request.bridgeRequestId), error)
}
