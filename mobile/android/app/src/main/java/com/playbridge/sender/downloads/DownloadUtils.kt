package com.playbridge.sender.downloads

/**
 * Small formatting helper retained from the legacy download path.
 *
 * The legacy downloader (system DownloadManager + Media3 offline + HLS export) was retired
 * in favour of the WorkManager engine in `downloads/engine/`. Only [formatFileSize] survives
 * because the cast sheet and magnet sheet still use it for display.
 */
object DownloadUtils {

    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        if (size < 1024) return "$size B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        val format = if (digitGroups >= 3) "%.2f %s" else "%.1f %s"
        return String.format(
            java.util.Locale.US,
            format,
            size / Math.pow(1024.0, digitGroups.toDouble()),
            units[digitGroups],
        )
    }
}
