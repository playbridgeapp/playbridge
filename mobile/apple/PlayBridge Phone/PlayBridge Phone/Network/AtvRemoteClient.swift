import Foundation
import Combine

struct AtvClientLimits {
    var maximumPending = 32
    var maximumCommandBytes = 1_048_576
    var maximumEventBytes = 1_048_576
    var operationTimeout: TimeInterval = 15
    var setupTimeout: TimeInterval = 30
}

/// Typed, independent ATV ABI 1 client. Construction does not start native work or networking.
/// Timeout/cancellation is terminal: an uncertain write is never retried; create a new client.
@MainActor
final class AtvRemoteClient: ObservableObject {
    @Published private(set) var state = AtvClientState()
    private let owner: AtvClientOwner
    private let publication = AtvStatePublication()

    init(transport: AtvNativeTransport = AtvNativeSession(), limits: AtvClientLimits = AtvClientLimits()) {
        owner = AtvClientOwner(transport: transport, limits: limits)
        owner.observe { [weak self, publication] state, intent in
            guard publication.offer(state, intent: intent) else { return }
            Task { @MainActor [weak self, publication] in
                publication.consume { self?.state = $0 }
            }
        }
    }

    func discover(timeoutMilliseconds: UInt64 = 4_000) async throws -> [AtvDiscoveredDevice] {
        guard timeoutMilliseconds <= 60_000 else { throw AtvClientError.invalidInput }
        let event = try await request("discover", ["timeout_ms": timeoutMilliseconds], timeout: Double(timeoutMilliseconds) / 1_000 + 5)
        guard case .devices(_, let devices) = event else { throw AtvClientError.malformedEvent }
        return devices
    }
    func generateIdentity(clientName: String = "PlayBridge") async throws -> AtvIdentity {
        try validateClientName(clientName)
        let event = try await request("generate_identity", ["client_name": clientName], setup: true)
        guard case .identity(_, let identity) = event else { throw AtvClientError.malformedEvent }
        return identity
    }
    func startPairing(host: String, port: UInt16? = nil, identity: AtvIdentity, clientName: String = "PlayBridge") async throws -> AtvPairingStarted {
        try validateTarget(host, port, identity)
        try validateClientName(clientName)
        let event = try await request("start_pairing", ["host": host, "port": port.map { $0 as Any } ?? NSNull(),
            "cert_pem": identity.certPem, "key_pem": identity.keyPem, "client_name": clientName], setup: true)
        guard case .pairingStarted(_, let started) = event else { throw AtvClientError.malformedEvent }
        return started
    }
    func finishPairing(code: String) async throws -> AtvPaired {
        guard code.utf8.count == 6, code.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }) else { throw AtvClientError.invalidInput }
        let event = try await request("finish_pairing", ["code": code.uppercased()], setup: true)
        guard case .paired(_, let paired) = event else { throw AtvClientError.malformedEvent }
        return paired
    }
    func cancelPairing() async throws { _ = try await request("cancel_pairing") }
    func connect(host: String, port: UInt16? = nil, identity: AtvIdentity, pinSHA256: String) async throws {
        try validateTarget(host, port, identity)
        guard atvValidFingerprint(pinSHA256) else { throw AtvClientError.invalidInput }
        _ = try await request("connect", ["host": host, "port": port.map { $0 as Any } ?? NSNull(),
            "cert_pem": identity.certPem, "key_pem": identity.keyPem, "pin_sha256_hex": pinSHA256.lowercased()], setup: true)
    }
    func key(_ key: AtvKey, direction: AtvKeyDirection = .short) async throws {
        var fields: [String: Any] = ["direction": direction.rawValue]
        switch key {
        case .name(let name): try validate(name, maximum: 128); fields["name"] = name
        case .code(let code): guard code <= Int32.max else { throw AtvClientError.invalidInput }; fields["code"] = code
        }
        _ = try await request("key", fields)
    }
    func volume(_ action: AtvVolumeAction) async throws { _ = try await request("volume", ["action": action.rawValue]) }
    /// Inserts a suffix. It does not replace the TV's entire field; editing belongs to a later layer.
    func insertText(_ text: String) async throws {
        try validate(text, maximum: 65_536)
        _ = try await request("text", ["text": text])
    }
    func launchApp(_ uriOrPackage: String) async throws {
        try validate(uriOrPackage, maximum: 4096)
        _ = try await request("launch_app", ["uri_or_package": uriOrPackage])
    }
    func power() async throws { _ = try await request("power") }
    func disconnect() async throws { _ = try await request("disconnect") }
    func close() async {
        let intent = publication.advance()
        await owner.close(intent: intent)
        state = AtvClientState(phase: .closed)
    }
    deinit { owner.closeDetached() }

    private func validate(_ value: String, maximum: Int) throws {
        guard !value.isEmpty, value.utf8.count <= maximum, !value.contains("\0") else { throw AtvClientError.invalidInput }
    }
    private func validateClientName(_ name: String) throws {
        try validate(name, maximum: 256)
        guard name.unicodeScalars.count <= 64, !name.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) else { throw AtvClientError.invalidInput }
    }
    private func validateTarget(_ host: String, _ port: UInt16?, _ identity: AtvIdentity) throws {
        try validate(host, maximum: 1024)
        try validate(identity.certPem, maximum: 65_536)
        try validate(identity.keyPem, maximum: 65_536)
        guard port != 0 else { throw AtvClientError.invalidInput }
    }
    private func request(_ operation: String, _ fields: [String: Any] = [:], setup: Bool = false, timeout: TimeInterval? = nil) async throws -> AtvEvent {
        let changesConnection = operation == "connect" || operation == "disconnect"
        let intent = changesConnection ? publication.advance() : publication.currentIntent
        if changesConnection { state = AtvClientState(phase: operation == "connect" ? .connecting : .disconnected) }
        do {
            return try await owner.request(operation, fields: fields, intent: intent, setup: setup, timeout: timeout)
        } catch {
            // A rejected/unavailable/retired owner must not leave the optimistic phase stuck.
            if changesConnection && publication.currentIntent == intent {
                let failure = (error as? AtvClientError) ?? .malformedEvent
                let terminal = [AtvClientError.closed, .cancelled, .timeout].contains(failure)
                state = AtvClientState(phase: terminal ? .closed : .disconnected, failure: failure)
            }
            throw error
        }
    }
}

