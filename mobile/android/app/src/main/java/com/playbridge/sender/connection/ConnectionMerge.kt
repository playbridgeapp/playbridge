package com.playbridge.sender.connection

import com.playbridge.sender.model.TvDevice

/** Pure connection-bookkeeping helpers, extracted for testability. */
object ConnectionMerge {
    /**
     * Returns [device] with its wssPort taken from the live [discovered] list when a
     * match is found (by uuid, then ip/port). A saved/history entry may predate TLS,
     * so we prefer the currently-advertised port. Token + certFingerprint are kept.
     */
    fun withDiscoveredWssPort(device: TvDevice, discovered: List<TvDevice>): TvDevice {
        val match = (if (device.uuid.isNotEmpty()) discovered.find { it.uuid == device.uuid } else null)
            ?: discovered.find { it.ip == device.ip && it.port == device.port }
        return device.copy(wssPort = match?.wssPort ?: device.wssPort)
    }

    /**
     * Combine native (mDNS) and DLNA (SSDP) discovery into one list. We intentionally
     * keep BOTH entries when one device offers both, so the user can choose the
     * full-featured native path or the DLNA renderer (distinguished by a badge).
     */
    fun mergeDiscovered(native: List<TvDevice>, dlna: List<TvDevice>): List<TvDevice> =
        native + dlna.filter { it.ip.isNotEmpty() }

    /** Same physical device: uuid match when both known, else ip/port. */
    fun isSameDevice(a: TvDevice, b: TvDevice): Boolean =
        (a.uuid.isNotEmpty() && a.uuid == b.uuid) || (a.ip == b.ip && a.port == b.port)

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
