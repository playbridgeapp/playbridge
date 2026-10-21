package com.playbridge.sender.logging

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Release summaries are frozen. Debug structures contain only explicitly permitted values. */
internal object WireFrameLog {
    private val typeField = Regex(""""type"\s*:\s*"([^"\\]+)"""")
    private val knownTypes = setOf(
        "auth", "auth_response", "pairing_commit", "pairing_challenge", "pairing_reveal", "pairing_confirmation",
        "pairing_approved", "pairing_denied", "command", "command_result", "status", "context", "context_query",
        "tracks", "playlist_status", "player_settings", "volume_state", "ping", "pong", "screen_mirror_status",
        "screen_mirror_answer", "screen_mirror_candidate", "user_scripts", "user_scripts_query", "user_script",
        "user_agent", "user_agent_query", "tv_logs", "tv_logs_query", "timeline",
    )

    fun summary(frame: String): String {
        val type = typeField.find(frame)?.groupValues?.getOrNull(1)?.takeIf { it in knownTypes } ?: "unknown"
        val bytes = frame.toByteArray(Charsets.UTF_8).size
        return "type=$type bytes=$bytes"
    }

    internal const val MAX_DEBUG_LENGTH = 2048
    private const val MAX_INPUT_LENGTH = 65536
    private val hidden = JsonPrimitive("<redacted>")
    private val engines = setOf("exo", "mpv", "avplayer", "internal_mpv", "webview", "gecko", "external")
    private val mediaKinds = setOf("video", "audio", "image")
    private val scales = setOf("Fit", "Fill", "Stretch", "Zoom", "fit", "fill", "stretch", "zoom")
    private val navKeys = setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center",
        "back", "home", "key_enter", "key_delete", "volume_up", "volume_down", "mute")
    private val errors = setOf("stale_playback", "item_not_found", "queue_full", "invalid_command",
        "unsupported", "no_active_playback", "subtitle_unavailable")

