package com.playbridge.shared.protocol

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.wire.WireJsonAdapterFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import playbridge.AuthMessage
import playbridge.BrowserControlPayload
import playbridge.BrowserPayload
import playbridge.ControlPayload
import playbridge.MousePayload
import playbridge.PairingRequestMessage
import playbridge.PlayPayload
import playbridge.PlaylistJumpPayload
import playbridge.PlaylistPayload
import playbridge.QueueAddPayload
import playbridge.RemotePayload
import playbridge.VisualMetadata

/**
 * Wire-typed incoming message. Each variant holds the generated proto type directly,
 * so adding a proto field is a single edit (in .proto) plus regen — no duplication
 * in this file or in [parseIncomingMessage].
 */
sealed class IncomingMessage {
    data object Ping : IncomingMessage()
    data class PairingRequest(val msg: playbridge.PairingRequestMessage) : IncomingMessage()
    data class Auth(val msg: playbridge.AuthMessage) : IncomingMessage()
    data class Playlist(val payload: playbridge.PlaylistPayload) : IncomingMessage()
    data class QueueAdd(val payload: playbridge.QueueAddPayload) : IncomingMessage()
    data class PlaylistJump(val payload: playbridge.PlaylistJumpPayload) : IncomingMessage()
    data class Control(val payload: playbridge.ControlPayload) : IncomingMessage()
    data class Remote(val payload: playbridge.RemotePayload) : IncomingMessage()
    data class Mouse(val payload: playbridge.MousePayload) : IncomingMessage()
    data class Browser(val payload: playbridge.BrowserPayload) : IncomingMessage()
    data class BrowserControl(val payload: playbridge.BrowserControlPayload) : IncomingMessage()
    data object ContextQuery : IncomingMessage()
    data class Unknown(val type: String, val raw: String) : IncomingMessage()
}

private val moshi: Moshi = Moshi.Builder()
    .add(WireJsonAdapterFactory())
    .build()

private val playlistAdapter = moshi.adapter(PlaylistPayload::class.java)
private val queueAddAdapter = moshi.adapter(QueueAddPayload::class.java)
private val playlistJumpAdapter = moshi.adapter(PlaylistJumpPayload::class.java)
private val controlAdapter = moshi.adapter(ControlPayload::class.java)
private val remoteAdapter = moshi.adapter(RemotePayload::class.java)
private val mouseAdapter = moshi.adapter(MousePayload::class.java)
private val browserAdapter = moshi.adapter(BrowserPayload::class.java)
private val browserControlAdapter = moshi.adapter(BrowserControlPayload::class.java)
private val pairingAdapter = moshi.adapter(PairingRequestMessage::class.java)
private val authAdapter = moshi.adapter(AuthMessage::class.java)
private val visualMetadataAdapter = moshi.adapter(VisualMetadata::class.java)

/** Decode a JSON-encoded VisualMetadata. Returns null on failure (logged). */
fun decodeVisualMetadataJson(json: String): VisualMetadata? = try {
    visualMetadataAdapter.fromJson(json)
} catch (e: Exception) {
    android.util.Log.w("IncomingMessage", "decodeVisualMetadataJson failed: ${e.message}")
    null
}

private val playListAdapter = moshi.adapter<List<PlayPayload>>(
    Types.newParameterizedType(List::class.java, PlayPayload::class.java)
)

/**
 * Decode a JSON-encoded list of PlayPayload (used to round-trip stored playlist state).
 * Returns null on parse failure (logged).
 */
fun decodePlayPayloadListJson(json: String): List<PlayPayload>? = try {
    playListAdapter.fromJson(json)
} catch (e: Exception) {
    android.util.Log.w("IncomingMessage", "decodePlayPayloadListJson failed: ${e.message}")
    null
}

/**
 * Encode a list of PlayPayload to JSON for storage.
 */
