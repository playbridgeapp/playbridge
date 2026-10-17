import Foundation
import Combine
import Security

@MainActor private func check(_ condition: @autoclosure () -> Bool, _ label: String, line: UInt = #line) {
    if !condition() { fatalError("ATV coordinator: \(label), line \(line)") }
}
@MainActor private func pump() async { for _ in 0..<80 { await Task.yield() } }
private let identity = AtvIdentity(certPem: "fixture-cert", keyPem: "fixture-key", fingerprint: String(repeating: "a", count: 64))
private let pin = String(repeating: "b", count: 64)
private func tv(_ endpoint: String = "playbridge:one", pin: String = pin) -> AtvTvRecord {
    .init(fingerprint: pin, mac: nil, serverName: "Living Room", lastIp: "192.0.2.1", receiverIds: [endpoint])
}
@MainActor private final class FakeStorage: AtvCoordinatorStorage {
    var identityValue: AtvIdentity? = identity
    var records: [AtvTvRecord] = [tv()]
    var identities: [String: AtvIdentity] = [:]
    var error: AtvStorageError?
    var failSave = false, failForget = false, failIP = false
    var saves = 0, forgets = 0
    var pauseSave = false
    var saveGate: CheckedContinuation<Void, Never>?
    func load() async -> AtvCredentialSnapshot { .init(identity: error == nil ? identityValue : nil, records: records, error: error, identities: identities) }
    func pairingIdentity() async throws -> AtvIdentity? {
        if let error, error != .corrupt { throw error }
        return identityValue
    }
    func save(_ identity: AtvIdentity, record: AtvTvRecord, permit: AtvStoragePermit) async throws {
        if pauseSave { await withCheckedContinuation { saveGate = $0 } }
        guard permit.isValid else { throw AtvStorageError.unavailable }
        if failSave { throw AtvStorageError.writeFailed }
        saves += 1; identityValue = identity; error = nil
        records.removeAll { $0.fingerprint == record.fingerprint || !$0.receiverIds.isDisjoint(with: record.receiverIds) }
        records.append(record)
    }
    func updateLastIp(_ fingerprint: String, host: String, permit: AtvStoragePermit) async throws {
        guard permit.isValid else { throw AtvStorageError.unavailable }
        if failIP { throw AtvStorageError.writeFailed }
    }
    func forget(_ fingerprint: String) async throws {
        forgets += 1
        if failForget { throw AtvStorageError.writeFailed }
        records.removeAll { $0.fingerprint == fingerprint }
    }
}
@MainActor private final class FakePaths: AtvPathWatching {
    var changed: (@MainActor () -> Void)?
    var starts = 0, stops = 0
    func start(changed: @escaping @MainActor () -> Void) { starts += 1; self.changed = changed }
    func stop() { stops += 1; changed = nil }
}
@MainActor private final class FakeClock: AtvCoordinatorClock {
    struct Waiter { let deadline: Double; let continuation: CheckedContinuation<Void, Error> }
    var now: Double = 0
    var waits: [UUID: Waiter] = [:]
    var requested: [Double] = []
    func sleep(seconds: TimeInterval) async throws {
        let id = UUID(); requested.append(seconds)
        try await withTaskCancellationHandler(operation: {
            try Task.checkCancellation()
            try await withCheckedThrowingContinuation { waits[id] = Waiter(deadline: now + seconds, continuation: $0) }
        }, onCancel: { Task { @MainActor in self.waits.removeValue(forKey: id)?.continuation.resume(throwing: CancellationError()) } })
    }
    func advance(_ seconds: Double) async {
        now += seconds
        let ready = waits.filter { $0.value.deadline <= now }
        for (id, waiter) in ready { waits[id] = nil; waiter.continuation.resume() }
        await pump()
    }
}
@MainActor private final class FakeClient: AtvCoordinatorClient {
    var onState: ((AtvClientState) -> Void)?
    var connectError: AtvClientError?
    var finishError: AtvClientError?
    var connectGate: CheckedContinuation<Void, Error>?
    var startGate: CheckedContinuation<AtvPairingStarted, Error>?
    var finishGate: CheckedContinuation<AtvPaired, Error>?
    var pauseConnect = false, pauseStart = false, pauseFinish = false
    var connects = 0, closes = 0, starts = 0, finishes = 0, generated = 0
    var receivedPin: String?, receivedHost: String?, receivedCert: String?
    var lastCodeValid = false
    var initialTelevision: AtvTelevisionState?
    var preparedIdentity: AtvIdentity?
    var offeredIdentity: AtvIdentity?
    func generateIdentity(existing: AtvIdentity?) async throws -> AtvIdentity {
        generated += 1; offeredIdentity = existing
        return preparedIdentity ?? existing ?? identity
    }
    func connect(host: String, identity: AtvIdentity, pin: String) async throws {
        connects += 1; receivedPin = pin; receivedHost = host; receivedCert = identity.certPem
        if pauseConnect { try await withCheckedThrowingContinuation { connectGate = $0 } }
        if let connectError { throw connectError }
        onState?(.init(phase: .connected, television: initialTelevision))
    }
    func startPairing(host: String, identity: AtvIdentity) async throws -> AtvPairingStarted {
        starts += 1
        if pauseStart { return try await withCheckedThrowingContinuation { startGate = $0 } }
        return .init(serverName: "Living Room", fingerprint: pin, mac: nil)
    }
    func finishPairing(code: String) async throws -> AtvPaired {
        finishes += 1; lastCodeValid = code.count == 6 && code == code.uppercased()
        if pauseFinish { return try await withCheckedThrowingContinuation { finishGate = $0 } }
        if let finishError { throw finishError }
        return paired
    }
    var paired: AtvPaired { .init(serverCertificateDER: Data([1]), fingerprint: pin, mac: nil, serverName: "Living Room") }
    var frames: [String] = []
    var inserts: [String] = []
    var pauseCommand = false
    var commandGate: CheckedContinuation<Void, Error>?
    var failFrame: Int?
    func command(_ action: AtvControlAction) async throws {
        switch action {
        case .key(let key): frames.append(key)
        case .volume: frames.append("volume")
        case .launch: frames.append("launch")
        case .power: frames.append("power")
        case .edit: fatalError("Edits must be split into fenced frames")
        }
        if pauseCommand { pauseCommand = false; try await withCheckedThrowingContinuation { commandGate = $0 } }
        if failFrame == frames.count { throw AtvClientError.native(.protocolError) }
    }
    func insertText(_ text: String) async throws { frames.append("insert"); inserts.append(text) }
    func close() async { closes += 1; onState = nil }
}
@MainActor private final class Rig {
    let storage = FakeStorage(), paths = FakePaths(), clock = FakeClock()
    var clients: [FakeClient] = []
    var configure: ((FakeClient) -> Void)?
    lazy var coordinator = AtvRemoteCoordinator(storage: storage, clock: clock, paths: paths) { [unowned self] in
        let client = FakeClient(); self.configure?(client); self.clients.append(client); return client
    }
    func target(_ id: String = "one", host: String = "192.0.2.1") -> AtvReceiverTarget {
        .init(endpoint: "playbridge:" + id, name: "Living Room", addresses: [host], androidTv: true)
    }
    func activate(_ id: String = "one") async {
        coordinator.update(receiver: target(id), remoteVisible: true, foreground: true); await pump()
    }
    func settled() async { await pump() }
    func stop() async { coordinator.update(receiver: nil, remoteVisible: false, foreground: false); await pump() }
    func unpaired() { storage.records = [] }
}

