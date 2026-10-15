package com.playbridge.sender.cast.remote

import com.playbridge.sender.connection.ReceiverProtocolState
import com.playbridge.shared.protocol.createAddSubtitleCommandJson
import com.playbridge.shared.protocol.createBrowserCommandJson
import com.playbridge.shared.protocol.createBrowserControlCommandJson
import com.playbridge.shared.protocol.createContextQueryJson
import com.playbridge.shared.protocol.createControlCommandJson
import com.playbridge.shared.protocol.createPlaylistJumpCommandJson
import com.playbridge.shared.protocol.createRemoteCommandJson
import com.playbridge.shared.protocol.createUserAgentQueryJson
import com.playbridge.shared.protocol.createUserAgentJson
import com.playbridge.shared.protocol.createUserScriptJson
import com.playbridge.shared.protocol.createUserScriptQueryJson
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class NativeRemoteConfiguration(
    val receiver: RemoteReceiver, val players: List<String> = emptyList(), val features: Set<String> = emptySet(),
    // A stable live reference keeps descriptor-only refreshes from changing registry/controller identity.
    val receiverProtocol: StateFlow<ReceiverProtocolState>? = null,
)

open class NativeProfileRemoteController(
    final override val sessionId: RemoteSessionId,
    private val configuration: NativeRemoteConfiguration,
    protected val transport: NativeRemoteTransport,
    scope: CoroutineScope,
    private val current: () -> Boolean = { true },
    private val requestId: () -> String = { UUID.randomUUID().toString() },
) : RemoteController {
    private var pointerActive = false
    private var pointerHeld = false
    private var pointerAnchorActive = false

    private fun decorate(
        raw: RemoteReceiverSnapshot, protocol: ReceiverProtocolState = raw.state.receiverProtocol,
    ): RemoteReceiverSnapshot {
        val state = raw.state
        val decorated = state.copy(
            sessionId = sessionId, receiver = configuration.receiver,
            receiverProtocol = if (state.connection.ready) protocol else ReceiverProtocolState(),
            playback = state.playback?.let { playback ->
                playback.copy(mediaId = RemoteMediaId(sessionId, state.queue?.playbackId, state.queue?.currentItemId, raw.observation.mediaGeneration))
            },
        )
        return raw.copy(state = resolveReceiverRemotePolicy(decorated, configuration.players, configuration.features))
    }
    final override val snapshot: StateFlow<RemoteReceiverSnapshot> = (configuration.receiverProtocol?.let { protocol ->
        combine(transport.snapshot, protocol) { raw, metadata -> decorate(raw, metadata) }
    } ?: transport.snapshot.map { decorate(it) })
        .stateIn(scope, SharingStarted.Eagerly, decorate(
            transport.snapshot.value, configuration.receiverProtocol?.value ?: transport.snapshot.value.state.receiverProtocol,
        ))

    override suspend fun execute(action: RemoteAction): RemoteCommandResult {
        if (!current()) return RemoteCommandResult.Failed("Remote session changed")
        // Resolve the current metadata synchronously too: a push may remove an
        // operation before the combined UI StateFlow has had a chance to emit.
        val state = decorate(transport.snapshot.value,
            configuration.receiverProtocol?.value ?: transport.snapshot.value.state.receiverProtocol).state
        if (!state.connection.ready) return RemoteCommandResult.Failed("Receiver is not connected")
        val caps = state.capabilities
        fun unsupported() = RemoteCommandResult.Unsupported("${action::class.simpleName} is unavailable on this receiver in ${state.context}")
        fun send(json: String): RemoteCommandResult = if (!current()) RemoteCommandResult.Failed("Remote session changed")
            else if (transport.send(json)) RemoteCommandResult.Submitted else RemoteCommandResult.Failed("Could not send remote command")
        fun control(command: String) = send(createControlCommandJson(command))
        fun browser(command: String) = send(createBrowserControlCommandJson(command))
        fun pointer(event: String, x: Float = 0f, y: Float = 0f): RemoteCommandResult {
            if (!x.isFinite() || !y.isFinite()) return RemoteCommandResult.Failed("Invalid pointer coordinates")
            return if (current() && transport.pointer(event, x, y)) {
                pointerActive = true
                if (event == "down") pointerHeld = true
                if (event == "up") pointerHeld = false
                if (event == "transform_anchor") pointerAnchorActive = x >= 0f && y >= 0f
                if (event == "reset") pointerAnchorActive = false
                RemoteCommandResult.Submitted
            } else RemoteCommandResult.Failed("Could not send pointer command")
        }
        fun setting(setting: PlayerSetting, command: String) = if (setting in caps.settings) control(command) else unsupported()
        val playback = state.playback
        // An in-flight pointer still needs release when context/authority changes.
        if (state.nativePolicy != null && !state.receiverActionSupported(action) &&
            !(action == RemoteAction.PointerUp && pointerHeld || action == RemoteAction.PointerGestureEnd && pointerActive)) return unsupported()
        return when (action) {
            RemoteAction.TogglePlayPause, is RemoteAction.SetPlaying -> {
                if (state.nativePolicy != null && action == RemoteAction.TogglePlayPause && "toggle" in state.nativePolicy.operations.transport)
                    if (state.context == RemoteContext.Browser) browser("toggle_play") else control("toggle")
                else if (state.nativePolicy == null && caps.transport.playPause != Support.Supported) unsupported()
                else if (state.context == RemoteContext.Browser) browser(if (state.nativePolicy == null) "toggle_play"
                    else if ((action as? RemoteAction.SetPlaying)?.playing ?: (playback?.displayedPlaying != true)) "play" else "pause")
                else control(if ((action as? RemoteAction.SetPlaying)?.playing ?: (playback?.displayedPlaying != true)) "play" else "pause")
            }
            is RemoteAction.SeekTo, is RemoteAction.SeekBy -> {
                if ((state.nativePolicy == null || action is RemoteAction.SeekTo) && caps.transport.seekAbsolute != Support.Supported || playback?.seekable != Support.Supported || playback.isLive || playback.mediaKind == RemoteMediaKind.Image) unsupported()
                else if (state.nativePolicy != null && action is RemoteAction.SeekBy && state.context != RemoteContext.Browser && caps.transport.seekAbsolute != Support.Supported)
                    control(if (action.deltaMs >= 0) "seek_forward" else "seek_back")
                else {
                    val target = when (action) {
                        is RemoteAction.SeekTo -> boundedSeek(action.positionMs, playback.durationMs)
                        is RemoteAction.SeekBy -> relativeRemoteSeek(playback.displayedPositionMs, action.deltaMs, playback.durationMs)
                    }
                    if (state.context == RemoteContext.Browser) browser("seek_to:$target") else control("seek_to:$target")
                }
            }
            RemoteAction.StopPlayback -> if (caps.transport.stop == Support.Supported) control("stop") else unsupported()
            is RemoteAction.JumpToEpisode -> {
                val item = state.queue?.items?.firstOrNull { it.index == action.index }
                when {
                    state.nativePolicy == null && caps.queue.jumpIndex != Support.Supported -> unsupported()
                    action.itemId == null && caps.queue.jumpIndex != Support.Supported -> unsupported()
                    action.index < 0 || item == null || action.itemId != null && item.itemId != action.itemId -> RemoteCommandResult.Failed("Episode is no longer in this queue")
                    action.itemId == null -> send(createPlaylistJumpCommandJson(action.index))
                    caps.queue.jumpItem != Support.Supported -> unsupported()
                    else -> queueCommand("playlist_jump", state.queue, buildJsonObject { put("itemId", action.itemId) })
                }
            }
            is RemoteAction.RemoveQueueItem -> if (caps.queue.remove != Support.Supported) unsupported()
                else queueCommand("queue_remove", state.queue, buildJsonObject { put("itemIds", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(action.itemId)) }) }, action.itemId)
            is RemoteAction.PlayNext -> {
                val queue = state.queue
                if (caps.queue.move != Support.Supported) unsupported()
                else if (queue?.currentItemId == null || queue.items.none { it.itemId == queue.currentItemId }) RemoteCommandResult.Failed("Current queue identity is unavailable")
                else if (queue.currentItemId == action.itemId) RemoteCommandResult.Failed("The current item cannot be moved to play next")
                else {
                    val currentIndex = queue.items.indexOfFirst { it.itemId == queue.currentItemId }
                    val before = queue.items.drop(currentIndex + 1).firstOrNull { it.itemId != action.itemId }?.itemId
                    queueCommand("queue_move", queue, buildJsonObject { put("itemId", action.itemId); put("beforeItemId", before) }, action.itemId)
                }
            }
            is RemoteAction.SetLoop -> setting(PlayerSetting.Loop, if (action.enabled) "loop_on" else "loop_off")
            is RemoteAction.SelectAudio -> if (caps.tracks.audio == Support.Supported) control("audio_track:${action.id}") else unsupported()
            is RemoteAction.SelectSubtitle -> if (caps.tracks.subtitle == Support.Supported) control("sub_track:${action.id}") else unsupported()
            is RemoteAction.SetVideoQuality -> setting(PlayerSetting.Quality, "video_quality:${action.maxHeight?.takeIf { it > 0 } ?: "auto"}")
            is RemoteAction.SetSpeed -> if (!action.speed.isFinite() || action.speed <= 0f) RemoteCommandResult.Failed("Invalid playback speed") else setting(PlayerSetting.Speed, "speed:${action.speed}")
            is RemoteAction.SetScaling -> setting(PlayerSetting.Scaling, "scaling:${action.mode.wireValue}")
            RemoteAction.ToggleAudioBoost -> setting(PlayerSetting.AudioBoost, "audio_boost")
            is RemoteAction.AdjustSubtitleOffset -> setting(PlayerSetting.SubtitleOffset, "sub_offset:${action.deltaMs}")
            is RemoteAction.SwitchEngine -> if (action.engineId !in caps.engines) unsupported() else setting(PlayerSetting.Engine, "switch_player:${action.engineId}")
            is RemoteAction.AddSubtitle -> {
                when {
                    action.legacyUrlOnly && action.resource.headers.isNotEmpty() -> unsupported()
                    !action.legacyUrlOnly && caps.tracks.addResource == Support.Supported -> {
                        val id = requestId()
                        val result = transport.confirmed(id, createAddSubtitleCommandJson(action.resource, id), 30_000L)
                        if (!current()) RemoteCommandResult.Failed("Remote session changed")
                        else if (result?.ok == true) RemoteCommandResult.Confirmed
                        else RemoteCommandResult.Failed(subtitleFailureMessage(result?.error))
                    }
                    caps.tracks.addUrl == Support.Supported && action.resource.headers.isEmpty() -> control("add_subtitle:${action.resource.url}")
                    else -> unsupported()
                }
            }
            is RemoteAction.Key -> if (action.key in caps.keys) send(createRemoteCommandJson(action.key.wireValue)) else unsupported()
            is RemoteAction.AdjustVolume -> {
                if (caps.volume.adjust != Support.Supported) unsupported()
                else if (action.steps == 0 || action.steps !in -100..100) RemoteCommandResult.Failed("Invalid volume adjustment")
                else {
                    var result: RemoteCommandResult = RemoteCommandResult.Submitted
                    repeat(kotlin.math.abs(action.steps)) {
                        if (result == RemoteCommandResult.Submitted) result = send(createRemoteCommandJson(if (action.steps > 0) "volume_up" else "volume_down"))
                    }
                    result
                }
            }
            is RemoteAction.SetVolume -> if (state.nativePolicy == null || caps.volume.absolute != Support.Supported) unsupported()
                else if (action.percent !in 0..100) RemoteCommandResult.Failed("Invalid volume level")
                else send(buildJsonObject {
                    put("type", "command"); put("action", "volume")
                    put("payload", buildJsonObject { put("scope", state.nativePolicy.volume!!.scope); put("level", action.percent / 100.0) })
                }.toString())
            is RemoteAction.ReplaceText -> if (caps.text.replace == Support.Supported)
                send(createRemoteCommandJson("text:${Base64.getEncoder().encodeToString(action.text.toByteArray(Charsets.UTF_8))}")) else unsupported()
            RemoteAction.DeleteText -> if (state.nativePolicy != null && caps.text.delete == Support.Supported) send(createRemoteCommandJson("key_delete")) else unsupported()
            is RemoteAction.PointerMove -> if (caps.pointer.move == Support.Supported) pointer("move", action.dx, action.dy) else unsupported()
            RemoteAction.PointerClick -> if (caps.pointer.click == Support.Supported) pointer("click") else unsupported()
            is RemoteAction.PointerScroll -> if (caps.pointer.scroll == Support.Supported) pointer("scroll", action.dx, action.dy) else unsupported()
            RemoteAction.PointerDown -> if (caps.pointer.drag == Support.Supported) pointer("down") else unsupported()
            RemoteAction.PointerUp -> when {
                pointerHeld -> pointer("up")
                caps.pointer.drag == Support.Supported -> RemoteCommandResult.Submitted // already released by gesture cleanup
                else -> unsupported()
            }
            RemoteAction.PointerGestureEnd -> if (state.nativePolicy == null && configuration.receiver.profile !in setOf(RemoteProfile.AndroidTv, RemoteProfile.Desktop)) unsupported()
                else if (!pointerActive) RemoteCommandResult.Submitted
                else {
                    if (pointerHeld) pointer("up")
                    val finished = current() && if (state.nativePolicy == null) transport.endPointerGesture() else transport.endPointerGesture(pointerAnchorActive)
                    pointerActive = false; pointerHeld = false; pointerAnchorActive = false
                    if (finished) RemoteCommandResult.Submitted else RemoteCommandResult.Failed("Could not finish pointer gesture")
                }
            is RemoteAction.ImageZoom -> if (caps.pointer.zoom != Support.Supported) unsupported()
                else if (action.factor <= 0f) RemoteCommandResult.Failed("Invalid zoom factor") else pointer("zoom", action.factor)
            is RemoteAction.ImageRotate -> if (caps.pointer.rotate == Support.Supported) pointer("rotate", action.degrees) else unsupported()
            is RemoteAction.ImageAnchor -> if (caps.pointer.anchor == Support.Supported) pointer("transform_anchor", action.x, action.y) else unsupported()
            RemoteAction.ResetImageTransform -> if (caps.pointer.reset == Support.Supported) pointer("reset") else unsupported()
            is RemoteAction.Browser -> if (action.action in caps.browser.actions) browser(action.action.wireValue) else unsupported()
            is RemoteAction.SetBrowserFullscreen -> if (caps.browser.fullscreen == Support.Supported) browser(if (action.fullscreen) "maximize_video" else "restore_video") else unsupported()
            is RemoteAction.OpenTvBrowser -> if (caps.browser.launch == Support.Supported) send(createBrowserCommandJson(action.url,
                action.engine ?: state.nativePolicy?.launchEngines?.firstOrNull())) else unsupported()
            RemoteAction.ManageUserScripts -> if (caps.browser.managers == Support.Supported) send(createUserScriptQueryJson()) else unsupported()
            RemoteAction.ManageUserAgent -> if (caps.browser.managers == Support.Supported) send(createUserAgentQueryJson()) else unsupported()
            is RemoteAction.UpdateUserScript -> send(createUserScriptJson(action.name, action.content))
            is RemoteAction.UpdateUserAgent -> send(createUserAgentJson(action.name, action.value, action.save))
            RemoteAction.Resync -> send(createContextQueryJson())
            is RemoteAction.Unavailable -> RemoteCommandResult.Unsupported(action.reason)
            else -> unsupported()
        }
    }

    private suspend fun queueCommand(action: String, queue: RemoteQueueState?, payload: JsonObject, itemId: String? = null): RemoteCommandResult {
        if (queue?.playbackId.isNullOrBlank() || itemId != null && queue.items.none { it.itemId == itemId }) return RemoteCommandResult.Failed("Queue identity is no longer available")
        if (!current()) return RemoteCommandResult.Failed("Remote session changed")
        val id = requestId()
        val json = buildJsonObject {
            put("type", "command"); put("action", action); put("requestId", id)
            put("payload", buildJsonObject { payload.forEach { (key, value) -> put(key, value) }; put("ifPlaybackId", queue.playbackId) })
        }.toString()
        val result = transport.confirmed(id, json, 8_000L)
        return when {
            !current() -> RemoteCommandResult.Failed("Remote session changed")
            result?.ok == true -> RemoteCommandResult.Confirmed
            else -> RemoteCommandResult.Failed(result?.error ?: "The TV did not confirm the queue command")
        }
    }
}

