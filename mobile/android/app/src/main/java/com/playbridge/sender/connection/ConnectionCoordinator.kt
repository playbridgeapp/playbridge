package com.playbridge.sender.connection

import android.util.Log
import com.playbridge.sender.cast.MediaTrack
import com.playbridge.sender.cast.TvPlaybackStatus
import com.playbridge.sender.cast.TvPlayerSettings
import com.playbridge.sender.cast.remote.RemoteObservation
import android.os.SystemClock
import com.playbridge.sender.library.PlaylistEpisode
import com.playbridge.sender.library.PlaylistUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

data class QueueCommandResult(
    val ok: Boolean,
    val error: String?,
)

/** Result of asking the connected TV for its persisted diagnostic log. */
sealed class TvLogsResult {
    /** [enabled] is false when logging is off on the TV; [truncated] means older lines were left out. */
    data class Logs(val enabled: Boolean, val text: String, val truncated: Boolean) : TvLogsResult()
    /** The TV predates paired log transfer (no `diagnostic_logs_v1`). */
    data object Unsupported : TvLogsResult()
    /** Not connected, or the TV did not answer in time. */
    data object Unavailable : TvLogsResult()
}

/** Fetches (or, with `clear`, deletes) the TV log over the paired WSS connection. */
typealias TvLogsSource = suspend (clear: Boolean) -> TvLogsResult

/**
 * TV browser User-Agent state, as last reported by the TV: [active] is the name of the
 * selected entry (blank = default/no override), [entries] are the name→value pairs saved
 * on the TV (for reselection — see `createUserAgentJson`/`IncomingMessage.UserAgent`).
 */
data class TvUserAgentState(
    val active: String = "",
    val entries: List<Pair<String, String>> = emptyList(),
)

/**
 * Coordinates WebSocket connection state parsing, TV playback updates, and command execution.
 * Extracts message processing out of BrowserActivity for better separation of concerns.
 */
