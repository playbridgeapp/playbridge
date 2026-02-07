package com.playbridge.receiver.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON parser with lenient settings for protocol messages
 */
val protocolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

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

// ==================== Commands (Phone → TV) ====================

/**
 * Play video command payload
 */
@Serializable
data class PlayPayload(
    val url: String,
    val title: String? = null,
    val headers: Map<String, String>? = null,
    val contentType: String? = null
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

// ==================== Status (TV → Phone) ====================

/**
 * Playback status message
 */
@Serializable
data class StatusMessage(
    val type: String = "status",
    val state: String, // playing, paused, stopped, buffering
    val position: Long = 0,
    val duration: Long = 0,
    val title: String? = null
)

// ==================== Heartbeat ====================

@Serializable
data class PingMessage(val type: String = "ping")

@Serializable
data class PongMessage(val type: String = "pong")

// ==================== Sealed class for parsed commands ====================

sealed class Command {
    data class Play(val url: String, val title: String?, val headers: Map<String, String>?, val contentType: String?) : Command()
    data class Browser(val url: String) : Command()
    data class Control(val command: String) : Command()
    data object Ping : Command()
    data class Unknown(val type: String) : Command()
}

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
                            contentType = payload?.contentType
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
                    else -> Command.Unknown(envelope.action ?: "unknown")
                }
            }
            else -> Command.Unknown(envelope.type)
        }
    } catch (e: Exception) {
        Command.Unknown("parse_error: ${e.message}")
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
