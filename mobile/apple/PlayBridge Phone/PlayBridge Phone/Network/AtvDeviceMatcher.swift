import Foundation

struct AtvReceiverTarget {
    /// Protocol-qualified receiver identity, identical to Android EndpointKey.toString().
    let endpoint: String
    let name: String
    let addresses: [String]
    let androidTv: Bool
    let googleCast: Bool

    /// Native PlayBridge only in this release. Other protocol endpoints have no adapter yet.
    init(playBridge receiver: PairedDevice) {
        endpoint = "playbridge:" + (receiver.uuid.isEmpty ? "\(receiver.ip):\(receiver.port)" : receiver.uuid)
        name = receiver.name
        addresses = receiver.ip.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? [] : [receiver.ip]
        androidTv = receiver.players.contains { $0.lowercased() == "exo" }
        googleCast = false
    }
    init(endpoint: String, name: String, addresses: [String], androidTv: Bool, googleCast: Bool = false) {
        self.endpoint = endpoint; self.name = name; self.addresses = addresses
        self.androidTv = androidTv; self.googleCast = googleCast
    }
}

struct AtvMatch {
    let record: AtvTvRecord?
    let device: AtvDiscoveredDevice?
    let available: Bool
    let host: String?
}

extension AtvDiscoveredDevice {
    var bluetoothMac: String? { txt.first { $0.count == 2 && $0[0].lowercased() == "bt" }?.last }
}

/// Port of Android AtvDeviceMatcher: endpoint IDs establish ownership; MAC only relocates an
/// already-associated TV. Names, IPs and MACs never transfer another receiver's credentials.
enum AtvDeviceMatcher {
    static let pairingPort: UInt16 = 6467
    static let controlPort: UInt16 = 6466

    static func match(receiver: AtvReceiverTarget, devices: [AtvDiscoveredDevice], records: [AtvTvRecord]) -> AtvMatch {
        let addresses = Set(receiver.addresses.map(normalizeIp).filter { !$0.isEmpty })
        let byIp = devices.first { device in
            (device.addresses + [device.host]).map(normalizeIp).contains { !$0.isEmpty && addresses.contains($0) }
        }
        let associated = records.filter { $0.receiverIds.contains(receiver.endpoint) }
        if associated.count > 1 { return .init(record: nil, device: nil, available: true, host: nil) }
        let record = associated.first
        let mac = normalizeMac(record?.mac)
        let relocated = mac == nil ? nil : devices.first { normalizeMac($0.bluetoothMac) == mac }
        let conflicts = record != nil && mac != nil && byIp?.bluetoothMac != nil && normalizeMac(byIp?.bluetoothMac) != mac
        let device = record == nil ? byIp : (relocated ?? (conflicts ? nil : byIp))
        if conflicts && relocated == nil { return .init(record: record, device: nil, available: true, host: nil) }
        let host = nonblank(device?.host) ?? receiver.addresses.first(where: { nonblank($0) != nil }) ?? nonblank(record?.lastIp)
        return .init(record: record, device: device, available: record != nil || receiver.androidTv || byIp != nil, host: host)
    }
    static func normalizeIp(_ value: String?) -> String {
        var value = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if value.hasPrefix("[") { value.removeFirst() }
        if value.hasSuffix("]") { value.removeLast() }
        return value.lowercased()
    }
    static func normalizeMac(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        return value.uppercased()
    }
    private static func nonblank(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return value
    }
}
