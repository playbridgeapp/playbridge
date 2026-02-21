package com.playbridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * JSON parser with lenient settings for protocol messages
 */
val protocolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

// ==================== Message Envelope ====================

/**
 * Base message envelope for WebSocket protocol
 */
@Serializable
data class MessageEnvelope(
    val type: String,
    val action: String? = null,
    val payload: JsonElement? = null,
    val state: String? = null,
    val position: Long? = null,
    val duration: Long? = null,
    val title: String? = null
)

// ==================== Payload Data Classes ====================

/**
 * Play video command payload
 */
@Serializable
data class PlayPayload(
    val url: String,
    val title: String? = null,
    val headers: Map<String, String>? = null,
    val contentType: String? = null,
    val subtitles: List<String>? = null,
    val detectedBy: String? = null
)

/**
 * Open browser command payload
 */
@Serializable
data class BrowserPayload(
    val url: String
)

/**
 * Player control command payload
 */
@Serializable
data class ControlPayload(
    val command: String // pause, play, seek, stop
)

/**
 * Remote D-pad/navigation command payload
 */
@Serializable
data class RemotePayload(
    val key: String // dpad_up, dpad_down, dpad_left, dpad_right, dpad_center, back
)

/**
 * Mouse/touchpad command payload
 */
@Serializable
data class MousePayload(
    val event: String, // move, click, scroll
    val dx: Float = 0f,
    val dy: Float = 0f
)

/**
 * Browser control command payload (refresh, toggle extensions)
 */
@Serializable
data class BrowserControlPayload(
    val action: String // refresh, toggle_ublock
)

// ==================== Command Wrapper Classes (for JSON encoding) ====================

/**
 * Play video command
 */
