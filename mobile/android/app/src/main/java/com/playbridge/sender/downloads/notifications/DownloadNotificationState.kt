package com.playbridge.sender.downloads.notifications

import com.playbridge.sender.downloads.engine.DownloadStatus
import com.playbridge.sender.downloads.engine.Progress
import java.security.MessageDigest
import java.util.Locale

internal const val DOWNLOAD_GROUP_SUMMARY_ID = 0x60000000
private const val HASH_MASK = 0x0fffffff

private fun identityHash(value: String): Int {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return ((digest[0].toInt() and 0xff) shl 24) or
        ((digest[1].toInt() and 0xff) shl 16) or
        ((digest[2].toInt() and 0xff) shl 8) or (digest[3].toInt() and 0xff)
}

/** Separate reserved ranges: WorkManager may remove its foreground ID after a result posts. */
internal fun downloadNotificationId(id: String, result: Boolean = false): Int =
    (if (result) 0x40000000 else 0x20000000) or (identityHash(id) and HASH_MASK)

internal fun downloadActionRequestCode(id: String, action: String): Int =
    identityHash("$id:$action") and Int.MAX_VALUE

internal enum class DownloadNotificationMode { RUNNING, FINALIZING, PAUSED, DONE, FAILED, NONE }

internal fun downloadNotificationMode(status: DownloadStatus?, cancelled: Boolean = false): DownloadNotificationMode =
    if (cancelled) DownloadNotificationMode.NONE else when (status) {
        DownloadStatus.QUEUED, DownloadStatus.RUNNING -> DownloadNotificationMode.RUNNING
        DownloadStatus.MERGING -> DownloadNotificationMode.FINALIZING
        DownloadStatus.PAUSED -> DownloadNotificationMode.PAUSED
        DownloadStatus.DONE -> DownloadNotificationMode.DONE
        DownloadStatus.FAILED -> DownloadNotificationMode.FAILED
        null -> DownloadNotificationMode.NONE // Cancel removes the Room row.
    }

internal data class DownloadNotificationProgress(
    val text: String,
    val percent: Int?,
    val finalizing: Boolean = false,
)

internal fun downloadSize(bytes: Long): String {
    val size = bytes.coerceAtLeast(0).toDouble()
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
    var value = size
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${bytes.coerceAtLeast(0)} B" else String.format(Locale.US, "%.1f %s", value, units[unit])
}

internal fun downloadNotificationProgress(progress: Progress): DownloadNotificationProgress {
    if (progress.info == "Merging…" || progress.info == "Finalizing…") {
        return DownloadNotificationProgress("Finalizing…", null, finalizing = true)
    }
    val downloaded = progress.downloadedBytes.coerceAtLeast(0)
    if (progress.totalBytes > 0) {
        // Double avoids overflow for downloads larger than Long.MAX_VALUE / 100.
        val percent = (downloaded.toDouble() / progress.totalBytes * 100).toInt().coerceIn(0, 100)
        return DownloadNotificationProgress(
            "$percent% · ${downloadSize(downloaded)} / ${downloadSize(progress.totalBytes)}", percent,
        )
    }
    // Progress.info is not arbitrary display text: accept only the engine's segment count.
    val segments = progress.info?.takeIf { Regex("Segments [0-9]+/[0-9]+").matches(it) }
    val text = listOfNotNull(segments, "${downloadSize(downloaded)} downloaded").joinToString(" · ")
    return DownloadNotificationProgress(text, null)
}

internal fun shouldUpdateDownloadNotification(
    previous: DownloadNotificationProgress?,
    next: DownloadNotificationProgress,
    lastUpdateMs: Long,
    nowMs: Long,
): Boolean = previous == null || (previous != next && nowMs - lastUpdateMs >= 1_000L)

/** A URL used as a fallback title must not leak credentials into the shade. */
internal fun downloadNotificationTitle(title: String): String =
    title.trim().takeIf {
        it.isNotBlank() && !Regex("(?i)([a-z][a-z0-9+.-]*://|\\b(?:cookie|authorization|token)\\s*[:=])").containsMatchIn(it)
    }?.take(160) ?: "Download"

/** A result and its retiring foreground card still represent one download. */
internal fun downloadNotificationGroupCount(ids: List<String>, excludingId: String? = null): Int =
    ids.filter { it != excludingId }.distinct().size