fun subtitleFailureMessage(error: String?): String = when (error) {
    "no_active_playback" -> "No video is playing on the TV"
    "invalid_command" -> "The TV rejected this subtitle"
    "subtitle_unavailable" -> "The TV could not load this subtitle"
    else -> "The TV did not confirm the subtitle"
}

class AndroidTvRemoteController(id: RemoteSessionId, config: NativeRemoteConfiguration, transport: NativeRemoteTransport, scope: CoroutineScope, current: () -> Boolean = { true }) : NativeProfileRemoteController(id, config.copy(receiver = config.receiver.copy(profile = RemoteProfile.AndroidTv)), transport, scope, current)
class AppleTvRemoteController(id: RemoteSessionId, config: NativeRemoteConfiguration, transport: NativeRemoteTransport, scope: CoroutineScope, current: () -> Boolean = { true }) : NativeProfileRemoteController(id, config.copy(receiver = config.receiver.copy(profile = RemoteProfile.AppleTv)), transport, scope, current)
class DesktopRemoteController(id: RemoteSessionId, config: NativeRemoteConfiguration, transport: NativeRemoteTransport, scope: CoroutineScope, current: () -> Boolean = { true }) : NativeProfileRemoteController(id, config.copy(receiver = config.receiver.copy(profile = RemoteProfile.Desktop)), transport, scope, current)
class CliRemoteController(id: RemoteSessionId, config: NativeRemoteConfiguration, transport: NativeRemoteTransport, scope: CoroutineScope, current: () -> Boolean = { true }) : NativeProfileRemoteController(id, config.copy(receiver = config.receiver.copy(profile = RemoteProfile.Cli)), transport, scope, current)
class UnknownPlayBridgeRemoteController(id: RemoteSessionId, config: NativeRemoteConfiguration, transport: NativeRemoteTransport, scope: CoroutineScope, current: () -> Boolean = { true }) : NativeProfileRemoteController(id, config.copy(receiver = config.receiver.copy(profile = RemoteProfile.Unknown)), transport, scope, current)