@MainActor private func lifecycleChecks() async {
    let r = Rig(); _ = r.coordinator
    r.configure = { $0.initialTelevision = .init(isOn: true, volumeLevel: 2, volumeMax: 10, volumeMuted: false, volumeRevision: 1, currentApp: nil, model: nil, vendor: nil) }
    check(r.clients.isEmpty, "construction is inert")
    await r.activate()
    check(r.clients.count == 1 && !r.clock.requested.contains(4), "selected host connects without discovery wait")
    check(r.coordinator.channel.phase == .connected && r.clients[0].receivedPin == pin, "stored pin connection")
    check(r.coordinator.channel.television?.volumeRevision == 1, "initial typed TV state survives setup")
    let old = r.clients[0], stale = old.onState, oldPath = r.paths.changed
    r.coordinator.update(receiver: r.target(), remoteVisible: true, foreground: true)
    check(r.clients.count == 1, "unchanged ownership does not restart")
    r.coordinator.update(receiver: r.target("two"), remoteVisible: true, foreground: true)
    check(r.coordinator.channel.phase == .idle, "switch clears connection synchronously")
    await pump(); await r.settled()
    stale?(.init(phase: .connected)); stale?(.init(phase: .disconnected, failure: .native(.pinMismatch)))
    check(r.coordinator.channel.endpoint == "playbridge:two" && r.coordinator.channel.phase == .available, "late old connected/closed ignored")
    oldPath?()
    check(r.coordinator.channel.phase == .available, "obsolete path watcher fenced")
    check(old.closes == 1, "old client closed")
    await r.stop()
    check(!r.coordinator.canPair && r.paths.changed == nil, "hidden/background stops paths and pairing")

    let pending = Rig(); pending.configure = { $0.pauseConnect = true }
    await pending.activate(); await pending.settled()
    let connecting = pending.clients[0]
    await pending.stop()
    connecting.connectGate?.resume(); connecting.connectGate = nil; await pump()
    check(pending.coordinator.channel.phase == .idle, "late connect success after background ignored")

    let path = Rig(); await path.activate(); await path.settled()
    let callback = path.clients[0].onState
    path.paths.changed?()
    check(path.coordinator.channel.phase == .idle, "path change clears live authority")
    await pump(); await path.settled()
    check(path.clients.count == 2 && path.clients[1].receivedPin == pin, "path rediscovery retains pin")
    callback?(.init(phase: .disconnected, failure: .native(.notTrusted)))
    check(path.coordinator.channel.phase == .connected, "late old path event fenced")
    await path.stop()
}

