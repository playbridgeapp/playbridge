package com.playbridge.sender.cast.atv

import com.playbridge.sender.cast.remote.RemoteAvailability
import com.playbridge.sender.cast.remote.RemoteTVChoice
import com.playbridge.sender.connection.ConnectionMerge
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice

data class RemoteTVRow(
    val device: TvDevice,
    val paired: Boolean,
    val ambiguous: Boolean,
    val receiverReachable: Boolean,
    val tvReachable: Boolean,
) {
    val endpoint: String get() = device.endpointKey.toString()
    val status: String get() = RemoteAvailability.pickerStatus(receiverReachable, tvReachable, paired, ambiguous)
    val choice: RemoteTVChoice get() = RemoteTVChoice(endpoint, paired, tvReachable, ambiguous)
}

/** Endpoint ownership uses the existing matcher. Names/addresses never transfer credentials. */
internal object RemoteTVCatalog {
    fun rows(
        saved: List<TvDevice>, selected: TvDevice?, records: List<AtvTvRecord>,
        discovered: List<TvDevice>, tvDevices: List<AtvDiscoveredDevice>,
        receiverOnline: Set<String>, connectedTV: String?, receiverOffline: Set<String> = emptySet(),
    ): List<RemoteTVRow> {
        val devices = linkedMapOf<String, TvDevice>()
        fun append(device: TvDevice) {
            if (device.resolvedProtocol == CastProtocol.PLAYBRIDGE) devices.putIfAbsent(device.endpointKey.toString(), device)
        }
        selected?.let(::append)
        saved.forEach(::append)
        discovered.forEach(::append)
        records.forEach { record ->
            if (devices.keys.none { it in record.receiverIds }) {
                record.receiverIds.sorted().firstOrNull { it.startsWith("playbridge:") }?.let { endpoint ->
                    append(TvDevice(ip = record.lastIp.orEmpty(), port = com.playbridge.shared.protocol.Config.DEFAULT_PORT, token = "",
                        name = record.serverName ?: "TV", uuid = endpoint.removePrefix("playbridge:")))
                }
            }
        }
        val fingerprints = mutableSetOf<String>()
        return devices.values.mapNotNull { savedDevice ->
            var device = ConnectionMerge.withDiscoveredEndpoint(savedDevice, discovered)
            val target = device.toAtvTarget()
            val ambiguous = records.count { target.endpoint.toString() in it.receiverIds } > 1
            val match = AtvDeviceMatcher.match(target, tvDevices, records)
            if (match.record != null && !fingerprints.add(match.record.fingerprint)) return@mapNotNull null
            if (match.device != null && match.host != null) device = device.copy(ip = match.host, addresses = listOf(match.host))
            RemoteTVRow(device, match.record != null, ambiguous,
                discovered.any { ConnectionMerge.isSameDevice(it, device) } ||
                    (target.endpoint.toString() !in receiverOffline && target.endpoint.toString() in receiverOnline),
                !ambiguous && (match.device != null || connectedTV == target.endpoint.toString()))
        }
    }
}
