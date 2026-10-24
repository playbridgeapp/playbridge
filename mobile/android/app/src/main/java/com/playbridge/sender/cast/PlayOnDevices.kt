package com.playbridge.sender.cast

import com.playbridge.sender.cast.atv.RemoteTVRow
import com.playbridge.sender.connection.ConnectionMerge
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice

internal data class PlayOnDevice(
    val device: TvDevice,
    val saved: TvDevice? = null,
    val reachable: Boolean = true,
    val remoteStatus: String? = null,
    val remotePaired: Boolean = false,
)

internal data class PlayOnDevices(
    val saved: List<PlayOnDevice>,
    val nearby: List<PlayOnDevice>,
    val unavailable: List<PlayOnDevice>,
)

/** Live endpoints refresh addresses; only history / paired remotes belong in Saved. */
internal fun groupPlayOnDevices(
    history: List<TvDevice>,
    discovered: List<TvDevice>,
    remoteRows: List<RemoteTVRow>,
    pings: Map<String, Boolean>,
    browserDevices: List<TvDevice> = emptyList(),
    playBridgeOnly: Boolean = false,
): PlayOnDevices {
    val live = discovered + browserDevices
    val saved = mutableListOf<PlayOnDevice>()
    (history + remoteRows.filter { it.paired }.map { it.device }).forEach { entry ->
        if (playBridgeOnly && entry.resolvedProtocol != CastProtocol.PLAYBRIDGE) return@forEach
        if (saved.any { ConnectionMerge.isSameDevice(it.device, entry) }) return@forEach
        val remote = remoteRows.firstOrNull { ConnectionMerge.isSameDevice(it.device, entry) }
        // A ping only proves something answers at that address; a different live receiver may hold it now.
        val addressTaken = live.any {
            it.ip == entry.ip && it.port == entry.port && ConnectionMerge.hasOtherId(it, entry)
        }
        saved += PlayOnDevice(
            device = ConnectionMerge.withDiscoveredEndpoint(remote?.device ?: entry, live),
            saved = history.firstOrNull { ConnectionMerge.isSameDevice(it, entry) },
            reachable = live.any { ConnectionMerge.isSameDevice(it, entry) } ||
                remote?.receiverReachable == true || remote?.tvReachable == true ||
                (!addressTaken && pings[entry.endpointKey.toString()] == true),
            remoteStatus = remote?.status,
            remotePaired = remote?.paired == true,
        )
    }
    val ordered = saved.sortedWith(compareByDescending<PlayOnDevice> { it.reachable }
        .thenByDescending { it.saved?.lastConnected ?: it.device.lastConnected })
    val nearby = mutableListOf<PlayOnDevice>()
    live.forEach { device ->
        if (playBridgeOnly && device.resolvedProtocol != CastProtocol.PLAYBRIDGE) return@forEach
        if ((saved + nearby).any { ConnectionMerge.isSameDevice(it.device, device) }) return@forEach
        nearby += PlayOnDevice(device)
    }
    val protocolOrder = listOf(CastProtocol.PLAYBRIDGE, CastProtocol.GOOGLE_CAST,
        CastProtocol.DLNA, CastProtocol.ROKU, CastProtocol.WEB_BROWSER)
    return PlayOnDevices(ordered.filter { it.reachable },
        nearby.sortedBy { protocolOrder.indexOf(it.device.resolvedProtocol) },
        ordered.filterNot { it.reachable })
}

/** Receiver names as people know them: hostnames lose their mDNS ".local" suffix. */
internal val TvDevice.playOnName: String get() = name.removeSuffix(".local")

/** Desktop receivers advertise their hostname and no TV player engines. */
internal val TvDevice.isDesktopReceiver: Boolean
    get() = resolvedProtocol == CastProtocol.PLAYBRIDGE && name.endsWith(".local")
