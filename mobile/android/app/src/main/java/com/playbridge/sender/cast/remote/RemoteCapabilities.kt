package com.playbridge.sender.cast.remote

enum class Support { Supported, Unsupported, Unknown }
enum class ControlScope { None, PlayBridgeActivity, WholeDevice }
enum class VolumeScope { None, System, ReceiverDevice, Renderer, Player, SystemWithPlayerFallback }
data class TransportCapabilities(
    val playPause: Support = Support.Unknown, val stop: Support = Support.Unknown,
    val seekAbsolute: Support = Support.Unknown, val seekRelative: Support = Support.Unknown,
)
data class PointerCapabilities(
    val move: Support = Support.Unknown, val click: Support = Support.Unknown,
    val drag: Support = Support.Unknown, val scroll: Support = Support.Unknown,
    val zoom: Support = Support.Unknown, val rotate: Support = Support.Unknown,
    val anchor: Support = Support.Unknown, val reset: Support = Support.Unknown,
)
data class TextCapabilities(val replace: Support = Support.Unknown, val delete: Support = Support.Unknown, val enter: Support = Support.Unknown)
data class BrowserCapabilities(val actions: Set<BrowserAction> = emptySet(), val fullscreen: Support = Support.Unknown, val launch: Support = Support.Unknown, val managers: Support = Support.Unknown)
data class QueueCapabilities(
    val jumpIndex: Support = Support.Unknown, val jumpItem: Support = Support.Unknown,
    val remove: Support = Support.Unknown, val move: Support = Support.Unknown, val phoneOwned: Boolean = false,
)
data class TrackCapabilities(val audio: Support = Support.Unknown, val subtitle: Support = Support.Unknown, val addUrl: Support = Support.Unknown, val addResource: Support = Support.Unknown)
data class VolumeCapabilities(
    val adjust: Support = Support.Unknown, val absolute: Support = Support.Unknown,
    val scope: VolumeScope = VolumeScope.None, val reportedPercent: Int? = null,
    val level: Int? = null, val maximum: Int? = null, val muted: Boolean? = null,
)

data class RemoteCapabilities(
    val transport: TransportCapabilities = TransportCapabilities(), val keys: Set<RemoteKey> = emptySet(),
    val keyScope: ControlScope = ControlScope.None, val pointer: PointerCapabilities = PointerCapabilities(),
    val text: TextCapabilities = TextCapabilities(), val browser: BrowserCapabilities = BrowserCapabilities(),
    val queue: QueueCapabilities = QueueCapabilities(), val tracks: TrackCapabilities = TrackCapabilities(),
    val settings: Set<PlayerSetting> = emptySet(), val engines: Set<String> = emptySet(),
    val volume: VolumeCapabilities = VolumeCapabilities(), val contextReporting: Set<RemoteContext> = emptySet(),
)

/** Ads establish a profile, never a device name or the broad CastTarget.REMOTE flag. */
fun detectRemoteProfile(players: List<String>, features: Set<String>): RemoteProfile = when {
    "exo" in players -> RemoteProfile.AndroidTv
    "avplayer" in players -> RemoteProfile.AppleTv
    "internal_mpv" in players && "subtitle_resource_add_v1" in features -> RemoteProfile.Desktop
    "internal_mpv" in players && "progress_webhook_v1" in features -> RemoteProfile.Cli
    else -> RemoteProfile.Unknown
}

fun nativeRemoteCapabilities(
    profile: RemoteProfile, context: RemoteContext, media: RemoteMediaKind,
    players: List<String> = emptyList(), features: Set<String> = emptySet(),
    settings: RemotePlayerSettings = RemotePlayerSettings(), browserEngine: RemoteBrowserEngine = RemoteBrowserEngine.Unknown,
    observedTracks: RemoteTracks = RemoteTracks(), observedQueue: RemoteQueueState? = null,
): RemoteCapabilities {
    val yes = Support.Supported
    val no = Support.Unsupported
    val player = context == RemoteContext.Player
    val browser = context == RemoteContext.Browser && profile == RemoteProfile.AndroidTv
    val image = media == RemoteMediaKind.Image
    val android = profile == RemoteProfile.AndroidTv
    val desktop = profile == RemoteProfile.Desktop
    val apple = profile == RemoteProfile.AppleTv
    val known = android || desktop || apple || profile == RemoteProfile.Cli
    fun supported(value: Boolean) = if (value) yes else no
    val keys = when {
        android && player -> buildSet {
            addAll(setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok, RemoteKey.Back))
            if ("player_home_v1" in features) add(RemoteKey.Home)
        }
        browser -> setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok, RemoteKey.Back, RemoteKey.Home, RemoteKey.Enter)
        apple && player -> setOf(RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok)
        else -> emptySet()
    }
    val transforms = player && image && (android || desktop)
    val supportedSettings = when {
        player && android && !image -> PlayerSetting.entries.filter { settings.availability[it] != no }.toSet()
        player && apple && !image -> setOf(PlayerSetting.Engine, PlayerSetting.Loop)
        else -> emptySet()
    }
    val resource = player && "subtitle_resource_add_v1" in features && (known || profile == RemoteProfile.Unknown)
    val observedFallback = player && profile == RemoteProfile.Unknown
    val tracksKnown = player && (android || desktop || apple)
    val queueFeature = features.containsAll(setOf("queue_crud_v1", "stable_item_ids", "command_results"))
    return RemoteCapabilities(
        transport = TransportCapabilities(
            playPause = supported(player && (known || profile == RemoteProfile.Unknown) || browser),
            stop = supported(player), seekAbsolute = supported(player && !image || browser), seekRelative = supported(player && !image || browser),
        ),
        keys = keys, keyScope = if (keys.isEmpty()) ControlScope.None else ControlScope.PlayBridgeActivity,
        pointer = PointerCapabilities(
            move = supported(browser || transforms), click = supported(browser), drag = supported(browser),
            scroll = supported(browser || transforms),
            zoom = if (transforms || browser && browserEngine != RemoteBrowserEngine.Gecko) yes else no,
            rotate = supported(transforms), anchor = supported(transforms), reset = supported(transforms),
        ),
        text = TextCapabilities(supported(browser), no, supported(browser)),
        browser = BrowserCapabilities(if (browser) BrowserAction.entries.toSet() else emptySet(), supported(browser), supported(android), supported(browser)),
        queue = QueueCapabilities(
            supported(player && (known || observedQueue != null)), supported(player && queueFeature),
            supported(player && queueFeature), supported(player && queueFeature),
        ),
        tracks = TrackCapabilities(
            supported(tracksKnown || observedFallback && observedTracks.audio.isNotEmpty()),
            supported(tracksKnown || observedFallback && observedTracks.subtitles.isNotEmpty()),
            supported(tracksKnown || resource), supported(resource),
        ),
        settings = supportedSettings, engines = if (PlayerSetting.Engine in supportedSettings) players.toSet() else emptySet(),
        volume = VolumeCapabilities(supported(android || desktop), no, when {
            android -> VolumeScope.System
            desktop -> VolumeScope.SystemWithPlayerFallback
            else -> VolumeScope.None
        }),
        contextReporting = when (profile) {
            RemoteProfile.AndroidTv -> setOf(RemoteContext.Idle, RemoteContext.Player, RemoteContext.Browser, RemoteContext.ScreenMirror)
            RemoteProfile.Desktop -> setOf(RemoteContext.Idle, RemoteContext.Player, RemoteContext.ScreenMirror)
            RemoteProfile.AppleTv, RemoteProfile.Cli -> setOf(RemoteContext.Idle, RemoteContext.Player)
            else -> emptySet()
        },
    )
}
