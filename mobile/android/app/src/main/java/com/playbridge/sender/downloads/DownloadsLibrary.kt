package com.playbridge.sender.downloads

import com.playbridge.sender.data.downloads.DownloadEntity
import com.playbridge.sender.downloads.engine.DownloadStatus
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/* Pure Downloads-screen logic: grouping, search/sort, labels. No Android types. */

enum class DownloadSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("Name"),
    LARGEST("Largest first"),
}

data class DownloadSections(
    val inProgress: List<DownloadEntity>,
    val failed: List<DownloadEntity>,
    val saved: List<DownloadEntity>,
) {
    val isEmpty get() = inProgress.isEmpty() && failed.isEmpty() && saved.isEmpty()
}

internal fun isInProgress(status: String) = status == DownloadStatus.RUNNING.name ||
    status == DownloadStatus.QUEUED.name ||
    status == DownloadStatus.PAUSED.name ||
    status == DownloadStatus.MERGING.name

/** Splits downloads into sections, applying the search query and sort to each. */
internal fun downloadSections(
    all: List<DownloadEntity>,
    query: String,
    sort: DownloadSort,
): DownloadSections {
    val q = query.trim()
    val matched = if (q.isEmpty()) all else all.filter { it.title.contains(q, ignoreCase = true) }
    val sorted = when (sort) {
        DownloadSort.NEWEST -> matched.sortedByDescending { it.createdAt }
        DownloadSort.OLDEST -> matched.sortedBy { it.createdAt }
        DownloadSort.NAME -> matched.sortedBy { it.title.lowercase(Locale.ROOT) }
        DownloadSort.LARGEST -> matched.sortedByDescending { sizeOf(it) }
    }
    return DownloadSections(
        // In-progress items keep their start order so cards don't jump around while sorting.
        inProgress = matched.filter { isInProgress(it.status) }.sortedBy { it.createdAt },
        failed = sorted.filter { it.status == DownloadStatus.FAILED.name },
        saved = sorted.filter { it.status == DownloadStatus.DONE.name },
    )
}

internal fun sizeOf(item: DownloadEntity): Long =
    if (item.status == DownloadStatus.DONE.name) item.bytesDownloaded
    else maxOf(item.bytesDownloaded, item.totalBytes)

/**
 * Where "Open source page" goes. [exactPage] is true for the tab page recorded when the
 * download started; false for the Referer fallback (older downloads), which browsers often
 * trim to just the site's origin or an embedded player's frame.
 */
data class DownloadSource(val url: String, val exactPage: Boolean)

internal fun downloadSource(item: DownloadEntity): DownloadSource? =
    webUrl(item.sourcePageUrl)?.let { DownloadSource(it, exactPage = true) }
        ?: refererUrl(item)?.let { DownloadSource(it, exactPage = false) }

/**
 * Host of the page the download came from, falling back to the media host. Only the
 * host is ever shown — never a full URL.
 */
internal fun sourceHost(item: DownloadEntity): String? =
    hostOf(downloadSource(item)?.url) ?: hostOf(item.url)

private fun webUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val scheme = runCatching { URI(url).scheme?.lowercase(Locale.ROOT) }.getOrNull()
    return url.takeIf { scheme == "http" || scheme == "https" }
}

/** The Referer header, when the download recorded one and it is a web page. */
internal fun refererUrl(item: DownloadEntity): String? {
    val json = item.headersJson ?: return null
    val headers = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val key = headers.keys().asSequence().firstOrNull { it.equals("Referer", ignoreCase = true) } ?: return null
    return webUrl(headers.optString(key))
}

internal fun hostOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val host = runCatching { URI(url).host }.getOrNull() ?: return null
    return host.removePrefix("www.").takeIf { it.isNotBlank() }
}

