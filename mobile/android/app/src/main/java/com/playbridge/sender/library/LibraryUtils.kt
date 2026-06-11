package com.playbridge.sender.library

import com.playbridge.sender.data.library.AddonCatalogRow

/**
 * Returns the first non-blank poster URL found across all loaded addon catalog
 * rows, used as the ambient backdrop image on the Home tab.
 * (StremioMetaPreview doesn't carry a background field — poster is the best proxy.)
 * Kept in its own file so Compose's Modifier.background() extension is never
 * in scope here and cannot shadow any property access.
 */
internal fun firstAddonBackdropUrl(rows: List<AddonCatalogRow>): String? {
    for (row in rows) {
        for (item in row.items) {
            val url = item.poster
            if (url != null && url.isNotBlank()) return url
        }
    }
    return null
}

/** ms → "23:14" / "1:02:33" for resume labels. */
internal fun formatResumeTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
