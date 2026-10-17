import Foundation
import Combine

struct AtvChannelState: Equatable {
    enum Phase { case idle, discovering, available, connecting, connected, retrying, repairRequired, storageUnavailable, unavailable }
    var phase: Phase = .idle
    var endpoint: String?
    var paired = false
    var retrySeconds: Int?
    var television: AtvTelevisionState?
    var failure: AtvClientError?
    var discoveryIssue = false
}
struct AtvPairingState: Equatable {
    enum Phase { case idle, recoveryConfirmation, starting, waitingForCode, verifying, failed, paired }
    var phase: Phase = .idle
    var failure: AtvClientError?
    var storageFailure = false
}

/// One foreground owner, separate from PlayBridge playback authority. No remote commands are
/// routed here yet. Every async publication checks the owner and attempt generation.
@MainActor final class AtvRemoteCoordinator: ObservableObject {
    @Published private(set) var channel = AtvChannelState()
    @Published private(set) var pairing = AtvPairingState()
    @Published private(set) var records: [AtvTvRecord] = []
    @Published private(set) var storageError: AtvStorageError?
    @Published private(set) var identityRecovered = false
    @Published private(set) var managementBusy = false
    private let storage: AtvCoordinatorStorage
    private let discovery: AtvCoordinatorDiscovery
    private let clock: AtvCoordinatorClock
    private let paths: AtvPathWatching
    private let makeClient: @MainActor () -> AtvCoordinatorClient
    private var receiver: AtvReceiverTarget?
    private var visible = false
    private var foreground = false
    private var generation = UUID()
    private var attempt = UUID()
    private var permit = AtvStoragePermit()
    private var work: Task<Void, Never>?
    private var pairWork: Task<Void, Never>?
    private var shutdown: Task<Void, Never>?
    private var client: AtvCoordinatorClient?
    private var host: String?
    private var retryAttempt = 1
    private struct TrustFailure { var count = 0; var stopped = false }
    private var trustFailures: [String: TrustFailure] = [:]
    private var pendingFailure: AtvClientError?
    private var pendingTelevision: AtvTelevisionState?
    private var pairingIdentity: AtvIdentity?
    private var pairingServerName: String?
    private var recoveringIdentity = false
    private var managementGeneration = UUID()

    init(storage: AtvCoordinatorStorage = AtvQueuedCredentialStore(),
         discovery: AtvCoordinatorDiscovery? = nil,
         clock: AtvCoordinatorClock? = nil,
         paths: AtvPathWatching? = nil,
         makeClient: @escaping @MainActor () -> AtvCoordinatorClient = { AtvLiveCoordinatorClient() }) {
        self.storage = storage; self.discovery = discovery ?? AtvBonjourBrowser(); self.clock = clock ?? AtvSystemCoordinatorClock()
        self.paths = paths ?? AtvForegroundPathMonitor(); self.makeClient = makeClient
    }
    var receiverName: String? { receiver?.name }
    var canPair: Bool { eligible && host != nil && channel.phase != .discovering && channel.phase != .unavailable && channel.phase != .idle && !managementBusy }
    private var eligible: Bool { visible && foreground && receiver != nil }

    func update(receiver: AtvReceiverTarget?, remoteVisible: Bool, foreground: Bool) {
        guard self.receiver != receiver || visible != remoteVisible || self.foreground != foreground else { return }
        self.receiver = receiver; visible = remoteVisible; self.foreground = foreground
        restart()
    }
    func pathChanged() { guard eligible else { return }; restart() }
    func refresh() { restart() }

