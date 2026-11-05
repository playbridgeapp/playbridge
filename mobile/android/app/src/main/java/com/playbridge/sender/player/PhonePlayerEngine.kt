package com.playbridge.sender.player

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks

/** The decoder actually playing a video in [PlayerActivity]. */
enum class PhonePlayerEngine(val label: String) {
    EXOPLAYER("ExoPlayer"),
    MPV("mpv"),
}

/** The phone player setting. [AUTOMATIC] starts with ExoPlayer and switches to mpv when it can't play a format. */
enum class PhonePlayerMode(val id: String, val label: String, val description: String) {
    AUTOMATIC("auto", "Automatic", "ExoPlayer, switching to mpv when a format isn't supported"),
    EXOPLAYER("exo", "ExoPlayer only", "Never switch to mpv"),
    MPV("mpv", "mpv", "Play everything with mpv"),
    ;

    val initialEngine: PhonePlayerEngine
        get() = if (this == MPV) PhonePlayerEngine.MPV else PhonePlayerEngine.EXOPLAYER

    companion object {
        fun fromId(id: String?): PhonePlayerMode = entries.firstOrNull { it.id == id } ?: AUTOMATIC
    }
}

/**
 * When [PhonePlayerMode.AUTOMATIC] hands a video from ExoPlayer to mpv. Only format and decoder
 * problems qualify: network, HTTP and DRM failures would fail in mpv too, so they stay on ExoPlayer
 * with its normal error and retry.
 */
object PhonePlayerFallback {
    private val formatErrors = setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    )

    fun isFormatError(errorCode: Int): Boolean = errorCode in formatErrors

    /**
     * ExoPlayer plays on silently when it has no decoder for a stream's only video or only audio
     * format: a black picture or no sound, and no error. True when every track of either type
     * is unsupported.
     */
    fun hasUnplayableTrackType(tracks: Tracks): Boolean =
        hasUnplayableTrackType(tracks.groups.map { it.type to it.isSupported })

    /** [groups] holds each track group's type and whether any of its tracks is supported. */
    internal fun hasUnplayableTrackType(groups: List<Pair<Int, Boolean>>): Boolean =
        listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO).any { type ->
            val ofType = groups.filter { it.first == type }
            ofType.isNotEmpty() && ofType.none { it.second }
        }

    /** mpv opens network URLs, files and (through a file descriptor) content URIs. */
    fun canOpen(url: String): Boolean {
        val scheme = url.substringBefore(':', "").lowercase()
        return scheme in setOf("http", "https", "file", "content") || url.startsWith("/")
    }
}
