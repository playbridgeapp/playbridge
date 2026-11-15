package com.playbridge.player.browser

import androidx.compose.ui.graphics.toArgb
import com.playbridge.player.ui.theme.OnSurface
import com.playbridge.player.ui.theme.OnSurfaceVariant
import com.playbridge.player.ui.theme.Primary
import com.playbridge.player.ui.theme.Surface
import com.playbridge.player.ui.theme.SurfaceBright
import com.playbridge.player.ui.theme.SurfaceContainer
import java.util.Locale

/**
 * Brand colours for the browser's Android Views (download card, cursor, overlays), as ARGB ints.
 * The browser is Views, not Compose, so it reads the same tokens as the rest of the app.
 */
internal object BrowserChrome {
    val card: Int = SurfaceContainer.toArgb()
    val cardOverlay: Int = (0xF0 shl 24) or (SurfaceContainer.toArgb() and 0xFFFFFF)
    val track: Int = SurfaceBright.toArgb()
    val accent: Int = Primary.toArgb()
    val text: Int = OnSurface.toArgb()
    val muted: Int = OnSurfaceVariant.toArgb()
    val onAccentRing: Int = Surface.toArgb()
}

/** "120 MB", "1.5 MB", "812 KB". Whole numbers from 100 up, one decimal below. */
internal fun formatDownloadSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (i == 0 || v >= 100) String.format(Locale.US, "%.0f %s", v, units[i]) else String.format(Locale.US, "%.1f %s", v, units[i])
}

/** "120 MB of 194 MB · saves to Downloads"; just the size so far when the total is unknown. */
internal fun downloadDetail(downloaded: Long, total: Long): String =
    if (total > 0) "${formatDownloadSize(downloaded)} of ${formatDownloadSize(total)} · saves to Downloads"
    else "${formatDownloadSize(downloaded)} · saves to Downloads"
