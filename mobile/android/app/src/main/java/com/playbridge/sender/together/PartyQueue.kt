package com.playbridge.sender.together

import com.playbridge.sender.cast.proxy.StreamRouteService
import com.playbridge.shared.together.TogetherEvent
import com.playbridge.shared.together.TogetherItem
import com.playbridge.shared.together.TogetherPolicy
import com.playbridge.shared.together.TogetherSource
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import playbridge.PlayPayload

/**
 * One item waiting in the phone's host-side party queue (`Up next`).
 *
 * Kept strictly in memory for the current party. Headers/cookies are never logged or
 * persisted and are only forwarded to this phone's own devices when loaded.
 */
data class PartyQueueItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val site: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<String> = emptyList(),
    val startMs: Long = 0L,
) {
    override fun toString(): String = "PartyQueueItem(id=$id, site=$site)"

    fun toTogetherItem(): TogetherItem = TogetherItem(
        title = title,
        site = site,
        source = TogetherSource.Url(url),
        headers = headers,
        subtitles = subtitles,
        startMs = startMs.coerceAtLeast(0L),
    )
}

/**
 * Ordered in-memory queue owned by the phone for the currently active watch party.
 * Not synced to the room or persisted across restarts.
 */
class PartyQueue(initial: List<PartyQueueItem> = emptyList()) {
    private val entries = initial.toMutableList()

    val items: List<PartyQueueItem> get() = entries.toList()
    val size: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()

    fun add(item: PartyQueueItem) {
        entries.add(item)
    }

    fun addAll(items: List<PartyQueueItem>) {
        entries.addAll(items)
    }

    fun remove(id: String): PartyQueueItem? {
        val index = entries.indexOfFirst { it.id == id }
        return if (index >= 0) entries.removeAt(index) else null
    }

    fun removeAll(ids: Set<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val removed = linkedSetOf<String>()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.id in ids) {
                removed += item.id
                iterator.remove()
            }
        }
        return removed
    }

    fun move(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in entries.indices || toIndex !in entries.indices || fromIndex == toIndex) {
            return false
        }
        val item = entries.removeAt(fromIndex)
        entries.add(toIndex, item)
        return true
    }

    fun peek(): PartyQueueItem? = entries.firstOrNull()

    fun takeNext(): PartyQueueItem? = if (entries.isNotEmpty()) entries.removeAt(0) else null

    fun clear() {
        entries.clear()
    }
}

/** Ordered snapshots emitted synchronously alongside in-memory [PartyQueue] mutations. */
sealed interface PartyQueueUpdate {
    val queueSnapshot: List<PartyQueueItem>

    data class Enqueued(
        val addedIds: List<String>,
        override val queueSnapshot: List<PartyQueueItem>,
    ) : PartyQueueUpdate

    data class LoadedFromQueue(
        val item: PartyQueueItem,
        override val queueSnapshot: List<PartyQueueItem>,
    ) : PartyQueueUpdate

    data class Removed(
        val removedId: String,
        override val queueSnapshot: List<PartyQueueItem>,
    ) : PartyQueueUpdate

    data class Reordered(
        override val queueSnapshot: List<PartyQueueItem>,
    ) : PartyQueueUpdate

    data class Cleared(
        override val queueSnapshot: List<PartyQueueItem>,
    ) : PartyQueueUpdate

    data class ReplacedSessionQueue(
        val removedIds: Set<String>,
        override val queueSnapshot: List<PartyQueueItem>,
    ) : PartyQueueUpdate

    data object ManualPlay : PartyQueueUpdate {
        override val queueSnapshot: List<PartyQueueItem> = emptyList()
    }
}

sealed interface PartyAdvanceDecision {
    data object None : PartyAdvanceDecision
    /** Room emitted Ended for [itemId]; wait briefly for leader's clear before advancing. */
    data class WaitForClear(val itemId: String) : PartyAdvanceDecision
    data class Load(val item: PartyQueueItem) : PartyAdvanceDecision
    data class Paused(val reason: String) : PartyAdvanceDecision
}

