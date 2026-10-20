package com.playbridge.player.server

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The players and browser engines this receiver can actually drive. Reported to the phone
 * at auth so the phone's pickers only ever offer what this TV supports (see the phone-side
 * `TvCapabilityOptions`). IDs match what [com.playbridge.player.preplay.PrePlayActivity] and
 * [ServerService] route on (`player_mode` / `browser_mode`).
 */
data class TvCapabilities(
    val players: List<String>,
    val browsers: List<String>,
    val mediaKinds: List<String> = listOf("video", "audio", "image"),
    val screenMirrorWebRtc: Boolean = true,
    val features: List<String> = listOf(
        "queue_crud_v1", "stable_item_ids", "command_results", "subtitle_resource_add_v1", "progress_webhook_v1",
        "diagnostic_logs_v1", "player_home_v1",
        com.playbridge.shared.protocol.RECEIVER_CAPABILITIES_V1_FEATURE,
    ),
    val capabilities: JsonObject = buildReceiverCapabilities(players, browsers),
)

object TvCapabilityProvider {
    const val GECKO_PACKAGE = "com.playbridge.geckoview.plugin"

    // Both engines are compiled into the player app; external players were removed.
    // Bare engine ids, shared with every receiver (Apple TV/desktop already use the same scheme).
    private val PLAYERS = listOf("exo", "mpv")

    /**
     * Resolved fresh per call so a GeckoView plugin installed after the server starts is
     * picked up by live publication as well as (re)connect, without a restart.
     */
    fun current(context: Context): TvCapabilities {
        val browsers = buildList {
            add("webview") // System WebView — built into the player app, always available.
            if (isGeckoInstalled(context)) add("gecko")
        }
        return TvCapabilities(
            players = PLAYERS,
            browsers = browsers,
            mediaKinds = listOf("video", "audio", "image"),
            screenMirrorWebRtc = true,
            capabilities = buildReceiverCapabilities(PLAYERS, browsers,
                volumeFixed = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isVolumeFixed),
        )
    }

    private fun isGeckoInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(GECKO_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

private val PLAYER_TRANSPORT = listOf("play", "pause", "toggle", "stop", "seek_absolute", "seek_relative")
private val PLAYER_KEYS = listOf("dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center", "back", "home")
private val PLAYER_SETTINGS = listOf("loop", "engine", "speed", "scaling", "quality", "audio_boost", "subtitle_offset")
private val PLAYER_IMAGE_TRANSFORMS = listOf("pan", "scroll", "zoom", "rotate", "anchor", "reset")
private val PLAYER_TRACKS = listOf("audio_select", "subtitle_select", "subtitle_url_add", "subtitle_resource_add")
private val PLAYER_QUEUE = listOf("replace", "append", "jump_index", "jump_item", "remove", "move", "clear")
private val PLAYER_REPORTING = listOf("status", "context", "tracks", "playlist_status", "player_settings")

private val BROWSER_TRANSPORT = listOf("toggle", "seek_absolute", "seek_relative")
private val BROWSER_KEYS = listOf("dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center", "back", "home", "key_enter")
private val BROWSER_POINTER_BASE = listOf("move", "click", "down", "up", "scroll")
private val BROWSER_TEXT = listOf("replace", "enter")
private val BROWSER_OPERATIONS = listOf("launch", "forward", "reload", "cycle_source", "unmute", "fullscreen", "manage_user_scripts", "manage_user_agent")
private val BROWSER_REPORTING = listOf("context", "status", "fullscreen")

private fun buildPlayerEngineDescriptor(): JsonObject = buildJsonObject {
    put("transport", buildJsonArray { PLAYER_TRANSPORT.forEach { add(it) } })
    put("keys", buildJsonArray { PLAYER_KEYS.forEach { add(it) } })
    put("settings", buildJsonArray { PLAYER_SETTINGS.forEach { add(it) } })
    put("imageTransforms", buildJsonArray { PLAYER_IMAGE_TRANSFORMS.forEach { add(it) } })
    put("tracks", buildJsonArray { PLAYER_TRACKS.forEach { add(it) } })
    put("queue", buildJsonArray { PLAYER_QUEUE.forEach { add(it) } })
    put("reporting", buildJsonArray { PLAYER_REPORTING.forEach { add(it) } })
}

private fun buildBrowserEngineDescriptor(pointer: List<String>): JsonObject = buildJsonObject {
    put("transport", buildJsonArray { BROWSER_TRANSPORT.forEach { add(it) } })
    put("keys", buildJsonArray { BROWSER_KEYS.forEach { add(it) } })
    put("pointer", buildJsonArray { pointer.forEach { add(it) } })
    put("text", buildJsonArray { BROWSER_TEXT.forEach { add(it) } })
    put("browser", buildJsonArray { BROWSER_OPERATIONS.forEach { add(it) } })
    put("reporting", buildJsonArray { BROWSER_REPORTING.forEach { add(it) } })
}

/**
 * Constructs the Stage 5 v1 ReceiverCapabilities descriptor for Android TV based on
 * available player and browser engines.
 *
 * Implements the contract defined in protocol/docs/RECEIVER_CAPABILITIES.md.
 */
fun buildReceiverCapabilities(
    players: List<String>,
    browsers: List<String>,
    volumeFixed: Boolean = false,
): JsonObject = buildJsonObject {
    put("version", 1)
    put("surfaces", buildJsonObject {
        if (players.isNotEmpty()) {
            put("player", buildJsonObject {
                put("engines", buildJsonObject {
                    if ("exo" in players) {
                        put("exo", buildPlayerEngineDescriptor())
                    }
                    if ("mpv" in players) {
                        put("mpv", buildPlayerEngineDescriptor())
                    }
                })
            })
        }
        if (browsers.isNotEmpty()) {
            put("browser", buildJsonObject {
                put("engines", buildJsonObject {
                    if ("webview" in browsers) {
                        put("webview", buildBrowserEngineDescriptor(pointer = BROWSER_POINTER_BASE + "zoom"))
                    }
                    if ("gecko" in browsers) {
                        put("gecko", buildBrowserEngineDescriptor(pointer = BROWSER_POINTER_BASE + "zoom"))
                    }
                })
            })
        }
    })
    put("host", buildJsonObject {
        put("osFamily", "android")
        put("deviceClass", "tv")
    })
    put("volume", buildJsonObject {
        put("scope", "system")
        put("operations", buildJsonArray {
            val operations = if (volumeFixed) listOf("relative_step", "mute_toggle")
                else listOf("relative_step", "absolute", "mute_toggle", "level_report", "mute_report")
            operations.forEach { add(it) }
        })
    })
    put("companionHints", buildJsonArray {
        add("android_tv_remote_v2")
    })
}

