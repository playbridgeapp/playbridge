package com.playbridge.sender.cast.remote

import com.playbridge.sender.model.EndpointKey

enum class RemoteContext { Idle, Player, Browser, ScreenMirror, Unknown }
enum class RemoteProfile { AndroidTv, AppleTv, Desktop, Cli, Unknown, External }
enum class RemoteInputMode { Context, Pad, Pointer, Keyboard }
enum class RemoteMediaKind { Video, Audio, Image }
enum class RemotePlaybackState { Idle, Playing, Paused, Buffering, Error, Unknown }
enum class RemoteBrowserEngine { WebView, Gecko, Unknown }
enum class RemoteSheet { More, BrowserMore, Subtitles, AddSubtitle, Keyboard, Connections, WholeTvControl, Queue, InputPreferences, OpenTvApp }
enum class RemoteTimeDisplay { Duration, Remaining }

data class RemoteSessionId(val endpoint: EndpointKey, val generation: Long)
data class RemoteMediaId(
    val session: RemoteSessionId?, val playbackId: String?, val itemId: String?, val mediaGeneration: Long,
)
data class RemoteReceiver(val endpoint: EndpointKey, val name: String, val profile: RemoteProfile)

sealed interface RemoteConnection {
    data object Disconnected : RemoteConnection
    data class Connecting(val name: String) : RemoteConnection
    data class Connected(val name: String, val secure: Boolean = false) : RemoteConnection
    data class Reconnecting(val attempt: Int, val maximum: Int, val seconds: Int) : RemoteConnection
    data class Pairing(val name: String, val phase: Phase, val attemptsLeft: Int = 3, val lastCodeWrong: Boolean = false) : RemoteConnection {
        enum class Phase { CodeInput, Verifying, Approval, Denied }
    }
    data class Failed(val message: String, val kind: Kind = Kind.Error, val name: String = "TV") : RemoteConnection {
        enum class Kind { Error, Auth, Pin }
    }
}

val RemoteConnection.ready: Boolean get() = this is RemoteConnection.Connected

data class RemotePlayback(
    val mediaId: RemoteMediaId? = null,
    val title: String? = null,
    val mediaKind: RemoteMediaKind = RemoteMediaKind.Video,
    val reportedState: RemotePlaybackState = RemotePlaybackState.Unknown,
    val reportedPositionMs: Long = 0L,
    val displayedState: RemotePlaybackState = reportedState,
    val displayedPositionMs: Long = reportedPositionMs,
    val durationMs: Long = 0L,
    val isLive: Boolean = false,
    val seekable: Support = Support.Unknown,
    val observedAtMs: Long = 0L,
) {
    val reportedPlaying: Boolean get() = reportedState in setOf(RemotePlaybackState.Playing, RemotePlaybackState.Buffering)
    val displayedPlaying: Boolean get() = displayedState in setOf(RemotePlaybackState.Playing, RemotePlaybackState.Buffering)
    val active: Boolean get() = reportedState in setOf(RemotePlaybackState.Playing, RemotePlaybackState.Paused, RemotePlaybackState.Buffering)
}

data class RemoteQueueItem(
    val index: Int, val title: String, val itemId: String? = null,
    val mediaKind: RemoteMediaKind = RemoteMediaKind.Video, val season: Int? = null,
    val episode: Int? = null, val imdbId: String? = null, val bingeGroup: String? = null, val tmdbId: String? = null,
)
data class RemoteQueueState(
    val playbackId: String? = null, val revision: Long = 0L, val currentItemId: String? = null,
    val currentIndex: Int = 0, val items: List<RemoteQueueItem> = emptyList(), val phoneOwned: Boolean = false,
)
data class RemoteTrack(val id: String, val name: String, val selected: Boolean, val type: String? = null)
data class RemoteTracks(
    val video: List<RemoteTrack> = emptyList(), val audio: List<RemoteTrack> = emptyList(),
    val subtitles: List<RemoteTrack> = emptyList(),
)
data class RemotePlayerSettings(
    val speed: Float? = null, val scaling: ScalingMode? = null, val audioBoost: Boolean? = null,
    val subtitleOffsetMs: Long? = null, val engine: String? = null, val qualityMaxHeight: Int? = null,
    val currentVideoHeight: Int? = null, val loopEnabled: Boolean? = null,
    val availability: Map<PlayerSetting, Support> = emptyMap(),
)
data class RemoteBrowserState(
    val engine: RemoteBrowserEngine = RemoteBrowserEngine.Unknown,
    val fullscreen: Boolean? = null, val muted: Boolean? = null, val selectedVideoId: String? = null,
    val url: String? = null,
)
data class RemoteMirrorState(
    val active: Boolean = false, val sessionId: String? = null, val phase: String = "IDLE",
    val message: String? = null, val deviceAudioRequested: Boolean = false,
    val audioStatus: String = "DISABLED", val audioMessage: String? = null,
)
data class LinkedWebsiteState(val active: Boolean = false, val controllerName: String? = null)
data class RemotePreferences(
    val buttonPad: Boolean = false, val timeDisplay: RemoteTimeDisplay = RemoteTimeDisplay.Duration,
    val learnedGestures: Set<String> = emptySet(),
    val modesByContext: Map<RemoteContext, RemoteInputMode> = mapOf(
        RemoteContext.Player to RemoteInputMode.Context, RemoteContext.Browser to RemoteInputMode.Pointer,
    ),
)
data class RemotePendingState(val seek: Boolean = false, val playing: Boolean = false, val subtitleRequests: Set<String> = emptySet())
data class RemoteNotice(val message: String)

/** Observation sequence advances even when the legacy StateFlow deduplicates identical status ticks. */
data class RemoteObservation(
    val sequence: Long = 0L, val atMs: Long = 0L, val playbackSequence: Long = 0L,
    val playbackAtMs: Long = 0L, val mediaGeneration: Long = 0L, val settingsReported: Boolean = false,
    val browserMuted: Boolean? = null,
    val contextSequence: Long = 0L,
    val settingsFields: Set<String> = emptySet(),
    val controlFailureVersion: Long = 0L,
)

data class RemoteUiState(
    val sessionId: RemoteSessionId? = null, val receiver: RemoteReceiver? = null,
    val connection: RemoteConnection = RemoteConnection.Disconnected,
    val context: RemoteContext = RemoteContext.Unknown,
    val capabilities: RemoteCapabilities = RemoteCapabilities(),
    val playback: RemotePlayback? = null, val queue: RemoteQueueState? = null,
    val queueProgress: Map<String, RemoteQueueProgress> = emptyMap(),
    val tracks: RemoteTracks = RemoteTracks(), val settings: RemotePlayerSettings = RemotePlayerSettings(),
    val browser: RemoteBrowserState? = null, val mirror: RemoteMirrorState? = null,
    val inputMode: RemoteInputMode = RemoteInputMode.Context,
    val linkedWebsite: LinkedWebsiteState? = null, val preferences: RemotePreferences = RemotePreferences(),
    val pending: RemotePendingState = RemotePendingState(), val sheet: RemoteSheet? = null,
    val notice: RemoteNotice? = null,
    val fullControl: FullControlStatus? = null,
)
data class RemoteReceiverSnapshot(val state: RemoteUiState = RemoteUiState(), val observation: RemoteObservation = RemoteObservation())
