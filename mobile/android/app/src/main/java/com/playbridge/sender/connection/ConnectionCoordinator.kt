package com.playbridge.sender.connection

import android.util.Log
import com.playbridge.sender.cast.MediaTrack
import com.playbridge.sender.cast.TvPlaybackStatus
import com.playbridge.sender.cast.TvPlayerSettings
import com.playbridge.sender.library.PlaylistEpisode
import com.playbridge.sender.library.PlaylistUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
class ConnectionCoordinator(
    private val webSocketClient: WebSocketClient,
    private val scope: CoroutineScope
) {
    private val TAG = "ConnectionCoordinator"

    // TV Playback & Playlist States synced from WebSocket
    val tvActiveContext = MutableStateFlow("idle")
    val tvPlaylistState = MutableStateFlow<PlaylistUiState?>(null)
    val tvPlayback = MutableStateFlow<TvPlaybackStatus?>(null)
    val tvVideoTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tvAudioTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tvSubtitleTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tvPlayerSettings = MutableStateFlow(TvPlayerSettings())
    
    // Names of user scripts currently installed on the TV (for the management UI).
    val installedUserScripts = MutableStateFlow<List<String>>(emptyList())

    // TV browser User-Agent: which one is active (name, blank = default) + which custom
    // ones are saved on the TV (for the management UI).
    val tvUserAgentState = MutableStateFlow(TvUserAgentState())

    // TMDb Sync & Now Playing Metadata states
    val nowPlayingTvId = MutableStateFlow<Int?>(null)
    val nowPlayingSeason = MutableStateFlow<Int?>(null)
    val nowPlayingEpisodeStart = MutableStateFlow(1)

    init {
        startListening()
    }

    private fun startListening() {
        scope.launch {
            webSocketClient.messages.collect { message ->
                try {
                    val json = org.json.JSONObject(message)
                    when (json.optString("type")) {
                        "context" -> {
                            val active = json.optString("active", "idle")
                            tvActiveContext.value = active
                            Log.d(TAG, "TV Active Context updated: $active")
                            // Clear playlist and playback states when TV goes idle
                            if (active == "idle") {
                                clearPlayerStates()
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
                                            )
                                        )
                                    }
                                }
                            }
                            tvPlaylistState.value = PlaylistUiState(
                                currentIndex = json.optInt("currentIndex", 0),
                                totalCount = json.optInt("totalCount", 0),
                                items = episodes
                            )
                            Log.d(
                                TAG,
                                "TV playlist_status index=${json.optInt("currentIndex", 0)} " +
                                    "total=${json.optInt("totalCount", 0)} items=${episodes.size}",
                            )
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
                            if (next != tvPlayback.value) tvPlayback.value = next
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
                            tvVideoTracks.value = parseTracks(json.optJSONArray("video"))
                            tvAudioTracks.value = parseTracks(json.optJSONArray("audio"))
                            tvSubtitleTracks.value = parseTracks(json.optJSONArray("subtitle"))
                            Log.d(TAG, "TV Audio/Subtitle tracks updated")
                        }
                        "user_scripts" -> {
                            val arr = json.optJSONArray("names")
                            installedUserScripts.value = buildList {
                                if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
                            }
                            Log.d(TAG, "TV user scripts: ${installedUserScripts.value}")
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
                            Log.d(TAG, "TV user agents updated: active=$active, ${entries.size} saved")
                        }
                        "player_settings" -> {
                            tvPlayerSettings.value = TvPlayerSettings(
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
                            )
                            Log.d(TAG, "TV Player settings updated")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing WebSocket message: ${e.message}", e)
                }
            }
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
        tvActiveContext.value = "player"
    }

    /** Drop the last-known status/playlist (stale once new content is being sent). */
    fun clearPlaybackSnapshots() {
        tvPlayback.value = null
        tvPlaylistState.value = null
    }

    /**
     * Mark the receiver idle locally (e.g. session ended from the phone while no native
     * target is connected to confirm it). Keeps external classes from poking
     * [tvActiveContext] directly.
     */
    fun markIdle() {
        tvActiveContext.value = "idle"
    }

    private fun clearPlayerStates() {
        tvPlaylistState.value = null
        tvPlayback.value = null
        tvVideoTracks.value = emptyList()
        tvAudioTracks.value = emptyList()
        tvSubtitleTracks.value = emptyList()
        tvPlayerSettings.value = TvPlayerSettings()
        nowPlayingTvId.value = null
        nowPlayingSeason.value = null
        nowPlayingEpisodeStart.value = 1
        Log.d(TAG, "TV Player states cleared as TV is idle")
    }
}