/// Only this queue owns requests/connection identity. Polling is separate and backpressured to
/// one delivery; no unbounded dispatch backlog is created when native state pushes arrive fast.
private final class AtvClientOwner: @unchecked Sendable {
    private struct Pending {
        let operation: String
        let continuation: CheckedContinuation<AtvEvent, Error>
        let deadline: DispatchWorkItem
    }
    private let queue = DispatchQueue(label: "PlayBridge.atv.owner")
    private let pollQueue = DispatchQueue(label: "PlayBridge.atv.poll", qos: .utility)
    private let transport: AtvNativeTransport
    private let limits: AtvClientLimits
    private let admission: AtvAdmission
    private var worker: AtvPollLifetime?
    private var finished = false
    private var stopped = false
    private var terminalError = AtvClientError.closed
    private var pending: [String: Pending] = [:]
    private var intent: UInt64 = 0
    private var connectionID: String?
    private var connected = false
    private var pairingReady = false
    private var state = AtvClientState()
    private var observer: ((AtvClientState, UInt64) -> Void)?
    private var closeWaiters: [CheckedContinuation<Void, Never>] = []
    private let controlOperations: Set<String> = ["key", "volume", "text", "launch_app", "power"]

    init(transport: AtvNativeTransport, limits: AtvClientLimits) {
        self.transport = transport
        self.limits = limits
        admission = AtvAdmission(maximum: min(32, max(1, limits.maximumPending)))
    }
    func observe(_ observer: @escaping (AtvClientState, UInt64) -> Void) { queue.async { self.observer = observer } }
    func request(_ operation: String, fields: [String: Any], intent: UInt64, setup: Bool, timeout: TimeInterval?) async throws -> AtvEvent {
        // Bound queued requests too, before encoding/capturing payloads on the owner queue.
        guard admission.reserve() else { throw AtvClientError.overflow }
        defer { admission.release() }
        // Encode outside queued closures: native work gets immutable data, never shared dictionaries.
        var body = fields
        let id = UUID().uuidString
        body["command"] = operation; body["request_id"] = id
        guard let data = try? JSONSerialization.data(withJSONObject: body) else { throw AtvClientError.invalidInput }
        guard data.count <= min(1_048_576, max(1, limits.maximumCommandBytes)) else { throw AtvClientError.overflow }
        let cancellation = AtvCancellation()
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                queue.async {
                    if cancellation.isCancelled { continuation.resume(throwing: AtvClientError.cancelled); return }
                    self.begin(id, operation, data, intent, setup, timeout, continuation)
                }
            }
        }, onCancel: {
            cancellation.cancel()
            self.queue.async {
                if self.pending[id] != nil { self.shutdown(.cancelled, intent: intent) }
            }
        })
    }
    private func begin(_ id: String, _ operation: String, _ data: Data, _ intent: UInt64, _ setup: Bool, _ timeout: TimeInterval?, _ continuation: CheckedContinuation<AtvEvent, Error>) {
        if stopped { continuation.resume(throwing: terminalError); return }
        guard pending.count < min(32, max(1, limits.maximumPending)) else { continuation.resume(throwing: AtvClientError.overflow); return }
        if controlOperations.contains(operation) && !connected { continuation.resume(throwing: AtvClientError.notConnected); return }
        if operation == "finish_pairing" && !pairingReady { continuation.resume(throwing: AtvClientError.notPairing); return }
        do { try startIfNeeded() } catch { continuation.resume(throwing: error); return }
        if operation == "connect" || operation == "disconnect" {
            self.intent = intent
            retireControl(.superseded)
            state = AtvClientState(phase: operation == "connect" ? .connecting : .disconnected)
            if operation == "connect" { connectionID = id }
            publish()
        }
        if operation == "start_pairing" || operation == "cancel_pairing" {
            for oldID in Array(pending.keys) where ["start_pairing", "finish_pairing"].contains(pending[oldID]!.operation) { complete(oldID, .failure(AtvClientError.superseded)) }
            pairingReady = false
        }
        if operation == "finish_pairing" { pairingReady = false }
        let deadline = DispatchWorkItem { [weak self] in
            guard let self, self.pending[id] != nil else { return }
            self.shutdown(.timeout, intent: self.intent)
        }
        pending[id] = Pending(operation: operation, continuation: continuation, deadline: deadline)
        guard transport.submit(data) else {
            complete(id, .failure(AtvClientError.rejected))
            if connectionID == id { retireControl(.rejected); state = AtvClientState(phase: .disconnected, failure: .rejected); publish() }
            return
        }
        let duration = timeout ?? (setup ? limits.setupTimeout : limits.operationTimeout)
        queue.asyncAfter(deadline: .now() + min(65, max(0.001, duration.isFinite ? duration : 15)), execute: deadline)
    }
    private func startIfNeeded() throws {
        guard worker == nil else { return }
        let abi = transport.abiVersion()
        guard abi != 0 else { throw AtvClientError.unavailable }
        guard abi == 1 else { throw AtvClientError.unsupportedABI }
        guard transport.start() else { throw AtvClientError.startFailed }
        let worker = AtvPollLifetime(transport: transport)
        self.worker = worker
        let maximum = min(1_048_576, max(1, limits.maximumEventBytes))
        pollQueue.async { [weak self, worker, queue] in
            while !worker.isStopped {
                let result: Result<Data?, Error> = Result { try worker.transport.nextEvent(waitMilliseconds: 250, maximumBytes: maximum) }
                if worker.isStopped { break }
                let delivered = DispatchSemaphore(value: 0)
                queue.async { [weak self] in
                    defer { delivered.signal() }
                    guard let self, !self.stopped else { return }
                    switch result {
                    case .success(let data): if let data { self.receive(data, maximum: maximum) }
                    case .failure(let error): self.shutdown((error as? AtvClientError) ?? .malformedEvent, intent: self.intent)
                    }
                }
                delivered.wait()
            }
            // shutdown serializes cancel after prior submits, then stops polling. No native call
            // can be in flight or start after this free. Close waiters finish only after free.
            worker.transport.free()
            queue.async { [weak self] in self?.finishClose() }
        }
    }
    private func receive(_ data: Data, maximum: Int) {
        let event: AtvEvent
        do {
            let id = try AtvEvent.correlationID(data, maximumBytes: maximum)
            if let id, pending[id] == nil && id != connectionID { return }
            event = try AtvEvent.decode(data, maximumBytes: maximum)
        }
        catch { shutdown((error as? AtvClientError) ?? .malformedEvent, intent: intent); return }
        switch event {
        case .state(let id, let television):
            guard connected, id == connectionID else { return }
            state.television = television; publish(); return
        case .closed(let id, let kind):
            guard id == connectionID else { return }
            retireControl(.native(kind)); state = AtvClientState(phase: .disconnected, failure: .native(kind)); publish(); return
        case .error(nil, let error): shutdown(error, intent: intent); return
        case .error(let id?, let error):
            guard pending[id] != nil else { return }
            complete(id, .failure(error))
            if id == connectionID { retireControl(error); state = AtvClientState(phase: .disconnected, failure: error); publish() }
            return
        default: break
        }
        guard let id = event.requestID, let request = pending[id] else { return }
        let valid: Bool
        switch event {
        case .devices: valid = request.operation == "discover"
        case .identity: valid = request.operation == "generate_identity"
        case .pairingStarted: valid = request.operation == "start_pairing"
        case .paired: valid = request.operation == "finish_pairing"
        case .connected: valid = request.operation == "connect" && connectionID == id
        case .operation(_, let operation): valid = request.operation == operation
        default: valid = false
        }
        guard valid else { shutdown(.malformedEvent, intent: intent); return }
        if case .pairingStarted = event { pairingReady = true }
        if case .connected = event { connected = true; state = AtvClientState(phase: .connected); publish() }
        complete(id, .success(event))
    }
    private func retireControl(_ error: AtvClientError) {
        connectionID = nil; connected = false
        for id in Array(pending.keys) where pending[id]!.operation == "connect" || controlOperations.contains(pending[id]!.operation) { complete(id, .failure(error)) }
    }
    private func complete(_ id: String, _ result: Result<AtvEvent, Error>) {
        guard let entry = pending.removeValue(forKey: id) else { return }
        entry.deadline.cancel(); entry.continuation.resume(with: result)
    }
    private func publish() { observer?(state, intent) }
    func close(intent: UInt64) async {
        await withCheckedContinuation { continuation in
            queue.async {
                if self.finished { continuation.resume(); return }
                self.closeWaiters.append(continuation)
                self.shutdown(.closed, intent: intent)
            }
        }
    }
    func closeDetached() { queue.async { self.shutdown(.closed, intent: self.intent) } }
    private func shutdown(_ error: AtvClientError, intent: UInt64) {
        guard !stopped else { return }
        stopped = true; terminalError = error; self.intent = intent
        connected = false; connectionID = nil; pairingReady = false
        for id in Array(pending.keys) { complete(id, .failure(error)) }
        state = AtvClientState(phase: .closed, failure: error); publish()
        if let worker { transport.cancel(); worker.stop() }
        else { finishClose() }
    }
    private func finishClose() {
        finished = true; worker = nil
        let waiters = closeWaiters; closeWaiters = []
        for waiter in waiters { waiter.resume() }
    }
}

