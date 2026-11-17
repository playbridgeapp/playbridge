import Foundation
import Security

/// A device this phone added to a party, so a rejoin still counts it as this phone's own.
struct SavedOwnDevice: Codable, Equatable {
    let member: String
    let boxKey: String
}

/// A party this phone joined recently, kept so it can come back to it after the app was
/// closed: as the host if it started the party, otherwise as the same guest.
struct SavedWatchParty: Codable, Equatable, CustomStringConvertible {
    /// The room ID, which is not secret.
    let roomId: String
    let relay: String
    /// This phone started the party.
    let host: Bool
    /// The engine's saved party: the invite and this phone's keys. Secret; never log it.
    let saved: String
    let joinedAtMs: Int64
    /// When the room ends, from its ID, or `SavedWatchParties.undatedLifetimeMs` after joining.
    let endsAtMs: Int64
    /// The host's name, once a guest has seen it.
    var hostName: String?
    var own: [SavedOwnDevice] = []

    var description: String { "SavedWatchParty(room=\(roomId), relay=\(relay), host=\(host), endsAt=\(endsAtMs))" }

    var title: String {
        if host { return "Your party" }
        if let hostName, !hostName.isEmpty { return "\(hostName)'s party" }
        return "Watch party"
    }
}

enum SavedWatchParties {
    /// How long a party from before rooms carried their end time is kept: the relay's party length.
    static let undatedLifetimeMs: Int64 = 8 * 60 * 60_000
    static let maxSaved = 20

    static func endsAt(roomEndsAtMs: Int64?, joinedAtMs: Int64) -> Int64 {
        roomEndsAtMs ?? (joinedAtMs + undatedLifetimeMs)
    }

    /// The parties that haven't ended, newest first.
    static func live(_ parties: [SavedWatchParty], nowMs: Int64) -> [SavedWatchParty] {
        Array(parties.filter { $0.endsAtMs > nowMs }.sorted { $0.joinedAtMs > $1.joinedAtMs }.prefix(maxSaved))
    }

    static func endsIn(_ party: SavedWatchParty, nowMs: Int64) -> String {
        let minutes = (party.endsAtMs - nowMs) / 60_000
        if minutes < 1 { return "Ends in less than a minute" }
        if minutes < 60 { return "Ends in \(minutes) min" }
        if minutes % 60 == 0 { return "Ends in \(minutes / 60) h" }
        return "Ends in \(minutes / 60) h \(minutes % 60) min"
    }

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// Where saved parties live, one item per room.
protocol SavedWatchPartyBackend: AnyObject {
    func all() -> [String: Data]
    func set(_ data: Data, room: String)
    func remove(room: String)
}

/// The Keychain, on this device only: not synced to iCloud and not restored to another phone.
final class KeychainSavedWatchPartyBackend: SavedWatchPartyBackend {
    private let service: String

    init(service: String = "app.playbridge.phone.watch-together.saved") {
        self.service = service
    }

    func all() -> [String: Data] {
        var query = baseQuery()
        query[kSecMatchLimit as String] = kSecMatchLimitAll
        query[kSecReturnAttributes as String] = true
        query[kSecReturnData as String] = true
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]] else { return [:] }
        var out: [String: Data] = [:]
        for item in items {
            if let room = item[kSecAttrAccount as String] as? String, let data = item[kSecValueData as String] as? Data {
                out[room] = data
            }
        }
        return out
    }

    func set(_ data: Data, room: String) {
        var match = baseQuery()
        match[kSecAttrAccount as String] = room
        let update: [String: Any] = [kSecValueData as String: data]
        if SecItemUpdate(match as CFDictionary, update as CFDictionary) == errSecItemNotFound {
            var add = match
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    func remove(room: String) {
        var match = baseQuery()
        match[kSecAttrAccount as String] = room
        SecItemDelete(match as CFDictionary)
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: false,
        ]
    }
}

/// For tests and previews.
final class MemorySavedWatchPartyBackend: SavedWatchPartyBackend {
    private(set) var items: [String: Data] = [:]
    func all() -> [String: Data] { items }
    func set(_ data: Data, room: String) { items[room] = data }
    func remove(room: String) { items.removeValue(forKey: room) }
}

/// The parties this phone can rejoin. Ended parties are dropped whenever the list changes.
final class SavedWatchPartyStore {
    private let backend: SavedWatchPartyBackend
    private let nowMs: () -> Int64
    private var all: [String: SavedWatchParty] = [:]
    /// Called with the live parties, newest first, after every change.
    var onChange: (([SavedWatchParty]) -> Void)?

    init(backend: SavedWatchPartyBackend, nowMs: @escaping () -> Int64 = SavedWatchParties.nowMs) {
        self.backend = backend
        self.nowMs = nowMs
        let decoder = JSONDecoder()
        for (room, data) in backend.all() {
            if let party = try? decoder.decode(SavedWatchParty.self, from: data), party.roomId == room {
                all[room] = party
            } else {
                backend.remove(room: room)
            }
        }
    }

    var parties: [SavedWatchParty] { SavedWatchParties.live(Array(all.values), nowMs: nowMs()) }

    func get(_ roomId: String) -> SavedWatchParty? {
        guard let party = all[roomId], party.endsAtMs > nowMs() else { return nil }
        return party
    }

    func put(_ party: SavedWatchParty) {
        guard let data = try? JSONEncoder().encode(party) else { return }
        all[party.roomId] = party
        backend.set(data, room: party.roomId)
        prune()
    }

    func update(_ roomId: String, _ change: (inout SavedWatchParty) -> Void) {
        guard var party = all[roomId] else { return }
        let before = party
        change(&party)
        if party != before { put(party) }
    }

    func remove(_ roomId: String) {
        all.removeValue(forKey: roomId)
        backend.remove(room: roomId)
        onChange?(parties)
    }

    /// Drops ended parties and all but the newest `SavedWatchParties.maxSaved`.
    func prune() {
        let keep = Set(parties.map(\.roomId))
        for room in Set(all.keys).union(backend.all().keys) where !keep.contains(room) {
            all.removeValue(forKey: room)
            backend.remove(room: room)
        }
        onChange?(parties)
    }
}
