package com.playbridge.sender.cast

import kotlin.math.abs

internal data class CastMediaPlayback(
    val state: PlaybackState,
    val positionMs: Long,
    val durationMs: Long,
    val isLive: Boolean = false,
    val isImage: Boolean = false,
) {
    val playing: Boolean get() = state == PlaybackState.PLAYING
    val loaded: Boolean get() = state in setOf(PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.BUFFERING)
}

internal fun nativeCastMediaPlayback(status: TvPlaybackStatus?, loaded: Boolean, live: Boolean): CastMediaPlayback =
    normalizedCastPlayback(
        when (status?.state?.lowercase()) {
            "playing" -> PlaybackState.PLAYING
            "paused" -> PlaybackState.PAUSED
            "buffering" -> PlaybackState.BUFFERING
            "error" -> PlaybackState.ERROR
            "stopped" -> PlaybackState.STOPPED
            "idle" -> PlaybackState.IDLE
            else -> if (loaded) PlaybackState.BUFFERING else PlaybackState.IDLE
        }, status?.positionMs ?: -1L, status?.durationMs ?: 0L, live, status?.mediaKind == "image",
    )

internal fun externalCastMediaPlayback(status: PlaybackStatus?, loaded: Boolean): CastMediaPlayback =
    normalizedCastPlayback(status?.state ?: if (loaded) PlaybackState.BUFFERING else PlaybackState.IDLE,
        status?.positionMs ?: -1L, status?.durationMs ?: 0L, status?.isLive == true, false)

private fun normalizedCastPlayback(state: PlaybackState, position: Long, duration: Long, live: Boolean, image: Boolean): CastMediaPlayback {
    val finiteDuration = duration.takeIf { it > 0 && it != Long.MAX_VALUE && !live && !image } ?: 0L
    return CastMediaPlayback(state, position.takeIf { it >= 0 }?.coerceAtMost(finiteDuration.takeIf { it > 0 } ?: Long.MAX_VALUE) ?: -1L,
        finiteDuration, live, image)
}

internal fun castNotificationUsesMedia(loaded: Boolean, mirroring: Boolean, playback: CastMediaPlayback): Boolean =
    loaded && !mirroring && !playback.isImage && playback.loaded

internal fun castNotificationCanSeek(playback: CastMediaPlayback, supported: Boolean): Boolean =
    supported && playback.loaded && !playback.isLive && !playback.isImage && playback.durationMs > 0 && playback.durationMs != Long.MAX_VALUE

internal data class CastMediaActions(val playPause: Boolean, val stop: Boolean, val seek: Boolean, val volume: Boolean, val unlink: Boolean = false)

/** Owner changes must publish even when two receivers report identical playback. */
internal data class CastPlaybackPublication(
    val owner: Any,
    val playback: CastMediaPlayback,
    val actions: CastMediaActions,
    val atMs: Long,
) {
    fun positionAt(nowMs: Long): Long = if (playback.positionMs < 0) -1L else {
        val elapsed = if (playback.playing) (nowMs.toDouble() - atMs.toDouble()).coerceAtLeast(0.0) else 0.0
        (playback.positionMs.toDouble() + elapsed)
            .coerceAtMost((playback.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE).toDouble()).toLong()
    }
}

internal fun castPlaybackNeedsPublication(previous: CastPlaybackPublication?, next: CastPlaybackPublication, nowMs: Long): Boolean {
    if (previous == null || previous.owner != next.owner || previous.playback.state != next.playback.state ||
        previous.playback.durationMs != next.playback.durationMs || previous.actions != next.actions
    ) return true
    val before = previous.positionAt(nowMs)
    val after = next.positionAt(nowMs)
    return if (before < 0 || after < 0) before != after else abs(before.toDouble() - after.toDouble()) > 2_000
}
