import Foundation
import Security
import LocalAuthentication

struct AtvTvRecord: Codable, Equatable, CustomStringConvertible, CustomDebugStringConvertible {
    let fingerprint: String
    let mac: String?
    let serverName: String?
    let lastIp: String?
    let receiverIds: Set<String>
    var description: String { "AtvTvRecord(redacted)" }
    var debugDescription: String { description }
}

enum AtvStorageError: Error, Equatable, LocalizedError {
    case locked, unavailable, corrupt, unsupportedVersion, invalidInput, writeFailed
    var errorDescription: String? {
        switch self {
        case .locked: return "Unlock this device to use TV pairing"
        case .unavailable: return "TV pairing storage is temporarily unavailable"
        case .corrupt: return "Pair again to recover TV pairing storage"
        case .unsupportedVersion: return "TV pairing storage requires a newer app"
        case .invalidInput: return "Invalid TV pairing record"
        case .writeFailed: return "Couldn't save TV pairing changes"
        }
    }
}

/// Injectable at the Security boundary: tests never access the real Keychain.
protocol AtvKeychainAccess {
    func read(_ query: [String: Any]) -> (OSStatus, Data?)
    func update(_ query: [String: Any], attributes: [String: Any]) -> OSStatus
    func add(_ attributes: [String: Any]) -> OSStatus
}

struct AtvSystemKeychain: AtvKeychainAccess {
    func read(_ query: [String: Any]) -> (OSStatus, Data?) {
        var result: CFTypeRef?
        let status = SecItemCopyMatching(withoutUI(query) as CFDictionary, &result)
        return (status, result as? Data)
    }
    func update(_ query: [String: Any], attributes: [String: Any]) -> OSStatus {
        SecItemUpdate(withoutUI(query) as CFDictionary, attributes as CFDictionary)
    }
    func add(_ attributes: [String: Any]) -> OSStatus { SecItemAdd(attributes as CFDictionary, nil) }
    private func withoutUI(_ query: [String: Any]) -> [String: Any] {
        let context = LAContext()
        context.interactionNotAllowed = true
        return query.merging([kSecUseAuthenticationContext as String: context]) { _, new in new }
    }
}

/// One atomic Keychain item, separate from PlayBridge WSS credentials. No cloud or preferences copy.
/// All instances serialize read/modify/write so a second store cannot silently lose another TV.
/// Synchronous APIs belong on the coordinator's storage queue, never its UI/input path.
final class AtvCredentialStore {
    private static let lock = NSLock()
    private let keychain: AtvKeychainAccess
    private var identity: AtvIdentity?
    private var tvs: [AtvTvRecord] = []
    private var tvIdentities: [String: AtvIdentity] = [:]
    private var failure: AtvStorageError?
    private static let maximumBytes = 1_048_576

    private struct SavedIdentity: Codable {
        let certPem: String
        let keyPem: String
        let fingerprint: String
        init(_ value: AtvIdentity) {
            certPem = value.certPem; keyPem = value.keyPem; fingerprint = value.fingerprint
        }
        var value: AtvIdentity { .init(certPem: certPem, keyPem: keyPem, fingerprint: fingerprint) }
    }
    private struct Document: Codable {
        let version: Int
        let identity: SavedIdentity?
        let tvs: [AtvTvRecord]
        let tvIdentities: [String: SavedIdentity]?
    }

    init(keychain: AtvKeychainAccess = AtvSystemKeychain()) {
        self.keychain = keychain
        Self.lock.lock(); defer { Self.lock.unlock() }
        load()
    }

    var records: [AtvTvRecord] { locked { tvs } }
    var identityOverrides: [String: AtvIdentity] { locked { failure == nil ? tvIdentities : [:] } }
    var storageError: AtvStorageError? { locked { failure } }
    var recoveryRequired: Bool { locked { failure == .corrupt } }
    func identityOrNull() -> AtvIdentity? { locked { failure == nil ? identity : nil } }

    /// Retry a locked/access failure without replacing the healthy item. A cached identity during
    /// corruption is usable for verified re-pairing only, never for pinned control.
    func pairingIdentityOrNull() throws -> AtvIdentity? {
        try locked { load(); try requireReadable(allowRecovery: true); return identity }
    }
    func reload() { locked { load() } }