    private func retireClient() {
        attempt = UUID(); pendingFailure = nil; pendingTelevision = nil
        guard let old = client else { return }
        client = nil; old.onState = nil
        let previous = shutdown
        shutdown = Task { await previous?.value; await old.close() }
    }
    private func invalidate() {
        generation = UUID(); permit.invalidate(); permit = AtvStoragePermit()
        work?.cancel(); work = nil; pairWork?.cancel(); pairWork = nil
        discovery.stop(); paths.stop(); retireClient()
        host = nil; pairingIdentity = nil; pairingServerName = nil
        pairing = AtvPairingState(); channel = AtvChannelState()
    }
    private func restart() {
        invalidate()
        guard eligible, !managementBusy else { return }
        retryAttempt = 1
        let ticket = generation
        watchPaths(ticket)
        work = Task { [weak self] in await self?.discover(ticket) }
    }
    private func watchPaths(_ ticket: UUID) {
        paths.start { [weak self] in
            guard let self, self.current(ticket) else { return }
            self.pathChanged()
        }
    }
    private func current(_ ticket: UUID) -> Bool { eligible && generation == ticket && !Task.isCancelled }
    private func discover(_ ticket: UUID) async {
        await shutdown?.value
        guard current(ticket), let receiver else { return }
        channel = .init(phase: .discovering, endpoint: receiver.endpoint)
        let saved = await storage.load()
        guard current(ticket) else { return }
        records = saved.records; storageError = saved.error
        discovery.start()
        do { try await clock.sleep(seconds: 4) } catch { return }
        guard current(ticket) else { return }
        let devices = discovery.devices, failed = discovery.failed
        discovery.stop()
        let match = AtvDeviceMatcher.match(receiver: receiver, devices: devices, records: saved.records)
        host = match.host
        channel = .init(phase: match.available ? .available : .unavailable, endpoint: receiver.endpoint,
                        paired: match.record != nil, discoveryIssue: failed || devices.isEmpty)
        guard saved.error == nil else { channel.phase = saved.error == .corrupt ? .repairRequired : .storageUnavailable; return }
        guard let record = match.record, let identity = saved.identity, let host = match.host else { return }
        if trustFailures[record.fingerprint]?.stopped == true { channel.phase = .repairRequired; return }
        await connect(ticket, record: record, host: host, identity: identity)
    }
    private func connect(_ ticket: UUID, record: AtvTvRecord, host: String, identity: AtvIdentity) async {
        await shutdown?.value
        guard current(ticket) else { return }
        let session = makeClient(), connection = UUID(), storagePermit = permit
        client = session; attempt = connection; pendingFailure = nil; pendingTelevision = nil
        channel.phase = .connecting; channel.retrySeconds = nil; channel.television = nil
        session.onState = { [weak self, weak session] state in
            guard let self, let session, self.current(ticket), self.attempt == connection,
                  self.client === session else { return }
            if state.phase == .disconnected || state.phase == .closed {
                let failure = state.failure ?? .native(.closed)
                self.pendingFailure = failure
                if self.channel.phase == .connected { self.failedConnection(failure, ticket: ticket, record: record, host: host, identity: identity) }
            } else if state.phase == .connected {
                self.pendingTelevision = state.television
                if self.channel.phase == .connected { self.channel.television = state.television }
            }
        }
        do {
            try await session.connect(host: host, identity: identity, pin: record.fingerprint)
            guard current(ticket), attempt == connection else { return }
            if let pendingFailure { throw pendingFailure }
            try await storage.updateLastIp(record.fingerprint, host: host, permit: storagePermit)
            guard current(ticket), attempt == connection else { return }
            if let pendingFailure { throw pendingFailure }
            trustFailures[record.fingerprint] = nil; retryAttempt = 1
            channel.phase = .connected; channel.failure = nil; channel.television = pendingTelevision
        } catch {
            guard current(ticket), attempt == connection else { return }
            if let error = error as? AtvStorageError {
                storageError = error; channel.phase = .storageUnavailable; channel.television = nil; retireClient()
            } else { failedConnection(error as? AtvClientError ?? .malformedEvent, ticket: ticket, record: record, host: host, identity: identity) }
        }
    }
    private func failedConnection(_ error: AtvClientError, ticket: UUID, record: AtvTvRecord, host: String, identity: AtvIdentity) {
        retireClient(); channel.television = nil; channel.failure = error
        var trust = trustFailures[record.fingerprint] ?? TrustFailure()
        if error == .native(.pinMismatch) { trust.stopped = true }
        if error == .native(.notTrusted) { trust.count += 1; trust.stopped = trust.count >= 3 }
        if trust.count > 0 || trust.stopped { trustFailures[record.fingerprint] = trust }
        if trust.stopped { channel.phase = .repairRequired; return }
        if [.unavailable, .unsupportedABI].contains(error) { channel.phase = .unavailable; return }
        let seconds = Self.backoff(retryAttempt); retryAttempt = min(6, retryAttempt + 1)
        channel.phase = .retrying; channel.retrySeconds = seconds
        work = Task { [weak self] in
            guard let self else { return }
            do { try await self.clock.sleep(seconds: TimeInterval(seconds)) } catch { return }
            guard self.current(ticket) else { return }
            await self.connect(ticket, record: record, host: host, identity: identity)
        }
    }
    static func backoff(_ attempt: Int) -> Int { min(30, 1 << min(5, max(0, attempt - 1))) }

