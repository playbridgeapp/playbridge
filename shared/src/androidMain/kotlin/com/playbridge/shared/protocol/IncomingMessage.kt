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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import playbridge.AuthMessage
import playbridge.BrowserControlPayload
import playbridge.BrowserPayload
import playbridge.ControlPayload
import playbridge.MousePayload
import playbridge.PairingRequestMessage
import playbridge.PairingCommitMessage
import playbridge.PairingChallengeMessage
import playbridge.PairingRevealMessage
import playbridge.PairingConfirmationMessage
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
    data class PairingCommit(val msg: playbridge.PairingCommitMessage) : IncomingMessage()
    data class PairingChallenge(val msg: playbridge.PairingChallengeMessage) : IncomingMessage()
    data class PairingReveal(val msg: playbridge.PairingRevealMessage) : IncomingMessage()
    data class PairingConfirmation(val msg: playbridge.PairingConfirmationMessage) : IncomingMessage()
    data class Auth(val msg: playbridge.AuthMessage) : IncomingMessage()
    data class Playlist(val payload: playbridge.PlaylistPayload) : IncomingMessage()
    data class QueueAdd(val payload: playbridge.QueueAddPayload) : IncomingMessage()
    data class PlaylistJump(val payload: playbridge.PlaylistJumpPayload) : IncomingMessage()
    data class Control(val payload: playbridge.ControlPayload) : IncomingMessage()
    data class Remote(val payload: playbridge.RemotePayload) : IncomingMessage()
    data class Mouse(val payload: playbridge.MousePayload) : IncomingMessage()
    data class Browser(val payload: playbridge.BrowserPayload) : IncomingMessage()
    data class BrowserControl(val payload: playbridge.BrowserControlPayload) : IncomingMessage()
    data class ScreenMirrorStart(val sessionId: String) : IncomingMessage()
    data class ScreenMirrorOffer(val sessionId: String, val sdp: String) : IncomingMessage()
    data class ScreenMirrorAnswer(val sessionId: String, val sdp: String) : IncomingMessage()
    data class ScreenMirrorCandidate(
        val sessionId: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int,
        val candidate: String,
    ) : IncomingMessage()
    data class ScreenMirrorStop(val sessionId: String, val reason: String?) : IncomingMessage()
    data class ScreenMirrorReady(val sessionId: String) : IncomingMessage()
    data class ScreenMirrorEvent(val sessionId: String, val state: String, val reason: String?) : IncomingMessage()
    data object ContextQuery : IncomingMessage()
    /**
     * User-supplied browser script the phone installs onto the TV (e.g. an ad-skipper we
     * deliberately don't ship). [content] blank means uninstall. Plain JSON, no proto.
     */
    data class UserScript(val name: String, val content: String) : IncomingMessage()
    /** Phone asks the TV which user scripts are installed; TV replies with `user_scripts`. */
    data object UserScriptQuery : IncomingMessage()
    /**
     * Select (and optionally save) a TV browser User-Agent override. Mirrors [UserScript]'s
     * shape: [name] blank means "switch back to default" (clears the active override without
     * touching saved entries); non-blank [name] with blank [value] removes that saved entry
     * (and resets to default if it was active); otherwise the TV applies [value] immediately
     * and, when [save] is true, remembers it under [name] for later reselection. Built-in
     * presets picked on the phone are sent with `save = false` so they apply without
     * cluttering the TV's saved list.
     */
    data class UserAgent(val name: String, val value: String, val save: Boolean) : IncomingMessage()
    /** Phone asks the TV which user agent is active + which custom ones are saved. */
    data object UserAgentQuery : IncomingMessage()
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
private val pairingCommitAdapter = moshi.adapter(PairingCommitMessage::class.java)
private val pairingChallengeAdapter = moshi.adapter(PairingChallengeMessage::class.java)
private val pairingRevealAdapter = moshi.adapter(PairingRevealMessage::class.java)
private val pairingConfirmationAdapter = moshi.adapter(PairingConfirmationMessage::class.java)
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

/**
 * Encode a full [PlaylistPayload] (items + start_index + visual_metadata) to JSON.
 * This is the canonical "what the phone sent" blob the TV stores in history so that a
 * replay can be reconstructed through the exact same launch path as a live cast.
 */
fun encodePlaylistPayloadJson(payload: PlaylistPayload): String = playlistAdapter.toJson(payload)

/**
 * Decode a JSON-encoded [PlaylistPayload]. Returns null on parse failure (logged).
 */
