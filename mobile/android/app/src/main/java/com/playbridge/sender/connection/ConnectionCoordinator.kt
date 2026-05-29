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
    val tvAudioTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tvSubtitleTracks = MutableStateFlow<List<MediaTrack>>(emptyList())
    val tvPlayerSettings = MutableStateFlow(TvPlayerSettings())
    
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
                                                title = o.optString("title", "Item ${i + 1}")
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
                            Log.d(TAG, "TV Playlist status updated with ${episodes.size} items")
                        }
                        "status" -> {
                            tvPlayback.value = TvPlaybackStatus(
                                state = json.optString("state", "paused"),
                                positionMs = json.optLong("position", 0L),
                                durationMs = json.optLong("duration", 0L),
                                title = json.optString("title", "").ifEmpty { null }
                            )
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
                                                    selected = o.optBoolean("selected", false)
                                                )
                                            )
                                        }
                                    }
                                }
                            tvAudioTracks.value = parseTracks(json.optJSONArray("audio"))
                            tvSubtitleTracks.value = parseTracks(json.optJSONArray("subtitle"))
                            Log.d(TAG, "TV Audio/Subtitle tracks updated")
                        }
                        "player_settings" -> {
                            tvPlayerSettings.value = TvPlayerSettings(
                                speed = json.optDouble("speed", 1.0).toFloat(),
                                scaling = json.optString("scaling", "Fit"),
                                audioBoost = json.optBoolean("audioBoost", false),
                                subtitleOffsetMs = json.optLong("subtitleOffsetMs", 0L),
                                filter = json.optString("filter", "NONE"),
                                engine = json.optString("engine", "")
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

    private fun clearPlayerStates() {
        tvPlaylistState.value = null
        tvPlayback.value = null
        tvAudioTracks.value = emptyList()
        tvSubtitleTracks.value = emptyList()
        tvPlayerSettings.value = TvPlayerSettings()
        nowPlayingTvId.value = null
        nowPlayingSeason.value = null
        nowPlayingEpisodeStart.value = 1
        Log.d(TAG, "TV Player states cleared as TV is idle")
    }
}