/**
 * Pure state machine deciding when the phone's party queue auto-advances.
 *
 * Rules:
 * - Only advances after an `Ended(item)` for the current item, never on an unprompted `VideoCleared`
 *   (which means another host or delegate pressed Stop).
 * - Waits after `Ended` for `VideoCleared` (leader auto-clear) or an explicit `onGraceElapsed`
 *   input (guest-only room that emits `Ended` without clearing), so loads don't race the clear.
 * - Cancels pending advance on a new `Video`, manual stop, `VideoCleared` consumed, or reset.
 * - Guards against repeats, stale `Ended`, permission loss, and skipping after load failures.
 */
class PartyAdvanceTracker {
    private var activeItemId: String? = null
    private val consumedEndedIds = linkedSetOf<String>()
    private var pendingEndItemId: String? = null
    private var manualStopPending: Boolean = false
    private var manualStopItemId: String? = null
    private var awaitingQueueLoad: Boolean = false
    private var lastLoadedFromQueueItemId: String? = null
    private var haltedOnFailure: Boolean = false

    val isHaltedOnFailure: Boolean get() = haltedOnFailure
    val isWaitingForClear: Boolean get() = pendingEndItemId != null

    fun reset() {
        activeItemId = null
        consumedEndedIds.clear()
        pendingEndItemId = null
        manualStopPending = false
        manualStopItemId = null
        awaitingQueueLoad = false
        lastLoadedFromQueueItemId = null
        haltedOnFailure = false
    }

    /** Clears failure/manual-stop/pending latches when the user explicitly plays or steps to an item. */
    fun onExplicitLoadSent(fromQueue: Boolean = false) {
        manualStopPending = false
        manualStopItemId = null
        pendingEndItemId = null
        haltedOnFailure = false
        awaitingQueueLoad = fromQueue
        if (!fromQueue) lastLoadedFromQueueItemId = null
    }

    fun onManualStopRequested(currentItemId: String?) {
        manualStopPending = true
        manualStopItemId = currentItemId ?: activeItemId ?: pendingEndItemId
        pendingEndItemId = null
        awaitingQueueLoad = false
    }

    fun onManualStopSendFailed() {
        manualStopPending = false
        manualStopItemId = null
    }

    /**
     * Marks a queue-triggered load as failed synchronously (before the room acknowledged it)
     * or asynchronously by an error event.
     */
    fun onQueuedLoadFailed(): PartyAdvanceDecision {
        awaitingQueueLoad = false
        pendingEndItemId = null
        haltedOnFailure = true
        activeItemId?.let { consumedEndedIds.add(it) }
        lastLoadedFromQueueItemId?.let { consumedEndedIds.add(it) }
        return PartyAdvanceDecision.Paused("Couldn't load the next queued video. Up next is paused.")
    }

    fun onEvent(
        event: TogetherEvent,
        party: PhoneTogether.Party,
        queue: PartyQueue,
    ): PartyAdvanceDecision = when (event) {
        is TogetherEvent.Video -> {
            // Anyone loading a video cancels any pending end->clear advance; queue stays intact.
            pendingEndItemId = null
            activeItemId = event.item.id
            if (event.item.id != manualStopItemId) {
                manualStopPending = false
                manualStopItemId = null
            }
            if (awaitingQueueLoad) {
                awaitingQueueLoad = false
                lastLoadedFromQueueItemId = event.item.id
                haltedOnFailure = false
            } else {
                lastLoadedFromQueueItemId = null
            }
            PartyAdvanceDecision.None
        }

        is TogetherEvent.Ended -> {
            val currentId = party.item?.id ?: activeItemId
            when {
                currentId == null || event.item != currentId -> PartyAdvanceDecision.None
                event.item in consumedEndedIds || event.item == pendingEndItemId -> PartyAdvanceDecision.None
                manualStopPending && (manualStopItemId == null || manualStopItemId == event.item) -> {
                    consumedEndedIds.add(event.item)
                    pendingEndItemId = null
                    manualStopPending = false
                    manualStopItemId = null
                    activeItemId = null
                    PartyAdvanceDecision.None
                }
                else -> {
                    consumedEndedIds.add(event.item)
                    activeItemId = null
                    if (haltedOnFailure || queue.isEmpty) {
                        pendingEndItemId = null
                        PartyAdvanceDecision.None
                    } else {
                        pendingEndItemId = event.item
                        PartyAdvanceDecision.WaitForClear(event.item)
                    }
                }
            }
        }

        TogetherEvent.VideoCleared -> {
            val pendingId = pendingEndItemId
            pendingEndItemId = null
            val currentId = party.item?.id ?: activeItemId
            activeItemId = null
            when {
                manualStopPending -> {
                    (currentId ?: pendingId)?.let { consumedEndedIds.add(it) }
                    manualStopPending = false
                    manualStopItemId = null
                    PartyAdvanceDecision.None
                }
                pendingId != null -> decideAdvance(party, queue)
                else -> PartyAdvanceDecision.None
            }
        }

        is TogetherEvent.Error -> {
            if (awaitingQueueLoad || (event.context in setOf("load", "play") && lastLoadedFromQueueItemId != null && lastLoadedFromQueueItemId == party.item?.id)) {
                onQueuedLoadFailed()
            } else {
                PartyAdvanceDecision.None
            }
        }

        else -> PartyAdvanceDecision.None
    }

