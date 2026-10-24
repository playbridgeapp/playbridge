import Foundation

struct RemoteTVRow: Identifiable {
    let device: PairedDevice
    let paired: Bool
    let ambiguous: Bool
    let receiverReachable: Bool
    let tvReachable: Bool
    var id: String { AtvReceiverTarget(playBridge: device).endpoint }
    var status: String { RemoteAvailability.pickerStatus(receiver: receiverReachable, tvRemote: tvReachable, paired: paired, ambiguous: ambiguous) }
    var choice: RemoteTVChoice { .init(endpoint: id, paired: paired, reachable: tvReachable, ambiguous: ambiguous) }
}

/// Verified endpoint associations establish identity. Discovery only relocates that identity.
/// Multiple aliases of one verified record collapse to one row; ambiguous associations never gain authority.
enum RemoteTVCatalog {
    static func rows(saved: [PairedDevice], selected: PairedDevice?, records: [AtvTvRecord],
                     discovered: [DiscoveredDevice], tvDevices: [AtvDiscoveredDevice],
                     receiverOnline: Set<String>, connectedTV: String?, receiverOffline: Set<String> = []) -> [RemoteTVRow] {
        var devices: [PairedDevice] = []
        func append(_ device: PairedDevice) {
            let endpoint = AtvReceiverTarget(playBridge: device).endpoint
            if !devices.contains(where: { AtvReceiverTarget(playBridge: $0).endpoint == endpoint }) { devices.append(device) }
        }
        if let selected { append(selected) }
        saved.forEach(append)
        for live in discovered {
            append(PairedDevice(ip: live.ip, port: live.port, name: live.name, uuid: live.uuid, wssPort: live.wssPort))
        }
        for record in records {
            if devices.contains(where: { record.receiverIds.contains(AtvReceiverTarget(playBridge: $0).endpoint) }) { continue }
            if let endpoint = record.receiverIds.sorted().first(where: { $0.hasPrefix("playbridge:") }) {
                append(PairedDevice(ip: record.lastIp ?? "", port: ProtocolConstants.defaultPort,
                    name: record.serverName ?? "TV", uuid: String(endpoint.dropFirst("playbridge:".count))))
            }
        }
        var fingerprints = Set<String>()
        return devices.compactMap { saved in
            var device = SavedReceiverEndpoint.refresh(saved, from: discovered)
            let target = AtvReceiverTarget(playBridge: device)
            let associations = records.filter { $0.receiverIds.contains(target.endpoint) }
            let ambiguous = associations.count > 1
            let match = AtvDeviceMatcher.match(receiver: target, devices: tvDevices, records: records)
            if let record = match.record, !fingerprints.insert(record.fingerprint).inserted { return nil }
            let receiverLive = SavedReceiverEndpoint.isLive(device, in: discovered)
            if match.device != nil, let host = match.host { device.ip = host }
            return RemoteTVRow(device: device, paired: match.record != nil, ambiguous: ambiguous,
                receiverReachable: receiverLive || (!receiverOffline.contains(target.endpoint) && receiverOnline.contains(target.endpoint)),
                tvReachable: !ambiguous && (match.device != nil || connectedTV == target.endpoint))
        }
    }
}
