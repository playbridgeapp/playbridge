package com.playbridge.sender.cast

internal data class DetectorPageVersion(
    val detectorEpoch: Long,
    val navigationGeneration: Long,
)

internal enum class DetectorMessageOrder {
    ADVANCE,
    CURRENT,
    STALE,
}

/**
 * Orders asynchronous GeckoView detector messages across both document
 * navigations and background-extension restarts.
 */
internal fun detectorMessageOrder(
    current: DetectorPageVersion?,
    incoming: DetectorPageVersion,
): DetectorMessageOrder = when {
    current == null -> DetectorMessageOrder.ADVANCE
    incoming.detectorEpoch > current.detectorEpoch -> DetectorMessageOrder.ADVANCE
    incoming.detectorEpoch < current.detectorEpoch -> DetectorMessageOrder.STALE
    incoming.navigationGeneration > current.navigationGeneration -> DetectorMessageOrder.ADVANCE
    incoming.navigationGeneration < current.navigationGeneration -> DetectorMessageOrder.STALE
    else -> DetectorMessageOrder.CURRENT
}

internal class DetectorPageTracker {
    private val versions = mutableMapOf<String, DetectorPageVersion>()

    fun observe(tabId: String, incoming: DetectorPageVersion): DetectorMessageOrder {
        val order = detectorMessageOrder(versions[tabId], incoming)
        if (order == DetectorMessageOrder.ADVANCE) versions[tabId] = incoming
        return order
    }

    fun forget(tabId: String) {
        versions.remove(tabId)
    }

    fun clear() {
        versions.clear()
    }
}
