package com.playbridge.player.ui.player

import androidx.compose.runtime.Immutable

/*
 * What the TV says when a PHONE does something (mockups PhonePaused, PhoneSeeking, PhoneChange,
 * PhoneWhileControls). The TV remote gets the real controls; a phone only gets a short
 * confirmation that never takes focus. All wording lives here as pure functions.
 */

/** A phone paused playback. [name] is null when the connection has no known name. */
@Immutable
data class PhoneOrigin(val name: String?)

/** A phone is moving the playback position to [targetMs]. */
@Immutable
data class PhoneSeek(val name: String?, val targetMs: Long)

enum class PhoneChangeKind { SUBTITLES, AUDIO, SPEED, QUEUE }

/** The small card for a setting a phone changed: a [title] naming the change and a [detail] line. */
@Immutable
data class PhoneChangeCard(val kind: PhoneChangeKind, val title: String, val detail: String)

/** Shown by pre-play when playback will resume from a saved place. */
@Immutable
data class PrePlayResume(val positionMs: Long, val durationMs: Long?)

/** How long the seek strip and change card stay up after the phone's last action. */
internal const val PHONE_CONFIRMATION_MS = 3_000L

internal fun phoneLabel(name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() } ?: "a phone"

/** "Paused from Pixel 9" / "Paused from a phone". */
internal fun pausedFromText(name: String?): String = "Paused from ${phoneLabel(name)}"

/** "Pixel 9 is seeking" / "A phone is seeking". */
internal fun seekingText(name: String?): String {
    val clean = name?.trim()?.takeIf { it.isNotEmpty() }
    return if (clean != null) "$clean is seeking" else "A phone is seeking"
}

/** "Changed from Pixel 9" / "Changed from a phone". */
internal fun changedFromText(name: String?): String = "Changed from ${phoneLabel(name)}"

/** The seek strip caption: who is seeking, then the chapter or segment at the target when there is one. */
internal fun phoneSeekCaption(name: String?, timelineCaption: String?): String =
    listOfNotNull(seekingText(name), timelineCaption?.takeIf { it.isNotBlank() }).joinToString(" · ")

/** "Subtitles: English", "Subtitles: Off". A null [trackName] means the real name is not known. */
internal fun subtitleChangeTitle(trackName: String?, off: Boolean): String = when {
    off -> "Subtitles: Off"
    !trackName.isNullOrBlank() -> "Subtitles: $trackName"
    else -> "Subtitles changed"
}

/** "Audio: Japanese 5.1". */
internal fun audioChangeTitle(trackName: String?): String =
    if (trackName.isNullOrBlank()) "Audio track changed" else "Audio: $trackName"

/** "Speed: 1.25×", "Speed: Normal". */
internal fun speedChangeTitle(speed: Float): String =
    if (speed == 1.0f) "Speed: Normal" else "Speed: ${speed.toString().removeSuffix(".0")}×"

/** "Subtitles 0.5 s later", "Subtitles 1.25 s earlier", "Subtitles in sync". [offsetMs] is the new total. */
internal fun subtitleOffsetTitle(offsetMs: Long): String {
    if (offsetMs == 0L) return "Subtitles in sync"
    val abs = kotlin.math.abs(offsetMs)
    val whole = abs / 1000L
    val fraction = (abs % 1000L).toString().padStart(3, '0').trimEnd('0')
    val seconds = if (fraction.isEmpty()) "$whole s" else "$whole.$fraction s"
    return "Subtitles $seconds ${if (offsetMs > 0L) "later" else "earlier"}"
}

/** "Now playing: Sintel"; falls back to the position when the item has no title. */
internal fun queuePositionTitle(itemTitle: String?, index: Int, count: Int): String =
    if (!itemTitle.isNullOrBlank()) "Now playing: $itemTitle" else "Now playing: item ${index + 1} of $count"

internal fun phoneChangeCard(kind: PhoneChangeKind, title: String, name: String?): PhoneChangeCard =
    PhoneChangeCard(kind, title, changedFromText(name))