@MainActor private func lifetimePolicyChecks() {
    var state = AtvConnectionLifetime()
    check(!state.keepConnection, "initially inactive")
    state.foregroundChanged(true)
    check(state.keepConnection, "foreground connects regardless of screen")
    state.foregroundChanged(false)
    check(state.keepConnection && state.backgroundGrace, "background grace preserves session")
    check(!state.expired(at: 59.999) && state.expired(at: 60), "elapsed deadline works without a running timer")
    let grace = state
    state.foregroundChanged(false)
    check(state == grace, "duplicate background cannot extend grace")
    state.foregroundChanged(true); state.graceExpired()
    check(state.keepConnection && !state.backgroundGrace, "return cancels stale expiry")
    state.foregroundChanged(false); state.graceExpired()
    check(!state.keepConnection, "expired background disconnects")
    state.foregroundChanged(true)
    check(state.keepConnection, "foreground reconnects after expiry")
    check(AtvConnectionLifetime.backgroundGraceSeconds == 60, "sixty second grace")
    for settled in [false, true] {
        check(!AtvConnectionLifetime.waitForDiscovery(hasSelection: true, settled: settled), "selection never waits for scan")
    }
    check(AtvConnectionLifetime.waitForDiscovery(hasSelection: false, settled: false), "auto-pick waits for settled snapshot")
    check(!AtvConnectionLifetime.waitForDiscovery(hasSelection: false, settled: true), "settled auto-pick can proceed")
}

