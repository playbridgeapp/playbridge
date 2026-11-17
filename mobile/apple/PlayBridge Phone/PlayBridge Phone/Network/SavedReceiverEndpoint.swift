import Foundation

/// Discovery updates routing information, never the saved receiver's identity or trust.
enum SavedReceiverEndpoint {
    static func refresh(_ saved: PairedDevice, from devices: [DiscoveredDevice]) -> PairedDevice {
        // Only a saved receiver that already has an id, matched by that id.
        // Address matching belongs to isLive: a uuid-less record must not take
        // the name of whoever currently answers at its address.
        guard !saved.uuid.isEmpty,
              let live = devices.first(where: { sameUUID($0.uuid, saved.uuid) && valid($0) }) else { return saved }
        var updated = saved
        updated.ip = live.ip
        updated.port = live.port
        if !live.name.isEmpty { updated.name = live.name }
        // A partial/legacy advertisement must not downgrade a pinned WSS receiver.
        updated.wssPort = live.wssPort ?? saved.wssPort
        return updated
    }

    /// Both non-empty ids must be equal. Address is only an identity when one side has no id.
    static func sameIdentity(_ saved: PairedDevice, _ live: DiscoveredDevice) -> Bool {
        if !saved.uuid.isEmpty, !live.uuid.isEmpty { return sameUUID(saved.uuid, live.uuid) }
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

    /// Discovered TV currently answering at the saved TV's IP and port (e.g. after a factory
    /// reset or app reinstall assigned a new UUID). Never carries credentials or TLS pins.
    static func installedReplacement(for saved: PairedDevice, in discovered: [DiscoveredDevice]) -> DiscoveredDevice? {
        let savedIP = saved.ip.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !savedIP.isEmpty, (1...65535).contains(saved.port) else { return nil }
        return discovered.first { live in
            valid(live)
                && live.ip.trimmingCharacters(in: .whitespacesAndNewlines) == savedIP
                && live.port == saved.port
        }
    }

    /// Clean un-credentialed target for re-pairing a reset/reinstalled TV. Drops any stale UUID
    /// when no live replacement has been discovered yet so code verification re-establishes trust.
    static func repairingTarget(from saved: PairedDevice, discovered: [DiscoveredDevice]) -> DiscoveredDevice {
        if let replacement = installedReplacement(for: saved, in: discovered) {
            return replacement
        }
        return DiscoveredDevice(
            ip: saved.ip,
            port: saved.port,
            name: saved.name,
            uuid: "",
            wssPort: saved.wssPort
        )
    }

    /// Drops only the specified saved TV (plus any stale entry at the same key) while leaving
    /// every other saved receiver untouched so re-pairing cannot create a duplicate entry.
    static func removingDevice(
        _ device: PairedDevice,
        from history: [PairedDevice],
        keyFor: (PairedDevice) -> String
    ) -> [PairedDevice] {
        let targetKey = keyFor(device)
        return history.filter { keyFor($0) != targetKey }
    }

    /// UPnP USNs (`uuid:X::upnp:rootdevice` and `uuid:X::urn:…`) are one device.
    static func sameUUID(_ lhs: String, _ rhs: String) -> Bool {
        func head(_ id: String) -> String {
            guard let range = id.range(of: "::") else { return id }
            let head = String(id[..<range.lowerBound])
            return head.isEmpty ? id : head
        }
        let left = head(lhs)
        let right = head(rhs)
        return !left.isEmpty && left == right
    }

    private static func valid(_ device: DiscoveredDevice) -> Bool {
        !device.ip.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && (1...65535).contains(device.port)
            && (device.wssPort.map { (1...65535).contains($0) } ?? true)
    }
}
