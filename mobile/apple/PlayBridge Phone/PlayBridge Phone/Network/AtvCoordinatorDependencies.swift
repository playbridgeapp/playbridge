import Foundation
import Combine
import Network

struct AtvCredentialSnapshot {
    let identity: AtvIdentity?
    let records: [AtvTvRecord]
    let error: AtvStorageError?
    var identities: [String: AtvIdentity] = [:]
    func identity(for record: AtvTvRecord) -> AtvIdentity? { identities[record.fingerprint] ?? identity }
}

/// Checked again on the storage queue immediately before starting a mutation. An already-started
/// atomic Security write may finish, but its completion can never republish a retired owner.
final class AtvStoragePermit: @unchecked Sendable {
    private let lock = NSLock()
    private var valid = true
    var isValid: Bool { lock.withLock { valid } }
    func invalidate() { lock.withLock { valid = false } }
}

protocol AtvCoordinatorStorage {
    func load() async -> AtvCredentialSnapshot
    func pairingIdentity() async throws -> AtvIdentity?
    func save(_ identity: AtvIdentity, record: AtvTvRecord, permit: AtvStoragePermit) async throws
    func updateLastIp(_ fingerprint: String, host: String, permit: AtvStoragePermit) async throws
    func forget(_ fingerprint: String) async throws
}

/// Lazily creates the Keychain store off MainActor. All Security work stays on this queue.
final class AtvQueuedCredentialStore: AtvCoordinatorStorage, @unchecked Sendable {
    private let queue = DispatchQueue(label: "PlayBridge.atv.storage", qos: .userInitiated)
    private var store: AtvCredentialStore?
    private let makeStore: () -> AtvCredentialStore
    init(makeStore: @escaping () -> AtvCredentialStore = { AtvCredentialStore() }) { self.makeStore = makeStore }
    private func current() -> AtvCredentialStore {
        if let store { return store }
        let value = makeStore(); store = value; return value
    }
    private func perform<T>(_ operation: @escaping (AtvCredentialStore) throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do { continuation.resume(returning: try operation(self.current())) }
                catch { continuation.resume(throwing: error as? AtvStorageError ?? AtvStorageError.writeFailed) }
            }
        }
    }
    func load() async -> AtvCredentialSnapshot {
        await withCheckedContinuation { continuation in
            queue.async {
                let store = self.current()
                store.reload()
                continuation.resume(returning: AtvCredentialSnapshot(identity: store.identityOrNull(), records: store.records, error: store.storageError, identities: store.identityOverrides))
            }
        }
    }
    func pairingIdentity() async throws -> AtvIdentity? { try await perform { try $0.pairingIdentityOrNull() } }
    func save(_ identity: AtvIdentity, record: AtvTvRecord, permit: AtvStoragePermit) async throws {
        try await perform {
            guard permit.isValid else { throw AtvStorageError.unavailable }
            try $0.saveVerifiedPairing(identity, record: record, preserveOtherPairings: identity.replacesLegacy)
        }
    }
    func updateLastIp(_ fingerprint: String, host: String, permit: AtvStoragePermit) async throws {
        try await perform {
            guard permit.isValid else { throw AtvStorageError.unavailable }
            try $0.updateLastIp(fingerprint: fingerprint, ip: host)
        }
    }
    func forget(_ fingerprint: String) async throws { try await perform { try $0.forget(fingerprint: fingerprint) } }
}

@MainActor protocol AtvCoordinatorClient: AnyObject {
    var onState: ((AtvClientState) -> Void)? { get set }
    func generateIdentity(existing: AtvIdentity?) async throws -> AtvIdentity
    func startPairing(host: String, identity: AtvIdentity) async throws -> AtvPairingStarted
    func finishPairing(code: String) async throws -> AtvPaired
    func connect(host: String, identity: AtvIdentity, pin: String) async throws
    func insertText(_ text: String) async throws
    func command(_ action: AtvControlAction) async throws
    func close() async
}

@MainActor final class AtvLiveCoordinatorClient: AtvCoordinatorClient {
    var onState: ((AtvClientState) -> Void)?
    private let client = AtvRemoteClient(phoneUUID: { PlayBridgePhoneIdentity.localDeviceUUID() })
    private var observation: AnyCancellable?
    init() {
        observation = client.$state.sink { [weak self] state in self?.onState?(state) }
    }
    func generateIdentity(existing: AtvIdentity?) async throws -> AtvIdentity { try await client.generateIdentity(existing: existing) }
    func startPairing(host: String, identity: AtvIdentity) async throws -> AtvPairingStarted {
        try await client.startPairing(host: host, port: AtvDeviceMatcher.pairingPort, identity: identity)
    }
    func finishPairing(code: String) async throws -> AtvPaired { try await client.finishPairing(code: code) }
    func connect(host: String, identity: AtvIdentity, pin: String) async throws {
        try await client.connect(host: host, port: AtvDeviceMatcher.controlPort, identity: identity, pinSHA256: pin)
    }
    func command(_ action: AtvControlAction) async throws {
        switch action {
        case .key(let key):
            guard let name = AtvControlAction.keyNames[key] else { throw AtvClientError.invalidInput }
            try await client.key(.name(name))
        case .volume(let value): try await client.volume(value)
        case .edit: throw AtvClientError.invalidInput // Coordinator splits and checks every frame.
        case .launch(let value): try await client.launchApp(value)
        case .power: try await client.power()
        }
    }
    func insertText(_ text: String) async throws { try await client.insertText(text) }
    func close() async { observation = nil; onState = nil; await client.close() }
}

@MainActor protocol AtvCoordinatorClock {
    var now: TimeInterval { get }
    func sleep(seconds: TimeInterval) async throws
}
struct AtvSystemCoordinatorClock: AtvCoordinatorClock {
    var now: TimeInterval { ProcessInfo.processInfo.systemUptime }
    func sleep(seconds: TimeInterval) async throws {
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }
}

@MainActor protocol AtvPathWatching: AnyObject {
    func start(changed: @escaping @MainActor () -> Void)
    func stop()
}
@MainActor final class AtvForegroundPathMonitor: AtvPathWatching {
    private var monitor: NWPathMonitor?
    private var ticket = UUID()
    private var hasBaseline = false
    func start(changed: @escaping @MainActor () -> Void) {
        stop()
        let ticket = self.ticket
        let monitor = NWPathMonitor()
        self.monitor = monitor
        hasBaseline = false
        monitor.pathUpdateHandler = { [weak self] _ in
            Task { @MainActor in
                guard let self, self.ticket == ticket else { return }
                if !self.hasBaseline { self.hasBaseline = true; return }
                changed()
            }
        }
        monitor.start(queue: DispatchQueue(label: "PlayBridge.atv.path"))
    }
    func stop() { ticket = UUID(); monitor?.cancel(); monitor = nil }
    deinit { monitor?.cancel() }
}