class ConnectionCoordinator internal constructor(
    private val messages: Flow<String>,
    private val webSocketClient: WebSocketClient?,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    constructor(webSocketClient: WebSocketClient, scope: CoroutineScope) : this(
        messages = webSocketClient.messages,
        webSocketClient = webSocketClient,
        scope = scope,
        clock = { SystemClock.uptimeMillis() },
    )

    private val TAG = "ConnectionCoordinator"

    /** android.util.Log is not mocked in pure JVM unit tests. */
    private fun logd(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: RuntimeException) {
            // unit tests
        }
    }

    private fun loge(message: String, error: Throwable) {
        try {
            Log.e(TAG, message, error)
        } catch (_: RuntimeException) {
            // unit tests
        }
    }

    // TV Playback & Playlist States synced from WebSocket
    val tvActiveContext = MutableStateFlow("idle")
    val tvPlaylistState = MutableStateFlow<PlaylistUiState?>(null)
    val tvPlayback = MutableStateFlow<TvPlaybackStatus?>(null)
    val tvVideoTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tvAudioTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tvSubtitleTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tvPlayerSettings = MutableStateFlow(TvPlayerSettings())
    private val mutableRemoteObservation = MutableStateFlow(RemoteObservation())
    val remoteObservation = mutableRemoteObservation.asStateFlow()
    private val pendingQueueCommands = ConcurrentHashMap<String, CompletableDeferred<QueueCommandResult>>()
    private val pendingLogRequests = ConcurrentHashMap<String, CompletableDeferred<TvLogsResult.Logs>>()
    
    // Names of user scripts currently installed on the TV (for the management UI).
    val installedUserScripts = MutableStateFlow<List<String>>(emptyList())

    // TV browser User-Agent: which one is active (name, blank = default) + which custom
    // ones are saved on the TV (for the management UI).
    val tvUserAgentState = MutableStateFlow(TvUserAgentState())

    // TMDb Sync & Now Playing Metadata states
    val nowPlayingTvId = MutableStateFlow<Int?>(null)
    val nowPlayingSeason = MutableStateFlow<Int?>(null)
    val nowPlayingEpisodeStart = MutableStateFlow(1)

    /**
     * Player snapshots that arrived while the TV was not in the player (the paused
     * player keeps broadcasting behind the browser). They are not shown until the
     * context returns to "player". A new local playback session discards them.
     */
    private var heldPlayerSnapshots = HeldPlayerSnapshots()
    // The phone starts idle, so a player snapshot that arrives before any context
    // frame is held until the TV actually reports "player".
    private var holdingPlayerSnapshots = true

    init {
        startListening()
    }

    private fun startListening() {
        scope.launch {
            messages.collect { message ->
                try {
                    val json = org.json.JSONObject(message)
                    when (json.optString("type")) {
                        "context" -> {
                            val active = json.optString("active", "idle")
                            val previous = tvActiveContext.value
                            // Publish the new context before clearing so watch-progress
                            // sees a non-player surface and does not judge the old item.
                            tvActiveContext.value = active
                            mutableRemoteObservation.update { it.copy(contextSequence = it.contextSequence + 1L) }
                            logd("TV Active Context updated: $active")
                            // Idle always drops the player snapshots. So does every other
                            // exit from the player. Repeating the same context does not.
                            if (active == "idle" || (previous == "player" && active != "player")) {
                                clearPlayerStates()
                                heldPlayerSnapshots = HeldPlayerSnapshots()
                            }
                            if (active == "player") {
                                holdingPlayerSnapshots = false
                                applyHeldPlayerSnapshots()
                            } else {
                                holdingPlayerSnapshots = true
                            }
                        }
                        "playlist_status" -> {
                            val itemsJson = json.optJSONArray("items")
                            val episodes = buildList {
                                if (itemsJson != null) {
                                    for (i in 0 until itemsJson.length()) {
                                        val o = itemsJson.optJSONObject(i) ?: continue
                                        add(
                                            PlaylistEpisode(
                                                index = o.optInt("index", i),
                                                title = o.optString("title", "Item ${i + 1}"),
                                                season = o.optInt("season", -1).takeIf { it >= 0 },
                                                episode = o.optInt("episode", -1).takeIf { it >= 0 },
                                                imdbId = o.optString("imdbId", "").ifEmpty { null },
                                                bingeGroup = o.optString("bingeGroup", "").ifEmpty { null },
                                                mediaKind = o.optString("mediaKind", "video")
                                                    .takeIf { it in setOf("video", "audio", "image") }
                                                    ?: "video",
                                                itemId = o.optString("itemId", "").ifEmpty { null },
                                                tmdbId = o.optString("tmdbId", "").ifEmpty { null },
                                            )
                                        )
                                    }
                                }
                            }
                            val playlist = PlaylistUiState(
                                currentIndex = json.optInt("currentIndex", 0),
                                totalCount = json.optInt("totalCount", 0),
                                items = episodes,
                                playbackId = json.optString("playbackId", "").ifEmpty { null },
                                queueRevision = json.optLong("queueRevision", 0),
                                currentItemId = json.optString("currentItemId", "").ifEmpty { null },
                            )
                            if (holdingPlayerSnapshots) {
                                heldPlayerSnapshots.playlist = playlist
                                logd(
                                    "TV playlist_status held while context=${tvActiveContext.value} " +
                                        "index=${playlist.currentIndex} total=${playlist.totalCount} items=${episodes.size}",
                                )
                            } else {
                                tvPlaylistState.value = playlist
                                logd(
                                    "TV playlist_status index=${playlist.currentIndex} " +
                                        "total=${playlist.totalCount} items=${episodes.size}",
                                )
                            }
                        }
                        "status" -> {
                            // Perf: dedupe identical ticks — the TV status cadence (~1/s)
                            // otherwise emits a distinct object per tick and recomposes
                            // every collector. Raw ms precision kept so progress bars
                            // glide instead of jumping in 1s steps.
                            val next = TvPlaybackStatus(
                                state = json.optString("state", "paused"),
                                positionMs = json.optLong("position", 0L),
                                durationMs = json.optLong("duration", 0L),
                                title = json.optString("title", "").ifEmpty { null },
                                mediaKind = json.optString("mediaKind", "video")
                                    .takeIf { it in setOf("video", "audio", "image") }
                                    ?: "video",
                            )
                            // Player status carries mediaKind, playbackId, or currentItemId
                            // (createStatusJson). Browser status never does: active frames add
                            // title, muted, fullscreen, and suppressUnmute; idle frames are only
                            // type/state/position/duration. Hold the player frame so the
                            // paused player behind the browser cannot repaint the card.
                            if (isBrowserSurface(tvActiveContext.value) && isPlayerOwnedStatus(json)) {
                                heldPlayerSnapshots.playback = next
                            } else {
                                val mediaChanged = tvPlayback.value?.let { it.title != next.title || it.mediaKind != next.mediaKind } == true
                                if (next != tvPlayback.value) tvPlayback.value = next
                                mutableRemoteObservation.update { previous ->
                                    previous.copy(
                                        playbackSequence = previous.playbackSequence + 1L,
                                        mediaGeneration = previous.mediaGeneration + if (mediaChanged) 1L else 0L,
                                        playbackAtMs = clock(),
                                        browserMuted = if (json.has("muted")) json.optBoolean("muted") else null,
                                        browserFullscreen = if (json.has("fullscreen")) json.optBoolean("fullscreen") else null,
                                    )
                                }
                            }
                        }
                        "command_result" -> {
                            val requestId = json.optString("requestId", "")
                            if (requestId.isNotEmpty()) {
                                pendingQueueCommands.remove(requestId)?.complete(
                                    QueueCommandResult(
                                        ok = json.optBoolean("ok", false),
                                        error = json.optString("error", "").ifEmpty { null },
                                    ),
                                )
                            }
                        }
                        "logs" -> {
                            val requestId = json.optString("requestId", "")
                            if (requestId.isNotEmpty()) {
                                pendingLogRequests.remove(requestId)?.complete(
                                    TvLogsResult.Logs(
                                        enabled = json.optBoolean("enabled", false),
                                        text = json.optString("text", ""),
                                        truncated = json.optBoolean("truncated", false),
                                    ),
                                )
                            }
                        }
                        "tracks" -> {
                            fun parseTracks(arr: org.json.JSONArray?): List<MediaTrack> =
                                buildList {
                                    if (arr != null) {
                                        for (i in 0 until arr.length()) {
                                            val o = arr.optJSONObject(i) ?: continue
                                            add(
                                                MediaTrack(
                                                    id = o.optString("id"),
                                                    name = o.optString("name", "Track ${i + 1}"),
                                                    selected = o.optBoolean("selected", false),
                                                    type = if (o.has("type")) o.optString("type") else null
                                                )
                                            )
                                        }
                                    }
                                }
                            val video = parseTracks(json.optJSONArray("video"))
                            val audio = parseTracks(json.optJSONArray("audio"))
                            val subtitles = parseTracks(json.optJSONArray("subtitle"))
                            if (holdingPlayerSnapshots) {
                                heldPlayerSnapshots.videoTracks = video
                                heldPlayerSnapshots.audioTracks = audio
                                heldPlayerSnapshots.subtitleTracks = subtitles
                                logd("TV Audio/Subtitle tracks held while context=${tvActiveContext.value}")
                            } else {
                                tvVideoTracks.value = video
                                tvAudioTracks.value = audio
                                tvSubtitleTracks.value = subtitles
                                logd("TV Audio/Subtitle tracks updated")
                            }
                        }
                        "user_scripts" -> {
                            val arr = json.optJSONArray("names")
                            installedUserScripts.value = buildList {
                                if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
                            }
                            logd("TV user scripts: ${installedUserScripts.value}")
                        }
                        "user_agents" -> {
                            val active = json.optString("active", "")
                            val entriesJson = json.optJSONArray("entries")
                            val entries = buildList {
                                if (entriesJson != null) {
                                    for (i in 0 until entriesJson.length()) {
                                        val o = entriesJson.optJSONObject(i) ?: continue
                                        add(o.optString("name") to o.optString("value"))
                                    }
                                }
                            }
                            tvUserAgentState.value = TvUserAgentState(active, entries)
                            logd("TV user agents updated: active=$active, ${entries.size} saved")
                        }
                        "player_settings" -> {
                            val settings = TvPlayerSettings(
                                speed = json.optDouble("speed", 1.0).toFloat(),
                                scaling = json.optString("scaling", "Fit"),
                                audioBoost = json.optBoolean("audioBoost", false),
                                subtitleOffsetMs = json.optLong("subtitleOffsetMs", 0L),
                                engine = json.optString("engine", ""),
                                qualityMaxHeight = json.optInt("qualityMaxHeight", 0),
                                currentVideoHeight = json.optInt("currentVideoHeight", 0),
                                isLive = json.optBoolean("isLive", false),
                                isSeekable = json.optBoolean("isSeekable", true),
                                speedAvailable = json.optBoolean("speedAvailable", true),
                                scalingAvailable = json.optBoolean("scalingAvailable", true),
                                audioBoostAvailable = json.optBoolean("audioBoostAvailable", true),
                                qualityAvailable = json.optBoolean("qualityAvailable", false),
                                loop = if (json.has("loop")) json.optBoolean("loop") else null,
                            )
                            val fields = json.keys().asSequence().toSet()
                            if (holdingPlayerSnapshots) {
                                heldPlayerSnapshots.settings = settings
                                heldPlayerSnapshots.settingsFields = fields
                                logd("TV Player settings held while context=${tvActiveContext.value}")
                            } else {
                                tvPlayerSettings.value = settings
                                logd("TV Player settings updated")
                                mutableRemoteObservation.update { it.copy(settingsReported = true, settingsFields = fields) }
                            }
                        }
                    }
                    val type = json.optString("type")
                    val heldPlayerStatus = type == "status" && isBrowserSurface(tvActiveContext.value) && isPlayerOwnedStatus(json)
                    val heldPlayerSnapshot = holdingPlayerSnapshots && type in setOf("playlist_status", "tracks", "player_settings")
                    if (!heldPlayerStatus && !heldPlayerSnapshot && type in setOf("context", "playlist_status", "status", "tracks", "player_settings")) {
                        mutableRemoteObservation.update { it.copy(sequence = it.sequence + 1L, atMs = clock()) }
                    }
                } catch (e: Exception) {
                    loge("Error parsing WebSocket message: ${e.message}", e)
                }
            }
        }
    }

    /** TV logs travel only over the authenticated connection, answered to this phone alone. */
    suspend fun requestTvLogs(clear: Boolean, timeoutMs: Long = 20_000L): TvLogsResult {
        val client = webSocketClient ?: return TvLogsResult.Unavailable
        if (client.connectionState.value !is WebSocketClient.ConnectionState.Connected) {
            return TvLogsResult.Unavailable
        }
        if ("diagnostic_logs_v1" !in client.tvCapabilitiesState.value.features) {
            return TvLogsResult.Unsupported
        }
        val requestId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<TvLogsResult.Logs>()
        pendingLogRequests[requestId] = deferred
        return try {
            val message = if (clear) {
                com.playbridge.shared.protocol.createLogsClearJson(requestId)
            } else {
                com.playbridge.shared.protocol.createLogsQueryJson(requestId)
            }
            if (!client.send(message)) return TvLogsResult.Unavailable
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: TvLogsResult.Unavailable
        } finally {
            pendingLogRequests.remove(requestId, deferred)
        }
    }

    suspend fun sendConfirmedQueueCommand(
        requestId: String,
        command: String,
        timeoutMs: Long = 8_000L,
    ): QueueCommandResult? {
        val client = webSocketClient ?: return null
        val deferred = CompletableDeferred<QueueCommandResult>()
        check(pendingQueueCommands.putIfAbsent(requestId, deferred) == null) {
            "duplicate queue request id"
        }
        return try {
            if (!client.send(command)) return null
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            pendingQueueCommands.remove(requestId, deferred)
        }
    }

    /**
     * Begin a locally-initiated playback session: record the library identity (nulls
     * for unidentified content like browser videos/phone files), clear the playback
     * snapshots left over from the previous session — the TV pushes fresh status and
     * playlist_status within a second, but the progress tracker must never pair the
     * NEW identity with the OLD position (it would instantly "watch" the new item) —
     * and flip the context to "player".
     */
    fun startLocalPlaybackSession(tmdbId: Int?, season: Int?, episodeStart: Int?) {
        nowPlayingTvId.value = tmdbId
        nowPlayingSeason.value = season
        nowPlayingEpisodeStart.value = episodeStart ?: 1
        clearPlaybackSnapshots()
        // The paused player behind the browser must not become this new session.
        heldPlayerSnapshots = HeldPlayerSnapshots()
        holdingPlayerSnapshots = false
        tvActiveContext.value = "player"
    }

    /** Drop the last-known status/playlist (stale once new content is being sent). */
    fun clearPlaybackSnapshots() {
        tvPlayback.value = null
        tvPlaylistState.value = null
        mutableRemoteObservation.update { it.copy(mediaGeneration = it.mediaGeneration + 1L) }
    }

    /**
     * Mark the receiver idle locally (e.g. session ended from the phone while no native
     * target is connected to confirm it). Keeps external classes from poking
     * [tvActiveContext] directly.
     */
    fun markIdle() {
        tvActiveContext.value = "idle"
    }

    private fun applyHeldPlayerSnapshots() {
        val held = heldPlayerSnapshots
        heldPlayerSnapshots = HeldPlayerSnapshots()
        if (held.playlist == null && held.playback == null && held.videoTracks == null &&
            held.audioTracks == null && held.subtitleTracks == null && held.settings == null) return
        held.playlist?.let { tvPlaylistState.value = it }
        held.playback?.let { tvPlayback.value = it }
        held.videoTracks?.let { tvVideoTracks.value = it }
        held.audioTracks?.let { tvAudioTracks.value = it }
        held.subtitleTracks?.let { tvSubtitleTracks.value = it }
        held.settings?.let { tvPlayerSettings.value = it }
        mutableRemoteObservation.update { previous ->
            previous.copy(
                playbackSequence = previous.playbackSequence + if (held.playback != null) 1L else 0L,
                playbackAtMs = if (held.playback != null) clock() else previous.playbackAtMs,
                mediaGeneration = previous.mediaGeneration + if (held.playback != null || held.playlist != null) 1L else 0L,
                settingsReported = held.settings != null || previous.settingsReported,
                settingsFields = held.settingsFields ?: previous.settingsFields,
            )
        }
    }

    private fun clearPlayerStates() {
        tvPlaylistState.value = null
        tvPlayback.value = null
        tvVideoTracks.value = emptyList()
        tvAudioTracks.value = emptyList()
        tvSubtitleTracks.value = emptyList()
        tvPlayerSettings.value = TvPlayerSettings()
        mutableRemoteObservation.update {
            it.copy(
                mediaGeneration = it.mediaGeneration + 1L,
                settingsReported = false,
                settingsFields = emptySet(),
                browserMuted = null,
                browserFullscreen = null,
            )
        }
        nowPlayingTvId.value = null
        nowPlayingSeason.value = null
        nowPlayingEpisodeStart.value = 1
        logd("TV player snapshots cleared")
    }

    /**
     * Player `status` is [com.playbridge.shared.protocol.createStatusJson]: it includes
     * `mediaKind`, `playbackId`, and/or `currentItemId` whenever the player has them.
     * Browser status never has those keys (title, muted, and suppressUnmute, or an idle
     * frame of type/state/position/duration). `optString("mediaKind", "video")` is not a
     * discriminator — the default fills in when the key is absent.
     */
    private fun isPlayerOwnedStatus(json: org.json.JSONObject): Boolean =
        json.has("mediaKind") || json.has("playbackId") || json.has("currentItemId")

    private fun isBrowserSurface(active: String): Boolean =
        active == "browser" || active == "browser_external"

    private data class HeldPlayerSnapshots(
        var playlist: PlaylistUiState? = null,
        var playback: TvPlaybackStatus? = null,
        var videoTracks: List<MediaTrack>? = null,
        var audioTracks: List<MediaTrack>? = null,
        var subtitleTracks: List<MediaTrack>? = null,
        var settings: TvPlayerSettings? = null,
        var settingsFields: Set<String>? = null,
    )
}
