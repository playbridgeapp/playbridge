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
    val detectedBy: String? = null,
    val playerMode: String? = null,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val defaultVideoQuality: String? = null
)

/**
 * Playlist command payload — ordered list of items to play sequentially
 */
@Serializable
data class PlaylistPayload(
    val items: List<PlayPayload>,
    val startIndex: Int = 0
)

/**
 * Queue add command payload — append a single item to the TV's active playlist
 */
@Serializable
data class QueueAddPayload(
    val item: PlayPayload
)

/**
 * Playlist jump command payload — jump to a specific index in the TV's playlist
 */
@Serializable
data class PlaylistJumpPayload(
    val index: Int
)

/**
 * Open browser command payload
 */
@Serializable
data class BrowserPayload(
    val url: String,
    val browserMode: String? = null,
    val desktopMode: Boolean? = null
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

/**
 * Playlist command — send multiple items to play in sequence
 */
@Serializable
data class PlaylistCommand(
    val type: String = "command",
    val action: String = "playlist",
    val payload: PlaylistPayload
)

/**
 * Queue add command — append a single item to the TV's active playlist
 */
@Serializable
data class QueueAddCommand(
    val type: String = "command",
    val action: String = "queue_add",
    val payload: QueueAddPayload
)

/**
 * Playlist jump command — tell TV to jump to a specific playlist index
 */
@Serializable
data class PlaylistJumpCommand(
    val type: String = "command",
    val action: String = "playlist_jump",
    val payload: PlaylistJumpPayload
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

/**
 * Playlist item info for status messages
 */
@Serializable
data class PlaylistItemInfo(
    val index: Int,
    val title: String
)

/**
 * Playlist status message (TV → Phone) — broadcasts current playlist state
 */
@Serializable
data class PlaylistStatusMessage(
    val type: String = "playlist_status",
    val items: List<PlaylistItemInfo> = emptyList(),
    val currentIndex: Int = 0,
    val totalCount: Int = 0
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
    data class Play(
        val url: String,
        val title: String?,
        val headers: Map<String, String>?,
        val contentType: String?,
        val subtitles: List<String>?,
        val detectedBy: String?,
        val playerMode: String?,
        val preferredAudioLanguage: String? = null,
        val preferredSubtitleLanguage: String? = null,
        val defaultVideoQuality: String? = null
    ) : Command()
    data class Browser(val url: String, val browserMode: String?, val desktopMode: Boolean? = null) : Command()
    data class Control(val command: String) : Command()
    data class Remote(val key: String) : Command()
    data class Mouse(val event: String, val dx: Float, val dy: Float) : Command()
    data class BrowserControl(val action: String) : Command()
    data object ContextQuery : Command()
    data class Playlist(val items: List<PlayPayload>, val startIndex: Int) : Command()
    data class QueueAdd(val item: PlayPayload) : Command()
    data class PlaylistJump(val index: Int) : Command()
    data object Ping : Command()
    // Sent by the phone before it has a PIN/token, as a lightweight "I'm about to pair"
    // signal so the TV can open its PairingScreen and show the PIN in advance.
    data object RequestPairing : Command()
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
            "request_pairing" -> Command.RequestPairing
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
                            detectedBy = payload?.detectedBy,
                            playerMode = payload?.playerMode,
                            preferredAudioLanguage = payload?.preferredAudioLanguage,
                            preferredSubtitleLanguage = payload?.preferredSubtitleLanguage,
                            defaultVideoQuality = payload?.defaultVideoQuality
                        )
                    }
                    "browser" -> {
                        val payload = envelope.payload?.let {
                            protocolJson.decodeFromJsonElement<BrowserPayload>(it)
                        }
                        Command.Browser(
                            url = payload?.url ?: "",
                            browserMode = payload?.browserMode,
                            desktopMode = payload?.desktopMode
                        )
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
                    "playlist" -> {
                        val payload = envelope.payload?.let {
                            protocolJson.decodeFromJsonElement<PlaylistPayload>(it)
                        }
                        Command.Playlist(
                            items = payload?.items ?: emptyList(),
                            startIndex = payload?.startIndex ?: 0
                        )
                    }
                    "queue_add" -> {
                        val payload = envelope.payload?.let {
                            protocolJson.decodeFromJsonElement<QueueAddPayload>(it)
                        }
                        Command.QueueAdd(
                            item = payload?.item ?: PlayPayload(url = "")
                        )
                    }
                    "playlist_jump" -> {
                        val payload = envelope.payload?.let {
                            protocolJson.decodeFromJsonElement<PlaylistJumpPayload>(it)
                        }
                        Command.PlaylistJump(
                            index = payload?.index ?: 0
                        )
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

// ==================== Helper Functions ====================

fun createPlayCommandJson(
    url: String,
    title: String? = null,
    headers: Map<String, String>? = null,
    contentType: String? = null,
    subtitles: List<String>? = null,
    detectedBy: String? = null,
    playerMode: String? = null,
    preferredAudioLanguage: String? = null,
    preferredSubtitleLanguage: String? = null,
    defaultVideoQuality: String? = null
): String {
    return protocolJson.encodeToString(
        PlayCommand.serializer(),
        PlayCommand(payload = PlayPayload(
            url = url,
            title = title,
            headers = headers,
            contentType = contentType,
            subtitles = subtitles,
            detectedBy = detectedBy,
            playerMode = playerMode,
            preferredAudioLanguage = preferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            defaultVideoQuality = defaultVideoQuality
        ))
    )
}

fun createBrowserCommandJson(url: String, browserMode: String? = null, desktopMode: Boolean? = null): String {
    return protocolJson.encodeToString(
        BrowserCommand.serializer(),
        BrowserCommand(payload = BrowserPayload(url = url, browserMode = browserMode, desktopMode = desktopMode))
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

/**
 * Create JSON string for playlist command (multiple items to play in sequence)
 */
fun createPlaylistCommandJson(
    items: List<PlayPayload>,
    startIndex: Int = 0
): String {
    return protocolJson.encodeToString(
        PlaylistCommand.serializer(),
        PlaylistCommand(payload = PlaylistPayload(items = items, startIndex = startIndex))
    )
}

/**
 * Create JSON string for queue_add command (append single item to active playlist)
 */
fun createQueueAddCommandJson(item: PlayPayload): String {
    return protocolJson.encodeToString(
        QueueAddCommand.serializer(),
        QueueAddCommand(payload = QueueAddPayload(item = item))
    )
}

/**
 * Create JSON string for playlist_jump command (jump to specific playlist index)
 */
fun createPlaylistJumpCommandJson(index: Int): String {
    return protocolJson.encodeToString(
        PlaylistJumpCommand.serializer(),
        PlaylistJumpCommand(payload = PlaylistJumpPayload(index = index))
    )
}

/**
 * Create JSON string for playlist_status message (TV → Phone)
 */
fun createPlaylistStatusJson(
    items: List<PlaylistItemInfo>,
    currentIndex: Int,
    totalCount: Int
): String {
    return protocolJson.encodeToString(
        PlaylistStatusMessage.serializer(),
        PlaylistStatusMessage(
            items = items,
            currentIndex = currentIndex,
            totalCount = totalCount
        )
    )
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

/**
 * Create JSON string for request_pairing message (Phone → TV).
 * Sent before auth so the TV opens its PairingScreen and displays the PIN
 * while the user is reading it from the screen to type on the phone.
 */
fun createRequestPairingJson(): String = """{"type":"request_pairing"}"""
