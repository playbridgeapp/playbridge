package com.playbridge.player.ui.player.panels

import java.util.Locale

/** Pure text helpers for the player side panels (kept free of Compose so they can be unit tested). */

/** "In sync", "+0.2 s", "-1.5 s", "+0.25 s". The delay is in milliseconds; positive means later. */
internal fun formatSubtitleDelay(delayMs: Long): String {
    if (delayMs == 0L) return "In sync"
    val sign = if (delayMs > 0) "+" else "-"
    val abs = kotlin.math.abs(delayMs)
    val seconds = if (abs % 100L == 0L) {
        String.format(Locale.ROOT, "%.1f", abs / 1000.0)
    } else {
        String.format(Locale.ROOT, "%.3f", abs / 1000.0).trimEnd('0').trimEnd('.')
    }
    return "$sign$seconds s"
}

/** "0:00", "1:45", "1:02:03". */
internal fun formatChapterTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/**
 * Chapter row title: the label produced by the chapter code is "Chapter 3 · The Heist" or "Chapter 3";
 * the panel already shows the start time and position, so the real title is kept when there is one.
 */
internal fun chapterPanelTitle(label: String): String = label.substringAfter(" · ", label)

private val EPISODE_MARKER = Regex("""S\d+\s*E\d+.*""", RegexOption.IGNORE_CASE)
private const val FAILED_PREFIX = "[FAILED] "

/**
 * Drops the redundant series-name prefix from an episode title for the narrow panel, e.g.
 * "Breaking Bad S2E5 - Breakage" -> "S2E5 - Breakage". A leading "[FAILED] " marker is kept;
 * null or markerless titles pass through unchanged.
 */
internal fun shortEpisodeLabel(title: String?): String? {
    if (title == null) return null
    return if (title.startsWith(FAILED_PREFIX)) {
        val rest = title.removePrefix(FAILED_PREFIX)
        FAILED_PREFIX + (EPISODE_MARKER.find(rest)?.value ?: rest)
    } else {
        EPISODE_MARKER.find(title)?.value ?: title
    }
}

internal data class QueueRowText(val title: String, val subtitle: String?, val failed: Boolean)

/** Text of queue row [index] (0-based) when [currentIndex] is playing. */
internal fun queueRowText(index: Int, title: String?, currentIndex: Int): QueueRowText {
    val failed = title?.startsWith(FAILED_PREFIX) == true
    val short = shortEpisodeLabel(title)?.removePrefix(FAILED_PREFIX)?.takeIf { it.isNotBlank() } ?: "Episode ${index + 1}"
    val subtitle = when {
        failed -> "Couldn't play"
        index == currentIndex -> "Playing now"
        index == currentIndex + 1 -> "Up next"
        else -> null
    }
    return QueueRowText("${index + 1}. $short", subtitle, failed)
}

/** "2 of 4"; blank when the queue is empty. */
internal fun queuePosition(currentIndex: Int, size: Int): String? =
    if (size <= 0) null else "${(currentIndex + 1).coerceIn(1, size)} of $size"

internal val PlaybackSpeeds = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

internal fun speedLabel(speed: Float): String =
    if (speed == 1.0f) "Normal" else "${speed.toString().removeSuffix(".0")}x"

internal data class ScalingOption(val mode: String, val label: String, val description: String)

/** The modes implemented consistently by both renderer services. */
internal val ScalingOptions = listOf(
    ScalingOption("Fit", "Fit screen", "Show the whole picture with letterboxing"),
    ScalingOption("Zoom", "Crop to fill", "Fill the screen while preserving aspect ratio"),
    ScalingOption("Fill", "Stretch", "Fill the screen without preserving aspect ratio"),
)

internal fun scalingLabel(mode: String): String = ScalingOptions.firstOrNull { it.mode == mode }?.label ?: mode

/** Player engine ids as used in state (`exo`, `mpv`), with their display names. */
internal val PlayerEngines = listOf("exo" to "ExoPlayer", "mpv" to "MPV")

internal fun playerEngineLabel(id: String): String =
    PlayerEngines.firstOrNull { it.first == id.lowercase() }?.second ?: id
