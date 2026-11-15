package com.playbridge.sender.history

import java.net.URI

const val MAX_LINK_CHECKS_PER_OPEN = 5
const val LINK_CHECK_FRESHNESS_MS: Long = 30L * 60 * 1000

/** One cast as the scheduler sees it. [lastCheckResult]/[lastCheckAtMs] come from the store. */
data class LinkCheckCandidate(
    val id: String,
    val url: String,
    val finished: Boolean,
    val savedAtMs: Long,
    val expiresAtMs: Long? = null,
    val lastCheckResult: LinkCheckResult? = null,
    val lastCheckAtMs: Long? = null,
)

/**
 * Picks which shown casts to check when history opens: unfinished first, then newest first,
 * at most [limit]. Skips non-http(s) links, links whose own expiry has passed, links already
 * found expired, and links checked within [freshnessMs]. "Check again" bypasses this policy.
 */
fun selectLinksToCheck(
    shown: List<LinkCheckCandidate>,
    nowMs: Long,
    limit: Int = MAX_LINK_CHECKS_PER_OPEN,
    freshnessMs: Long = LINK_CHECK_FRESHNESS_MS,
): List<LinkCheckCandidate> = shown.asSequence()
    .filter { isHttpLink(it.url) }
    .filter { it.expiresAtMs == null || it.expiresAtMs > nowMs }
    .filter { it.lastCheckResult != LinkCheckResult.Expired }
    .filter { c -> c.lastCheckAtMs == null || nowMs - c.lastCheckAtMs >= freshnessMs }
    .sortedWith(compareBy<LinkCheckCandidate> { it.finished }.thenByDescending { it.savedAtMs })
    .take(limit.coerceAtLeast(0))
    .toList()

internal fun isHttpLink(url: String): Boolean = runCatching {
    val uri = URI(url)
    (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
}.getOrDefault(false)
