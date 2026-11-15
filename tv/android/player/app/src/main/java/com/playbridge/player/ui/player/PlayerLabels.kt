package com.playbridge.player.ui.player

/** Plain-language strings for the player surfaces, kept pure so they can be unit tested. */

/** Whole minutes, rounded to the nearest (39 min 40 s is 40). Callers handle anything under a minute first. */
private fun roundedMinutes(ms: Long): Long = (ms + 30_000L) / 60_000L

private fun hoursAndMinutes(minutes: Long): String = when {
    minutes < 60L -> "$minutes min"
    minutes % 60L == 0L -> "${minutes / 60L} hr"
    else -> "${minutes / 60L} hr ${minutes % 60L} min"
}

/** "8 min", "1 hr 5 min", "Less than a minute": a length of time, rounded sensibly. */
internal fun durationText(ms: Long): String =
    if (ms < 60_000L) "Less than a minute" else hoursAndMinutes(roundedMinutes(ms))

/**
 * "8 min left", "40 min left", "1 hr 5 min left", "Less than a minute left". Null when the length is
 * unknown. Shared by the controls, the Home bookmark tiles and the bookmark actions panel.
 */
internal fun timeLeftText(durationMs: Long, positionMs: Long): String? {
    if (durationMs <= 0L) return null
    return "${durationText((durationMs - positionMs).coerceAtLeast(0L))} left"
}

/**
 * The line under the title on full controls: season info, "From <phone>", the queue position
 * and, while paused, how much is left. Only parts that have real data are included.
 */
internal fun controlsContextLine(state: PlayerControlsState): String? {
    val parts = buildList {
        state.subtitle?.takeIf { it.isNotBlank() }?.let(::add)
        state.senderName?.takeIf { it.isNotBlank() }?.let { add("From $it") }
        if (state.hasPlaylist && state.playlistItems.size > 1) {
            add("${state.playlistIndex + 1} of ${state.playlistItems.size} in the queue")
        }
        if (!state.isPlaying && state.duration > 0L) {
            add(listOfNotNull("Paused", timeLeftText(state.duration, state.currentPosition)).joinToString(" · "))
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Label on the skip button for a skip segment type from the skip-service ("intro", "recap", "outro"...). */
internal fun skipButtonLabel(segmentType: String): String = when (segmentType.lowercase()) {
    "intro" -> "Skip intro"
    "recap" -> "Skip recap"
    "outro", "credits" -> "Skip credits"
    "preview" -> "Skip preview"
    else -> "Skip ${segmentType.lowercase()}".trim()
}

/** "Stopping in 42 seconds" / "Stopping in 1 second" / "Stopping in 4:30" style countdown. */
internal fun stillWatchingCountdownText(secondsRemaining: Int): String {
    val s = secondsRemaining.coerceAtLeast(0)
    return when {
        s >= 120 -> "Stopping in ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        s == 1 -> "Stopping in 1 second"
        else -> "Stopping in $s seconds"
    }
}

/** Fraction of the Still Watching countdown left, 0..1. [totalSeconds] is the count at the start. */
internal fun stillWatchingFraction(secondsRemaining: Int, totalSeconds: Int): Float =
    if (totalSeconds <= 0) 0f else (secondsRemaining.coerceAtLeast(0).toFloat() / totalSeconds).coerceIn(0f, 1f)

/** "3 of 24" for photos in a queue; null for a single photo. */
internal fun photoCounterText(state: PlayerControlsState): String? =
    if (state.playlistItems.size > 1) "${state.playlistIndex + 1} of ${state.playlistItems.size}" else null

/** Label text for the control row's label slot. */
internal object ControlLabels {
    const val BACK_10 = "Back 10 seconds"
    const val FORWARD_10 = "Forward 10 seconds"
    const val PREVIOUS = "Previous"
    const val NEXT = "Next"
    const val SUBTITLES = "Subtitles"
    const val AUDIO = "Audio"
    const val QUEUE = "Queue"
    const val MORE = "More"
    fun playPause(isPlaying: Boolean) = if (isPlaying) "Pause" else "Play"
    fun bookmark(state: BookmarkState) = if (state == BookmarkState.ON) "Bookmarked" else "Bookmark"
}

/** Confirmation shown after the bookmark button is pressed. [nowBookmarked] is the new state. */
internal fun bookmarkNoticeText(nowBookmarked: Boolean): String =
    if (nowBookmarked) "Bookmarked. Your place is saved as you watch." else "Bookmark removed."