fun decodePlaylistPayloadJson(json: String): PlaylistPayload? = try {
    playlistAdapter.fromJson(json)
} catch (e: Exception) {
    android.util.Log.w("IncomingMessage", "decodePlaylistPayloadJson failed: ${e.message}")
    null
}

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
    envelope(
        "playlist",
        playlistAdapter.toJson(payload.copy(items = payload.items.map(PlayPayload::withResolvedMediaKind))),
    )

/**
 * Send a single video. There is no standalone `play` command anymore — a single video
 * is just a one-item playlist, so the TV always sets up a queue and `queue_add` can
 * append after it.
 */
fun createSingleVideoCommandJson(payload: PlayPayload): String =
    createPlaylistCommandJson(PlaylistPayload(items = listOf(payload)))

fun createQueueAddCommandJson(item: PlayPayload): String =
    envelope("queue_add", queueAddAdapter.toJson(QueueAddPayload(item = item.withResolvedMediaKind())))

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

private fun screenMirrorPayload(sessionId: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
    buildJsonObject {
        put("sessionId", sessionId)
        block()
    }.toString()

fun createScreenMirrorStartJson(sessionId: String): String =
    envelope("screen_mirror_start", screenMirrorPayload(sessionId) { put("protocolVersion", 1) })

fun createScreenMirrorOfferJson(sessionId: String, sdp: String): String =
    envelope("screen_mirror_offer", screenMirrorPayload(sessionId) { put("sdp", sdp) })

fun createScreenMirrorCandidateCommandJson(
    sessionId: String,
    sdpMid: String?,
    sdpMLineIndex: Int,
    candidate: String,
): String = envelope("screen_mirror_candidate", screenMirrorPayload(sessionId) {
    if (sdpMid != null) put("sdpMid", sdpMid)
    put("sdpMLineIndex", sdpMLineIndex)
    put("candidate", candidate)
})

fun createScreenMirrorStopJson(sessionId: String, reason: String? = null): String =
    envelope("screen_mirror_stop", screenMirrorPayload(sessionId) { if (reason != null) put("reason", reason) })

fun createScreenMirrorReadyJson(sessionId: String): String =
    buildJsonObject { put("type", "screen_mirror_ready"); put("sessionId", sessionId) }.toString()

fun createScreenMirrorAnswerJson(sessionId: String, sdp: String): String =
    buildJsonObject { put("type", "screen_mirror_answer"); put("sessionId", sessionId); put("sdp", sdp) }.toString()

fun createScreenMirrorCandidateJson(sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String): String =
    buildJsonObject {
        put("type", "screen_mirror_candidate")
        put("sessionId", sessionId)
        if (sdpMid != null) put("sdpMid", sdpMid)
        put("sdpMLineIndex", sdpMLineIndex)
        put("candidate", candidate)
    }.toString()

fun createScreenMirrorEventJson(sessionId: String, state: String, reason: String? = null): String =
    buildJsonObject {
        put("type", "screen_mirror_event")
        put("sessionId", sessionId)
        put("state", state)
        if (reason != null) put("reason", reason)
    }.toString()

/**
 * Install (or, with blank [content], uninstall) a user-supplied browser script on the TV.
 * Standalone message (not a Wire command) so it needs no proto change.
 */
fun createUserScriptJson(name: String, content: String): String =
    buildJsonObject {
        put("type", "user_script")
        put("name", name)
        put("content", content)
    }.toString()

/** Phone → TV: list the installed user scripts. */
fun createUserScriptQueryJson(): String = """{"type":"user_script_query"}"""

/** TV → phone: the names of the currently-installed user scripts. */
fun createUserScriptsJson(names: List<String>): String =
    buildJsonObject {
        put("type", "user_scripts")
        put("names", buildJsonArray { names.forEach { add(it) } })
    }.toString()

/**
 * Apply (and, with [save], remember) a TV browser User-Agent. See [IncomingMessage.UserAgent]
 * for the blank-name / blank-value semantics. Standalone message (not a Wire command).
 */
fun createUserAgentJson(name: String, value: String, save: Boolean = true): String =
    buildJsonObject {
        put("type", "user_agent")
        put("name", name)
        put("value", value)
        put("save", save)
    }.toString()

/** Phone → TV: ask which user agent is active + which custom ones are saved. */
fun createUserAgentQueryJson(): String = """{"type":"user_agent_query"}"""

/** TV → phone: the active selection's name (blank = default) and the saved {name, value} entries. */
fun createUserAgentsJson(active: String, entries: List<Pair<String, String>>): String =
    buildJsonObject {
        put("type", "user_agents")
        put("active", active)
        put("entries", buildJsonArray {
            entries.forEach { (name, value) ->
                add(buildJsonObject {
                    put("name", name)
                    put("value", value)
                })
            }
        })
    }.toString()

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

fun createPairingCommitJson(commit: String, deviceName: String, deviceUUID: String): String =
    pairingCommitAdapter.toJson(
        PairingCommitMessage(type = "pairing_commit", commit = commit, device_name = deviceName, device_uuid = deviceUUID)
    )

fun createPairingChallengeJson(tvEphPub: String, nonceT: String): String =
    pairingChallengeAdapter.toJson(
        PairingChallengeMessage(type = "pairing_challenge", tv_eph_pub = tvEphPub, nonce_t = nonceT)
    )

fun createPairingRevealJson(senderEphPub: String, nonceS: String): String =
    pairingRevealAdapter.toJson(
        PairingRevealMessage(type = "pairing_reveal", sender_eph_pub = senderEphPub, nonce_s = nonceS)
    )

fun createPairingConfirmationJson(mac: String): String =
    pairingConfirmationAdapter.toJson(
        PairingConfirmationMessage(type = "pairing_confirmation", mac = mac)
    )

fun createProtectedPairingApprovedJson(nonce: String, ciphertext: String): String =
    buildJsonObject {
        put("type", "pairing_approved")
        put("nonce", nonce)
        put("ciphertext", ciphertext)
    }.toString()

fun createPairingDeniedJson(): String = """{"type":"pairing_denied"}"""

fun createAuthResponseJson(
    success: Boolean,
    certFingerprint: String? = null,
    players: List<String> = emptyList(),
    browsers: List<String> = emptyList(),
    mediaKinds: List<String> = emptyList(),
    screenMirrorWebRtc: Boolean = false,
): String =
    buildJsonObject {
        put("type", "auth_response")
        put("success", success)
        if (certFingerprint != null) put("certFingerprint", certFingerprint)
        if (players.isNotEmpty()) put("players", buildJsonArray { players.forEach { add(it) } })
        if (browsers.isNotEmpty()) put("browsers", buildJsonArray { browsers.forEach { add(it) } })
        if (mediaKinds.isNotEmpty()) put("mediaKinds", buildJsonArray { mediaKinds.forEach { add(it) } })
        if (screenMirrorWebRtc) put("screenMirrorWebRtc", true)
    }.toString()

fun createContextJson(active: String): String =
    buildJsonObject {
        put("type", "context")
        put("active", active)
    }.toString()

fun createStatusJson(
    state: String,
    position: Long,
    duration: Long,
    title: String?,
    mediaKind: String? = null,
): String = buildJsonObject {
        put("type", "status")
        put("state", state)
        put("position", position)
        put("duration", duration)
        if (title != null) put("title", title)
        if (mediaKind != null) put("mediaKind", mediaKind)
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
            "pairing_commit" -> pairingCommitAdapter.fromJson(text)
                ?.let { IncomingMessage.PairingCommit(it) }
                ?: IncomingMessage.Unknown("pairing_commit_parse_error", text)
            "pairing_challenge" -> pairingChallengeAdapter.fromJson(text)
                ?.let { IncomingMessage.PairingChallenge(it) }
                ?: IncomingMessage.Unknown("pairing_challenge_parse_error", text)
            "pairing_reveal" -> pairingRevealAdapter.fromJson(text)
                ?.let { IncomingMessage.PairingReveal(it) }
                ?: IncomingMessage.Unknown("pairing_reveal_parse_error", text)
            "pairing_confirmation" -> pairingConfirmationAdapter.fromJson(text)
                ?.let { IncomingMessage.PairingConfirmation(it) }
                ?: IncomingMessage.Unknown("pairing_confirmation_parse_error", text)
            "auth" -> authAdapter.fromJson(text)
                ?.let { IncomingMessage.Auth(it) }
                ?: IncomingMessage.Unknown("auth_parse_error", text)
            "command" -> {
                val action = root["action"]?.jsonPrimitive?.contentOrNull
                    ?: return IncomingMessage.Unknown("missing_action", text)
                val payloadJson = root["payload"]?.toString()
                parseCommandAction(action, payloadJson, text)
            }
            "user_script" -> IncomingMessage.UserScript(
                name = root["name"]?.jsonPrimitive?.contentOrNull ?: "user.js",
                content = root["content"]?.jsonPrimitive?.contentOrNull ?: ""
            )
            "user_script_query" -> IncomingMessage.UserScriptQuery
            "user_agent" -> IncomingMessage.UserAgent(
                name = root["name"]?.jsonPrimitive?.contentOrNull ?: "",
                value = root["value"]?.jsonPrimitive?.contentOrNull ?: "",
                save = root["save"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
            )
            "user_agent_query" -> IncomingMessage.UserAgentQuery
            "screen_mirror_ready" -> screenMirrorSession(root)?.let(IncomingMessage::ScreenMirrorReady)
                ?: IncomingMessage.Unknown("screen_mirror_ready_parse_error", text)
            "screen_mirror_answer" -> screenMirrorSdp(root)?.let { (sessionId, sdp) ->
                IncomingMessage.ScreenMirrorAnswer(sessionId, sdp)
            } ?: IncomingMessage.Unknown("screen_mirror_answer_parse_error", text)
            "screen_mirror_candidate" -> screenMirrorCandidate(root)?.let { candidate ->
                IncomingMessage.ScreenMirrorCandidate(
                    candidate.sessionId, candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate,
                )
            } ?: IncomingMessage.Unknown("screen_mirror_candidate_parse_error", text)
            "screen_mirror_event" -> screenMirrorSession(root)?.let { sessionId ->
                val state = root["state"]?.jsonPrimitive?.contentOrNull
                if (state in setOf("connected", "stopped", "failed")) {
                    IncomingMessage.ScreenMirrorEvent(sessionId, state!!, root["reason"]?.jsonPrimitive?.contentOrNull)
                } else null
            } ?: IncomingMessage.Unknown("screen_mirror_event_parse_error", text)
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
        "screen_mirror_start" -> screenMirrorStartPayload(payloadJson)?.let(IncomingMessage::ScreenMirrorStart)
        "screen_mirror_offer" -> screenMirrorSdpPayload(payloadJson)?.let { (sessionId, sdp) ->
            IncomingMessage.ScreenMirrorOffer(sessionId, sdp)
        }
        "screen_mirror_candidate" -> screenMirrorCandidatePayload(payloadJson)?.let { candidate ->
            IncomingMessage.ScreenMirrorCandidate(
                candidate.sessionId, candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate,
            )
        }
        "screen_mirror_stop" -> screenMirrorSessionPayload(payloadJson)?.let { sessionId ->
            val reason = runCatching { envelopeJson.parseToJsonElement(payloadJson).jsonObject["reason"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            IncomingMessage.ScreenMirrorStop(sessionId, reason)
        }
        else -> IncomingMessage.Unknown(action, raw)
    } ?: IncomingMessage.Unknown("${action}_parse_error", raw)
}

private data class MirrorCandidate(
    val sessionId: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String,
)

private fun screenMirrorSession(root: JsonObject): String? =
    root["sessionId"]?.jsonPrimitive?.contentOrNull?.takeIf(::isMirrorSessionId)

private fun screenMirrorSessionPayload(payloadJson: String): String? =
    runCatching { screenMirrorSession(envelopeJson.parseToJsonElement(payloadJson).jsonObject) }.getOrNull()

private fun screenMirrorStartPayload(payloadJson: String): String? = runCatching {
    val root = envelopeJson.parseToJsonElement(payloadJson).jsonObject
    if (root["protocolVersion"]?.jsonPrimitive?.intOrNull != 1) return@runCatching null
    screenMirrorSession(root)
}.getOrNull()

private fun screenMirrorSdp(root: JsonObject): Pair<String, String>? {
    val sessionId = screenMirrorSession(root) ?: return null
    val sdp = root["sdp"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 131_072 } ?: return null
    return sessionId to sdp
}

private fun screenMirrorSdpPayload(payloadJson: String): Pair<String, String>? =
    runCatching { screenMirrorSdp(envelopeJson.parseToJsonElement(payloadJson).jsonObject) }.getOrNull()

private fun screenMirrorCandidate(root: JsonObject): MirrorCandidate? {
    val sessionId = screenMirrorSession(root) ?: return null
    val candidate = root["candidate"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 8_192 } ?: return null
    val mLine = root["sdpMLineIndex"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 } ?: return null
    return MirrorCandidate(sessionId, root["sdpMid"]?.jsonPrimitive?.contentOrNull, mLine, candidate)
}

private fun screenMirrorCandidatePayload(payloadJson: String): MirrorCandidate? =
    runCatching { screenMirrorCandidate(envelopeJson.parseToJsonElement(payloadJson).jsonObject) }.getOrNull()

private fun isMirrorSessionId(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess
