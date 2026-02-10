package com.playbridge.sender.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON parser with lenient settings for protocol messages
 */
val protocolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

// ==================== QR Code Data ====================

/**
 * Data encoded in the TV's QR code
 */
@Serializable
data class QRCodeData(
    val ip: String,
    val port: Int,
    val token: String,
    val name: String,
    val version: Int = 1
)

// ==================== Commands (Phone → TV) ====================

/**
 * Play video command
 */
@Serializable
data class PlayCommand(
    val type: String = "command",
    val action: String = "play",
    val payload: PlayPayload
)

@Serializable
data class PlayPayload(
    val url: String,
    val title: String? = null,
    val headers: Map<String, String>? = null,
    val contentType: String? = null
)

/**
 * Open browser command
 */
@Serializable
data class BrowserCommand(
    val type: String = "command",
    val action: String = "browser",
    val payload: BrowserPayload
)

@Serializable
data class BrowserPayload(
    val url: String
)

/**
 * Player control command
 */
@Serializable
data class ControlCommand(
    val type: String = "command",
    val action: String = "control",
    val payload: ControlPayload
)

@Serializable
data class ControlPayload(
    val command: String // pause, play, seek, stop
)

// ==================== Heartbeat ====================

@Serializable
data class PingMessage(val type: String = "ping")

@Serializable
data class PongMessage(val type: String = "pong")

// ==================== Status (TV → Phone) ====================

@Serializable
data class StatusMessage(
    val type: String = "status",
    val state: String = "",
    val position: Long = 0,
    val duration: Long = 0,
    val title: String? = null
)

// ==================== Helper Functions ====================

fun createPlayCommandJson(url: String, title: String? = null, headers: Map<String, String>? = null, contentType: String? = null): String {
    return protocolJson.encodeToString(
        PlayCommand.serializer(),
        PlayCommand(payload = PlayPayload(url = url, title = title, headers = headers, contentType = contentType))
    )
}

fun createBrowserCommandJson(url: String): String {
    return protocolJson.encodeToString(
        BrowserCommand.serializer(),
        BrowserCommand(payload = BrowserPayload(url = url))
    )
}

fun createControlCommandJson(command: String): String {
    return protocolJson.encodeToString(
        ControlCommand.serializer(),
        ControlCommand(payload = ControlPayload(command = command))
    )
}

fun createPingJson(): String {
    return protocolJson.encodeToString(PingMessage.serializer(), PingMessage())
}

// ==================== Remote Control Commands ====================

/**
 * Remote D-pad/navigation command
 */
@Serializable
data class RemoteCommand(
    val type: String = "command",
    val action: String = "remote",
    val payload: RemotePayload
)

@Serializable
data class RemotePayload(
    val key: String // dpad_up, dpad_down, dpad_left, dpad_right, dpad_center, back
)

/**
 * Mouse/touchpad command for TV browser
 */
@Serializable
data class MouseCommand(
    val type: String = "command",
    val action: String = "mouse",
    val payload: MousePayload
)

@Serializable
data class MousePayload(
    val event: String, // move, click, scroll
    val dx: Float = 0f,
    val dy: Float = 0f
)

fun createRemoteCommandJson(key: String): String {
    return protocolJson.encodeToString(
        RemoteCommand.serializer(),
        RemoteCommand(payload = RemotePayload(key = key))
    )
}

fun createMouseCommandJson(event: String, dx: Float = 0f, dy: Float = 0f): String {
    return protocolJson.encodeToString(
        MouseCommand.serializer(),
        MouseCommand(payload = MousePayload(event = event, dx = dx, dy = dy))
    )
}

// ==================== Browser Control Commands ====================

/**
 * Browser control command (refresh, toggle extensions)
 */
@Serializable
data class BrowserControlCommand(
    val type: String = "command",
    val action: String = "browser_control",
    val payload: BrowserControlPayload
)

@Serializable
data class BrowserControlPayload(
    val action: String // refresh, toggle_ublock
)

fun createBrowserControlCommandJson(action: String): String {
    return protocolJson.encodeToString(
        BrowserControlCommand.serializer(),
        BrowserControlCommand(payload = BrowserControlPayload(action = action))
    )
}

// ==================== Context Query ====================

/**
 * Query TV for its active context (player, browser, or idle)
 */
@Serializable
data class ContextQueryCommand(
    val type: String = "command",
    val action: String = "context_query"
)

/**
 * Context response from TV
 */
@Serializable
data class ContextMessage(
    val type: String = "context",
    val active: String = "idle" // "player", "browser", or "idle"
)

fun createContextQueryJson(): String {
    return protocolJson.encodeToString(ContextQueryCommand.serializer(), ContextQueryCommand())
}

fun parseQRCode(jsonString: String): QRCodeData? {
    return try {
        protocolJson.decodeFromString<QRCodeData>(jsonString)
    } catch (e: Exception) {
        null
    }
}