private final class AtvCancellation: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    var isCancelled: Bool { lock.withLock { cancelled } }
    func cancel() { lock.withLock { cancelled = true } }
}
private final class AtvPollLifetime: @unchecked Sendable {
    let transport: AtvNativeTransport
    private let cancellation = AtvCancellation()
    init(transport: AtvNativeTransport) { self.transport = transport }
    var isStopped: Bool { cancellation.isCancelled }
    func stop() { cancellation.cancel() }
}

/// At most one MainActor publication is queued; state pushes coalesce. Intent fencing prevents
/// an already queued old connection from reappearing after connect/disconnect/close begins.
private final class AtvStatePublication: @unchecked Sendable {
    private let lock = NSRecursiveLock()
    private var intent: UInt64 = 0
    private var scheduled = false
    private var latest: AtvClientState?
    var currentIntent: UInt64 { lock.withLock { intent } }
    func advance() -> UInt64 { lock.withLock { intent &+= 1; latest = nil; return intent } }
    func offer(_ state: AtvClientState, intent: UInt64) -> Bool {
        lock.withLock {
            guard self.intent == intent else { return false }
            latest = state
            guard !scheduled else { return false }
            scheduled = true; return true
        }
    }
    func consume(_ apply: (AtvClientState) -> Void) {
        lock.withLock {
            scheduled = false
            if let latest { self.latest = nil; apply(latest) }
        }
    }
}


private final class AtvAdmission: @unchecked Sendable {
    private let lock = NSLock()
    private let maximum: Int
    private var count = 0
    init(maximum: Int) { self.maximum = maximum }
    func reserve() -> Bool {
        lock.withLock {
            guard count < maximum else { return false }
            count += 1; return true
        }
    }
    func release() { lock.withLock { count -= 1 } }
}