@MainActor private func foregroundGraceChecks() async {
    let r = Rig()
    r.coordinator.update(receiver: r.target(), remoteVisible: false, foreground: true); await pump()
    check(r.coordinator.channel.phase == .connected && r.clients.count == 1, "foreground dashboard connects immediately")
    let live = r.clients[0], connection = r.coordinator.channel.connectionID
    check(!r.coordinator.canPair && r.coordinator.pairing.phase == .idle, "hidden UI cannot pair")
    r.coordinator.beginPairing(); await pump()
    check(r.clients.count == 1 && live.starts == 0, "no hidden pairing session or prompt")
    r.coordinator.update(receiver: r.target(), remoteVisible: true, foreground: true); await pump()
    r.coordinator.update(receiver: r.target(), remoteVisible: false, foreground: true); await pump()
    check(r.clients.count == 1 && live.closes == 0, "Remote entry and exit keep socket")
    r.coordinator.update(receiver: r.target(), remoteVisible: false, foreground: false); await pump()
    await r.clock.advance(30)
    check(r.coordinator.channel.connectionID == connection && live.closes == 0, "background retains physical socket")
    check(r.coordinator.fullControl.ticket == nil, "background cannot issue UI commands")
    r.coordinator.update(receiver: r.target(), remoteVisible: false, foreground: true); await pump()
    await r.clock.advance(40)
    check(r.coordinator.channel.connectionID == connection && r.clients.count == 1, "return within grace cancels old expiry")
    r.coordinator.update(receiver: r.target(), remoteVisible: false, foreground: false); await pump()
    await r.clock.advance(59.999)
    check(live.closes == 0, "session survives until grace deadline")
    await r.clock.advance(0.001)
    check(live.closes == 1 && r.coordinator.channel.phase == .idle, "grace expires at sixty seconds")
    r.coordinator.update(receiver: r.target(), remoteVisible: false, foreground: true); await pump()
    check(r.clients.count == 2 && r.coordinator.channel.phase == .connected, "foreground reconnects without Remote entry")
    await r.stop()

    let suspended = Rig(); await suspended.activate()
    suspended.coordinator.update(receiver: suspended.target(), remoteVisible: false, foreground: false); await pump()
    suspended.clock.now += 61 // No waiter resumes while the app is suspended.
    suspended.coordinator.update(receiver: suspended.target(), remoteVisible: false, foreground: true); await pump()
    check(suspended.clients.count == 2 && suspended.clients[0].closes == 1, "return past elapsed deadline reconnects even if timer never ran")
    await suspended.stop()

    let switching = Rig(); switching.storage.records.append(tv("playbridge:two", pin: String(repeating: "c", count: 64)))
    switching.coordinator.update(receiver: switching.target(), remoteVisible: false, foreground: true); await pump()
    let first = switching.clients[0]
    switching.coordinator.update(receiver: switching.target(), remoteVisible: false, foreground: false); await pump()
    switching.coordinator.update(receiver: switching.target("two"), remoteVisible: false, foreground: false); await pump()
    check(first.closes == 1 && switching.clients.count == 2, "selection switch closes previous socket during grace")
    check(switching.clients[1].receivedPin == String(repeating: "c", count: 64), "switch uses only selected pairing")
    await switching.stop(); await switching.clock.advance(60)
    check(switching.clients[1].closes == 1 && switching.clients.count == 2, "deselect closes immediately, stale grace cannot reconnect")

    let silent = Rig(); silent.configure = { $0.connectError = .native(.pinMismatch) }
    silent.coordinator.update(receiver: silent.target(), remoteVisible: false, foreground: true); await pump()
    check(silent.coordinator.channel.phase == .repairRequired && silent.coordinator.pairing.phase == .idle, "background setup records Pair again silently")
    silent.coordinator.beginPairing(); await pump()
    check(silent.clients.count == 1 && !silent.coordinator.canPair, "repair never presents pairing outside Remote")
    await silent.stop()

    let relocation = Rig(); await relocation.activate()
    relocation.coordinator.updateDiscovery([.init(name: "Living Room", host: "192.0.2.8", port: 6466, addresses: ["192.0.2.8"], txt: [["bt", "AA:BB"]])])
    // IP/name alone cannot relocate or transfer another TV's credentials.
    await pump()
    check(relocation.clients.count == 1, "unrelated discovery does not restart selected socket")
    await relocation.stop()

    let moved = Rig()
    moved.storage.records = [.init(fingerprint: pin, mac: "AA:BB", serverName: "Living Room", lastIp: "192.0.2.1", receiverIds: ["playbridge:one"])]
    await moved.activate()
    moved.coordinator.updateDiscovery([.init(name: "Living Room", host: "192.0.2.8", port: 6466, addresses: ["192.0.2.8"], txt: [["bt", "AA:BB"]])]); await pump()
    check(moved.clients.count == 2 && moved.clients[0].closes == 1 && moved.clients[1].receivedHost == "192.0.2.8", "passive matching MAC relocates only verified owner")
    moved.coordinator.update(receiver: moved.target(), remoteVisible: false, foreground: false); await pump()
    moved.coordinator.updateDiscovery([]); await pump()
    check(moved.clients.count == 2 && moved.clients[1].closes == 0, "stopping discovery preserves relocated socket through grace")
    await moved.stop()

    let handshake = Rig(); handshake.configure = { $0.pauseConnect = true }
    handshake.coordinator.update(receiver: handshake.target(), remoteVisible: false, foreground: true); await pump()
    handshake.coordinator.update(receiver: handshake.target(), remoteVisible: true, foreground: true); await pump()
    handshake.coordinator.update(receiver: handshake.target(), remoteVisible: false, foreground: false); await pump()
    await handshake.clock.advance(1)
    handshake.coordinator.update(receiver: handshake.target(), remoteVisible: false, foreground: true); await pump()
    check(handshake.clients.count == 1 && handshake.clients[0].closes == 0, "Remote entry and short foreground return preserve in-flight handshake")
    handshake.clients[0].connectGate?.resume(); handshake.clients[0].connectGate = nil; await pump()
    check(handshake.coordinator.channel.phase == .connected, "original handshake becomes ready")
    await handshake.stop()

    let retry = Rig(); retry.configure = { $0.connectError = .native(.unreachable) }
    retry.coordinator.update(receiver: retry.target(), remoteVisible: false, foreground: true); await pump()
    for seconds in [1, 2, 4, 8, 16] { await retry.clock.advance(Double(seconds)) }
    check(retry.coordinator.channel.retrySeconds == 30 && retry.clients.count == 6, "long backoff reached")
    retry.coordinator.update(receiver: retry.target(), remoteVisible: true, foreground: true); await pump()
    check(retry.clients.count == 7 && retry.coordinator.channel.retrySeconds == 1, "Remote entry resets long backoff immediately")
    await retry.clock.advance(1)
    check(retry.clients.count == 8, "Remote retry restarts at one second")
    retry.coordinator.update(receiver: retry.target(), remoteVisible: false, foreground: false); await pump()
    retry.coordinator.update(receiver: retry.target(), remoteVisible: false, foreground: true); await pump()
    check(retry.clients.count == 9 && retry.coordinator.channel.retrySeconds == 1, "foreground resets backoff without Remote")
    await retry.stop()
    print("PASS: any-screen foreground ownership, saved-host fast path, 60s grace, return/switch/deselect, silent repair and backoff resets")
}

