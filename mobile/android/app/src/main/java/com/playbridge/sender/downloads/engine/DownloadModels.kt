package com.playbridge.sender.downloads.engine

/**
 * Phase-1 download engine — core value types.
 *
 * This package is the new WorkManager-backed downloader. It is built ALONGSIDE the
 * legacy path (`downloads/DownloadUtils`, `MediaDownloadService`, `cast/HlsExporter`),
 * which keeps working until the Phase-2 UI cutover. Nothing here is wired into the
 * cast sheet / browser yet — see DOWNLOAD_REWRITE_PLAN.md.
 */

/** What kind of source a download is, which picks the [DownloadStrategy]. */
enum class DownloadKind { FILE, HLS, MPD }

/** Lifecycle of a single download. Persisted to Room as a string. */
enum class DownloadStatus { QUEUED, RUNNING, PAUSED, MERGING, DONE, FAILED }

/**
 * An immutable description of one download. The [id] is stable across restarts so the
 * worker, the Room row, the temp dir, and the header store all agree on identity.
 */
data class DownloadRequest(
    val id: String,
    val url: String,
    val title: String,
    val kind: DownloadKind,
    val mimeType: String? = null,
    /** Per-download request headers (Cookie/User-Agent/Referer). NOT global/host-keyed. */
    val headers: Map<String, String> = emptyMap(),
    /** For HLS: the specific variant playlist the user picked; null = auto. */
    val selectedVariantUrl: String? = null,
    val audioOnly: Boolean = false,
)

/**
 * Progress tick reported by a strategy. [totalBytes] is -1 when unknown
 * (e.g. an HLS playlist before segment sizes are estimated).
 */
data class Progress(
    val downloadedBytes: Long,
    val totalBytes: Long = -1L,
    val info: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** Thrown by a strategy when the controller signals pause — distinct from cancel. */
class DownloadPausedException : Exception("Download paused by user")
