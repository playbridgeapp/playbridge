package com.playbridge.player.player

/** Resume from 30 seconds until strictly before 95%, only for known durations. */
internal fun resumePosition(position: Long, duration: Long): Long? {
    if (duration <= 0 || position < 30_000L) return null
    // ceil(duration * 95 / 100), without overflowing Long or rounding via floating point.
    val finishedAt = (duration / 100L) * 95L + ((duration % 100L) * 95L + 99L) / 100L
    return position.takeIf { it < finishedAt }
}