@MainActor private func retryChecks() async {
    let r = Rig(); r.configure = { $0.connectError = .native(.unreachable) }
    await r.activate(); await r.settled()
    for seconds in [1,2,4,8,16,30,30] {
        check(r.coordinator.channel.retrySeconds == seconds && r.coordinator.channel.phase == .retrying, "bounded backoff")
        let old = r.clients.last!
        await r.clock.advance(Double(seconds))
        check(old.closes == 1, "retire failed attempt before retry")
    }
    let count = r.clients.count; await r.stop(); await r.clock.advance(60)
    check(r.clients.count == count, "cancelled retry cannot reconnect")

    let trust = Rig(); trust.configure = { $0.connectError = .native(.notTrusted) }
    await trust.activate(); await trust.settled(); await trust.clock.advance(1); await trust.clock.advance(2)
    check(trust.clients.count == 3 && trust.coordinator.channel.phase == .repairRequired, "not trusted stops after three")
    await trust.stop(); await trust.activate(); await trust.settled(); await trust.clock.advance(60)
    check(trust.clients.count == 3 && trust.coordinator.channel.phase == .repairRequired, "trust stop survives foreground")
    trust.configure = nil
    trust.coordinator.beginPairing(); await pump(); trust.coordinator.submitCode("abcdef"); await pump()
    check(trust.coordinator.pairing.phase == .paired, "verified repair succeeds")
    trust.coordinator.cancelPairing(); await pump(); await trust.settled()
    check(trust.coordinator.channel.phase == .connected && trust.clients.count == 5, "verified repair clears trust stop")
    let callback = trust.clients.last!.onState
    callback?(.init(phase: .disconnected, failure: .native(.closed))); await pump()
    check(trust.coordinator.channel.phase == .retrying && trust.coordinator.channel.retrySeconds == 1, "live closed event reconnects")
    await trust.clock.advance(1)
    check(trust.coordinator.channel.phase == .connected, "reconnected after close")
    await trust.stop()

    let mismatch = Rig(); mismatch.configure = { $0.connectError = .native(.pinMismatch) }
    await mismatch.activate(); await mismatch.settled(); await mismatch.clock.advance(60)
    check(mismatch.clients.count == 1 && mismatch.coordinator.channel.phase == .repairRequired, "pin mismatch never retries")
    await mismatch.stop()
    let disk = Rig(); disk.storage.failIP = true
    await disk.activate(); await disk.settled()
    check(disk.coordinator.channel.phase == .storageUnavailable && disk.clients[0].closes == 1, "failed durable IP save never publishes ready")
    await disk.stop()
}

@MainActor private func ordinaryClosureChecks() async {
    let r = Rig(); await r.activate()
    for _ in 0..<4 {
        r.clients.last?.onState?(.init(phase: .disconnected, failure: .native(.closed))); await pump()
        check(r.coordinator.channel.phase == .retrying && !r.coordinator.fullControl.needsRepair, "another remote disconnect is retryable, not Pair again")
        await r.clock.advance(1)
        check(r.coordinator.channel.phase == .connected, "ordinary closure reconnects")
    }
    await r.stop()
}

@MainActor private func legacyMigrationChecks() async {
    let r = Rig()
    let migrated = AtvIdentity(certPem: "new-cert", keyPem: "new-key", fingerprint: String(repeating: "d", count: 64),
                               clientName: "PlayBridge (iPhone) fixture", replacesLegacy: true)
    r.configure = { $0.preparedIdentity = migrated }
    await r.activate()
    check(r.clients[0].generated == 0 && r.storage.identityValue == identity, "control keeps existing identity without preparing migration")
    r.coordinator.beginPairing(); await pump()
    check(r.clients.last?.offeredIdentity == identity && r.storage.identityValue == identity, "pairing offers existing certificate to native migration, no early save")
    r.coordinator.cancelPairing(); await pump()
    check(r.storage.identityValue == identity, "cancelled pairing retains old identity")
    r.coordinator.beginPairing(); await pump(); r.coordinator.submitCode("ABCDEF"); await pump()
    check(r.storage.identityValue == migrated && r.coordinator.pairing.phase == .paired, "verified pairing commits prepared identity")
    await r.stop()
    let retained = Rig()
    retained.storage.identityValue = migrated
    retained.storage.records.append(tv("playbridge:two", pin: String(repeating: "e", count: 64)))
    retained.storage.identities[pin] = identity
    await retained.activate()
    check(retained.clients[0].receivedCert == identity.certPem, "unmigrated TV connects with retained identity")
    retained.coordinator.update(receiver: retained.target("two"), remoteVisible: true, foreground: true); await pump()
    check(retained.clients.last?.receivedCert == migrated.certPem, "newly paired TV connects with unique install identity")
    await retained.stop()
    print("PASS: pairing-only identity preparation, no connect-time rotation, cancellation retention and verified commit")
}

