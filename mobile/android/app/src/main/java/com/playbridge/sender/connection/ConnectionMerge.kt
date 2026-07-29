package com.playbridge.sender.connection

import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice

/** Pure connection-bookkeeping helpers, extracted for testability. */
object ConnectionMerge {
    /** Paired / saved PlayBridge receivers shown as first-class "Your TVs". */
    const val MAX_PLAYBRIDGE_HISTORY = 10

    /**
     * Recent non-PlayBridge cast targets kept as optional shortcuts (DLNA / Roku / Cast).
     * These have no pairing token — persistence is convenience only.
     */
    const val MAX_EXTERNAL_HISTORY = 3
    /**
     * Returns [device] with its complete endpoint taken from the live [discovered] list
     * when a match is found (by uuid, then ip/port). Receiver ports and DHCP addresses
     * can change between launches, while credentials remain attached to stable identity.
     */
    fun withDiscoveredEndpoint(device: TvDevice, discovered: List<TvDevice>): TvDevice {
        val sameProtocol = discovered.filter { it.resolvedProtocol == device.resolvedProtocol }
        val match = (if (device.uuid.isNotEmpty()) sameProtocol.find { it.uuid == device.uuid } else null)
            ?: sameProtocol.find { it.ip == device.ip && it.port == device.port }
            ?: return device
        return device.copy(
            ip = match.ip,
            addresses = match.addresses,
            port = match.port,
            name = match.name,
            wssPort = match.wssPort,
            logsPort = match.logsPort,
            descriptionUrl = match.descriptionUrl,
            controlUrl = match.controlUrl,
            renderingControlUrl = match.renderingControlUrl,
        )
    }

    /** Same protocol endpoint: stable ID match when known, else protocol + ip/port. */
    fun isSameDevice(a: TvDevice, b: TvDevice): Boolean =
        a.resolvedProtocol == b.resolvedProtocol &&
            ((a.uuid.isNotEmpty() && a.uuid == b.uuid) || (a.ip == b.ip && a.port == b.port))

    /**
     * Put [device] at the front of its protocol class while replacing older endpoints
     * for the same receiver. PlayBridge entries are first-class (pairing credentials);
     * external protocols are capped to [MAX_EXTERNAL_HISTORY] recent shortcuts.
     */
    fun upsertHistory(
        current: List<TvDevice>,
        device: TvDevice,
        playBridgeLimit: Int = MAX_PLAYBRIDGE_HISTORY,
        externalLimit: Int = MAX_EXTERNAL_HISTORY,
    ): List<TvDevice> {
        val without = current.filterNot { isSameDevice(it, device) }
        val playBridge = without.filter { it.resolvedProtocol == CastProtocol.PLAYBRIDGE }
        val external = without.filter { it.resolvedProtocol != CastProtocol.PLAYBRIDGE }
        return if (device.resolvedProtocol == CastProtocol.PLAYBRIDGE) {
            (listOf(device) + playBridge).take(playBridgeLimit) + external.take(externalLimit)
        } else {
            playBridge.take(playBridgeLimit) + (listOf(device) + external).take(externalLimit)
        }
    }

    /**
     * Collapse duplicates already written by older builds. History is newest-first,
     * so the first entry retains the current endpoint and pairing credentials.
     * Also re-applies PlayBridge / external caps for legacy oversized lists.
     */
    fun normalizeHistory(history: List<TvDevice>): List<TvDevice> {
        val unique = history.fold(emptyList<TvDevice>()) { acc, device ->
            if (acc.any { isSameDevice(it, device) }) acc else acc + device
        }
        val playBridge = unique.filter { it.resolvedProtocol == CastProtocol.PLAYBRIDGE }
            .take(MAX_PLAYBRIDGE_HISTORY)
        val external = unique.filter { it.resolvedProtocol != CastProtocol.PLAYBRIDGE }
            .take(MAX_EXTERNAL_HISTORY)
        return playBridge + external
    }

    fun playBridgeHistory(history: List<TvDevice>): List<TvDevice> =
        history.filter { it.resolvedProtocol == CastProtocol.PLAYBRIDGE }

    fun recentExternalHistory(history: List<TvDevice>): List<TvDevice> =
        history.filter { it.resolvedProtocol != CastProtocol.PLAYBRIDGE }

    /** Remove every stale endpoint that belongs to [device]. */
    fun removeHistoryDevice(history: List<TvDevice>, device: TvDevice): List<TvDevice> =
        history.filterNot { isSameDevice(it, device) }

    /** What to do with stored credentials after an auth failure / pairing denial. */
    enum class AuthFailureAction {
        /** First-time pairing with the stored device failed — forget it entirely. */
        CLEAR_SAVED_DEVICE,
        /** The stored, already-paired device's token was rejected — wipe just its token. */
        WIPE_SAVED_TOKEN,
        /** A different device failed — leave the stored device alone entirely. */
        WIPE_FAILED_HISTORY_ONLY,
    }

    /**
     * Decide which device's credentials an auth failure belongs to. [failed] is the
     * device of the in-flight connect attempt (null for the startup auto-connect,
     * where the failing device IS the stored [saved] one). Crucially, a failure while
     * pairing a NEW TV must never wipe the token of a different, already-paired TV —
     * that regression made saved TVs ask for the pairing code again.
     *
     * Returns the failing device plus the action to take, or null if nothing is known.
     */
    fun resolveAuthFailure(failed: TvDevice?, saved: TvDevice?): Pair<TvDevice, AuthFailureAction>? {
        val target = failed ?: saved ?: return null
        return if (saved != null && isSameDevice(target, saved)) {
            if (saved.token.isEmpty()) target to AuthFailureAction.CLEAR_SAVED_DEVICE
            else target to AuthFailureAction.WIPE_SAVED_TOKEN
        } else {
            target to AuthFailureAction.WIPE_FAILED_HISTORY_ONLY
        }
    }
}
