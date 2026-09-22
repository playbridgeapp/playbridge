package com.playbridge.sender.cast

internal fun thumbnailSeekTimesUs(durationMs: Long): List<Long> {
    val endMs = if (durationMs > 0) (durationMs - 100).coerceAtLeast(0) else 10_000L
    return listOf(1_000L, 5_000L, 10_000L).map { minOf(it, endMs) * 1_000 }.distinct()
}

/** Reject only almost entirely black frames, not ordinary dark scenes. */
internal fun isNearlyBlackThumbnail(width: Int, height: Int, pixel: (Int, Int) -> Int): Boolean {
    var dark = 0
    var samples = 0
    for (y in 0 until height step maxOf(1, height / 24)) {
        for (x in 0 until width step maxOf(1, width / 24)) {
            val color = pixel(x, y)
            val luma = (54 * ((color shr 16) and 255) + 183 * ((color shr 8) and 255) +
                19 * (color and 255)) / 256
            if (luma < 18) dark++
            samples++
        }
    }
    return samples > 0 && dark.toDouble() / samples >= 0.98
}
