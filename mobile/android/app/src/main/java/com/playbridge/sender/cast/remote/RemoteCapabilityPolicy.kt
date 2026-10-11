package com.playbridge.sender.cast.remote

import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey

data class RemoteViewport(
    val widthDp: Int = 360, val heightDp: Int = 800, val landscape: Boolean = false,
    val fontScale: Float = 1f, val insetDp: Int = 0, val minimumInputHeightDp: Int = 160,
)
data class RemoteControlAvailability(val enabled: Boolean, val reason: String? = null)
data class RemoteNavSpec(
    val back: RemoteControlAvailability, val home: RemoteControlAvailability,
    val keys: RemoteControlAvailability, val volume: RemoteControlAvailability,
)
data class RemoteTransportSpec(val visible: Boolean, val enabled: Boolean, val seek: Boolean, val playPause: Boolean, val previous: Int?, val next: Int?)
data class RemoteTrackChipSpec(val audio: Boolean, val subtitles: Boolean, val more: Boolean)
data class RemoteLayoutSpec(
    val header: Boolean = true, val navLayer: Boolean = true,
    val modes: List<RemoteInputMode>, val selectedMode: RemoteInputMode, val showModeRow: Boolean,
    val nav: RemoteNavSpec, val transport: RemoteTransportSpec,
    val idle: Boolean, val info: Boolean, val wave: Boolean, val tracks: RemoteTrackChipSpec,
    val browserRow: Boolean, val showQueue: Boolean, val columns: Int = 1,
)

/** Stage-1 geometry and affordances, including its Android player pointer tab, remain unchanged.
 * Granular execution capabilities are separate: a displayed legacy affordance is not proof of a route.
 * Viewport is deliberately not used to introduce the stage-3 adaptive layout early. */
@Suppress("UNUSED_PARAMETER")
fun resolveRemoteLayout(state: RemoteUiState, viewport: RemoteViewport): RemoteLayoutSpec {
    val external = state.receiver?.profile == RemoteProfile.External
    val android = state.receiver?.profile == RemoteProfile.AndroidTv
    val context = state.context
    val player = context == RemoteContext.Player
    val browser = context == RemoteContext.Browser
    val idle = context !in setOf(RemoteContext.Player, RemoteContext.Browser)
    val playback = state.playback
    val image = playback?.mediaKind == RemoteMediaKind.Image
    val audio = playback?.mediaKind == RemoteMediaKind.Audio
    val keys = state.capabilities.keys
    val padKeys = setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok)
    val hasPad = keys.containsAll(padKeys)
    val pointer = !external && (!idle && android || !idle && image && state.capabilities.pointer.move == Support.Supported)
    val modes = buildList {
        add(RemoteInputMode.Context)
        if (hasPad && (!idle || external)) add(RemoteInputMode.Pad)
        if (pointer) add(RemoteInputMode.Pointer)
        if (!external && browser && state.capabilities.text.replace == Support.Supported) add(RemoteInputMode.Keyboard)
    }
    fun enabled(route: Boolean, reason: String): RemoteControlAvailability =
        RemoteControlAvailability(state.connection.ready && route, if (state.connection.ready && route) null else reason)
    val queue = state.queue
    val indices = queue?.items.orEmpty().map { it.index }
    val current = indices.indexOf(queue?.currentIndex)
    return RemoteLayoutSpec(
        modes = modes, selectedMode = state.inputMode.takeIf { it in modes } ?: RemoteInputMode.Context,
        showModeRow = modes.size > 1,
        nav = RemoteNavSpec(
            enabled(RemoteKey.Back in keys && (!idle || external), "TV Back unavailable: no remote route"),
            enabled(RemoteKey.Home in keys && (browser || external), "TV Home unavailable: no Home route; full TV control is coming later"),
            enabled(!external && browser && state.capabilities.text.replace == Support.Supported, "Keys unavailable: text input requires the TV browser"),
            enabled(state.capabilities.volume.adjust == Support.Supported, "TV volume unavailable: no volume route"),
        ),
        transport = RemoteTransportSpec(
            visible = player, enabled = state.connection.ready,
            seek = player && !image && playback?.isLive != true && playback?.seekable == Support.Supported,
            playPause = player && (!image || playback.durationMs > 0L),
            previous = if (!external && current > 0) indices[current - 1] else null,
            next = if (!external && current >= 0) indices.getOrNull(current + 1) else null,
        ),
        idle = idle, info = player || browser && playback?.active == true,
        wave = player && (!image || playback.durationMs > 0L) || browser && playback?.active == true,
        tracks = RemoteTrackChipSpec(player && !external && !image && state.tracks.audio.isNotEmpty(), player && !external && !image && !audio, player),
        browserRow = browser, showQueue = player && !external && indices.size > 1,
    )
}

/** Compatibility inputs for the stage-1/external entry while 2b migrates its state source. */
internal fun stage1PolicyState(
    context: String, external: Boolean, remote: Boolean, pointer: Boolean = remote,
    volume: Boolean = false, connected: Boolean = true, seekable: Boolean = false,
    live: Boolean = false, image: Boolean = false, indices: List<Int> = emptyList(), currentIndex: Int = 0,
): RemoteUiState {
    val typedContext = remoteContextFromWire(context)
    val yes = Support.Supported
    val no = Support.Unsupported
    val profile = when { external -> RemoteProfile.External; remote && pointer -> RemoteProfile.AndroidTv; else -> RemoteProfile.Unknown }
    return RemoteUiState(
        receiver = RemoteReceiver(EndpointKey(CastProtocol.PLAYBRIDGE, "compatibility"), "TV", profile),
        context = typedContext, connection = if (connected) RemoteConnection.Connected("TV") else RemoteConnection.Disconnected,
        capabilities = RemoteCapabilities(
            keys = if (remote) RemoteKey.entries.toSet() else emptySet(),
            pointer = PointerCapabilities(move = if (pointer) yes else no),
            text = TextCapabilities(replace = if (remote) yes else no),
            volume = VolumeCapabilities(adjust = if (volume) yes else no),
        ),
        playback = RemotePlayback(mediaKind = if (image || pointer && !remote) RemoteMediaKind.Image else RemoteMediaKind.Video, seekable = if (seekable) yes else no, isLive = live),
        queue = RemoteQueueState(currentIndex = currentIndex, items = indices.map { RemoteQueueItem(it, "") }),
    )
}

internal fun remoteContextFromWire(context: String): RemoteContext = when (context) {
    "idle" -> RemoteContext.Idle
    "player" -> RemoteContext.Player
    "browser", "browser_external" -> RemoteContext.Browser
    "screen_mirror" -> RemoteContext.ScreenMirror
    else -> RemoteContext.Unknown
}
