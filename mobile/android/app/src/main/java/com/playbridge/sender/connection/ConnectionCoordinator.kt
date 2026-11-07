package com.playbridge.sender.connection

import android.util.Log
import com.playbridge.sender.cast.MediaTrack
import com.playbridge.sender.cast.TvPlaybackStatus
import com.playbridge.sender.cast.TvPlayerSettings
import com.playbridge.sender.cast.TvTimeline
import com.playbridge.sender.cast.parseTvTimeline
import com.playbridge.sender.cast.remote.RemoteObservation
import android.os.SystemClock
import com.playbridge.sender.connection.ReceiverQueueItem
import com.playbridge.sender.connection.ReceiverQueueState
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
    /** The receiver's queue revision after the command, when it reports one. */
    val queueRevision: Long? = null,
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

private const val SUBTITLE_STYLE_FEATURE = "subtitle_style_v1"
private const val SUBTITLE_FONT_FEATURE = "subtitle_font_v1"
private const val FONT_REPLY_TIMEOUT_MS = 15_000L

/**
 * Coordinates WebSocket connection state parsing, TV playback updates, and command execution.
 * Extracts message processing out of BrowserActivity for better separation of concerns.
 */
class ConnectionCoordinator internal constructor(
    private val messages: Flow<String>,
    private val webSocketClient: WebSocketClient?,
    private val scope: CoroutineScope,
    private val takePlaybackRequest: (String) -> Boolean = { webSocketClient?.takePlaybackRequest(it) == true },
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

    // TV Playback & Playlist States synced from WebSocket
    val tvActiveContext = MutableStateFlow("idle")
    val tvPlaylistState = MutableStateFlow<ReceiverQueueState?>(null)
    val tvPlayback = MutableStateFlow<TvPlaybackStatus?>(null)
    private val playbackFailures = ReceiverPlaybackFailures()
    internal val playbackFailure = playbackFailures.notice
    internal fun dismissPlaybackFailure() = playbackFailures.dismiss()
    private var playbackMessageSession = webSocketClient?.messageSession?.value
    val tvVideoTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tvAudioTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tvSubtitleTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tvPlayerSettings = MutableStateFlow(TvPlayerSettings())
    val tvTimeline = MutableStateFlow<TvTimeline?>(null)
    private val mutableRemoteObservation = MutableStateFlow(RemoteObservation())
    val remoteObservation = mutableRemoteObservation.asStateFlow()
    private val pendingQueueCommands = ConcurrentHashMap<String, CompletableDeferred<QueueCommandResult>>()
    private val pendingLogRequests = ConcurrentHashMap<String, CompletableDeferred<TvLogsResult.Logs>>()
    
    // Names of user scripts currently installed on the TV (for the management UI).
    val installedUserScripts = MutableStateFlow<List<String>>(emptyList())

    /** The connected TV's subtitle style, as it last reported it; null until then. */
    val tvSubtitleStyle = MutableStateFlow<com.playbridge.shared.player.SubtitleStyle?>(null)

    /** Whether the connected TV keeps a subtitle style the phone can change. */
    val tvSupportsSubtitleStyle: Boolean
        get() = webSocketClient?.connectionState?.value is WebSocketClient.ConnectionState.Connected &&
            SUBTITLE_STYLE_FEATURE in webSocketClient.tvCapabilitiesState.value.features

    /** Asks the TV for its style; it answers with `subtitle_style`. */
    fun queryTvSubtitleStyle() {
        if (tvSupportsSubtitleStyle) {
            webSocketClient?.send(com.playbridge.shared.protocol.createSubtitleStyleQueryJson())
        }
    }

    /** Replaces the TV's style. The TV echoes what it applied to every connected phone. */
    fun setTvSubtitleStyle(style: com.playbridge.shared.player.SubtitleStyle) {
        if (!tvSupportsSubtitleStyle) return
        if (webSocketClient?.send(com.playbridge.shared.protocol.createSubtitleStyleJson(style)) == true) {
            tvSubtitleStyle.value = style
        }
    }

    /** The custom fonts installed on the TV, selected or not. */
    val tvInstalledFonts = MutableStateFlow<List<String>>(emptyList())

    /** Uninstalls [family] from the TV; it reports its fonts again in `subtitle_style`. */
    fun removeTvSubtitleFont(family: String) {
        if (tvSupportsSubtitleFont) {
            webSocketClient?.send(com.playbridge.shared.protocol.createSubtitleFontRemoveJson(family))
        }
    }

    /** Whether the connected TV accepts a custom subtitle font from the phone. */
    val tvSupportsSubtitleFont: Boolean
        get() = tvSupportsSubtitleStyle && SUBTITLE_FONT_FEATURE in webSocketClient!!.tvCapabilitiesState.value.features

    /** Bytes of the font being sent to the TV so far, or null when no font is being sent. */
    val tvFontUploadProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    @Volatile private var pendingFontReply: Pair<String, CompletableDeferred<org.json.JSONObject>>? = null

    /**
     * Sends [bytes] (an already checked font) to the TV in ordered pieces, waiting for each
     * acknowledgement. On success the TV selects the font and reports its new style; returns
     * the installed family, or the TV's error code as a failure.
     */
    suspend fun uploadTvSubtitleFont(bytes: ByteArray): Result<String> {
        val client = webSocketClient
        if (client == null || !tvSupportsSubtitleFont) return Result.failure(IllegalStateException("unsupported"))
        val id = java.util.UUID.randomUUID().toString()
        suspend fun exchange(frame: String): org.json.JSONObject? {
            val reply = CompletableDeferred<org.json.JSONObject>()
            pendingFontReply = id to reply
            if (!client.send(frame)) return null
            return withTimeoutOrNull(FONT_REPLY_TIMEOUT_MS) { reply.await() }
        }
        fun failed(reply: org.json.JSONObject?) =
            Result.failure<String>(IllegalStateException(reply?.optString("error")?.takeIf { it.isNotEmpty() } ?: "no_reply"))
        try {
            tvFontUploadProgress.value = 0 to bytes.size
            val sha = com.playbridge.shared.player.SubtitleFontReceiver.sha256(bytes)
            var reply = exchange(com.playbridge.shared.protocol.createSubtitleFontBeginJson(id, bytes.size, sha))
            var offset = 0
            while (offset < bytes.size) {
                if (reply?.optString("type") != "subtitle_font_ack" || reply.optInt("received") != offset) return failed(reply)
                val end = minOf(offset + com.playbridge.shared.player.SUBTITLE_FONT_CHUNK_BYTES, bytes.size)
                reply = exchange(com.playbridge.shared.protocol.createSubtitleFontChunkJson(id, offset, bytes.copyOfRange(offset, end)))
                offset = end
                tvFontUploadProgress.value = offset to bytes.size
            }
            if (reply?.optString("type") != "subtitle_font_ack" || reply.optInt("received") != bytes.size) return failed(reply)
            reply = exchange(com.playbridge.shared.protocol.createSubtitleFontEndJson(id))
            return if (reply?.optBoolean("ok") == true) Result.success(reply.optString("family")) else failed(reply)
        } finally {
            pendingFontReply = null
            tvFontUploadProgress.value = null
        }
    }

    // TV browser User-Agent: which one is active (name, blank = default) + which custom
    // ones are saved on the TV (for the management UI).
    val tvUserAgentState = MutableStateFlow(TvUserAgentState())


    /**
     * Player snapshots that arrived while the TV was not in the player (the paused
     * player keeps broadcasting behind the browser). They are not shown until the
     * context returns to "player". A new local playback session discards them.
     */
    private var heldPlayerSnapshots = HeldPlayerSnapshots()
    // The phone starts idle, so a player snapshot that arrives before any context
    // frame is held until the TV actually reports "player".
    private var holdingPlayerSnapshots = true
    private val idleStatusDetector = ReceiverIdleStatusDetector()

    init {
        startListening()
        webSocketClient?.let { client ->
            scope.launch { client.messageSession.collect { syncPlaybackMessageSession() } }
        }
    }

    private fun syncPlaybackMessageSession() {
        val current = webSocketClient?.messageSession?.value
        if (current != playbackMessageSession) {
            playbackMessageSession = current
            playbackFailures.reset()
        }
    }

    private fun startListening() {
        scope.launch {
            messages.collect { message ->
                syncPlaybackMessageSession()
                try {
                    val json = org.json.JSONObject(message)
                    when (json.optString("type")) {
                        "context" -> applyActiveContext(json.optString("active", "idle"))
                        "playlist_status" -> {
                            playbackFailures.playlist((json.opt("playbackId") as? String)?.takeIf(String::isNotEmpty))
                            val itemsJson = json.optJSONArray("items")
                            val episodes = buildList {
                                if (itemsJson != null) {
                                    for (i in 0 until itemsJson.length()) {
                                        val o = itemsJson.optJSONObject(i) ?: continue
                                        add(
                                            ReceiverQueueItem(
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
                            val playlist = ReceiverQueueState(
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
                            if (!playbackFailures.status(
                                    json.optString("state", "paused"),
                                    (json.opt("playbackId") as? String)?.takeIf(String::isNotEmpty),
                                    json.opt("error"),
                                )) return@collect
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
                                bufferedMs = (json.opt("buffered") as? Number)?.toLong()?.takeIf { it > 0L },
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
                            // Receivers that stop locally without sending context idle
                            // (older desktop builds) would otherwise stay "Casting".
                            if (idleStatusDetector.onStatus(tvActiveContext.value, next.state, clock())) {
                                logd("TV status idle in player context — treating as context idle")
                                applyActiveContext("idle")
                            }
                        }
                        "command_result" -> {
                            val requestId = json.optString("requestId", "")
                            if (requestId.isNotEmpty() && takePlaybackRequest(requestId) &&
                                !json.optBoolean("ok", false)
                            ) {
                                playbackFailures.rejected(json.optString("error", "").ifEmpty { null })
                            }
                            if (requestId.isNotEmpty()) {
                                pendingQueueCommands.remove(requestId)?.complete(
                                    QueueCommandResult(
                                        ok = json.optBoolean("ok", false),
                                        error = json.optString("error", "").ifEmpty { null },
                                        queueRevision = (json.opt("queueRevision") as? Number)?.toLong(),
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
                        "timeline" -> {
                            val timeline = parseTvTimeline(json)
                            if (holdingPlayerSnapshots) {
                                heldPlayerSnapshots.timeline = timeline
                            } else {
                                tvTimeline.value = timeline
                                logd("TV timeline chapters=${timeline.chapters.size} segments=${timeline.segments.size}")
                            }
                        }
                        "subtitle_font_ack", "subtitle_font_result" -> pendingFontReply
                            ?.takeIf { it.first == json.optString("transferId") }
                            ?.second?.complete(json)
                        "subtitle_style" -> {
                            tvSubtitleStyle.value = com.playbridge.shared.player.SubtitleStyle.fromWire(
                                size = json.optString("size"),
                                background = json.optString("background"),
                                edge = json.optString("edge"),
                                font = json.optString("font"),
                                overrideStyled = if (json.has("overrideStyled")) json.optBoolean("overrideStyled") else null,
                                customFont = json.optString("customFont").takeIf { it.isNotEmpty() },
                            )
                            tvInstalledFonts.value = json.optJSONArray("installedFonts")
                                ?.let { fonts -> (0 until fonts.length()).mapNotNull { fonts.optString(it).takeIf(String::isNotEmpty) } }
                                .orEmpty()
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
                    val heldPlayerSnapshot = holdingPlayerSnapshots && type in setOf("playlist_status", "tracks", "player_settings", "timeline")
                    if (!heldPlayerStatus && !heldPlayerSnapshot && type in setOf("context", "playlist_status", "status", "tracks", "player_settings", "timeline")) {
                        mutableRemoteObservation.update { it.copy(sequence = it.sequence + 1L, atMs = clock()) }
                    }
                } catch (_: Exception) {
                    logd("Error parsing receiver message")
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

    /** Clear stale receiver snapshots before a locally initiated cast. */
    fun startLocalPlaybackSession() {
        clearPlaybackSnapshots()
        // The paused player behind the browser must not become this new session.
        heldPlayerSnapshots = HeldPlayerSnapshots()
        holdingPlayerSnapshots = false
        idleStatusDetector.reset()
        tvActiveContext.value = "player"
    }

    /** Drop the last-known status/playlist (stale once new content is being sent). */
    fun clearPlaybackSnapshots() {
        playbackFailures.reset()
        tvPlayback.value = null
        tvPlaylistState.value = null
        tvTimeline.value = null
        mutableRemoteObservation.update { it.copy(mediaGeneration = it.mediaGeneration + 1L) }
    }

    /**
     * Mark the receiver idle locally (e.g. session ended from the phone while no native
     * target is connected to confirm it). Keeps external classes from poking
     * [tvActiveContext] directly.
     */
    fun markIdle() {
        idleStatusDetector.reset()
        tvActiveContext.value = "idle"
    }

    private fun applyActiveContext(active: String) {
        val previous = tvActiveContext.value
        idleStatusDetector.reset()
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

    private fun applyHeldPlayerSnapshots() {
        val held = heldPlayerSnapshots
        heldPlayerSnapshots = HeldPlayerSnapshots()
        if (held.playlist == null && held.playback == null && held.videoTracks == null &&
            held.audioTracks == null && held.subtitleTracks == null && held.settings == null && held.timeline == null) return
        held.playlist?.let { tvPlaylistState.value = it }
        held.playback?.let { tvPlayback.value = it }
        held.videoTracks?.let { tvVideoTracks.value = it }
        held.audioTracks?.let { tvAudioTracks.value = it }
        held.subtitleTracks?.let { tvSubtitleTracks.value = it }
        held.settings?.let { tvPlayerSettings.value = it }
        held.timeline?.let { tvTimeline.value = it }
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
        tvTimeline.value = null
        mutableRemoteObservation.update {
            it.copy(
                mediaGeneration = it.mediaGeneration + 1L,
                settingsReported = false,
                settingsFields = emptySet(),
                browserMuted = null,
                browserFullscreen = null,
            )
        }
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
        var playlist: ReceiverQueueState? = null,
        var playback: TvPlaybackStatus? = null,
        var videoTracks: List<MediaTrack>? = null,
        var audioTracks: List<MediaTrack>? = null,
        var subtitleTracks: List<MediaTrack>? = null,
        var settings: TvPlayerSettings? = null,
        var settingsFields: Set<String>? = null,
        var timeline: TvTimeline? = null,
    )
}