@Serializable
data class PlayCommand(
    val type: String = "command",
    val action: String = "play",
    val payload: PlayPayload
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

/**
 * Player control command
 */
@Serializable
data class ControlCommand(
    val type: String = "command",
    val action: String = "control",
    val payload: ControlPayload
)

/**
 * Remote D-pad/navigation command
 */
@Serializable
data class RemoteCommand(
    val type: String = "command",
    val action: String = "remote",
    val payload: RemotePayload
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

/**
 * Browser control command (refresh, toggle extensions)
 */
@Serializable
data class BrowserControlCommand(
    val type: String = "command",
    val action: String = "browser_control",
    val payload: BrowserControlPayload
)

/**
 * Query TV for its active context (player, browser, or idle)
 */
@Serializable
data class ContextQueryCommand(
    val type: String = "command",
    val action: String = "context_query"
)

// ==================== Status (TV → Phone) ====================

/**
 * Playback status message
 */
@Serializable
data class StatusMessage(
    val type: String = "status",
    val state: String = "",
    val position: Long = 0,
    val duration: Long = 0,
    val title: String? = null
)

/**
 * Context response from TV
 */
@Serializable
data class ContextMessage(
    val type: String = "context",
    val active: String = "idle" // "player", "browser", or "idle"
)

// ==================== Authentication ====================

@Serializable
data class AuthMessage(
    val type: String = "auth",
    val token: String? = null,
    val pin: String? = null
)

@Serializable
data class AuthResponse(
    val type: String = "auth_response",
    val success: Boolean,
    val token: String? = null
)

// ==================== Heartbeat ====================

@Serializable
data class PingMessage(val type: String = "ping")

@Serializable
data class PongMessage(val type: String = "pong")

// ==================== Sealed Command Class (for parsing) ====================

sealed class Command {
    data class Play(val url: String, val title: String?, val headers: Map<String, String>?, val contentType: String?, val subtitles: List<String>?, val detectedBy: String?) : Command()
    data class Browser(val url: String) : Command()
    data class Control(val command: String) : Command()
    data class Remote(val key: String) : Command()
    data class Mouse(val event: String, val dx: Float, val dy: Float) : Command()
    data class BrowserControl(val action: String) : Command()
    data object ContextQuery : Command()
    data object Ping : Command()
    data class Unknown(val type: String) : Command()
}

// ==================== Command Parser ====================

/**
 * Parse incoming WebSocket message into a Command
 */
fun parseCommand(jsonString: String): Command {
    return try {
        val envelope = protocolJson.decodeFromString<MessageEnvelope>(jsonString)
        
        when (envelope.type) {
            "ping" -> Command.Ping
            "command" -> {
                when (envelope.action) {
                    "play" -> {
                        val payload = envelope.payload?.let {
                            protocolJson.decodeFromJsonElement<PlayPayload>(it)
                        }
                        Command.Play(
                            url = payload?.url ?: "",
                            title = payload?.title,
                            headers = payload?.headers,
                            contentType = payload?.contentType,
                            subtitles = payload?.subtitles,
                            detectedBy = payload?.detectedBy
                        )
                    }
                    "browser" -> {
                        val payload = envelope.payload?.let {
                            protocolJson.decodeFromJsonElement<BrowserPayload>(it)
                        }
                        Command.Browser(url = payload?.url ?: "")
                    }
                    "control" -> {
                        val payload = envelope.payload?.let {
                            protocolJson.decodeFromJsonElement<ControlPayload>(it)
                        }
                        Command.Control(command = payload?.command ?: "")
                    }
                    "remote" -> {
                        val payload = envelope.payload?.let {
                            protocolJson.decodeFromJsonElement<RemotePayload>(it)
                        }
                        Command.Remote(key = payload?.key ?: "")
                    }
                    "mouse" -> {
                        val payload = envelope.payload?.let {
                            protocolJson.decodeFromJsonElement<MousePayload>(it)
                        }
                        Command.Mouse(
                            event = payload?.event ?: "",
                            dx = payload?.dx ?: 0f,
                            dy = payload?.dy ?: 0f
                        )
                    }
                    "browser_control" -> {
                        val payload = envelope.payload?.let {
                            protocolJson.decodeFromJsonElement<BrowserControlPayload>(it)
                        }
                        Command.BrowserControl(action = payload?.action ?: "")
                    }
                    "context_query" -> Command.ContextQuery
                    else -> Command.Unknown(envelope.action ?: "unknown")
                }
            }
            else -> Command.Unknown(envelope.type)
        }
    } catch (e: Exception) {
        Command.Unknown("parse_error: ${e.message}")
    }
}

// ==================== Helper Functions ====================

fun createPlayCommandJson(
    url: String,
    title: String? = null,
    headers: Map<String, String>? = null,
    contentType: String? = null,
    subtitles: List<String>? = null,
    detectedBy: String? = null
): String {
    return protocolJson.encodeToString(
        PlayCommand.serializer(),
        PlayCommand(payload = PlayPayload(url, title, headers, contentType, subtitles, detectedBy))
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

fun createBrowserControlCommandJson(action: String): String {
    return protocolJson.encodeToString(
        BrowserControlCommand.serializer(),
        BrowserControlCommand(payload = BrowserControlPayload(action = action))
    )
}

fun createContextQueryJson(): String {
    return protocolJson.encodeToString(ContextQueryCommand.serializer(), ContextQueryCommand())
}

fun createAuthJson(token: String): String {
    // Determine if it's a PIN (4 chars) or full token
    return if (token.length <= 4) {
        protocolJson.encodeToString(AuthMessage.serializer(), AuthMessage(pin = token))
    } else {
        protocolJson.encodeToString(AuthMessage.serializer(), AuthMessage(token = token))
    }
}

/**
 * Create JSON string for status update
 */
fun createStatusJson(state: String, position: Long, duration: Long, title: String?): String {
    return protocolJson.encodeToString(
        StatusMessage.serializer(),
        StatusMessage(state = state, position = position, duration = duration, title = title)
    )
}

/**
 * Create JSON string for pong response
 */
fun createPongJson(): String {
    return protocolJson.encodeToString(PongMessage.serializer(), PongMessage())
}

/**
 * Create JSON string for context response
 * @param active "player", "browser", or "idle"
 */
fun createContextJson(active: String): String {
    return """{"type":"context","active":"$active"}"""
}
