import Foundation

/// Discovery updates routing information, never the saved receiver's identity or trust.
enum SavedReceiverEndpoint {
    static func refresh(_ saved: PairedDevice, from devices: [DiscoveredDevice]) -> PairedDevice {
        guard let live = devices.first(where: { sameIdentity(saved, $0) && valid($0) }) else { return saved }
        var updated = saved
        updated.ip = live.ip
        updated.port = live.port
        if !live.name.isEmpty { updated.name = live.name }
        // A partial/legacy advertisement must not downgrade a pinned WSS receiver.
        // Identity stays on the saved record: a uuid-less advertiser must not replace it.
        updated.wssPort = live.wssPort ?? saved.wssPort
        return updated
    }

    /// Both non-empty ids must be equal. Address is only an identity when one side has no id.
    static func sameIdentity(_ saved: PairedDevice, _ live: DiscoveredDevice) -> Bool {
        if !saved.uuid.isEmpty, !live.uuid.isEmpty { return saved.uuid == live.uuid }
        return saved.ip == live.ip && saved.port == live.port
    }

    static func sameAddress(_ lhs: PairedDevice, _ rhs: PairedDevice) -> Bool {
        lhs.ip == rhs.ip && lhs.port == rhs.port && lhs.wssPort == rhs.wssPort
    }

    static func isLive(_ saved: PairedDevice, in devices: [DiscoveredDevice]) -> Bool {
        devices.contains { live in valid(live) && sameIdentity(saved, live) }
    }

    static func refreshHistory(_ saved: [PairedDevice], from devices: [DiscoveredDevice]) -> [PairedDevice] {
        saved.map { refresh($0, from: devices) }
    }

    private static func valid(_ device: DiscoveredDevice) -> Bool {
        !device.ip.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && (1...65535).contains(device.port)
            && (device.wssPort.map { (1...65535).contains($0) } ?? true)
    }
}
