package com.playbridge.sender.diagnostics

import android.content.Context
import android.os.Build
import com.playbridge.sender.BuildConfig
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.dlna.DlnaActionFailure
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A deliberately small, allowlisted report. Never put a URL, title, device name or header here. */
data class CastAttempt(
    val id: String,
    val startedAtMs: Long,
    val receiver: ReceiverKind,
    val route: RouteKind,
    val media: MediaKind,
    val outcome: AttemptOutcome,
    val events: List<CastAttemptEvent>,
    val upstream: UpstreamStats = UpstreamStats(),
) {
    enum class ReceiverKind { PLAYBRIDGE, DLNA, GOOGLE_CAST, ROKU, WEB_BROWSER }
    enum class RouteKind { DIRECT, VIA_PHONE, VIA_PROXY, UNKNOWN }
    enum class MediaKind { VIDEO, AUDIO, IMAGE, SCREEN_MIRROR, UNKNOWN }
    enum class AttemptOutcome { STARTING, SENT, BUFFERING, PLAYING, PAUSED, STOPPED, FAILED }
}

data class UpstreamStats(
    val playlistsOk: Int = 0,
    val playlistsFailed: Int = 0,
    val segmentsOk: Int = 0,
    val segmentsFailed: Int = 0,
    val otherFailed: Int = 0,
    val lastFailureStatus: Int? = null,
)

data class CastAttemptEvent(
    val elapsedMs: Long,
    val kind: Kind,
    val httpStatus: Int? = null,
    val upnpCode: Int? = null,
) {
    enum class Kind {
        REQUESTED, COMMAND_SENT, BUFFERING, PLAYING, PAUSED, STOPPED, FAILED,
        RECEIVER_STOPPED_BEFORE_PLAYBACK, DLNA_SET_URI_FAILED, DLNA_PLAY_FAILED, DLNA_ACTION_FAILED,
    }
}

internal fun formatCastAttemptReport(attempt: CastAttempt, appVersion: String, appCode: Int, sdk: Int): String =
    buildString {
        appendLine("PlayBridge cast diagnostics v1")
        appendLine("App: $appVersion ($appCode)")
        appendLine("Android SDK: $sdk")
        appendLine("Attempt: ${attempt.id}")
        appendLine("Started: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date(attempt.startedAtMs))}")
        appendLine("Receiver: ${attempt.receiver.name}")
        appendLine("Route: ${attempt.route.name}")
        appendLine("Media: ${attempt.media.name}")
        appendLine("Outcome: ${attempt.outcome.name}")
        val upstream = attempt.upstream
        appendLine("Phone upstream fetches (may include prefetch): playlists ${upstream.playlistsOk} OK / ${upstream.playlistsFailed} failed; segments ${upstream.segmentsOk} OK / ${upstream.segmentsFailed} failed; other failures ${upstream.otherFailed}")
        appendLine("Fetch counters are capped at 100 per category and do not prove TV requests.")
        upstream.lastFailureStatus?.let { appendLine("Last upstream HTTP failure: $it") }
        attempt.events.forEach { event ->
            append("+${event.elapsedMs} ms: ${event.kind.name}")
            event.httpStatus?.let { append(" HTTP $it") }
            event.upnpCode?.let { append(" UPnP $it") }
            appendLine()
        }
        append("No media URLs, titles, receiver names, IP addresses, or request headers are included.")
    }

