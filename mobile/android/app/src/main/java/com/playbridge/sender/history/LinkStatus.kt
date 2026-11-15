package com.playbridge.sender.history

/** What a history row says about its link. Pure; the UI maps these to text and styling. */
sealed interface LinkStatus {
    /** Nothing worth showing (unknown, far-off expiry, or a check that passed). */
    data object None : LinkStatus
    data class ExpiresSoon(val remainingMs: Long) : LinkStatus
    /** [expiredAtMs] is known only when the link itself states its expiry. */
    data class Expired(val expiredAtMs: Long?) : LinkStatus
    data object Checking : LinkStatus
    data object CouldNotCheck : LinkStatus
}

const val EXPIRY_WARNING_WINDOW_MS: Long = 24L * 3600 * 1000

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/**
 * Priority: checking; link-stated expiry in the past (unless a later check succeeded);
 * a failed check as expired; an upcoming expiry within 24 h; a check that could not run;
 * otherwise nothing.
 */
fun linkStatus(
    nowMs: Long,
    expiresAtMs: Long?,
    lastCheckResult: LinkCheckResult?,
    lastCheckAtMs: Long?,
    checking: Boolean,
): LinkStatus {
    if (checking) return LinkStatus.Checking
    val checkedWorking = lastCheckResult == LinkCheckResult.Works
    if (expiresAtMs != null && expiresAtMs <= nowMs) {
        val contradicted = checkedWorking && lastCheckAtMs != null && lastCheckAtMs >= expiresAtMs
        if (!contradicted) return LinkStatus.Expired(expiresAtMs)
    }
    if (lastCheckResult == LinkCheckResult.Expired) return LinkStatus.Expired(null)
    if (expiresAtMs != null && expiresAtMs > nowMs && expiresAtMs - nowMs <= EXPIRY_WARNING_WINDOW_MS) {
        return LinkStatus.ExpiresSoon(expiresAtMs - nowMs)
    }
    if (lastCheckResult == LinkCheckResult.CouldNotCheck) return LinkStatus.CouldNotCheck
    return LinkStatus.None
}

/** Plain-words span: "5 min", "2 h", "3 days". Rounds down; under a minute is "1 min". */
fun formatLinkDuration(ms: Long): String {
    val span = ms.coerceAtLeast(0)
    return when {
        span < HOUR_MS -> "${(span / MINUTE_MS).coerceAtLeast(1)} min"
        span < DAY_MS -> "${span / HOUR_MS} h"
        else -> (span / DAY_MS).let { if (it == 1L) "1 day" else "$it days" }
    }
}

/** English row text, or null for [LinkStatus.None]. */
fun linkStatusLabel(status: LinkStatus, nowMs: Long): String? = when (status) {
    LinkStatus.None -> null
    is LinkStatus.ExpiresSoon -> "Link expires in ${formatLinkDuration(status.remainingMs)}"
    is LinkStatus.Expired -> status.expiredAtMs
        ?.let { nowMs - it }
        ?.takeIf { it >= MINUTE_MS }
        ?.let { "Link expired ${formatLinkDuration(it)} ago" }
        ?: "Link expired"
    LinkStatus.Checking -> "Checking the link…"
    LinkStatus.CouldNotCheck -> "Couldn’t check · may have expired"
}

/** HTTP statuses that mean the saved link no longer grants access. */
fun shouldMarkExpired(httpStatus: Int?): Boolean = httpStatus in EXPIRED_STATUSES

internal val EXPIRED_STATUSES = setOf(401, 403, 404, 410)

private val HTTP_IN_MESSAGE = Regex("\\(HTTP ([1-5][0-9]{2})\\)\\s*$")

/**
 * Maps a receiver playback failure sentence such as "The link was refused (HTTP 403)" or
 * "The video wasn't found (HTTP 404)" to whether the cast should be marked expired.
 * Sentences without an HTTP code (network, decode, format) never mark expired.
 */
fun shouldMarkExpiredFromFailure(message: String?): Boolean =
    message?.let { HTTP_IN_MESSAGE.find(it.trim())?.groupValues?.get(1)?.toInt() }
        .let(::shouldMarkExpired)
