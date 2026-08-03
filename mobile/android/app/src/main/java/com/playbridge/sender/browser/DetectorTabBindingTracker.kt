package com.playbridge.sender.browser

internal data class DetectorTabCandidate(
    val kotlinTabId: String,
    val url: String,
)

/**
 * Maintains an authoritative one-to-one association between the WebExtension's
 * numeric tab IDs and Android Components' Kotlin tab IDs.
 *
 * Unknown detector tabs (including blocked popups) are never assigned to the
 * selected tab merely because it is selected. A binding is created only from
 * an exact document URL match and then survives cross-origin navigations.
 */
internal class DetectorTabBindingTracker {
    private var detectorEpoch: Long? = null
    private val kotlinTabByDetectorTab = mutableMapOf<Int, String>()
    private val detectorTabByKotlinTab = mutableMapOf<String, Int>()

    fun resolve(
        incomingEpoch: Long,
        detectorTabId: Int,
        messageUrls: List<String>,
        candidates: List<DetectorTabCandidate>,
        selectedKotlinTabId: String?,
    ): String? {
        if (!acceptEpoch(incomingEpoch) || detectorTabId < 0) return null

        val liveTabIds = candidates.mapTo(mutableSetOf()) { it.kotlinTabId }
        pruneClosedTabs(liveTabIds)
        kotlinTabByDetectorTab[detectorTabId]?.let { return it }

        val normalizedUrls = messageUrls
            .asSequence()
            .mapNotNull(::normalizeDocumentUrl)
            .toSet()
        if (normalizedUrls.isEmpty()) return null

        val matches = candidates.filter { candidate ->
            normalizeDocumentUrl(candidate.url) in normalizedUrls &&
                detectorTabByKotlinTab[candidate.kotlinTabId]
                    .let { boundDetectorTab ->
                        boundDetectorTab == null || boundDetectorTab == detectorTabId
                    }
        }
        val selectedMatch = matches.singleOrNull { it.kotlinTabId == selectedKotlinTabId }
        val resolved = selectedMatch ?: matches.singleOrNull() ?: return null
        kotlinTabByDetectorTab[detectorTabId] = resolved.kotlinTabId
        detectorTabByKotlinTab[resolved.kotlinTabId] = detectorTabId
        return resolved.kotlinTabId
    }

    fun resolveLegacy(
        messageUrls: List<String>,
        candidates: List<DetectorTabCandidate>,
        selectedKotlinTabId: String?,
    ): String? {
        val normalizedUrls = messageUrls
            .asSequence()
            .mapNotNull(::normalizeDocumentUrl)
            .toSet()
        if (normalizedUrls.isEmpty()) return null
        val matches = candidates.filter { normalizeDocumentUrl(it.url) in normalizedUrls }
        return matches.singleOrNull { it.kotlinTabId == selectedKotlinTabId }?.kotlinTabId
            ?: matches.singleOrNull()?.kotlinTabId
    }

    fun forgetDetectorTab(incomingEpoch: Long, detectorTabId: Int): String? {
        if (incomingEpoch != detectorEpoch) return null
        val kotlinTabId = kotlinTabByDetectorTab.remove(detectorTabId) ?: return null
        detectorTabByKotlinTab.remove(kotlinTabId, detectorTabId)
        return kotlinTabId
    }

    private fun acceptEpoch(incomingEpoch: Long): Boolean {
        val currentEpoch = detectorEpoch
        if (currentEpoch != null && incomingEpoch < currentEpoch) return false
        if (currentEpoch == null || incomingEpoch > currentEpoch) {
            detectorEpoch = incomingEpoch
            kotlinTabByDetectorTab.clear()
            detectorTabByKotlinTab.clear()
        }
        return true
    }

    private fun pruneClosedTabs(liveTabIds: Set<String>) {
        kotlinTabByDetectorTab.entries.removeAll { (detectorTabId, kotlinTabId) ->
            if (kotlinTabId in liveTabIds) {
                false
            } else {
                detectorTabByKotlinTab.remove(kotlinTabId, detectorTabId)
                true
            }
        }
    }
}

private fun normalizeDocumentUrl(url: String?): String? {
    val normalized = url?.trim()?.substringBefore("#")?.takeIf { it.isNotEmpty() }
    return normalized
}