@MainActor private func pairingChecks() async {
    let r = Rig(); r.unpaired(); r.storage.identityValue = nil
    await r.activate(); await r.settled()
    r.coordinator.beginPairing(); await pump()
    check(r.coordinator.pairing.phase == .waitingForCode && r.storage.saves == 0, "identity not persisted before verified pairing")
    r.coordinator.submitCode("12"); await pump()
    check(r.clients[0].finishes == 0 && r.coordinator.pairing.phase == .waitingForCode, "invalid code cannot submit")
    r.coordinator.submitCode("ab-cd ef"); await pump()
    check(r.coordinator.pairing.phase == .paired && r.storage.saves == 1 && r.clients[0].lastCodeValid, "uppercase six hex and durable verified save")
    check(r.storage.records.first?.receiverIds == ["playbridge:one"], "pair bound to selected endpoint")
    r.coordinator.cancelPairing(); await pump(); await r.settled()
    check(r.coordinator.channel.phase == .connected, "pair sheet dismissal connects pinned channel")
    r.coordinator.forget(pin)
    check(r.coordinator.channel.phase == .idle, "forget clears control before disk")
    await pump(); await r.settled()
    check(r.storage.records.isEmpty && r.coordinator.channel.phase == .available, "forget removes pairing")
    await r.stop()

    let fail = Rig(); fail.unpaired(); fail.storage.failSave = true
    await fail.activate(); await fail.settled(); fail.coordinator.beginPairing(); await pump()
    fail.coordinator.submitCode("ABCDEF"); await pump()
    check(fail.coordinator.pairing.phase == .failed && fail.coordinator.pairing.storageFailure && fail.storage.records.isEmpty, "failed durable save not paired")
    fail.storage.failSave = false
    fail.coordinator.beginPairing(); await pump()
    check(fail.clients.count == 2 && fail.clients[1].starts == 1, "retry restarts pairing with fresh client")
    fail.clients[1].finishError = .native(.badCode)
    fail.coordinator.submitCode("ABCDEF"); await pump()
    check(fail.coordinator.pairing.failure == .native(.badCode), "bad code failure")
    fail.coordinator.beginPairing(); await pump()
    check(fail.clients.count == 3 && fail.coordinator.pairing.phase == .waitingForCode, "bad code consumes session")
    fail.coordinator.cancelPairing(); await pump()
    check(fail.clients[2].closes == 1 && fail.coordinator.pairing.phase == .idle, "cancel closes pairing")
    await fail.stop()

    let recovery = Rig(); recovery.storage.error = .corrupt; recovery.storage.identityValue = nil
    await recovery.activate(); await recovery.settled()
    recovery.coordinator.beginPairing(); await pump()
    check(recovery.coordinator.pairing.phase == .recoveryConfirmation && recovery.clients.isEmpty, "recovery warning before replacing install identity")
    recovery.coordinator.beginPairing(confirmRecovery: true); await pump()
    recovery.coordinator.submitCode("ABCDEF"); await pump()
    check(recovery.coordinator.identityRecovered && recovery.coordinator.pairing.phase == .paired, "recovery explanation after verified commit")
    await recovery.stop()

    for interruption in 0..<4 {
        let late = Rig(); late.unpaired(); late.configure = { $0.pauseFinish = true }
        await late.activate(); await late.settled(); late.coordinator.beginPairing(); await pump()
        late.coordinator.submitCode("ABCDEF"); await pump()
        let session = late.clients[0]
        switch interruption {
        case 0: late.coordinator.update(receiver: late.target(), remoteVisible: true, foreground: false)
        case 1: late.coordinator.update(receiver: late.target("two"), remoteVisible: true, foreground: true)
        case 2: late.coordinator.pathChanged()
        default: late.coordinator.forget(pin)
        }
        session.finishGate?.resume(returning: session.paired); session.finishGate = nil; await pump()
        check(late.storage.saves == 0 && late.coordinator.pairing.phase != .paired, "late paired event fenced")
        await late.stop()
    }
    let queued = Rig(); queued.unpaired(); queued.storage.pauseSave = true
    await queued.activate(); await queued.settled(); queued.coordinator.beginPairing(); await pump()
    queued.coordinator.submitCode("ABCDEF"); await pump(); await queued.stop()
    queued.storage.saveGate?.resume(); queued.storage.saveGate = nil; await pump()
    check(queued.storage.saves == 0, "queued write permission revoked on background")

    let start = Rig(); start.unpaired(); start.configure = { $0.pauseStart = true }
    await start.activate(); await start.settled(); start.coordinator.beginPairing(); await pump()
    let session = start.clients[0]; await start.stop()
    session.startGate?.resume(returning: .init(serverName: "Living Room", fingerprint: pin, mac: nil)); session.startGate = nil; await pump()
    check(start.coordinator.pairing.phase == .idle, "late pairing-start event fenced")

    let forget = Rig(); await forget.activate(); await forget.settled(); forget.storage.failForget = true
    forget.coordinator.forget(pin); await pump()
    check(forget.coordinator.channel.phase == .storageUnavailable && forget.storage.records.count == 1, "failed forget reported with live channel disconnected")
    await forget.stop()
}

