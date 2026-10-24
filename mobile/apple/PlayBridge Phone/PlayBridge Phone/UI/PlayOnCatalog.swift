import Foundation

/// One row the Play on sheet can place in Saved, Nearby, or Not available.
/// Identity matching uses `id` plus `matchKeys` (UUID and `ip:port`) so a live
/// discovery of a saved receiver does not also appear under Nearby.
struct PlayOnListedDevice: Equatable, Identifiable {
    enum Kind: String, Equatable {
        case playBridge
        case googleCast
        case dlna
        case roku
        case browser
    }

    let id: String
    let name: String
    let kind: Kind
    let address: String
    let reachable: Bool
    let saved: Bool
    let lastConnected: Date?
    let needsPairing: Bool
    let matchKeys: Set<String>
    /// Empty when this receiver has no stable id. Two non-empty ids that differ are different devices.
    var uuid: String = ""

    var protocolName: String {
        switch kind {
        case .playBridge: return "PlayBridge"
        case .googleCast: return "Google Cast"
        case .dlna: return "DLNA"
        case .roku: return "Roku"
        case .browser: return "Browser"
        }
    }
}

struct PlayOnGroups: Equatable {
    var reachableSaved: [PlayOnListedDevice]
    var nearby: [PlayOnListedDevice]
    var unavailable: [PlayOnListedDevice]
}

enum PlayOnCatalog {
    /// Saved rows split by reachability, most recently used first.
    /// Nearby drops a discovery only when it is the same receiver as a saved row.
    /// Two PlayBridge receivers that both have ids never match on address alone.
    static func group(saved: [PlayOnListedDevice], discovered: [PlayOnListedDevice]) -> PlayOnGroups {
        let nearby = discovered.filter { device in
            !saved.contains { sameReceiver($0, device) }
        }.sorted(by: nearbyOrder)
        return PlayOnGroups(
            reachableSaved: saved.filter(\.reachable).sorted(by: savedOrder),
            nearby: nearby,
            unavailable: saved.filter { !$0.reachable }.sorted(by: savedOrder)
        )
    }

    /// UUID match when both sides have one. Address match only when one side has no id.
    static func sameReceiver(_ saved: PlayOnListedDevice, _ live: PlayOnListedDevice) -> Bool {
        if saved.kind == .playBridge, live.kind == .playBridge {
            if !saved.uuid.isEmpty, !live.uuid.isEmpty { return saved.uuid == live.uuid }
            if !saved.address.isEmpty, saved.address == live.address { return true }
        }
        if saved.id == live.id { return true }
        return !saved.matchKeys.isDisjoint(with: live.matchKeys.union([live.id]))
    }

    /// A different identified receiver currently advertised at the saved address.
    static func addressHeldByOther(savedUUID: String, savedAddress: String, sightings: [(uuid: String, address: String)]) -> Bool {
        guard !savedUUID.isEmpty, !savedAddress.isEmpty else { return false }
        return sightings.contains { sighting in
            !sighting.uuid.isEmpty && sighting.uuid != savedUUID && sighting.address == savedAddress
        }
    }

    /// A live sighting of this receiver wins. Otherwise a stranger at the saved address
    /// beats a TCP probe: the probe only proves that some process answered.
    static func savedReachable(probe: Bool, seenLive: Bool, remoteReachable: Bool, addressHeldByOther: Bool) -> Bool {
        if seenLive { return true }
        if addressHeldByOther { return false }
        return probe || remoteReachable
    }

    /// Hostnames lose a trailing mDNS suffix. ".local-ipv6" is a different suffix and stays.
    static func displayName(_ name: String) -> String {
        guard name.lowercased().hasSuffix(".local") else { return name }
        let trimmed = String(name.dropLast(".local".count))
        return trimmed.isEmpty ? name : trimmed
    }

    static func isDesktopName(_ name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.lowercased().hasSuffix(".local") && trimmed.count > ".local".count
    }

    static func symbol(kind: PlayOnListedDevice.Kind, desktop: Bool) -> String {
        if desktop { return "laptopcomputer" }
        switch kind {
        case .playBridge, .roku: return "tv"
        case .googleCast: return "airplayvideo"
        case .dlna: return "hifispeaker"
        case .browser: return "globe"
        }
    }

    static func savedSubtitle(protocolName: String, reachable: Bool, used: String?) -> String {
        if reachable { return "\(protocolName) · Ready" }
        if let used, !used.isEmpty { return "\(protocolName) · \(used)" }
        return protocolName
    }

    static func nearbySubtitle(kind: PlayOnListedDevice.Kind, needsPairing: Bool, protocolName: String) -> String {
        if kind == .playBridge && needsPairing { return "PlayBridge · Tap to pair" }
        return protocolName
    }

    static func actionsSubtitle(protocolName: String, address: String, used: String?) -> String {
        var parts = [protocolName]
        let address = address.trimmingCharacters(in: .whitespacesAndNewlines)
        if !address.isEmpty { parts.append(address) }
        if let used, !used.isEmpty { parts.append(used) }
        return parts.joined(separator: " · ")
    }

    /// Wi-Fi name only when the caller already has it. iOS does not gain a new entitlement here.
    static func networkSubtitle(onWiFi: Bool, wifiName: String?) -> String {
        guard onWiFi else { return "Mobile data" }
        let name = wifiName?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return name.isEmpty ? "Wi-Fi" : name
    }

    static func nowStatus(connecting: Bool, failed: Bool, castingTitle: String?, protocolName: String) -> String {
        if connecting { return "Connecting…" }
        if failed { return "Couldn’t connect" }
        let title = castingTitle?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !title.isEmpty { return "Casting \(title)" }
        return "Connected · \(protocolName)"
    }

    /// Redacted status for the existing share sheet. No tokens, URLs, or headers.
    static func connectionDiagnostics(state: String, destination: String?, protocolName: String?, scanning: Bool) -> String {
        [
            "PlayBridge connection",
            "State: \(state)",
            "Destination: \(destination?.isEmpty == false ? destination! : "This phone")",
            "Protocol: \(protocolName?.isEmpty == false ? protocolName! : "none")",
            "Scanning: \(scanning ? "yes" : "no")"
        ].joined(separator: "\n")
    }

    private static func savedOrder(_ lhs: PlayOnListedDevice, _ rhs: PlayOnListedDevice) -> Bool {
        switch (lhs.lastConnected, rhs.lastConnected) {
        case let (left?, right?) where left != right: return left > right
        case (.some, nil): return true
        case (nil, .some): return false
        default:
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }

    private static func nearbyOrder(_ lhs: PlayOnListedDevice, _ rhs: PlayOnListedDevice) -> Bool {
        let left = nearbyRank(lhs)
        let right = nearbyRank(rhs)
        if left != right { return left < right }
        return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
    }

    private static func nearbyRank(_ device: PlayOnListedDevice) -> Int {
        if device.kind == .playBridge && device.needsPairing { return 0 }
        switch device.kind {
        case .playBridge: return 1
        case .googleCast: return 2
        case .dlna: return 3
        case .roku: return 4
        case .browser: return 5
        }
    }
}

enum PlayOnRelativeTime {
    static func used(_ date: Date, now: Date = Date(), calendar: Calendar = .current) -> String {
        if calendar.isDate(date, inSameDayAs: now) {
            let seconds = max(0, now.timeIntervalSince(date))
            if seconds < 60 { return "Used just now" }
            if seconds < 3_600 { return "Used \(Int(seconds / 60))m ago" }
            return "Used today"
        }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
           calendar.isDate(date, inSameDayAs: yesterday) {
            return "Used yesterday"
        }
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: now)).day ?? 0
        if days > 1 && days < 7 { return "Used \(days)d ago" }
        return "Used earlier"
    }
}

enum PlayOnPlayerCopy {
    static func detail(for id: String) -> String {
        switch id {
        case "exo": return "Default player, smooth on most streams"
        case "mpv", "internal_mpv": return "Plays more formats and subtitle styles"
        case "avplayer": return "Apple’s player on this TV"
        case "vlc": return "VLC on this TV"
        default: return "Player on this TV"
        }
    }
}

/// Same preference key as Android (`auto_connect_tv`), default on so existing reconnect behavior stays.
enum PlayOnPreferences {
    static let autoConnectKey = "auto_connect_tv"

    static func autoConnectEnabled(_ defaults: UserDefaults = .standard) -> Bool {
        guard defaults.object(forKey: autoConnectKey) != nil else { return true }
        return defaults.bool(forKey: autoConnectKey)
    }
}

enum PlayOnPage: Hashable {
    case pairing
    case add
    case actions(String)
    case configure
    case manualPlayBridge
    case manualRoku
    case manualDLNA
}
