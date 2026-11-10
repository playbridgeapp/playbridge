package com.playbridge.shared.together

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Watch Together commands and events, mirroring `cast/ffi/src/together.rs`.
 * See `docs/design-watch-together.md`.
 *
 * Invite links and [TogetherItem.headers] can hold secrets: never log them.
 */
enum class TogetherDeviceKind(val wire: String) {
    Tv("tv"), Phone("phone"), Desktop("desktop"), Cli("cli");

    companion object {
        fun from(wire: String?): TogetherDeviceKind = entries.firstOrNull { it.wire == wire } ?: Phone
    }
}

/** Who can change the video. */
enum class TogetherPolicy(val wire: String) {
    OnlyHost("only_host"), Ask("ask"), Anyone("anyone");

    companion object {
        fun from(wire: String?): TogetherPolicy = entries.firstOrNull { it.wire == wire } ?: OnlyHost
    }
}

data class TogetherDevice(val name: String, val kind: TogetherDeviceKind, val screen: Boolean)

/** Where the video comes from. Each member resolves it itself. */
sealed interface TogetherSource {
    data class Url(val url: String) : TogetherSource
    data class Page(val url: String) : TogetherSource
    data class App(val app: String, val id: String) : TogetherSource
}

data class TogetherItem(
    /** Empty lets the engine pick one. */
    val id: String = "",
    val title: String,
    val site: String? = null,
    val source: TogetherSource,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<String> = emptyList(),
    val startMs: Long = 0,
    /** Member ID of whoever shared it; set by the engine. */
    val sharedBy: String = "",
) {
    override fun toString(): String = "TogetherItem(id=$id, site=$site)"
}

sealed interface TogetherPlayerCommand {
    /** Load paused. [headers] includes sign-in headers when this device may have them. */
    data class Load(val item: TogetherItem, val headers: Map<String, String>, val startMs: Long) : TogetherPlayerCommand {
        override fun toString(): String = "Load(${item.id}, startMs=$startMs)"
    }
    data object Play : TogetherPlayerCommand
    data object Pause : TogetherPlayerCommand
    data class Seek(val positionMs: Long) : TogetherPlayerCommand
    data class Rate(val rate: Float) : TogetherPlayerCommand
}

enum class TogetherStop { Left, Unauthorized, RoomFull, Expired, Replaced, Error }

sealed interface TogetherEvent {
    /** Entered a room. [invite] is the shareable link and holds the room key. */
    data class Room(
        /** The room ID, which is not secret; [Stopped.roomId] names the same room. */
        val roomId: String,
        val invite: String,
        val relay: String,
        val member: String,
        val boxKey: String,
        val signKey: String,
        val host: Boolean,
    ) : TogetherEvent {
        override fun toString(): String = "Room(room=$roomId, relay=$relay, member=$member, host=$host)"
    }
    data class Connected(val members: List<String>) : TogetherEvent
    data object Disconnected : TogetherEvent
    data class MemberJoined(val member: String) : TogetherEvent
    data class MemberLeft(val member: String) : TogetherEvent
    data class Member(val member: String, val name: String, val kind: TogetherDeviceKind, val host: Boolean) : TogetherEvent
    data class Video(val item: TogetherItem) : TogetherEvent
    data class Timeline(val item: String, val playing: Boolean, val positionMs: Long, val rev: Long) : TogetherEvent
    data class Waiting(val members: List<String>) : TogetherEvent
    data class ContinueWithoutAvailable(val members: List<String>) : TogetherEvent
    data class PlayRequested(val request: String, val from: String, val title: String, val site: String?) : TogetherEvent
    data class RequestAnswered(val request: String, val allowed: Boolean) : TogetherEvent
    data class RequestExpired(val request: String) : TogetherEvent
    data class Locked(val locked: Boolean) : TogetherEvent
    data class Policy(val policy: TogetherPolicy) : TogetherEvent
    data class CredentialsReceived(val item: String) : TogetherEvent
    data class Warning(val message: String) : TogetherEvent
    /** A message the engine dropped; for logs only. */
    data class Rejected(val reason: String) : TogetherEvent
    data class Player(val command: TogetherPlayerCommand) : TogetherEvent
    /**
     * A receiver's pairing code. [qr] is the link the QR code carries; [qrModules] is
     * that QR code as [qrSize] rows of `1` (dark) and `0`, ready to draw.
     */
    data class PairingCode(
        val code: String,
        val qr: String,
        val qrSize: Int,
        val qrModules: String,
        val expiresAtMs: Long,
    ) : TogetherEvent
    /** An invite arrived for this receiver; ask before calling `pairAccept`. */
    data class PairingInvite(val from: String, val relay: String) : TogetherEvent
    data class PairSent(val id: String?) : TogetherEvent
    data class Error(val context: String, val message: String, val id: String?) : TogetherEvent
    data class Stopped(val roomId: String, val reason: TogetherStop) : TogetherEvent
}

object TogetherJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun command(name: String, build: JsonObjectBuilder.() -> Unit = {}): String =
        buildJsonObject {
            put("command", name)
            build()
        }.toString()

    fun device(builder: JsonObjectBuilder, device: TogetherDevice) {
        builder.put("name", device.name)
        builder.put("kind", device.kind.wire)
        builder.put("screen", device.screen)
    }

    fun item(item: TogetherItem): JsonObject = buildJsonObject {
        if (item.id.isNotEmpty()) put("id", item.id)
        put("title", item.title)
        item.site?.let { put("site", it) }
        putJsonObject("source") {
            when (val source = item.source) {
                is TogetherSource.Url -> { put("type", "url"); put("url", source.url) }
                is TogetherSource.Page -> { put("type", "page"); put("url", source.url) }
                is TogetherSource.App -> { put("type", "app"); put("app", source.app); put("id", source.id) }
            }
        }
        putJsonObject("headers") { item.headers.forEach { (name, value) -> put(name, value) } }
        putJsonArray("subtitles") { item.subtitles.forEach { add(JsonPrimitive(it)) } }
        put("start_ms", item.startMs)
    }

    /** Parses one event, or null for an unknown or malformed one. */
    fun event(text: String): TogetherEvent? = runCatching {
        parse(json.parseToJsonElement(text).jsonObject)
    }.getOrNull()

    private fun parse(o: JsonObject): TogetherEvent? = when (o.string("event")) {
        "room" -> TogetherEvent.Room(
            roomId = o.string("room").orEmpty(),
            invite = o.string("invite") ?: return null,
            relay = o.string("relay").orEmpty(),
            member = o.string("member") ?: return null,
            boxKey = o.string("box_pk") ?: return null,
            signKey = o.string("sign_pk") ?: return null,
            host = o.bool("host"),
        )
        "connected" -> TogetherEvent.Connected(o.strings("members"))
        "disconnected" -> TogetherEvent.Disconnected
        "member_joined" -> TogetherEvent.MemberJoined(o.string("member") ?: return null)
        "member_left" -> TogetherEvent.MemberLeft(o.string("member") ?: return null)
        "member" -> TogetherEvent.Member(
            member = o.string("member") ?: return null,
            name = o.string("name").orEmpty(),
            kind = TogetherDeviceKind.from(o.string("kind")),
            host = o.bool("host"),
        )
        "video" -> TogetherEvent.Video(parseItem(o.obj("item") ?: return null) ?: return null)
        "timeline" -> TogetherEvent.Timeline(
            item = o.string("item") ?: return null,
            playing = o.bool("playing"),
            positionMs = o.long("position_ms"),
            rev = o.long("rev"),
        )
        "waiting" -> TogetherEvent.Waiting(o.strings("members"))
        "continue_without_available" -> TogetherEvent.ContinueWithoutAvailable(o.strings("members"))
        "play_requested" -> TogetherEvent.PlayRequested(
            request = o.string("request") ?: return null,
            from = o.string("from").orEmpty(),
            title = o.string("title").orEmpty(),
            site = o.string("site"),
        )
        "request_answered" -> TogetherEvent.RequestAnswered(o.string("request") ?: return null, o.bool("allowed"))
        "request_expired" -> TogetherEvent.RequestExpired(o.string("request") ?: return null)
        "locked" -> TogetherEvent.Locked(o.bool("locked"))
        "policy" -> TogetherEvent.Policy(TogetherPolicy.from(o.string("policy")))
        "credentials" -> TogetherEvent.CredentialsReceived(o.string("item") ?: return null)
        "warning" -> TogetherEvent.Warning(o.string("message").orEmpty())
        "rejected" -> TogetherEvent.Rejected(o.string("reason").orEmpty())
        "player" -> parsePlayer(o.obj("command") ?: return null)?.let { TogetherEvent.Player(it) }
        "pairing_code" -> TogetherEvent.PairingCode(
            code = o.string("code") ?: return null,
            qr = o.string("qr") ?: return null,
            qrSize = o.long("qr_size").toInt(),
            qrModules = o.string("qr_modules").orEmpty(),
            expiresAtMs = o.long("expires_at"),
        )
        "pairing_invite" -> TogetherEvent.PairingInvite(o.string("from").orEmpty(), o.string("relay").orEmpty())
        "pair_sent" -> TogetherEvent.PairSent(o.string("id"))
        "error" -> TogetherEvent.Error(o.string("context").orEmpty(), o.string("message").orEmpty(), o.string("id"))
        "stopped" -> TogetherEvent.Stopped(
            o.string("room").orEmpty(),
            when (o.string("reason")) {
                "left" -> TogetherStop.Left
                "unauthorized" -> TogetherStop.Unauthorized
                "room_full" -> TogetherStop.RoomFull
                "expired" -> TogetherStop.Expired
                "replaced" -> TogetherStop.Replaced
                else -> TogetherStop.Error
            },
        )
        else -> null
    }

    private fun parsePlayer(o: JsonObject): TogetherPlayerCommand? = when (o.string("action")) {
        "load" -> TogetherPlayerCommand.Load(
            item = parseItem(o.obj("item") ?: return null) ?: return null,
            headers = o.stringMap("headers"),
            startMs = o.long("start_ms"),
        )
        "play" -> TogetherPlayerCommand.Play
        "pause" -> TogetherPlayerCommand.Pause
        "seek" -> TogetherPlayerCommand.Seek(o.long("position_ms"))
        "rate" -> TogetherPlayerCommand.Rate((o["rate"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 1f)
        else -> null
    }

    private fun parseItem(o: JsonObject): TogetherItem? {
        val source = o.obj("source") ?: return null
        return TogetherItem(
            id = o.string("id") ?: return null,
            title = o.string("title").orEmpty(),
            site = o.string("site"),
            source = when (source.string("type")) {
                "url" -> TogetherSource.Url(source.string("url") ?: return null)
                "page" -> TogetherSource.Page(source.string("url") ?: return null)
                "app" -> TogetherSource.App(source.string("app") ?: return null, source.string("id") ?: return null)
                else -> return null
            },
            headers = o.stringMap("headers"),
            subtitles = o.strings("subtitles"),
            startMs = o.long("start_ms"),
            sharedBy = o.string("shared_by").orEmpty(),
        )
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonObject.long(name: String): Long =
        (this[name] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.strings(name: String): List<String> =
        (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .orEmpty()

    private fun JsonObject.stringMap(name: String): Map<String, String> =
        obj(name)?.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content }
        }?.toMap().orEmpty()
}