private final class QueueKeychain: AtvKeychainAccess, @unchecked Sendable {
    var data: Data?
    var touchedMain = false
    var writes = 0
    var writeStatus: OSStatus = errSecSuccess
    private func visit() { touchedMain = touchedMain || Thread.isMainThread }
    func read(_ query: [String: Any]) -> (OSStatus, Data?) { visit(); return (data == nil ? errSecItemNotFound : errSecSuccess, data) }
    func update(_ query: [String: Any], attributes: [String: Any]) -> OSStatus {
        visit(); guard data != nil else { return errSecItemNotFound }
        guard writeStatus == errSecSuccess else { return writeStatus }
        writes += 1; data = attributes[kSecValueData as String] as? Data; return errSecSuccess
    }
    func add(_ attributes: [String: Any]) -> OSStatus {
        visit(); guard writeStatus == errSecSuccess else { return writeStatus }
        writes += 1; data = attributes[kSecValueData as String] as? Data; return errSecSuccess
    }
}
@MainActor private func queuedStorageChecks() async {
    let keychain = QueueKeychain()
    let storage = AtvQueuedCredentialStore { AtvCredentialStore(keychain: keychain) }
    check(keychain.data == nil, "production storage queue is lazy")
    let empty = await storage.load()
    check(empty.records.isEmpty && empty.error == nil, "queued empty snapshot")
    do {
        try await storage.save(identity, record: tv(), permit: AtvStoragePermit())
        let snapshot = await storage.load()
        check(snapshot.identity == identity && snapshot.records == [tv()], "real queue/store integration")
        let cancelled = AtvStoragePermit(); cancelled.invalidate()
        do { try await storage.save(identity, record: tv("playbridge:two"), permit: cancelled); check(false, "revoked save must fail") }
        catch { check(error as? AtvStorageError == .unavailable && keychain.writes == 1, "revoked queued write never mutates Keychain") }
        keychain.writeStatus = errSecParam
        do { try await storage.forget(pin); check(false, "failed forget must report") }
        catch { check(error as? AtvStorageError == .writeFailed, "OSStatus from real queue propagated") }
        keychain.writeStatus = errSecSuccess
        try await storage.forget(pin)
        let forgotten = await storage.load()
        check(forgotten.records.isEmpty && forgotten.identity == identity, "queue forget retains identity")
        check(!keychain.touchedMain, "every Keychain call and store construction off main thread")
    } catch { check(false, "queued storage integration") }
}

@MainActor private func commandChecks() async {
    let r = Rig(); await r.activate(); await r.settled()
    let ticket = r.coordinator.fullControl.ticket!, session = r.clients[0]
    do {
        try await r.coordinator.execute(.edit(deletes: 2, suffix: "😀e\u{301}", enter: true), ticket: ticket)
        check(session.frames == ["key_delete", "key_delete", "insert", "key_enter"] && session.inserts == ["😀e\u{301}"], "production delete/insert/Enter frame order")
        try await r.coordinator.execute(.key("menu"), ticket: ticket)
        try await r.coordinator.execute(.volume(.mute), ticket: ticket)
        try await r.coordinator.execute(.launch("com.example.app"), ticket: ticket)
        try await r.coordinator.execute(.power, ticket: ticket)
        check(session.frames.suffix(4) == ["menu", "volume", "launch", "power"], "TV menu/volume/app/power boundary")
        session.pauseCommand = true
        var authority = true
        let delayed = Task { try await r.coordinator.execute(.edit(deletes: 2, suffix: "retired", enter: true), ticket: ticket, authority: { authority }) }
        await pump()
        let before = session.frames.count
        authority = false // Route/context changed while the first delete was in flight.
        session.commandGate?.resume(); session.commandGate = nil
        do { try await delayed.value; check(false, "stale route must fail") } catch {}
        check(session.frames.count == before, "late route cannot insert suffix or Enter after partial deletion")
        session.failFrame = session.frames.count + 1
        do { try await r.coordinator.execute(.edit(deletes: 2, suffix: "ambiguous", enter: true), ticket: ticket); check(false, "partial failure expected") } catch {}
        check(session.frames.last == "key_delete", "failed delete never continues edit")
    } catch { check(false, "unexpected native control fixture failure") }
    await r.stop()
    let count = session.frames.count
    do { try await r.coordinator.execute(.power, ticket: ticket); check(false, "retired owner must fail") } catch {}
    check(session.frames.count == count, "retired connection ticket rejected")
}