    func beginPairing(confirmRecovery: Bool = false) {
        guard canPair, pairing.phase != .starting, pairing.phase != .waitingForCode, pairing.phase != .verifying,
              let host, let receiver else { return }
        if storageError == .corrupt && !confirmRecovery { pairing.phase = .recoveryConfirmation; return }
        let previousPhase = channel
        invalidate()
        self.host = host; channel = previousPhase; channel.phase = .available; channel.television = nil
        recoveringIdentity = recoveringIdentity || storageError == .corrupt
        pairing = .init(phase: .starting)
        let ticket = generation
        watchPaths(ticket)
        pairWork = Task { [weak self] in
            guard let self else { return }
            await self.shutdown?.value
            guard self.current(ticket) else { return }
            let session = self.makeClient(); self.client = session
            do {
                let identity: AtvIdentity
                if let stored = try await self.storage.pairingIdentity() { identity = stored }
                else { identity = try await session.generateIdentity() }
                guard self.current(ticket) else { return }
                self.pairingIdentity = identity
                let started = try await session.startPairing(host: host, identity: identity)
                guard self.current(ticket), self.receiver?.endpoint == receiver.endpoint else { return }
                self.pairingServerName = started.serverName
                self.pairing.phase = .waitingForCode
            } catch { self.pairingFailed(error, ticket: ticket) }
        }
    }
    func submitCode(_ raw: String) {
        guard pairing.phase == .waitingForCode, let session = client, let identity = pairingIdentity,
              let receiver, let host else { return }
        guard let code = AtvPairingPresentation.validCode(raw) else { return }
        pairing.phase = .verifying
        let ticket = generation, storagePermit = permit, recovered = recoveringIdentity
        pairWork = Task { [weak self] in
            guard let self else { return }
            do {
                let paired = try await session.finishPairing(code: code)
                guard self.current(ticket) else { return }
                let previousPins = self.records.filter { $0.receiverIds.contains(receiver.endpoint) }.map(\.fingerprint)
                try await self.storage.save(identity, record: AtvTvRecord(fingerprint: paired.fingerprint, mac: paired.mac,
                    serverName: paired.serverName ?? self.pairingServerName, lastIp: host, receiverIds: [receiver.endpoint]), permit: storagePermit)
                guard self.current(ticket) else { return }
                previousPins.forEach { self.trustFailures[$0] = nil }
                self.trustFailures[paired.fingerprint.lowercased()] = nil
                let snapshot = await self.storage.load()
                guard self.current(ticket) else { return }
                self.records = snapshot.records; self.storageError = snapshot.error
                guard snapshot.error == nil, snapshot.records.contains(where: { $0.fingerprint == paired.fingerprint.lowercased() && $0.receiverIds.contains(receiver.endpoint) }) else {
                    throw AtvStorageError.writeFailed
                }
                self.identityRecovered = self.identityRecovered || recovered
                self.pairing.phase = .paired
                self.retireClient()
                // Keep success visible until sheet dismissal; then rediscover and use the stored pin.
            } catch { self.pairingFailed(error, ticket: ticket) }
        }
    }
    private func pairingFailed(_ error: Error, ticket: UUID) {
        guard current(ticket) else { return }
        if let storageError = error as? AtvStorageError {
            self.storageError = storageError; pairing = .init(phase: .failed, storageFailure: true)
        } else { pairing = .init(phase: .failed, failure: error as? AtvClientError ?? .malformedEvent) }
        retireClient(); pairingIdentity = nil
    }
    func cancelPairing() { restart() }

    func reloadManagement() {
        let ticket = UUID(); managementGeneration = ticket
        Task { [weak self] in
            guard let self else { return }
            let snapshot = await self.storage.load()
            guard self.managementGeneration == ticket else { return }
            self.records = snapshot.records; self.storageError = snapshot.error
            if snapshot.error != nil && self.channel.phase == .connected { self.restart() }
        }
    }
    func forget(_ fingerprint: String) {
        guard !managementBusy else { return }
        managementBusy = true
        invalidate() // Clear live authority before waiting for durable removal, even if it fails.
        let ticket = UUID(); managementGeneration = ticket
        Task { [weak self] in
            guard let self else { return }
            do {
                try await self.storage.forget(fingerprint)
                let snapshot = await self.storage.load()
                guard self.managementGeneration == ticket else { return }
                self.records = snapshot.records; self.storageError = snapshot.error
                self.managementBusy = false; self.restart()
            } catch {
                guard self.managementGeneration == ticket else { return }
                self.managementBusy = false; self.storageError = error as? AtvStorageError ?? .writeFailed
                self.channel.phase = .storageUnavailable
            }
        }
    }
}