    // A rule never copies an arbitrary string. IDs are opaque correlation tags, never raw values.
    private val redactValue: (JsonElement) -> JsonElement = { hidden }
    private val number: (JsonElement) -> JsonElement = { value ->
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive.isString) hidden
        else primitive.longOrNull?.let { JsonPrimitive(it) }
            ?: primitive.doubleOrNull?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) } ?: hidden
    }
    private val flag: (JsonElement) -> JsonElement = { value ->
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive.isString) hidden
        else primitive.booleanOrNull?.let { JsonPrimitive(it) } ?: hidden
    }
    private val id: (JsonElement) -> JsonElement = { value ->
        val primitive = value as? JsonPrimitive
        when {
            value == JsonNull -> JsonNull
            primitive == null || primitive.content.length > 128 -> hidden
            else -> {
                val digest = MessageDigest.getInstance("SHA-256").digest(primitive.content.toByteArray(Charsets.UTF_8))
                JsonPrimitive("<id:${digest.take(6).joinToString("") { "%02x".format(it) }}>")
            }
        }
    }

    private fun enum(values: Set<String>): (JsonElement) -> JsonElement = { value ->
        val primitive = value as? JsonPrimitive
        if (value == JsonNull) JsonNull
        else if (primitive?.isString == true && primitive.content in values) JsonPrimitive(primitive.content)
        else hidden
    }

    private fun fields(vararg groups: Pair<String, (JsonElement) -> JsonElement>): Map<String, (JsonElement) -> JsonElement> =
        buildMap { groups.forEach { (names, rule) -> names.split(' ').forEach { put(it, rule) } } }

    private fun structure(value: JsonElement, rules: Map<String, (JsonElement) -> JsonElement>): JsonObject {
        val obj = value as? JsonObject ?: error("Expected object")
        // Unknown field names themselves can contain secrets; never echo them.
        require(obj.keys.all { it in rules })
        return JsonObject(obj.mapValues { (key, item) -> rules.getValue(key)(item) })
    }

    private fun array(rule: (JsonElement) -> JsonElement): (JsonElement) -> JsonElement = { value ->
        val items = value as? JsonArray ?: error("Expected array")
        JsonObject(mapOf("count" to JsonPrimitive(items.size), "items" to JsonArray(items.map(rule).take(8))))
    }

    private val itemRules = fields(
        "index season episode displayDurationMs startPositionMs maxBitrateCapMbps" to number,
        "itemId" to id, "mediaKind" to enum(mediaKinds), "playerMode" to enum(engines), "skipHistory" to flag,
        "title url headers subtitles subtitleResources progressIdentity progressWebhook visualMetadata contentType detectedBy preferredAudioLanguage preferredSubtitleLanguage defaultVideoQuality allowedPrivateOrigins imdbId tmdbId bingeGroup" to redactValue,
    )
    private val trackRules = fields("id" to id, "selected" to flag, "name" to redactValue,
        "type" to enum(setOf("audio", "subtitle", "video")))

    private val commandName: (JsonElement) -> JsonElement = { value ->
        val raw = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        val bare = setOf("toggle", "play", "pause", "stop", "seek_back", "seek_forward", "loop_on", "loop_off",
            "audio_boost", "add_subtitle", "refresh", "toggle_ublock", "forward", "video_target_cycle",
            "video_unmute", "maximize_video", "restore_video", "toggle_play")
        when {
            raw == null -> hidden
            raw in bare -> JsonPrimitive(raw)
            ':' !in raw -> hidden
            else -> {
                val prefix = raw.substringBefore(':')
                val suffix = raw.substringAfter(':')
                val safe = when (prefix) {
                    "seek_to", "speed", "sub_offset" -> runCatching { number(Json.parseToJsonElement(suffix)) }.getOrDefault(hidden)
                    "video_quality" -> if (suffix == "auto") JsonPrimitive("auto")
                        else runCatching { number(Json.parseToJsonElement(suffix)) }.getOrDefault(hidden)
                    "scaling" -> enum(scales)(JsonPrimitive(suffix))
                    "switch_player" -> enum(engines)(JsonPrimitive(suffix))
                    "audio_track", "sub_track" -> id(JsonPrimitive(suffix))
                    else -> hidden
                }
                if (prefix in setOf("seek_to", "speed", "sub_offset", "video_quality", "scaling", "switch_player",
                        "audio_track", "sub_track", "add_subtitle"))
                    JsonObject(mapOf("name" to JsonPrimitive(prefix), "value" to safe)) else hidden
            }
        }
    }

    private fun commandPayload(action: String): (JsonElement) -> JsonElement = { value ->
        val rules = when (action) {
            "remote" -> fields("key" to enum(navKeys), "text" to redactValue)
            "control" -> fields("command" to commandName, "subtitleResource" to redactValue)
            "browser_control" -> fields("action" to commandName)
            "volume" -> fields("scope" to enum(setOf("system", "app")), "level" to number)
            "mouse" -> fields("event" to enum(setOf("move", "click", "scroll", "down", "up", "zoom", "rotate", "transform_anchor", "reset")), "dx dy" to number)
            "browser" -> fields("url" to redactValue, "browserMode" to enum(engines), "desktopMode" to flag)
            "play" -> itemRules
            "playlist" -> fields("items" to array { structure(it, itemRules) }, "startIndex" to number,
                "skipPreplay" to flag, "progressWebhook visualMetadata" to redactValue)
            "queue_add" -> fields("item" to { structure(it, itemRules) }, "items" to array { structure(it, itemRules) }, "ifPlaybackId" to id)
            "playlist_jump", "queue_remove", "queue_move", "queue_clear" -> fields("index" to number,
                "itemId beforeItemId ifPlaybackId" to id, "itemIds" to array(id))
            else -> emptyMap()
        }
        structure(value, rules)
    }

    private fun debugStructure(obj: JsonObject, type: String): JsonObject? {
        val rules = when (type) {
            "status" -> fields("state" to enum(setOf("idle", "buffering", "playing", "paused", "stopped", "ended", "error")),
                "position duration buffered" to number, "title" to redactValue, "mediaKind" to enum(mediaKinds),
                "playbackId currentItemId" to id, "fullscreen muted" to flag)
            "context" -> fields("active surface" to enum(setOf("idle", "player", "browser", "screen_mirror")), "engine" to enum(engines))
            "player_settings" -> fields("speed subtitleOffsetMs qualityMaxHeight currentVideoHeight" to number,
                "scaling" to enum(scales), "engine" to enum(engines),
                "audioBoost isLive isSeekable speedAvailable scalingAvailable audioBoostAvailable qualityAvailable subtitleOffsetAvailable engineAvailable loopAvailable loop" to flag)
            "volume_state" -> fields("scope" to enum(setOf("system", "app")), "level" to number, "muted" to flag)
            "playlist_status" -> fields("items" to array { structure(it, itemRules) },
                "currentIndex totalCount queueRevision" to number, "playbackId currentItemId" to id)
            "tracks" -> fields("audio subtitle video" to array { structure(it, trackRules) })
            "timeline" -> fields("playbackId currentItemId" to id,
                "chapters" to array { structure(it, fields("startMs" to number, "title" to redactValue)) },
                "segments" to array { structure(it, fields("type" to enum(setOf("intro", "recap", "outro", "preview")),
                    "startMs endMs" to number)) })
            "command_result" -> fields("requestId playbackId" to id, "ok success" to flag,
                "queueRevision" to number, "error" to enum(errors), "message" to redactValue)
            "auth_response" -> fields("success screenMirrorWebRtc" to flag,
                "players browsers" to array(enum(engines)), "mediaKinds" to array(enum(mediaKinds)),
                "features token certFingerprint pin spki credentials credentialBundle" to redactValue,
                "capabilities" to { value ->
                    val descriptor = value as? JsonObject
                    val version = (descriptor?.get("version") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    val surfaces = descriptor?.get("surfaces") as? JsonObject
                    if (version == 1 && surfaces != null) JsonPrimitive("<v1, ${surfaces.size} surfaces>") else hidden
                })
            "command" -> {
                val action = (obj["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val actions = setOf("remote", "control", "browser_control", "volume", "mouse", "browser", "play",
                    "playlist", "queue_add", "playlist_jump", "queue_remove", "queue_move", "queue_clear", "queue_query", "context_query")
                if (action !in actions) return null
                fields("action" to enum(actions), "requestId" to id, "payload" to commandPayload(action!!))
            }
            // Pairing, script/agent, log, SDP/ICE and other envelopes remain summary-only.
            else -> return null
        }
        return structure(obj, rules + ("type" to enum(setOf(type))))
    }

    /** Selects the same release summary as before, or a bounded, allowlisted debug structure. */
    fun format(frame: String, debug: Boolean): String = if (debug) redact(frame) else summary(frame)

    fun redact(frame: String): String {
        val fallback = summary(frame)
        if (frame.length > MAX_INPUT_LENGTH || !boundedNesting(frame)) return fallback
        return runCatching {
            val obj = Json.parseToJsonElement(frame) as? JsonObject ?: return@runCatching fallback
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (type !in knownTypes) return@runCatching fallback
            val sanitized = debugStructure(obj, type!!) ?: return@runCatching fallback
            val result = "$fallback frame=$sanitized"
            if (result.length <= MAX_DEBUG_LENGTH) result else "$fallback frame=<redacted: output limit>"
        }.getOrDefault(fallback)
    }

    // Reject excessive depth before the parser can recurse; braces in strings do not count.
    private fun boundedNesting(frame: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in frame) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 16) return false
                '}', ']' -> depth--
            }
        }
        return true
    }
}
