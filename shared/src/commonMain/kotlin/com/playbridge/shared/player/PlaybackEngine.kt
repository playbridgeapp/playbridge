package com.playbridge.shared.player

import playbridge.PlayPayload
import kotlinx.coroutines.flow.StateFlow

/**
 * Common interface for video playback engines across Android and Apple TV.
 */
interface PlaybackEngine {
    val state: StateFlow<PlaybackState>
    val position: StateFlow<Long>     // ms
    val duration: StateFlow<Long>     // ms, -1 if unknown
    val audioTracks: StateFlow<List<Track>>
    val subtitleTracks: StateFlow<List<Track>>
    var isTransitioning: Boolean

    suspend fun load(payload: PlayPayload)
    fun play()
    fun pause()
    fun stop()
    fun seek(positionMs: Long)
    fun setRate(rate: Float)
    fun setAudioTrack(id: String?)
    fun setSubtitleTrack(id: String?)
    suspend fun attachExternalSubtitle(url: String, language: String?)
    fun setFilter(filter: VideoFilter, customParams: List<Float>? = null)
    fun release()
}

sealed class PlaybackState {
    data object Idle         : PlaybackState()
    data object Buffering    : PlaybackState()
    data object Ready        : PlaybackState()
    data object Playing      : PlaybackState()
    data object Paused       : PlaybackState()
    data object Ended        : PlaybackState()
    data class  Error(val code: String, val msg: String) : PlaybackState()
}

data class Track(val id: String, val label: String, val language: String?)

enum class VideoFilter(val label: String) {
    NONE("None"),
    HDR("HDR"),
    NIGHT("Night"),
    MOVIE("Movie"),
    CINEMA("Cinema"),
    ACTION("Action"),
    DEEP_BLACK("Deep Black"),
    GRAYSCALE("Grayscale"),
    VIVID("Vivid"),
    CUSTOM("Custom")
}