/** "305.8 MB · Oct 12 · example.com" — host last since it's the part that truncates. */
internal fun savedMetaLine(item: DownloadEntity, nowMs: Long, locale: Locale = Locale.getDefault()): String =
    listOfNotNull(
        formatSize(item.bytesDownloaded).takeIf { item.bytesDownloaded > 0 },
        formatDay(item.updatedAt.takeIf { it > 0 } ?: item.createdAt, nowMs, locale),
        sourceHost(item),
    ).joinToString(" · ")

/** "Today", "Yesterday", "Oct 12", or "Oct 12, 2024" for other years. */
internal fun formatDay(timeMs: Long, nowMs: Long, locale: Locale = Locale.getDefault()): String {
    val then = Calendar.getInstance().apply { timeInMillis = timeMs }
    val now = Calendar.getInstance().apply { timeInMillis = nowMs }
    val sameYear = then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val dayDiff = if (sameYear) now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) else Int.MAX_VALUE
    return when (dayDiff) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> SimpleDateFormat(if (sameYear) "MMM d" else "MMM d, yyyy", locale).format(Date(timeMs))
    }
}

/** Header summary: "6 videos · 1.85 GB" plus free space when known. */
internal fun librarySummary(saved: List<DownloadEntity>, freeBytes: Long?): String {
    val count = saved.size
    val parts = mutableListOf("$count ${if (count == 1) "video" else "videos"}")
    val total = saved.sumOf { it.bytesDownloaded }
    if (total > 0) parts += formatSize(total)
    if (freeBytes != null && freeBytes > 0) parts += "${formatSize(freeBytes)} free"
    return parts.joinToString(" · ")
}

/** "45.2 MB of 300 MB · 2.1 MB/s · 2 min left" for a running download. */
internal fun progressDetail(item: DownloadEntity, bytesPerSec: Long): String {
    val parts = mutableListOf<String>()
    parts += if (item.totalBytes > 0) {
        "${formatSize(item.bytesDownloaded)} of ${formatSize(item.totalBytes)}"
    } else {
        formatSize(item.bytesDownloaded)
    }
    if (item.status == DownloadStatus.RUNNING.name && bytesPerSec > 0) {
        parts += "${formatSize(bytesPerSec)}/s"
        etaSeconds(item, bytesPerSec)?.let { parts += "${formatEta(it)} left" }
    }
    return parts.joinToString(" · ")
}

internal fun etaSeconds(item: DownloadEntity, bytesPerSec: Long): Long? {
    if (item.totalBytes <= 0 || bytesPerSec <= 0) return null
    val remaining = item.totalBytes - item.bytesDownloaded
    if (remaining <= 0) return null
    return (remaining + bytesPerSec - 1) / bytesPerSec
}

internal fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds.coerceAtLeast(1)} s"
    seconds < 3_600 -> "${(seconds + 30) / 60} min"
    else -> {
        val h = seconds / 3_600
        val m = (seconds % 3_600) / 60
        if (m == 0L) "$h h" else "$h h $m min"
    }
}

/** Exponential moving average so speed/ETA don't jitter with each 1 s tick. */
internal fun smoothSpeed(previous: Long?, sample: Long, weight: Double = 0.3): Long =
    if (previous == null || previous <= 0) sample
    else (previous + weight * (sample - previous)).toLong().coerceAtLeast(0)

/** "1:05:09" / "4:07" for a video length. */
internal fun formatDuration(ms: Long): String? {
    if (ms <= 0) return null
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

internal fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val group = (ln(size.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
    val fmt = if (group >= 3) "%.2f %s" else "%.1f %s"
    return String.format(Locale.US, fmt, size / 1024.0.pow(group), units[group])
}

/**
 * New file display name for a rename: keeps the original extension, strips characters
 * that are invalid in file names, and returns null when nothing usable remains.
 */
internal fun renamedDisplayName(newTitle: String, currentDisplayName: String?): String? {
    val base = newTitle.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ')
    if (base.isEmpty()) return null
    val ext = currentDisplayName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() && it.length <= 5 }
    return if (ext == null || base.endsWith(".$ext", ignoreCase = true)) base else "$base.$ext"
}