internal fun classifyExternalPlayback(
    previous: CastAttempt?,
    state: PlaybackState,
): Pair<CastAttempt.AttemptOutcome, Boolean>? {
    val stoppedBeforePlayback = state == PlaybackState.STOPPED &&
        previous?.outcome == CastAttempt.AttemptOutcome.BUFFERING &&
        previous.media != CastAttempt.MediaKind.IMAGE
    val outcome = when (state) {
        PlaybackState.IDLE -> return null
        PlaybackState.BUFFERING -> CastAttempt.AttemptOutcome.BUFFERING
        PlaybackState.PLAYING -> CastAttempt.AttemptOutcome.PLAYING
        PlaybackState.PAUSED -> CastAttempt.AttemptOutcome.PAUSED
        PlaybackState.STOPPED -> if (stoppedBeforePlayback) {
            CastAttempt.AttemptOutcome.FAILED
        } else {
            CastAttempt.AttemptOutcome.STOPPED
        }
        PlaybackState.ERROR -> CastAttempt.AttemptOutcome.FAILED
    }
    return outcome to stoppedBeforePlayback
}

internal fun isNativePlaybackStartCommand(message: String): Boolean {
    if (!message.contains("\"playlist\"")) return false
    return runCatching {
        val envelope = Json.parseToJsonElement(message) as? JsonObject ?: return@runCatching false
        envelope["type"]?.jsonPrimitive?.contentOrNull == "command" &&
            envelope["action"]?.jsonPrimitive?.contentOrNull == "playlist"
    }.getOrDefault(false)
}

