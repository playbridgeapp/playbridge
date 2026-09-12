import Foundation

/// Discovery updates routing information, never the saved receiver's identity or trust.
enum SavedReceiverEndpoint {
    static func refresh(_ saved: PairedDevice, from devices: [DiscoveredDevice]) -> PairedDevice {
        guard !saved.uuid.isEmpty,
              let live = devices.first(where: { $0.uuid == saved.uuid && valid($0) }) else { return saved }
        var updated = saved
        updated.ip = live.ip
        updated.port = live.port
        if !live.name.isEmpty { updated.name = live.name }
        // A partial/legacy advertisement must not downgrade a pinned WSS receiver.
        updated.wssPort = live.wssPort ?? saved.wssPort
        return updated
    }

    static func sameAddress(_ lhs: PairedDevice, _ rhs: PairedDevice) -> Bool {
        lhs.ip == rhs.ip && lhs.port == rhs.port && lhs.wssPort == rhs.wssPort
    }

    private static func valid(_ device: DiscoveredDevice) -> Bool {
        !device.ip.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && (1...65535).contains(device.port)
            && (device.wssPort.map { (1...65535).contains($0) } ?? true)
    }
}
