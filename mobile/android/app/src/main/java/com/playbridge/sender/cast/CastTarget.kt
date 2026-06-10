package com.playbridge.sender.cast

import kotlinx.coroutines.flow.Flow

/**
 * A transport-agnostic playback target — the seam that lets the cast sheet,
 * now-playing UI, and transport controls drive playback without knowing whether
 * the receiver is PlayBridge's own APK (over WebSocket) or a third-party
 * DLNA/UPnP renderer (over SOAP + the local proxy).
 *
 * Implementations:
 *  - `NativeCastTarget` — wraps the existing WS path (full capabilities).
 *  - `DlnaCastTarget`   — wraps AVTransport + [LocalProxyServer] (reduced set).
 *
 * This is the foundation for future non-APK targets (Chromecast, AirPlay) too.
 */
interface CastTarget {
    /** Stable identity for dedup/reconnect — native uuid or DLNA UDN. */
    val id: String

    /** Human-readable name shown in the device list. */
    val name: String

    val kind: TargetKind

    /** What this target can do — drives UI gating (greys out unsupported controls). */
    val capabilities: Set<Capability>

    /** Load media and begin playback from the start (the cast hand-off). */
    suspend fun load(media: MediaItem)

    /** Resume from the current position (does NOT reload — see DLNA AVTransport semantics). */
    suspend fun play()

    suspend fun pause()

    suspend fun stop()

    suspend fun seekTo(positionMs: Long)

    /** No-op when [Capability.VOLUME] is absent. */
    suspend fun setVolume(percent: Int)

    /**
     * Playback status. Native targets push it (over WS); DLNA targets poll
     * `GetPositionInfo`/`GetTransportInfo`. Cold flow — polling/listeners stop
     * when collection is cancelled.
     */
    fun status(): Flow<PlaybackStatus>

    /** Release transport resources (stop polling, free the proxy entry, etc.). */
    fun release()
}

enum class TargetKind { NATIVE, DLNA }

enum class Capability {
    /** Set a new media URI. */
    LOAD,
    PLAY_PAUSE,
    SEEK,
    STOP,
    VOLUME,

    /** Position + transport state are observable (drives the now-playing scrubber). */
    NOW_PLAYING,

    // --- Native-only surfaces; DLNA targets omit these so the UI can grey them out. ---
    /** D-pad / touchpad / keyboard remote. */
    REMOTE,

    /** Cast a browser page / drive the TV browser. */
    BROWSER,

    /** Playlist / queue / episode-jump. */
    QUEUE,

    SUBTITLES,
}

/**
 * What to play. Transport-agnostic: [url] may be an `http(s)://` web stream (with
 * [headers]) or a `content://` local file — the target/proxy inspects the scheme.
 * For DLNA, web streams and local files are both served through the local proxy.
 */
data class MediaItem(
    val url: String,
    /** Request headers for web streams (Referer/Cookie/User-Agent). Ignored for local files. */
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val title: String? = null,
    val artUrl: String? = null,
    val subtitles: List<SubtitleRef> = emptyList(),
    /** Known duration (e.g. from MediaStore for local files); 0 if unknown. */
    val durationMs: Long = 0L,
    /** Resume point — start playback here (native: EXTRA_START_POSITION; DLNA: Seek after load). */
    val startPositionMs: Long = 0L,
    /** Library identity (tmdb/imdb/season/episode) for watch-progress tracking; null = untracked. */
    val visualMetadata: playbridge.VisualMetadata? = null,
)

data class SubtitleRef(
    val url: String,
    val label: String? = null,
    val language: String? = null,
)

data class PlaybackStatus(
    val state: PlaybackState,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** Live stream (no fixed duration) — UI shows a LIVE indicator instead of a seekbar. */
    val isLive: Boolean = false,
)

enum class PlaybackState { IDLE, BUFFERING, PLAYING, PAUSED, STOPPED, ERROR }