    func saveIdentity(_ value: AtvIdentity) throws {
        try locked {
            load(); try requireReadable()
            guard valid(value), tvs.isEmpty || sameIdentity(identity, value) else { throw AtvStorageError.invalidInput }
            try persist(value, tvs)
        }
    }

    /// Only call after the pairing exchange has verified this client identity and the TV pin.
    /// New identity recovery invalidates other TVs; a future UI must explain that before pairing.
    func saveVerifiedPairing(_ value: AtvIdentity, record: AtvTvRecord, preserveOtherPairings: Bool = false) throws {
        try saveRecord(record, verifiedIdentity: value, preserveOtherPairings: preserveOtherPairings)
    }
    /// Persist an already-verified record using the current identity; never a discovery result.
    func upsert(_ record: AtvTvRecord) throws { try saveRecord(record, verifiedIdentity: nil) }

    private func saveRecord(_ record: AtvTvRecord, verifiedIdentity: AtvIdentity?, preserveOtherPairings: Bool = false) throws {
        try locked {
            load(); try requireReadable(allowRecovery: verifiedIdentity != nil)
            let savedIdentity = verifiedIdentity ?? identity
            guard let savedIdentity, valid(savedIdentity), valid(record) else { throw AtvStorageError.invalidInput }
            // Pins/IPs/MACs never move ownership: only exact endpoint aliases and verified pin.
            let keepPrevious = sameIdentity(identity, savedIdentity) || preserveOtherPairings && failure == nil
            let previous = keepPrevious ? tvs : []
            var retained = keepPrevious ? tvIdentities : [:]
            if preserveOtherPairings, failure == nil, !sameIdentity(identity, savedIdentity), let old = identity {
                for item in previous where retained[item.fingerprint] == nil { retained[item.fingerprint] = old }
            }
            let pin = record.fingerprint.lowercased()
            let replaced = previous.filter { $0.fingerprint == pin || !$0.receiverIds.isDisjoint(with: record.receiverIds) }
            let old = replaced.last
            let merged = AtvTvRecord(fingerprint: pin, mac: (record.mac ?? old?.mac)?.uppercased(),
                                     serverName: record.serverName ?? old?.serverName, lastIp: record.lastIp ?? old?.lastIp,
                                     receiverIds: replaced.reduce(record.receiverIds) { $0.union($1.receiverIds) })
            replaced.forEach { retained[$0.fingerprint] = nil }
            try persist(savedIdentity, previous.filter { !replaced.contains($0) } + [merged], savedTvIdentities: retained)
        }
    }

    func updateLastIp(fingerprint: String, ip: String) throws {
        try locked {
            load(); try requireReadable()
            guard !ip.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, ip.utf8.count <= 1024 else {
                throw AtvStorageError.invalidInput
            }
            let updated = tvs.map { record in
                record.fingerprint == fingerprint.lowercased()
                    ? AtvTvRecord(fingerprint: record.fingerprint, mac: record.mac, serverName: record.serverName,
                                  lastIp: ip, receiverIds: record.receiverIds) : record
            }
            if updated != tvs { try persist(identity, updated) }
        }
    }