/** Private, bounded storage for reports users may explicitly share from Cast History. */
class CastAttemptDiagnostics(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _attempts = MutableStateFlow(readStored())
    val attempts: StateFlow<List<CastAttempt>> = _attempts.asStateFlow()
    @Volatile var activeNativeAttemptId: String? = null
        private set

    init {
        // Re-write on startup so expired or malformed persisted entries are actually removed.
        publish(_attempts.value)
    }

    @Synchronized
    fun recordUpstreamFetch(id: String, category: UpstreamCategory, status: Int) {
        val current = _attempts.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0 || current[index].outcome in TERMINAL_OUTCOMES) return
        val attempt = current[index]
        val ok = status in 200..299
        val previous = attempt.upstream
        val next = when (category) {
            UpstreamCategory.PLAYLIST -> previous.copy(
                playlistsOk = (previous.playlistsOk + if (ok) 1 else 0).coerceAtMost(MAX_UPSTREAM_COUNT),
                playlistsFailed = (previous.playlistsFailed + if (ok) 0 else 1).coerceAtMost(MAX_UPSTREAM_COUNT),
            )
            UpstreamCategory.SEGMENT -> previous.copy(
                segmentsOk = (previous.segmentsOk + if (ok) 1 else 0).coerceAtMost(MAX_UPSTREAM_COUNT),
                segmentsFailed = (previous.segmentsFailed + if (ok) 0 else 1).coerceAtMost(MAX_UPSTREAM_COUNT),
            )
            UpstreamCategory.OTHER -> previous.copy(otherFailed = (previous.otherFailed + if (ok) 0 else 1).coerceAtMost(MAX_UPSTREAM_COUNT))
        }.copy(lastFailureStatus = if (!ok && status in 100..599) status else previous.lastFailureStatus)
        if (next == previous) return
        publish(current.toMutableList().apply { this[index] = attempt.copy(upstream = next) })
    }

    enum class UpstreamCategory { PLAYLIST, SEGMENT, OTHER }

    @Synchronized
    fun recordNativePlaybackCommand(sent: Boolean): String {
        mark(activeNativeAttemptId, CastAttempt.AttemptOutcome.STOPPED)
        val id = start(
            receiver = CastAttempt.ReceiverKind.PLAYBRIDGE,
            route = CastAttempt.RouteKind.UNKNOWN,
            media = CastAttempt.MediaKind.UNKNOWN,
        )
        activeNativeAttemptId = id
        mark(id, if (sent) CastAttempt.AttemptOutcome.SENT else CastAttempt.AttemptOutcome.FAILED)
        return id
    }

    @Synchronized
    fun clearNativePlaybackTracking() {
        mark(activeNativeAttemptId, CastAttempt.AttemptOutcome.STOPPED)
        activeNativeAttemptId = null
    }

    @Synchronized
    fun start(receiver: CastAttempt.ReceiverKind, route: CastAttempt.RouteKind, media: CastAttempt.MediaKind): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val attempt = CastAttempt(
            id = id,
            startedAtMs = now,
            receiver = receiver,
            route = route,
            media = media,
            outcome = CastAttempt.AttemptOutcome.STARTING,
            events = listOf(CastAttemptEvent(0, CastAttemptEvent.Kind.REQUESTED)),
        )
        publish(listOf(attempt) + _attempts.value)
        return id
    }

    @Synchronized
    fun mark(
        id: String?,
        outcome: CastAttempt.AttemptOutcome,
        failure: Throwable? = null,
        receiverStoppedBeforePlayback: Boolean = false,
    ) {
        if (id == null) return
        val current = _attempts.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val attempt = current[index]
        if (attempt.outcome == outcome || attempt.outcome in TERMINAL_OUTCOMES) return
        val detail = failure as? DlnaActionFailure
        val eventKind = when (outcome) {
            CastAttempt.AttemptOutcome.STARTING -> CastAttemptEvent.Kind.REQUESTED
            CastAttempt.AttemptOutcome.SENT -> CastAttemptEvent.Kind.COMMAND_SENT
            CastAttempt.AttemptOutcome.BUFFERING -> CastAttemptEvent.Kind.BUFFERING
            CastAttempt.AttemptOutcome.PLAYING -> CastAttemptEvent.Kind.PLAYING
            CastAttempt.AttemptOutcome.PAUSED -> CastAttemptEvent.Kind.PAUSED
            CastAttempt.AttemptOutcome.STOPPED -> CastAttemptEvent.Kind.STOPPED
            CastAttempt.AttemptOutcome.FAILED -> when {
                detail?.actionName == "SetAVTransportURI" -> CastAttemptEvent.Kind.DLNA_SET_URI_FAILED
                detail?.actionName == "Play" -> CastAttemptEvent.Kind.DLNA_PLAY_FAILED
                detail != null -> CastAttemptEvent.Kind.DLNA_ACTION_FAILED
                receiverStoppedBeforePlayback -> CastAttemptEvent.Kind.RECEIVER_STOPPED_BEFORE_PLAYBACK
                else -> CastAttemptEvent.Kind.FAILED
            }
        }
        val event = CastAttemptEvent(
            elapsedMs = (System.currentTimeMillis() - attempt.startedAtMs).coerceAtLeast(0),
            kind = eventKind,
            httpStatus = detail?.httpStatus?.takeIf { it in 100..599 },
            upnpCode = detail?.upnpCode?.toIntOrNull()?.takeIf { it in 100..999 },
        )
        publish(current.toMutableList().apply {
            this[index] = attempt.copy(outcome = outcome, events = (attempt.events + event).takeLast(MAX_EVENTS))
        })
    }

    @Synchronized
    fun markPlayback(id: String?, state: PlaybackState, failure: Throwable? = null) {
        val previous = _attempts.value.firstOrNull { it.id == id }
        val (outcome, stoppedBeforePlayback) = classifyExternalPlayback(previous, state) ?: return
        mark(id, outcome, failure, stoppedBeforePlayback)
    }

    @Synchronized
    fun delete(id: String) = publish(_attempts.value.filterNot { it.id == id })

    @Synchronized
    fun clear() = publish(emptyList())

    fun latestFailed(): CastAttempt? = _attempts.value.firstOrNull { it.outcome == CastAttempt.AttemptOutcome.FAILED }

    fun report(id: String): String? = _attempts.value.firstOrNull { it.id == id }?.let(::report)

    fun report(attempt: CastAttempt): String = formatCastAttemptReport(
        attempt,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
        Build.VERSION.SDK_INT,
    )

    @Synchronized
    private fun publish(items: List<CastAttempt>) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val bounded = items.filter { it.startedAtMs >= cutoff }.take(MAX_ATTEMPTS)
        _attempts.value = bounded
        val json = JSONArray()
        bounded.forEach { attempt ->
            json.put(JSONObject().apply {
                put("id", attempt.id)
                put("started", attempt.startedAtMs)
                put("receiver", attempt.receiver.name)
                put("route", attempt.route.name)
                put("media", attempt.media.name)
                put("outcome", attempt.outcome.name)
                put("upstream", JSONObject().apply {
                    put("playlistOk", attempt.upstream.playlistsOk)
                    put("playlistFailed", attempt.upstream.playlistsFailed)
                    put("segmentOk", attempt.upstream.segmentsOk)
                    put("segmentFailed", attempt.upstream.segmentsFailed)
                    put("otherFailed", attempt.upstream.otherFailed)
                    attempt.upstream.lastFailureStatus?.let { put("lastStatus", it) }
                })
                put("events", JSONArray().apply {
                    attempt.events.forEach { event ->
                        put(JSONObject().apply {
                            put("elapsed", event.elapsedMs)
                            put("kind", event.kind.name)
                            event.httpStatus?.let { put("http", it) }
                            event.upnpCode?.let { put("upnp", it) }
                        })
                    }
                })
            })
        }
        preferences.edit().putString(KEY_ATTEMPTS, json.toString()).apply()
    }

    private fun readStored(): List<CastAttempt> = runCatching {
        val json = JSONArray(preferences.getString(KEY_ATTEMPTS, "[]"))
        buildList {
            for (index in 0 until json.length().coerceAtMost(MAX_ATTEMPTS)) {
                val item = json.optJSONObject(index) ?: continue
                val attempt = runCatching {
                    CastAttempt(
                        id = UUID.fromString(item.getString("id")).toString(),
                        startedAtMs = item.getLong("started"),
                        receiver = enumValueOf(item.getString("receiver")),
                        route = enumValueOf(item.getString("route")),
                        media = enumValueOf(item.getString("media")),
                        outcome = enumValueOf(item.getString("outcome")),
                        upstream = item.optJSONObject("upstream")?.let { stats ->
                            UpstreamStats(
                                playlistsOk = stats.optInt("playlistOk").coerceIn(0, 100_000),
                                playlistsFailed = stats.optInt("playlistFailed").coerceIn(0, 100_000),
                                segmentsOk = stats.optInt("segmentOk").coerceIn(0, 100_000),
                                segmentsFailed = stats.optInt("segmentFailed").coerceIn(0, 100_000),
                                otherFailed = stats.optInt("otherFailed").coerceIn(0, 100_000),
                                lastFailureStatus = stats.optInt("lastStatus").takeIf { it in 100..599 },
                            )
                        } ?: UpstreamStats(),
                        events = buildList {
                            val events = item.optJSONArray("events") ?: JSONArray()
                            for (eventIndex in 0 until events.length().coerceAtMost(MAX_EVENTS)) {
                                val event = events.optJSONObject(eventIndex) ?: continue
                                add(CastAttemptEvent(
                                    elapsedMs = event.getLong("elapsed").coerceAtLeast(0),
                                    kind = enumValueOf(event.getString("kind")),
                                    httpStatus = event.optInt("http").takeIf { it in 100..599 },
                                    upnpCode = event.optInt("upnp").takeIf { it in 100..999 },
                                ))
                            }
                        },
                    )
                }.getOrNull() ?: continue
                if (attempt.startedAtMs >= System.currentTimeMillis() - RETENTION_MS) add(attempt)
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val PREFS_NAME = "cast_attempt_diagnostics"
        private const val KEY_ATTEMPTS = "attempts_v1"
        private const val MAX_ATTEMPTS = 40
        private const val MAX_EVENTS = 24
        private const val MAX_UPSTREAM_COUNT = 100
        private const val RETENTION_MS = 14L * 24 * 60 * 60 * 1000
        private val TERMINAL_OUTCOMES = setOf(CastAttempt.AttemptOutcome.STOPPED, CastAttempt.AttemptOutcome.FAILED)
    }
}
