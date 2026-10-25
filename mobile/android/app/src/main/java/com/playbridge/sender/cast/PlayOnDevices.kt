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

/** Receiver names as people know them: hostnames lose their mDNS ".local", also inside "Kodi (Host.local)". */
internal val TvDevice.playOnName: String get() = name.replace(".local)", ")").removeSuffix(".local")

/**
 * What the Dashboard device chip says: videos play either on a chosen TV or on this phone.
 * Connecting, reconnecting and offline describe the chosen TV's link, not a third destination.
 */
internal data class PlayOnStatus(
    val state: State = State.THIS_PHONE,
    val deviceName: String = THIS_PHONE_NAME,
    /** Receiver kind for a chosen TV ("PlayBridge", "Google Cast", …); null on this phone. */
    val receiver: String? = null,
    val secure: Boolean = false,
) {
    enum class State { THIS_PHONE, CONNECTING, RECONNECTING, OFFLINE, CONNECTED }

    val tvChosen: Boolean get() = state != State.THIS_PHONE
    val isConnected: Boolean get() = state == State.CONNECTED
    /** A plain-WebSocket PlayBridge link keeps its encryption warning. */
    val unencrypted: Boolean get() = isConnected && !secure
    val statusLine: String get() = when (state) {
        State.THIS_PHONE -> "Plays here · Tap to pick a TV"
        State.CONNECTING -> "Connecting…"
        State.RECONNECTING -> "Reconnecting…"
        State.OFFLINE -> "Not connected"
        State.CONNECTED -> "${receiver ?: "TV"} · ${if (unencrypted) "Not encrypted" else "Connected"}"
    }

    companion object {
        const val THIS_PHONE_NAME = "This phone"
    }
}

/**
 * Presentation follows the selected route, never the socket alone: on [CastSessionManager.Route.ThisDevice]
 * the chip says This phone even while an idle native link is still open.
 */
internal fun playOnStatus(
    route: CastSessionManager.Route,
    socketConnected: Boolean,
    nativeDevice: TvDevice?,
    externalDevice: TvDevice?,
    externalReady: Boolean,
    socketSecure: Boolean = false,
    connecting: Boolean = false,
    reconnecting: Boolean = false,
): PlayOnStatus = when (route) {
    is CastSessionManager.Route.External -> {
        val name = externalDevice?.playOnName ?: route.name.removeSuffix(".local")
        val receiver = externalDevice?.resolvedProtocol?.displayName
        // External transports use the neutral presentation, independent of the native socket.
        when {
            externalReady -> PlayOnStatus(PlayOnStatus.State.CONNECTED, name, receiver, secure = true)
            connecting -> PlayOnStatus(PlayOnStatus.State.CONNECTING, name, receiver)
            else -> PlayOnStatus(PlayOnStatus.State.OFFLINE, name, receiver)
        }
    }
    CastSessionManager.Route.NativeTv -> {
        val name = nativeDevice?.playOnName ?: "TV"
        val receiver = CastProtocol.PLAYBRIDGE.displayName
        when {
            socketConnected -> PlayOnStatus(PlayOnStatus.State.CONNECTED, name, receiver, secure = socketSecure)
            reconnecting -> PlayOnStatus(PlayOnStatus.State.RECONNECTING, name, receiver)
            connecting -> PlayOnStatus(PlayOnStatus.State.CONNECTING, name, receiver)
            else -> PlayOnStatus(PlayOnStatus.State.OFFLINE, name, receiver)
        }
    }
    CastSessionManager.Route.ThisDevice -> PlayOnStatus()
}

/** Desktop receivers advertise their hostname and no TV player engines. */
internal val TvDevice.isDesktopReceiver: Boolean
    get() = resolvedProtocol == CastProtocol.PLAYBRIDGE && name.endsWith(".local")
