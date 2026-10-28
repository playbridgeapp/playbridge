package com.playbridge.sender.cast.remote

import com.playbridge.sender.connection.ReceiverEngine
import com.playbridge.sender.connection.ReceiverVolume

/** Detailed authority where the legacy UI capability model groups distinct wire operations. */
class DescriptorRemotePolicy internal constructor(
    internal val operations: ReceiverEngine,
    internal val browserSurface: Boolean,
    internal val mediaTransport: Boolean,
    val launchEngines: Set<String>,
    internal val volume: ReceiverVolume?,
    internal val receiverVolume: VolumeCapabilities,
    internal val reportedVolumeScope: String?,
) {
    override fun toString(): String = "DescriptorRemotePolicy"
}

private val knownPlayerEngines = setOf("exo", "mpv", "avplayer", "internal_mpv")
private val knownBrowserEngines = setOf("webview", "gecko")

/** One resolver, called by the native controller before UI and execution receive the snapshot. */
internal fun resolveReceiverRemotePolicy(state: RemoteUiState, players: List<String>, features: Set<String>): RemoteUiState {
    val protocol = state.receiverProtocol
    val descriptor = protocol.descriptor?.takeIf { it.version == 1 && !protocol.malformedV1 }
    if (descriptor == null && !protocol.malformedV1) return state.copy(nativePolicy = null, capabilities = nativeRemoteCapabilities(
        state.receiver?.profile ?: RemoteProfile.Unknown, state.context, state.playback?.mediaKind ?: RemoteMediaKind.Video,
        players, features, state.settings, state.browser?.engine ?: RemoteBrowserEngine.Unknown, state.tracks, state.queue,
    ))
    val player = state.context == RemoteContext.Player
    val browser = state.context == RemoteContext.Browser
    val surface = when { player -> "player"; browser -> "browser"; state.context == RemoteContext.Idle -> "app"; else -> null }
    val engines = descriptor?.surfaces?.get(surface)?.engines.orEmpty()
    val reported = protocol.contextEngine?.takeIf { player || browser } ?: if (player) state.settings.engine else if (browser) when (state.browser?.engine) {
        RemoteBrowserEngine.WebView -> "webview"
        RemoteBrowserEngine.Gecko -> "gecko"
        else -> state.settings.engine?.takeIf { it in engines }
    } else null
    val candidate = reported ?: if (player) players.filter { it in engines }.distinct().singleOrNull() else null
    // An absent/unrecognized active engine selects the intersection of ALL available engines
    // on this surface. Never union engines or import operations from a different surface.
    val ops = engines[candidate] ?: intersectEngines(engines.values)
    val image = state.playback?.mediaKind == RemoteMediaKind.Image
    val video = state.playback?.mediaKind == RemoteMediaKind.Video
    val seek = !image && state.playback?.isLive != true && state.playback?.seekable == Support.Supported
    val mediaTransport = player && (!image || (state.playback?.durationMs ?: 0L) > 0L) ||
        browser && state.playback?.active == true && (state.playback.durationMs > 0L)
    fun support(value: Boolean) = if (value) Support.Supported else Support.Unsupported
    val settings = if (player && !image) PlayerSetting.entries.filter { setting ->
        val operation = when (setting) {
            PlayerSetting.Quality -> "quality"; PlayerSetting.Speed -> "speed"; PlayerSetting.Scaling -> "scaling"
            PlayerSetting.AudioBoost -> "audio_boost"; PlayerSetting.SubtitleOffset -> "subtitle_offset"
            PlayerSetting.Engine -> "engine"; PlayerSetting.Loop -> "loop"
        }
        val available = when (setting) {
            PlayerSetting.SubtitleOffset -> protocol.settingsAvailability.subtitleOffsetAvailable
            PlayerSetting.Engine -> protocol.settingsAvailability.engineAvailable
            PlayerSetting.Loop -> protocol.settingsAvailability.loopAvailable
            else -> null
        }
        operation in ops.settings && available != false && state.settings.availability[setting] != Support.Unsupported &&
            (setting != PlayerSetting.Loop || state.playback?.isLive != true)
    }.toSet() else emptySet()
    val volume = descriptor?.volume?.takeIf { it.scope in setOf("system", "app") }
    val volumeOps = volume?.operations.orEmpty()
    val report = protocol.volume?.takeIf { it.scope == volume?.scope ||
        volume?.scope == "system" && volume.fallbackScope == "app" && "relative_step" in volumeOps && it.scope == "app" }
    val level = report?.level?.takeIf { "level_report" in volumeOps }?.let { kotlin.math.round(it * 100).toInt().coerceIn(0, 100) }
    val muted = report?.muted?.takeIf { "mute_report" in volumeOps }
    val keys = RemoteKey.entries.filter { it in setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right,
        RemoteKey.Ok, RemoteKey.Back, RemoteKey.Home, RemoteKey.Enter) && it.wireValue in ops.keys }.toMutableSet()
    if (browser && "enter" in ops.text) keys += RemoteKey.Enter
    if (browser && "delete" in ops.text) keys += RemoteKey.Delete
    if ("mute_toggle" in volumeOps) keys += RemoteKey.Mute
    val imageOps = ops.imageTransforms.takeIf { player && image }.orEmpty()
    val pointer = ops.pointer.takeIf { browser }.orEmpty()
    val launches = descriptor?.surfaces?.get("browser")?.engines.orEmpty().filter { (id, engine) ->
        id in knownBrowserEngines && "launch" in engine.browser
    }.keys
    val guardedQueue = features.containsAll(setOf("queue_crud_v1", "stable_item_ids", "command_results"))
    val playPause = "toggle" in ops.transport || (if (state.playback?.displayedPlaying == true) "pause" else "play") in ops.transport
    val capabilities = RemoteCapabilities(
        transport = TransportCapabilities(support(mediaTransport && playPause), support(player && "stop" in ops.transport),
            support((player || browser) && seek && "seek_absolute" in ops.transport),
            support((player || browser) && seek && "seek_relative" in ops.transport)),
        keys = keys, keyScope = if (keys.isEmpty()) ControlScope.None else ControlScope.PlayBridgeActivity,
        pointer = PointerCapabilities(
            support("move" in pointer || "pan" in imageOps), support("click" in pointer),
            support(pointer.containsAll(setOf("down", "move", "up"))), support("scroll" in pointer || "scroll" in imageOps),
            support("zoom" in pointer || "zoom" in imageOps), support("rotate" in imageOps),
            support("anchor" in imageOps), support("reset" in imageOps),
        ),
        text = TextCapabilities(support(browser && "replace" in ops.text), support(browser && "delete" in ops.text), support(browser && "enter" in ops.text)),
        browser = BrowserCapabilities(
            BrowserAction.entries.filter { action -> browser && when (action) {
                BrowserAction.Forward -> "forward"; BrowserAction.Reload -> "reload"
                BrowserAction.CycleSource -> "cycle_source"; BrowserAction.Unmute -> "unmute"
            } in ops.browser }.toSet(), support(browser && "fullscreen" in ops.browser), support(launches.isNotEmpty()),
            support(browser && ops.browser.any { it in setOf("manage_user_scripts", "manage_user_agent") }),
        ),
        queue = QueueCapabilities(support(player && "jump_index" in ops.queue), support(player && guardedQueue && "jump_item" in ops.queue),
            support(player && guardedQueue && "remove" in ops.queue), support(player && guardedQueue && "move" in ops.queue)),
        tracks = TrackCapabilities(support(player && !image && "audio_select" in ops.tracks),
            support(player && video && "subtitle_select" in ops.tracks), support(player && video && "subtitle_url_add" in ops.tracks),
            support(player && video && "subtitle_resource_add" in ops.tracks && "subtitle_resource_add_v1" in features)),
        settings = settings,
        engines = if (PlayerSetting.Engine in settings) descriptor?.surfaces?.get("player")?.engines.orEmpty().keys.intersect(knownPlayerEngines) else emptySet(),
        volume = VolumeCapabilities(support("relative_step" in volumeOps), support("absolute" in volumeOps), when {
            volume?.scope == "app" -> VolumeScope.Player
            volume?.scope == "system" && volume.fallbackScope == "app" && "relative_step" in volumeOps -> VolumeScope.SystemWithPlayerFallback
            volume?.scope == "system" -> VolumeScope.System
            else -> VolumeScope.None
        }, reportedPercent = level, level = level, maximum = if (level != null) 100 else null, muted = muted),
        contextReporting = buildSet {
            descriptor?.surfaces.orEmpty().forEach { (id, item) ->
                val reporting = if (id == surface) ops.reporting else intersectEngines(item.engines.values).reporting
                if (id in setOf("player", "browser") && "context" in reporting) {
                    add(RemoteContext.Idle)
                    if (id == "player") add(RemoteContext.Player)
                    if (id == "browser") add(RemoteContext.Browser)
                }
            }
        },
    )
    return state.copy(capabilities = capabilities, nativePolicy = DescriptorRemotePolicy(ops, browser, mediaTransport, launches, volume,
        capabilities.volume, report?.scope.takeIf { level != null || muted != null }))
}

