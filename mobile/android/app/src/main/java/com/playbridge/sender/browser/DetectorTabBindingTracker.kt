package com.playbridge.sender.browser

internal data class DetectorTabCandidate(
    val kotlinTabId: String,
    val url: String,
)

/** Native-created tabs disambiguate URL fallback while popup content is still blank. */
internal class DetectorTabBindingTracker {
    private data class Binding(
        val kotlinTabId: String,
        val generation: Long,
        val documentConfirmed: Boolean,
    )
    private data class CreatedTab(val url: String, val openerId: String?, val popup: Boolean, val sequence: Long)

    private var detectorEpoch: Long? = null
    private var sequence = 0L
    private val bindings = mutableMapOf<Int, Binding>()
    private val detectorTabByKotlinTab = mutableMapOf<String, Int>()
    private val createdTabs = mutableMapOf<String, CreatedTab>()
    private val retiredDetectorTabs = mutableSetOf<Int>()

    fun registerCreatedTab(tabId: String, url: String, openerId: String?, popup: Boolean = false) {
        createdTabs[tabId] = CreatedTab(url, openerId, popup, ++sequence)
    }

    /** A replacement native session has a new detector tab and a fresh document counter. */
    fun sessionChanged(tabId: String) {
        val detectorTabId = detectorTabByKotlinTab.remove(tabId) ?: return
        bindings.remove(detectorTabId)
        retiredDetectorTabs.add(detectorTabId)
    }

    fun bindingGeneration(epoch: Long, detectorTabId: Int, kotlinTabId: String): Long? =
        bindings[detectorTabId]?.takeIf {
            epoch == detectorEpoch && it.kotlinTabId == kotlinTabId
        }?.generation

    fun resolve(
        incomingEpoch: Long,
        detectorTabId: Int,
        messageUrls: List<String>,
        candidates: List<DetectorTabCandidate>,
        selectedKotlinTabId: String?,
        documentUrl: String? = null,
        recoverStaleBinding: Boolean = false,
    ): String? {
        if (!acceptEpoch(incomingEpoch) || detectorTabId < 0) return null
        pruneClosedTabs(candidates.mapTo(mutableSetOf()) { it.kotlinTabId })
        if (detectorTabId in retiredDetectorTabs) return null
        val binding = bindings[detectorTabId]
        val urls = messageUrls.mapNotNull(::normalizeDocumentUrl).toSet()
        val document = normalizeDocumentUrl(documentUrl)
        if (binding != null) {
            val boundDocument = normalizeDocumentUrl(candidates.firstOrNull {
                it.kotlinTabId == binding.kotlinTabId
            }?.url)
            if (document != null && document == boundDocument) {
                bindings[detectorTabId] = binding.copy(documentConfirmed = true)
            }
            // Only a context-URL guess can be repaired. A confirmed document's
            // old navigation stays stale even when a new tab opens its old URL.
            if (!recoverStaleBinding || binding.documentConfirmed || document == null ||
                document == boundDocument
            ) return binding.kotlinTabId
        }
        // Popup registration precedes the asynchronous store add/link. Wait for
        // that native tab instead of permanently assigning its opener.
        val awaitingPopup = createdTabs.any { (tabId, created) ->
            if (!created.popup || candidates.any { it.kotlinTabId == tabId }) false else {
                val openerUrl = normalizeDocumentUrl(candidates.firstOrNull {
                    it.kotlinTabId == created.openerId
                }?.url)
                normalizeDocumentUrl(created.url) in urls || openerUrl != null && openerUrl in urls
            }
        }
        if (binding == null && awaitingPopup) return null
        val unbound = candidates.filter { it.kotlinTabId !in detectorTabByKotlinTab }
        val pending = unbound.filter { candidate ->
            val created = createdTabs[candidate.kotlinTabId] ?: return@filter false
            // Recovery requires a tab created after the suspect binding. A delayed
            // old navigation must not jump to an unrelated restored/background tab.
            if (binding != null && created.sequence <= binding.generation) return@filter false
            val target = normalizeDocumentUrl(created.url)
            val opener = candidates.firstOrNull { it.kotlinTabId == created.openerId }
            val openerUrl = normalizeDocumentUrl(opener?.url)
            (document != null && document == target) ||
                (created.popup && (document == null || normalizeDocumentUrl(candidate.url) == null) &&
                    (target in urls || openerUrl != null && openerUrl in urls))
        }
        val pendingMatch = pending.singleOrNull { it.kotlinTabId == selectedKotlinTabId }
            ?: pending.maxByOrNull { createdTabs.getValue(it.kotlinTabId).sequence }
        // Current document identity wins over previousUrl/originUrl. Those may
        // identify an opener or an iframe, and cannot choose among duplicate tabs.
        val exact = unbound.filter { candidate ->
            val url = normalizeDocumentUrl(candidate.url)
            url != null && if (document != null) url == document else url in urls
        }
        val exactMatch = exact.singleOrNull { it.kotlinTabId == selectedKotlinTabId }
            ?: exact.filter { it.kotlinTabId in createdTabs }
                .maxByOrNull { createdTabs.getValue(it.kotlinTabId).sequence }
            ?: exact.singleOrNull()
        val resolved = pendingMatch ?: exactMatch?.takeIf { binding == null } ?: return binding?.kotlinTabId
        if (binding != null) detectorTabByKotlinTab.remove(binding.kotlinTabId, detectorTabId)
        bindings[detectorTabId] = Binding(
            resolved.kotlinTabId, ++sequence,
            document != null && document == normalizeDocumentUrl(resolved.url),
        )
        detectorTabByKotlinTab[resolved.kotlinTabId] = detectorTabId
        createdTabs.remove(resolved.kotlinTabId)
        return resolved.kotlinTabId
    }

