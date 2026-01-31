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
    val headers: Map<String, String>? = null
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

fun createPlayCommandJson(url: String, title: String? = null, headers: Map<String, String>? = null): String {
    return protocolJson.encodeToString(
        PlayCommand.serializer(),
        PlayCommand(payload = PlayPayload(url = url, title = title, headers = headers))
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

fun parseQRCode(jsonString: String): QRCodeData? {
    return try {
        protocolJson.decodeFromString<QRCodeData>(jsonString)
    } catch (e: Exception) {
        null
    }
}