private fun intersectEngines(engines: Collection<ReceiverEngine>): ReceiverEngine {
    fun ids(get: (ReceiverEngine) -> Set<String>): Set<String> = engines.map(get).reduceOrNull { common, next -> common intersect next }.orEmpty()
    return ReceiverEngine(ids { it.transport }, ids { it.keys }, ids { it.pointer }, ids { it.imageTransforms }, ids { it.text },
        ids { it.settings }, ids { it.tracks }, ids { it.queue }, ids { it.browser }, ids { it.reporting })
}

/** UI and executor use the same resolved authority; Full control remains a separate verified route. */
internal fun RemoteUiState.receiverActionSupported(action: RemoteAction): Boolean {
    if (fullControlSupports(action)) return true
    val policy = nativePolicy ?: return true // Legacy UI/execution keep their existing gates.
    val caps = capabilities
    return when (action) {
        RemoteAction.TogglePlayPause -> caps.transport.playPause == Support.Supported
        is RemoteAction.SetPlaying -> policy.mediaTransport && (if (action.playing) "play" else "pause") in policy.operations.transport
        is RemoteAction.SeekTo -> caps.transport.seekAbsolute == Support.Supported
        is RemoteAction.SeekBy -> caps.transport.seekRelative == Support.Supported
        RemoteAction.StopPlayback -> caps.transport.stop == Support.Supported
        is RemoteAction.Key -> action.key in caps.keys
        is RemoteAction.AdjustVolume -> caps.volume.adjust == Support.Supported
        is RemoteAction.SetVolume -> caps.volume.absolute == Support.Supported
        is RemoteAction.SelectAudio -> caps.tracks.audio == Support.Supported && tracks.audio.any { it.id == action.id }
        is RemoteAction.SelectSubtitle -> caps.tracks.subtitle == Support.Supported && (tracks.subtitles.any { it.id == action.id } || action.id in com.playbridge.sender.cast.subtitleOffIds)
        is RemoteAction.AddSubtitle -> when {
            action.legacyUrlOnly -> caps.tracks.addUrl == Support.Supported && action.resource.headers.isEmpty()
            action.resource.headers.isNotEmpty() -> caps.tracks.addResource == Support.Supported
            else -> caps.tracks.addUrl == Support.Supported || caps.tracks.addResource == Support.Supported
        }
        is RemoteAction.ReplaceText -> caps.text.replace == Support.Supported
        RemoteAction.DeleteText -> caps.text.delete == Support.Supported
        is RemoteAction.PointerMove -> caps.pointer.move == Support.Supported
        RemoteAction.PointerClick -> caps.pointer.click == Support.Supported
        RemoteAction.PointerDown, RemoteAction.PointerUp -> caps.pointer.drag == Support.Supported
        RemoteAction.PointerGestureEnd -> hasPointerRoute()
        is RemoteAction.PointerScroll -> caps.pointer.scroll == Support.Supported
        is RemoteAction.ImageZoom -> caps.pointer.zoom == Support.Supported
        is RemoteAction.ImageRotate -> caps.pointer.rotate == Support.Supported
        is RemoteAction.ImageAnchor -> caps.pointer.anchor == Support.Supported
        RemoteAction.ResetImageTransform -> caps.pointer.reset == Support.Supported
        is RemoteAction.Browser -> action.action in caps.browser.actions
        is RemoteAction.SetBrowserFullscreen -> caps.browser.fullscreen == Support.Supported
        is RemoteAction.OpenTvBrowser -> caps.browser.launch == Support.Supported && (action.engine == null || action.engine in policy.launchEngines)
        RemoteAction.ManageUserScripts, is RemoteAction.UpdateUserScript -> policy.browserSurface && "manage_user_scripts" in policy.operations.browser
        RemoteAction.ManageUserAgent, is RemoteAction.UpdateUserAgent -> policy.browserSurface && "manage_user_agent" in policy.operations.browser
        is RemoteAction.SwitchEngine -> PlayerSetting.Engine in caps.settings && action.engineId in caps.engines
        is RemoteAction.SetLoop -> PlayerSetting.Loop in caps.settings
        is RemoteAction.SetSpeed -> PlayerSetting.Speed in caps.settings
        is RemoteAction.SetScaling -> PlayerSetting.Scaling in caps.settings
        is RemoteAction.SetVideoQuality -> PlayerSetting.Quality in caps.settings
        RemoteAction.ToggleAudioBoost -> PlayerSetting.AudioBoost in caps.settings
        is RemoteAction.AdjustSubtitleOffset -> PlayerSetting.SubtitleOffset in caps.settings
        is RemoteAction.JumpToEpisode -> if (action.itemId != null) caps.queue.jumpItem == Support.Supported else caps.queue.jumpIndex == Support.Supported
        is RemoteAction.RemoveQueueItem -> caps.queue.remove == Support.Supported
        is RemoteAction.PlayNext -> caps.queue.move == Support.Supported
        else -> true
    }
}

internal fun RemoteUiState.queueJump(index: Int, itemId: String?): RemoteAction.JumpToEpisode =
    RemoteAction.JumpToEpisode(index, if (nativePolicy == null || capabilities.queue.jumpItem == Support.Supported) itemId else null)

internal fun RemoteUiState.volumeLabel(): String =
    // Plain "Volume" for the device's own volume; "App volume" only when just the player is controlled.
    if (!fullControlSupports(RemoteAction.AdjustVolume(1)) && capabilities.volume.scope == VolumeScope.Player) "App volume" else "Volume"

internal fun RemoteUiState.hasPointerRoute(): Boolean = capabilities.pointer.run {
    if (nativePolicy == null) move == Support.Supported else
        listOf(move, click, drag, scroll, zoom, rotate, anchor, reset).any { it == Support.Supported }
}

internal fun RemoteUiState.volumeReportLabel(): String =
    if (!fullControlSupports(RemoteAction.AdjustVolume(1)) && nativePolicy?.reportedVolumeScope == "app") "App volume" else volumeLabel()