    /** Called by PhoneTogether when the post-Ended clear grace expires without a VideoCleared event. */
    fun onGraceElapsed(party: PhoneTogether.Party, queue: PartyQueue): PartyAdvanceDecision {
        if (pendingEndItemId == null) return PartyAdvanceDecision.None
        pendingEndItemId = null
        activeItemId = null
        return decideAdvance(party, queue)
    }

    /** Called when PhoneTogether sees the phone player fail before playback started on [itemId]. */
    fun onPhonePlayerLoadFailed(itemId: String): Boolean {
        val matchesPending = awaitingQueueLoad || (lastLoadedFromQueueItemId != null && lastLoadedFromQueueItemId == itemId)
        consumedEndedIds.add(itemId)
        if (matchesPending) {
            awaitingQueueLoad = false
            pendingEndItemId = null
            haltedOnFailure = true
            return true
        }
        return false
    }

    private fun decideAdvance(party: PhoneTogether.Party, queue: PartyQueue): PartyAdvanceDecision {
        if (haltedOnFailure || queue.isEmpty) return PartyAdvanceDecision.None
        if (!party.canChangeVideo) {
            val reason = when {
                party.locked -> "Up next paused: the host locked the controls."
                party.policy == TogetherPolicy.OnlyHost -> "Up next paused: only the host can change the video."
                else -> "Up next paused: you need the host to approve videos."
            }
            return PartyAdvanceDecision.Paused(reason)
        }
        val next = queue.takeNext() ?: return PartyAdvanceDecision.None
        awaitingQueueLoad = true
        lastLoadedFromQueueItemId = null
        return PartyAdvanceDecision.Load(next)
    }
}

/** Helpers for validating URLs and turning playlists into party items. */
object PartyMediaSupport {
    data class PlaylistSplit(
        val first: PartyQueueItem?,
        val rest: List<PartyQueueItem>,
    )

    fun siteFromUrl(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }

    fun itemOf(
        url: String,
        title: String?,
        headers: Map<String, String> = emptyMap(),
        subtitles: List<String> = emptyList(),
        startMs: Long? = 0L,
        fallbackSite: String? = null,
    ): PartyQueueItem {
        val site = siteFromUrl(url) ?: fallbackSite?.takeIf { it.isNotBlank() }
        return PartyQueueItem(
            url = url,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: site ?: "Video",
            site = site,
            headers = headers,
            subtitles = subtitles,
            startMs = (startMs ?: 0L).coerceAtLeast(0L),
        )
    }

    fun itemOf(
        payload: PlayPayload,
        fallbackTitle: String? = null,
        fallbackSite: String? = null,
    ): PartyQueueItem {
        val rawTitle = payload.title
        return itemOf(
            url = payload.url,
            title = if (!rawTitle.isNullOrBlank()) rawTitle else fallbackTitle,
            headers = payload.headers,
            subtitles = payload.subtitles + payload.subtitle_resources.map { it.url },
            startMs = payload.start_position_ms,
            fallbackSite = fallbackSite,
        )
    }

    /** Splits a list so the first item plays now and any remaining items append to Up next in order. */
    fun splitForPlay(items: List<PartyQueueItem>): PlaylistSplit = PlaylistSplit(
        first = items.firstOrNull(),
        rest = if (items.size > 1) items.drop(1) else emptyList(),
    )