    /// Forget only this television. Retain the install identity and all other TV records.
    func forget(fingerprint: String) throws {
        try locked {
            load(); try requireReadable()
            let updated = tvs.filter { $0.fingerprint != fingerprint.lowercased() }
            if updated != tvs { try persist(identity, updated) }
        }
    }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.playbridge.atv-remote",
         kSecAttrAccount as String: "identity-and-tvs-v1",
         kSecAttrSynchronizable as String: false]
    }
    private var protection: [String: Any] {
        [kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
         kSecAttrSynchronizable as String: false]
    }

    private func load() {
        var readQuery = query
        readQuery[kSecReturnData as String] = true
        readQuery[kSecMatchLimit as String] = kSecMatchLimitOne
        let (status, bytes) = keychain.read(readQuery)
        switch status {
        case errSecItemNotFound:
            identity = nil; tvs = []; tvIdentities = [:]; failure = nil
        case errSecSuccess:
            guard let bytes, bytes.count <= Self.maximumBytes else { failure = .corrupt; return }
            // Check version before decoding the remainder; never overwrite a future document.
            struct Header: Decodable { let version: Int }
            guard let header = try? JSONDecoder().decode(Header.self, from: bytes) else { failure = .corrupt; return }
            guard header.version == 1 else { failure = .unsupportedVersion; return }
            guard let doc = try? JSONDecoder().decode(Document.self, from: bytes),
                  doc.identity.map({ valid($0.value) }) ?? doc.tvs.isEmpty,
                  doc.tvs.count <= 256, doc.tvs.allSatisfy(valid) else { failure = .corrupt; return }
            // A valid v1 document cannot grant two pins the same receiver alias.
            var seenPins: Set<String> = [], seenIds: Set<String> = []
            for record in doc.tvs {
                guard seenPins.insert(record.fingerprint.lowercased()).inserted,
                      seenIds.isDisjoint(with: record.receiverIds) else { failure = .corrupt; return }
                seenIds.formUnion(record.receiverIds)
            }
            let overrides = doc.tvIdentities ?? [:]
            guard overrides.allSatisfy({ seenPins.contains($0.key) && valid($0.value.value) }) else { failure = .corrupt; return }
            identity = doc.identity?.value
            tvIdentities = overrides.mapValues(\.value)
            tvs = doc.tvs.map { .init(fingerprint: $0.fingerprint.lowercased(), mac: $0.mac?.uppercased(),
                                     serverName: $0.serverName, lastIp: $0.lastIp, receiverIds: $0.receiverIds) }
            failure = nil
        case errSecDecode: failure = .corrupt
        case errSecInteractionNotAllowed: failure = .locked
        default: failure = .unavailable
        }
    }

    private func requireReadable(allowRecovery: Bool = false) throws {
        if let failure, !(allowRecovery && failure == .corrupt) { throw failure }
    }
    private func persist(_ value: AtvIdentity?, _ records: [AtvTvRecord], savedTvIdentities: [String: AtvIdentity]? = nil) throws {
        guard records.count <= 256, records.allSatisfy(valid) else { throw AtvStorageError.invalidInput }
        let bytes: Data
        let retained = (savedTvIdentities ?? tvIdentities).filter { pin, _ in records.contains { $0.fingerprint == pin } }
        do { bytes = try JSONEncoder().encode(Document(version: 1, identity: value.map(SavedIdentity.init), tvs: records,
                                                       tvIdentities: retained.isEmpty ? nil : retained.mapValues(SavedIdentity.init))) }
        catch { throw AtvStorageError.writeFailed }
        guard bytes.count <= Self.maximumBytes else { throw AtvStorageError.invalidInput }
        var attributes = protection
        attributes[kSecValueData as String] = bytes
        var status = keychain.update(query, attributes: attributes)
        if status == errSecItemNotFound {
            let addition = query.merging(attributes) { _, new in new }
            status = keychain.add(addition)
            if status == errSecDuplicateItem { status = keychain.update(query, attributes: attributes) }
        }
        guard status == errSecSuccess else {
            if status == errSecInteractionNotAllowed { failure = .locked; throw AtvStorageError.locked }
            if status == errSecDecode { failure = .corrupt; throw AtvStorageError.corrupt }
            if [errSecNotAvailable, errSecAuthFailed, errSecMissingEntitlement].contains(status) {
                failure = .unavailable; throw AtvStorageError.unavailable
            }
            throw AtvStorageError.writeFailed
        }
        // A single successful Security write commits identity and every pin/alias together.
        identity = value; tvs = records; tvIdentities = retained; failure = nil
    }
    private func sameIdentity(_ lhs: AtvIdentity?, _ rhs: AtvIdentity) -> Bool {
        lhs?.certPem == rhs.certPem && lhs?.keyPem == rhs.keyPem
    }
    private func valid(_ value: AtvIdentity) -> Bool {
        !value.certPem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && value.certPem.utf8.count <= 65_536 &&
        !value.keyPem.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && value.keyPem.utf8.count <= 65_536 &&
        atvValidFingerprint(value.fingerprint)
    }
    private func valid(_ record: AtvTvRecord) -> Bool {
        atvValidFingerprint(record.fingerprint) && record.receiverIds.count <= 64 &&
        record.receiverIds.allSatisfy { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.utf8.count <= 2048 } &&
        (record.mac?.utf8.count ?? 0) <= 128 && (record.serverName?.utf8.count ?? 0) <= 1024 && (record.lastIp?.utf8.count ?? 0) <= 1024
    }
    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        Self.lock.lock(); defer { Self.lock.unlock() }; return try body()
    }
}