@MainActor private func navigationChecks() async {
    let r = Rig(); await r.activate(); await r.settled()
    let ticket = r.coordinator.fullControl.ticket!, session = r.clients[0]
    session.pauseCommand = true
    let first = Task { try await r.coordinator.execute(.key("dpad_up"), ticket: ticket) }
    await pump()
    check(session.frames == ["dpad_up"], "navigation starts without a serial queue")
    for _ in 0..<100 {
        do { try await r.coordinator.execute(.key("dpad_right"), ticket: ticket); check(false, "navigation admission must be bounded") }
        catch { check(error as? AtvClientError == .overflow, "busy navigation is dropped, never queued") }
    }
    check(session.frames == ["dpad_up"], "direct navigation cannot build a backlog")
    session.commandGate?.resume(); session.commandGate = nil
    do { try await first.value } catch { check(false, "first navigation should complete") }

    session.frames = []; session.pauseCommand = true
    let keyboard = Task { try await r.coordinator.execute(.edit(deletes: 2, suffix: "text", enter: true), ticket: ticket) }
    await pump()
    do { try await r.coordinator.execute(.key("dpad_left"), ticket: ticket) }
    catch { check(false, "navigation cannot wait behind keyboard acknowledgement") }
    check(session.frames == ["key_delete", "dpad_left"], "navigation bypasses the edit queue")
    session.commandGate?.resume(); session.commandGate = nil
    do { try await keyboard.value } catch { check(false, "keyboard should retain its acknowledged path") }
    check(session.frames == ["key_delete", "dpad_left", "key_delete", "insert", "key_enter"], "keyboard frames remain ordered")

    session.frames = []; session.pauseCommand = true
    let dispatcher = RemoteNavigationDispatcher(), id = dispatcher.beginGesture()
    var haptics = 0
    for _ in 0..<100 {
        dispatcher.submit(gesture: id, send: { allowed in
            try await r.coordinator.execute(.key("dpad_right"), ticket: ticket, authority: allowed)
        }, onSent: { haptics += 1 }, onError: { _ in })
    }
    await pump()
    check(session.frames == ["dpad_right"] && dispatcher.pendingCount == 1, "slow native acknowledgement holds only one pending step")
    dispatcher.endGesture(id)
    session.commandGate?.resume(); session.commandGate = nil; await pump()
    check(session.frames == ["dpad_right"] && haptics == 1 && !dispatcher.inFlight, "native release never drains pending arrows/haptics")
    let newID = dispatcher.beginGesture()
    dispatcher.submit(gesture: newID, send: { allowed in
        try await r.coordinator.execute(.key("dpad_center"), ticket: ticket, authority: allowed)
    }, onSent: { haptics += 1 }, onError: { _ in })
    dispatcher.endGesture(newID); await pump()
    check(session.frames == ["dpad_right"] && haptics == 1, "release before dispatch writes no OK or haptic")
    do {
        try await r.coordinator.execute(.key("dpad_up"), ticket: ticket, authority: { false })
        check(false, "retired gesture authority must fail")
    } catch {}
    check(session.frames == ["dpad_right"], "authority checked at the native boundary")
    await r.stop()
    print("PASS: native navigation admission cap, keyboard queue bypass/order and release-before/after-write with no drain")
}

@MainActor private func presentationChecks() {
    check(AtvPairingPresentation.normalizedInput("ab cd-ef") == "ABCDEF", "paste normalization")
    check(AtvPairingPresentation.validCode("１２３４５６") == nil, "only ASCII hex")
    check(AtvPairingPresentation.validCode("ﬀﬀﬀ") == nil, "Unicode expansions cannot become ASCII code input")
    check(AtvPairingPresentation.pairingAction(.init(phase: .available)) == "Get full control", "first pairing entry")
    check(AtvPairingPresentation.pairingAction(.init(phase: .retrying, paired: true)) == nil, "temporary disconnect does not invite re-pair")
    check(AtvPairingPresentation.pairingAction(.init(phase: .repairRequired, paired: true)) == "Pair again", "trust repair entry")
    for phase: AtvPairingState.Phase in [.idle,.recoveryConfirmation,.starting,.waitingForCode,.verifying,.failed,.paired] {
        check(!AtvPairingPresentation.message(.init(phase: phase)).isEmpty, "all sheet state fixtures have status text")
    }
    check(AtvPairingPresentation.message(.init(phase: .failed, storageFailure: true)).contains("Couldn't save"), "storage error distinct")
    check(AtvPairingPresentation.recoveryExplanation.contains("Other TVs"), "recovery explains other TV pairings")
    check(AtvPairingPresentation.networkHelp.contains("does not necessarily"), "empty scan not permission denial")
}
@main struct AtvCoordinatorTests {
    @MainActor static func main() async {
        lifetimePolicyChecks(); await foregroundGraceChecks(); await lifecycleChecks(); await retryChecks(); await ordinaryClosureChecks(); await legacyMigrationChecks(); await pairingChecks(); await queuedStorageChecks(); await commandChecks(); await navigationChecks(); presentationChecks()
        print("PASS: ATV foreground ownership, fake-clock grace/backoff, trust stops, stale events, verified durable pairing/forget and UI state fixtures")
    }
}