fun encodePlayPayloadListJson(items: List<PlayPayload>): String = playListAdapter.toJson(items)

// ==================== Outbound command encoders (Wire-typed) ====================
// Build the canonical envelope `{"type":"command","action":<a>,"payload":<wire-json>}`.
// Inner payload comes from Moshi+Wire; the envelope uses kotlinx-serialization so we
// don't string-concatenate JSON.

private fun envelope(action: String, payloadJson: String): String =
    buildJsonObject {
        put("type", "command")
        put("action", action)
        put("payload", Json.parseToJsonElement(payloadJson))
    }.toString()

fun createPlaylistCommandJson(payload: PlaylistPayload): String =
    envelope("playlist", playlistAdapter.toJson(payload))

/**
 * Send a single video. There is no standalone `play` command anymore — a single video
 * is just a one-item playlist, so the TV always sets up a queue and `queue_add` can
 * append after it.
 */
fun createSingleVideoCommandJson(payload: PlayPayload): String =
    createPlaylistCommandJson(PlaylistPayload(items = listOf(payload)))

fun createQueueAddCommandJson(item: PlayPayload): String =
    envelope("queue_add", queueAddAdapter.toJson(QueueAddPayload(item = item)))

fun createPlaylistJumpCommandJson(index: Int): String =
    envelope("playlist_jump", playlistJumpAdapter.toJson(PlaylistJumpPayload(index = index)))

fun createBrowserCommandJson(url: String, browserMode: String? = null, desktopMode: Boolean? = null): String =
    envelope("browser", browserAdapter.toJson(
        BrowserPayload(url = url, browser_mode = browserMode, desktop_mode = desktopMode)
    ))

fun createControlCommandJson(command: String): String =
    envelope("control", controlAdapter.toJson(ControlPayload(command = command)))

fun createRemoteCommandJson(key: String): String =
    envelope("remote", remoteAdapter.toJson(RemotePayload(key = key)))

fun createMouseCommandJson(event: String, dx: Float = 0f, dy: Float = 0f): String =
    envelope("mouse", mouseAdapter.toJson(MousePayload(event = event, dx = dx, dy = dy)))

fun createBrowserControlCommandJson(action: String): String =
    envelope("browser_control", browserControlAdapter.toJson(BrowserControlPayload(action = action)))

fun createContextQueryJson(): String =
    buildJsonObject {
        put("type", "command")
        put("action", "context_query")
    }.toString()

// ==================== Standalone (non-command) outbound messages ====================

fun createPingJson(): String = """{"type":"ping"}"""
fun createPongJson(): String = """{"type":"pong"}"""

fun createAuthJson(token: String): String =
    authAdapter.toJson(AuthMessage(type = "auth", token = token))

fun createPairingRequestJson(deviceName: String, deviceUUID: String): String =
    pairingAdapter.toJson(
        PairingRequestMessage(type = "pairing_request", device_name = deviceName, device_uuid = deviceUUID)
    )

fun createPairingApprovedJson(
    token: String,
    certFingerprint: String? = null,
    players: List<String> = emptyList(),
    browsers: List<String> = emptyList(),
): String =
    buildJsonObject {
        put("type", "pairing_approved")
        put("token", token)
        if (certFingerprint != null) put("certFingerprint", certFingerprint)
        if (players.isNotEmpty()) put("players", buildJsonArray { players.forEach { add(it) } })
        if (browsers.isNotEmpty()) put("browsers", buildJsonArray { browsers.forEach { add(it) } })
    }.toString()

fun createPairingDeniedJson(): String = """{"type":"pairing_denied"}"""

fun createAuthResponseJson(
    success: Boolean,
    token: String? = null,
    certFingerprint: String? = null,
    players: List<String> = emptyList(),
    browsers: List<String> = emptyList(),
): String =
    buildJsonObject {
        put("type", "auth_response")
        put("success", success)
        if (token != null) put("token", token)
        if (certFingerprint != null) put("certFingerprint", certFingerprint)
        if (players.isNotEmpty()) put("players", buildJsonArray { players.forEach { add(it) } })
        if (browsers.isNotEmpty()) put("browsers", buildJsonArray { browsers.forEach { add(it) } })
    }.toString()

