package com.playbridge.player.ui

import java.util.Locale

/** "812 bytes", "4 KB", "4.2 KB", "1.5 MB". Whole numbers drop the decimal. */
internal fun formatScriptSize(bytes: Int): String {
    if (bytes < 1024) return if (bytes == 1) "1 byte" else "$bytes bytes"
    val (value, unit) = if (bytes < 1024 * 1024) bytes / 1024.0 to "KB" else bytes / (1024.0 * 1024.0) to "MB"
    val rounded = Math.round(value * 10) / 10.0
    val text = if (rounded == Math.floor(rounded)) String.format(Locale.US, "%d", rounded.toLong()) else String.format(Locale.US, "%.1f", rounded)
    return "$text $unit"
}

/** Groups a hash prefix in fours for reading aloud: "9f2c41ab07de" becomes "9f2c 41ab 07de". */
internal fun groupFingerprint(hash: String): String = hash.chunked(4).joinToString(" ")

/** Seconds left, rounded up, never negative. */
internal fun secondsLeft(remainingMs: Long): Int = ((remainingMs.coerceAtLeast(0L) + 999L) / 1000L).toInt()
