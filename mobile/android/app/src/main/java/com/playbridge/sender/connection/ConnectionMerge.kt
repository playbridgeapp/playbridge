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
}