    fun resolveLegacy(
        messageUrls: List<String>,
        candidates: List<DetectorTabCandidate>,
        selectedKotlinTabId: String?,
    ): String? {
        val urls = messageUrls.mapNotNull(::normalizeDocumentUrl).toSet()
        val matches = candidates.filter { normalizeDocumentUrl(it.url)?.let(urls::contains) == true }
        return matches.singleOrNull { it.kotlinTabId == selectedKotlinTabId }?.kotlinTabId
            ?: matches.singleOrNull()?.kotlinTabId
    }

    fun forgetDetectorTab(incomingEpoch: Long, detectorTabId: Int): String? {
        if (incomingEpoch != detectorEpoch) return null
        retiredDetectorTabs.add(detectorTabId)
        val binding = bindings.remove(detectorTabId) ?: return null
        detectorTabByKotlinTab.remove(binding.kotlinTabId, detectorTabId)
        return binding.kotlinTabId
    }

    fun forgetKotlinTab(tabId: String) {
        sessionChanged(tabId)
        createdTabs.remove(tabId)
    }

    fun acceptEpoch(incomingEpoch: Long): Boolean {
        val current = detectorEpoch
        if (current != null && incomingEpoch < current) return false
        if (current == null || incomingEpoch > current) {
            detectorEpoch = incomingEpoch
            bindings.clear()
            detectorTabByKotlinTab.clear()
            retiredDetectorTabs.clear()
        }
        return true
    }

    private fun pruneClosedTabs(liveTabIds: Set<String>) {
        bindings.entries.removeAll { (detectorTabId, binding) ->
            if (binding.kotlinTabId in liveTabIds) false else {
                detectorTabByKotlinTab.remove(binding.kotlinTabId, detectorTabId)
                retiredDetectorTabs.add(detectorTabId)
                true
            }
        }
        // Creation is registered before AddTabAction reaches the store. Do not
        // discard those records during that asynchronous link/selection window.
    }
}

private fun normalizeDocumentUrl(url: String?): String? =
    url?.trim()?.substringBefore("#")?.takeIf { it.isNotEmpty() && it != "about:blank" }