fun createContextJson(active: String): String =
    buildJsonObject {
        put("type", "context")
        put("active", active)
    }.toString()

fun createStatusJson(state: String, position: Long, duration: Long, title: String?): String =
    buildJsonObject {
        put("type", "status")
        put("state", state)
        put("position", position)
        put("duration", duration)
        if (title != null) put("title", title)
    }.toString()

fun createPlaylistStatusJson(
    items: List<Pair<Int, String>>,
    currentIndex: Int,
    totalCount: Int,
): String {
    val itemsArray = kotlinx.serialization.json.buildJsonArray {
        items.forEach { (index, title) ->
            add(buildJsonObject {
                put("index", index)
                put("title", title)
            })
        }
    }
    return buildJsonObject {
        put("type", "playlist_status")
        put("items", itemsArray)
        put("currentIndex", currentIndex)
        put("totalCount", totalCount)
    }.toString()
}


private val envelopeJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun parseIncomingMessage(text: String): IncomingMessage {
    val root: JsonObject = try {
        envelopeJson.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        return IncomingMessage.Unknown("parse_error: ${e.message}", text)
    }
    val type = root["type"]?.jsonPrimitive?.contentOrNull
        ?: return IncomingMessage.Unknown("missing_type", text)

    return try {
        when (type) {
            "ping" -> IncomingMessage.Ping
            "pairing_request" -> pairingAdapter.fromJson(text)
                ?.let { IncomingMessage.PairingRequest(it) }
                ?: IncomingMessage.Unknown("pairing_parse_error", text)
            "auth" -> authAdapter.fromJson(text)
                ?.let { IncomingMessage.Auth(it) }
                ?: IncomingMessage.Unknown("auth_parse_error", text)
            "command" -> {
                val action = root["action"]?.jsonPrimitive?.contentOrNull
                    ?: return IncomingMessage.Unknown("missing_action", text)
                val payloadJson = root["payload"]?.toString()
                parseCommandAction(action, payloadJson, text)
            }
            else -> IncomingMessage.Unknown(type, text)
        }
    } catch (e: Exception) {
        IncomingMessage.Unknown("parse_error: ${e.message}", text)
    }
}

private fun parseCommandAction(action: String, payloadJson: String?, raw: String): IncomingMessage {
    if (action == "context_query") return IncomingMessage.ContextQuery
    if (payloadJson == null) return IncomingMessage.Unknown("missing_payload_for_$action", raw)
    return when (action) {
        "playlist" -> playlistAdapter.fromJson(payloadJson)?.let { IncomingMessage.Playlist(it) }
        "queue_add" -> queueAddAdapter.fromJson(payloadJson)?.let { IncomingMessage.QueueAdd(it) }
        "playlist_jump" -> playlistJumpAdapter.fromJson(payloadJson)?.let { IncomingMessage.PlaylistJump(it) }
        "control" -> controlAdapter.fromJson(payloadJson)?.let { IncomingMessage.Control(it) }
        "remote" -> remoteAdapter.fromJson(payloadJson)?.let { IncomingMessage.Remote(it) }
        "mouse" -> mouseAdapter.fromJson(payloadJson)?.let { IncomingMessage.Mouse(it) }
        "browser" -> browserAdapter.fromJson(payloadJson)?.let { IncomingMessage.Browser(it) }
        "browser_control" -> browserControlAdapter.fromJson(payloadJson)?.let { IncomingMessage.BrowserControl(it) }
        else -> IncomingMessage.Unknown(action, raw)
    } ?: IncomingMessage.Unknown("${action}_parse_error", raw)
}