    data class PartyUrlCheck(
        val rejection: String? = null,
        val isPrivateNetwork: Boolean = false,
    )

    /**
     * Evaluates whether [url] can be played/queued in a watch party:
     * - rejects non-HTTP schemes, loopback/localhost/`0.0.0.0`, and this phone's own interface addresses;
     * - allows other private-network hosts (home LAN NAS/Jellyfin, `.local`, CGNAT, RFC1918) with
     *   [PartyUrlCheck.isPrivateNetwork] = true so the caller can warn the user once.
     */
    fun evaluatePartyUrl(
        url: String,
        ownAddresses: Set<String> = StreamRouteService.ownHostAddresses(),
    ): PartyUrlCheck {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return PartyUrlCheck(rejection = "This video doesn't have a playable link.")
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return PartyUrlCheck(rejection = "This video's link isn't valid for a watch party.")
        val scheme = uri.scheme?.lowercase()
        when (scheme) {
            "content", "file" -> return PartyUrlCheck(rejection = "Files stored on this phone can't be played in a watch party.")
            "data", "blob" -> return PartyUrlCheck(rejection = "This phone-only stream can't be shared with a watch party.")
            "playlist" -> return PartyUrlCheck(rejection = "This playlist doesn't contain playable links.")
            "http", "https" -> Unit
            else -> return PartyUrlCheck(rejection = "Watch party only supports web video links.")
        }
        val host = uri.host?.trim('[', ']')?.substringBefore('%')?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: return PartyUrlCheck(rejection = "This video's link isn't valid for a watch party.")
        if (isLoopbackOrUnspecifiedHost(host)) {
            return PartyUrlCheck(rejection = "Loopback links can't be played in a watch party because other devices can't reach this phone.")
        }
        if (matchesOwnAddress(host, ownAddresses)) {
            return PartyUrlCheck(rejection = "Links hosted on this phone can't be played in a watch party.")
        }
        return PartyUrlCheck(rejection = null, isPrivateNetwork = isPrivateLanHost(host))
    }

    /**
     * Returns `null` when [url] can be played in a watch party, or a plain-language explanation when refused.
     */
    fun checkPartyUrl(
        url: String,
        ownAddresses: Set<String> = StreamRouteService.ownHostAddresses(),
    ): String? = evaluatePartyUrl(url, ownAddresses).rejection

    internal fun isLoopbackOrUnspecifiedHost(rawHost: String): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isEmpty()) return true
        if (host == "localhost" || host.endsWith(".localhost") || host == "0.0.0.0" || host == "::" || host == "::1") {
            return true
        }
        val addr = parseIpLiteralOrNull(host) ?: return false
        return addr.isLoopbackAddress || addr.isAnyLocalAddress
    }

    internal fun isPrivateLanHost(rawHost: String): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isEmpty()) return false
        if (host.endsWith(".local")) return true
        val addr = parseIpLiteralOrNull(host) ?: return false
        if (addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isMulticastAddress) return true
        return isCarrierGradeNat(addr) || isUniqueLocalIpv6(addr)
    }

    private fun matchesOwnAddress(host: String, ownAddresses: Set<String>): Boolean {
        if (ownAddresses.isEmpty()) return false
        val normalizedHost = normalizeHost(host)
        val normalizedSet = ownAddresses.asSequence().map(::normalizeHost).toSet()
        if (normalizedHost in normalizedSet) return true
        val hostBytes = parseIpLiteralOrNull(normalizedHost)?.address ?: return false
        return normalizedSet.any { owned ->
            val ownedBytes = parseIpLiteralOrNull(owned)?.address ?: return@any false
            hostBytes.contentEquals(ownedBytes)
        }
    }

    private fun normalizeHost(raw: String): String =
        raw.trim().trim('[', ']').substringBefore('%').lowercase()

    private fun parseIpLiteralOrNull(host: String): InetAddress? {
        if (!isIpv4Literal(host) && ':' !in host) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull() ?: return@all false
            n in 0..255
        }
    }

    private fun isCarrierGradeNat(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 100 && second in 64..127
    }

    private fun isUniqueLocalIpv6(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size != 16) return false
        val first = bytes[0].toInt() and 0xFF
        return (first and 0xFE) == 0xFC
    }
}
