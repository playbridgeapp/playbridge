import Foundation
import Combine
#if ATV_NATIVE_SHIM
import PlayBridgeCastCore
#endif

private let pin = String(repeating: "a", count: 64)
private let identity = AtvIdentity(certPem: "CERT-SENTINEL", keyPem: "PRIVATE-SENTINEL", fingerprint: pin)

/// No sockets or native framework. Captured commands remain in memory and are never printed.
private final class FakeAtvNative: AtvNativeTransport, @unchecked Sendable {
    private let condition = NSCondition()
    private var events: [Data] = []
    private var commands: [[String: Any]] = []
    private var cancelled = false
    private var freed = false
    private var reads = 0
    private var deliveries = 0
    private var starts = 0
    private var cancels = 0
    private var frees = 0
    var abi: UInt32 = 1
    var accept = true
    var canStart = true

    func abiVersion() -> UInt32 { precondition(!Thread.isMainThread); return abi }
    func start() -> Bool {
        condition.lock(); defer { condition.unlock() }
        precondition(!Thread.isMainThread && starts == 0 && !freed)
        starts += 1; return canStart
    }
    func submit(_ data: Data) -> Bool {
        condition.lock(); defer { condition.unlock() }
        precondition(!Thread.isMainThread && !freed && !cancelled)
        if !accept { return false }
        commands.append(try! JSONSerialization.jsonObject(with: data) as! [String: Any])
        condition.broadcast()
        return true
    }
    func nextEvent(waitMilliseconds: UInt64, maximumBytes: Int) throws -> Data? {
        condition.lock(); defer { condition.unlock() }
        precondition(!Thread.isMainThread && !freed)
        reads += 1
        defer { reads -= 1 }
        let deadline = Date().addingTimeInterval(Double(waitMilliseconds) / 1_000)
        while events.isEmpty && !cancelled {
            if !condition.wait(until: deadline) { break }
        }
        guard !events.isEmpty else { return nil }
        // Return even oversized data to check the owner enforces bounds independently.
        deliveries += 1
        return events.removeFirst()
    }
    func cancel() {
        condition.lock(); defer { condition.unlock() }
        precondition(!Thread.isMainThread && !freed && !cancelled)
        cancels += 1; cancelled = true; condition.broadcast()
    }
    func free() {
        condition.lock(); defer { condition.unlock() }
        precondition(!Thread.isMainThread && cancelled && reads == 0 && !freed)
        frees += 1; freed = true
    }
    func push(_ value: [String: Any]) { pushData(try! JSONSerialization.data(withJSONObject: value)) }
    func pushData(_ data: Data) {
        condition.lock(); defer { condition.unlock() }
        events.append(data); condition.broadcast()
    }
    func command(_ index: Int) -> [String: Any]? {
        condition.lock(); defer { condition.unlock() }
        return commands.indices.contains(index) ? commands[index] : nil
    }
    var deliveredCount: Int {
        condition.lock(); defer { condition.unlock() }
        return deliveries
    }
    var counts: (start: Int, cancel: Int, free: Int) {
        condition.lock(); defer { condition.unlock() }
        return (starts, cancels, frees)
    }
}

@main struct AtvRemoteClientTests {
    @MainActor static func main() async throws {
        identitySuffixChecks()
        try await typedFlow()
        try await pairingIdentityPreparation()
        try await replacementAndStaleEvents()
        try await coalescedState()
        try await limitsAndFailures()
        try await cancellationAndClose()
        try await pairingReplacementAndProtocolFailure()
        try decodingBounds()
        try await unavailable()
#if ATV_NATIVE_SHIM
        try await nativeCBridge()
#endif
        print("PASS: ATV typed requests/events; ABI/stub; MainActor state; stale replacement/close; limits/timeouts; cancel/join/free; bounded/sanitized decoding")
    }
    @MainActor private static func wait(_ predicate: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(4)
        while !predicate() {
            guard Date() < deadline else { throw CheckFailure.timeout }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
    }
    private enum CheckFailure: Error { case timeout, expectedFailure }
    @MainActor private static func expect(_ expected: AtvClientError, _ task: Task<Void, Error>) async throws {
        do { try await task.value; throw CheckFailure.expectedFailure }
        catch let actual as AtvClientError { precondition(actual == expected) }
    }
    @MainActor private static func command(_ fake: FakeAtvNative, _ index: Int, _ name: String) async throws -> String {
        try await wait { fake.command(index) != nil }
        let value = fake.command(index)!
        precondition(value["command"] as? String == name)
        return value["request_id"] as! String
    }
    private static func state(_ id: String, _ level: Int, revision: UInt64 = 1) -> [String: Any] {
        ["event": "state", "request_id": id, "state": ["is_on": true, "volume_level": level, "volume_max": 100,
         "volume_muted": false, "volume_revision": revision, "current_app": "example.app", "model": "model", "vendor": "vendor"]]
    }
    @MainActor private static func connected(_ fake: FakeAtvNative, _ client: AtvRemoteClient, at index: Int = 0) async throws -> String {
        let task = Task { try await client.connect(host: "127.0.0.1", identity: identity, pinSHA256: pin) }
        let id = try await command(fake, index, "connect")
        fake.push(["event": "connected", "request_id": id])
        try await task.value
        try await wait { client.state.phase == .connected }
        return id
    }
    private static func identitySuffixChecks() {
        let uuid = "00000000-0000-0000-0000-000000000001"
        let name = PlayBridgePhoneIdentity.atvClientName(label: "PlayBridge (iPhone)", uuid: uuid)
        precondition(name == "PlayBridge (iPhone) d52db763")
        precondition(name == PlayBridgePhoneIdentity.atvClientName(label: "PlayBridge (iPhone)", uuid: uuid))
        precondition(PlayBridgePhoneIdentity.atvClientName(label: "PlayBridge (iPhone)", uuid: "00000000-0000-0000-0000-000000000002").hasSuffix("2a87b8e8"))
        precondition(!name.contains(uuid))
        var stored: String?, creations = 0
        let create = { creations += 1; return uuid }
        let first = PlayBridgePhoneIdentity.localDeviceUUID(read: { stored }, write: { stored = $0 }, create: create)
        let second = PlayBridgePhoneIdentity.localDeviceUUID(read: { stored }, write: { stored = $0 }, create: create)
        precondition(first == second && stored == first && creations == 1)
        print("PASS: shared UUID accessor, exact domain-separated suffix fixtures, stable reuse and no raw identifier in client name")
    }

    @MainActor private static func pairingIdentityPreparation() async throws {
        let fake = FakeAtvNative(), client = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: FakeAtvNative())
        await client.close()
        let live = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: fake)
        let task = Task { try await live.generateIdentity(existing: identity) }
        let id = try await command(fake, 0, "generate_identity")
        precondition(fake.command(0)?["cert_pem"] as? String == identity.certPem)
        precondition(fake.command(0)?["key_pem"] as? String == identity.keyPem)
        precondition(fake.command(0)?["client_name"] as? String == PlayBridgePhoneIdentity.atvClientName(label: "PlayBridge (iPhone)", uuid: "fixture-phone-uuid"))
        precondition(!String(describing: fake.command(0)?["client_name"]).contains("fixture-phone-uuid"))
        let name = "PlayBridge (iPhone) fixture"
        fake.push(["event": "identity", "request_id": id, "cert_pem": identity.certPem, "key_pem": identity.keyPem,
                   "fingerprint": pin, "client_name": name, "replaces_legacy": true])
        let prepared = try await task.value
        precondition(prepared.replacesLegacy && prepared.clientName == name)
        let start = Task { try await live.startPairing(host: "127.0.0.1", identity: prepared) }
        let pairID = try await command(fake, 1, "start_pairing")
        precondition(fake.command(1)?["client_name"] as? String == name)
        fake.push(["event": "pairing_started", "request_id": pairID, "server_name": "Living Room", "fingerprint": pin])
        _ = try await start.value
        await live.close()
        print("PASS: explicit pairing forwards stored identity and reuses the native persisted client name without exposing it")
    }

    @MainActor private static func typedFlow() async throws {
        let fake = FakeAtvNative(), client = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: FakeAtvNative())
        await client.close() // Closing an unused owner has no native calls.
        let live = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: fake)
        var publications = 0
        let observation = live.$state.sink { _ in precondition(Thread.isMainThread); publications += 1 }
        let discover = Task { try await live.discover(timeoutMilliseconds: 0) }
        var id = try await command(fake, 0, "discover")
        fake.push(["event": "devices", "request_id": id, "devices": [["name": "Living Room", "host": "127.0.0.1", "port": 6466, "addresses": ["127.0.0.1"], "txt": [["bt", "AA:BB"]]]]])
        let devices = try await discover.value
        precondition(devices.count == 1 && devices[0].port == 6466)
        let generated = Task { try await live.generateIdentity() }
        id = try await command(fake, 1, "generate_identity")
        fake.push(["event": "identity", "request_id": id, "cert_pem": identity.certPem, "key_pem": identity.keyPem, "fingerprint": pin])
        let generatedIdentity = try await generated.value
        precondition(generatedIdentity == identity)
        let pairing = Task { try await live.startPairing(host: "127.0.0.1", identity: identity) }
        id = try await command(fake, 2, "start_pairing")
        fake.push(["event": "pairing_started", "request_id": id, "server_name": "Living Room", "fingerprint": pin, "mac": "AA:BB"])
        let pairingStarted = try await pairing.value
        precondition(pairingStarted.serverName == "Living Room")
        let finish = Task { try await live.finishPairing(code: "a1b2c3") }
        id = try await command(fake, 3, "finish_pairing")
        precondition(fake.command(3)?["code"] as? String == "A1B2C3")
        fake.push(["event": "paired", "request_id": id, "server_cert_der_b64": Data([1, 2, 3]).base64EncodedString(), "fingerprint": pin])
        let paired = try await finish.value
        precondition(paired.serverCertificateDER == Data([1, 2, 3]))
        let connectionID = try await connected(fake, live, at: 4)
        fake.push(state(connectionID, 19, revision: .max))
        try await wait { live.state.television?.volumeLevel == 19 }
        precondition(live.state.television?.volumeRevision == .max)
        let actions: [(String, () async throws -> Void)] = [
            ("key", { try await live.key(.name("KEYCODE_HOME"), direction: .startLong) }),
            ("key", { try await live.key(.code(3), direction: .endLong) }),
            ("volume", { try await live.volume(.mute) }),
            ("text", { try await live.insertText("TEXT-SENTINEL") }),
            ("launch_app", { try await live.launchApp("example.app") }),
            ("power", { try await live.power() }),
            ("cancel_pairing", { try await live.cancelPairing() }),
        ]
        for (offset, action) in actions.enumerated() {
            let task = Task { try await action.1() }
            id = try await command(fake, 5 + offset, action.0)
            fake.push(["event": "operation", "request_id": id, "operation": action.0, "ok": true])
            try await task.value
        }
        let disconnect = Task { try await live.disconnect() }
        id = try await command(fake, 12, "disconnect")
        fake.push(state(connectionID, 99))
        fake.push(["event": "closed", "request_id": connectionID, "kind": "pin_mismatch"])
        fake.push(["event": "operation", "request_id": id, "operation": "disconnect", "ok": true])
        try await disconnect.value
        try await wait { live.state.phase == .disconnected }
        precondition(live.state.television == nil)
        await live.close(); await live.close()
        precondition(fake.counts == (1, 1, 1) && publications > 2)
        withExtendedLifetime(observation) {}
    }
    @MainActor private static func replacementAndStaleEvents() async throws {
        let fake = FakeAtvNative(), client = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: FakeAtvNative())
        await client.close()
        let live = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: fake)
        let first = Task { try await live.connect(host: "old.test", identity: identity, pinSHA256: pin) }
        let a = try await command(fake, 0, "connect")
        let second = Task { try await live.connect(host: "new.test", identity: identity, pinSHA256: pin) }
        let b = try await command(fake, 1, "connect")
        try await expect(.superseded, first)
        fake.push(["event": "connected", "request_id": a]); fake.push(state(a, 88))
        fake.push(["event": "state", "request_id": a, "state": "retired invalid payload"])
        fake.push(["event": "error", "request_id": a, "kind": "pin_mismatch", "message": "PRIVATE-SENTINEL"])
        fake.push(["event": "connected", "request_id": b]); fake.push(state(b, 20))
        try await second.value
        try await wait { live.state.television?.volumeLevel == 20 }
        fake.push(["event": "closed", "request_id": a, "kind": "not_trusted"])
        // A barrier request ensures the stale terminal event was consumed before assertions.
        let barrier = Task { try await live.power() }
        let id = try await command(fake, 2, "power")
        fake.push(["event": "operation", "request_id": id, "operation": "power", "ok": true])
        try await barrier.value
        precondition(live.state.phase == .connected && live.state.television?.volumeLevel == 20)
        fake.push(["event": "closed", "request_id": b, "kind": "not_trusted"])
        try await wait { live.state.phase == .disconnected }
        precondition(live.state.failure == .native(.notTrusted))
        await live.close()
        precondition(fake.counts == (1, 1, 1))
    }
    // Hold MainActor only inside this bounded test helper so the background delivery queue can
    // drain a burst. A slow UI must retain one latest state, not hundreds of publishing Tasks.
    private static func waitForBackgroundDrain(_ fake: FakeAtvNative, target: Int) throws {
        let deadline = Date().addingTimeInterval(3)
        while fake.deliveredCount < target {
            if Date() >= deadline { throw CheckFailure.timeout }
            Thread.sleep(forTimeInterval: 0.001)
        }
    }
    @MainActor private static func coalescedState() async throws {
        let fake = FakeAtvNative(), client = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: fake)
        let id = try await connected(fake, client)
        var publications = 0
        let observation = client.$state.sink { _ in precondition(Thread.isMainThread); publications += 1 }
        let baseline = publications, delivered = fake.deliveredCount
        for revision in 0..<200 { fake.push(state(id, revision % 100, revision: UInt64(revision))) }
        try waitForBackgroundDrain(fake, target: delivered + 200)
        precondition(publications == baseline)
        try await wait { client.state.television?.volumeRevision == 199 }
        // The last native read can still be in its owner callback when MainActor resumes.
        precondition(publications <= baseline + 2)
        await client.close()
        withExtendedLifetime(observation) {}
    }
    @MainActor private static func limitsAndFailures() async throws {
        var limits = AtvClientLimits(); limits.maximumPending = 1; limits.operationTimeout = 0.25
        let fake = FakeAtvNative(), client = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: fake, limits: limits)
        _ = try await connected(fake, client)
        let slow = Task { try await client.power() }
        _ = try await command(fake, 1, "power")
        try await expect(.overflow, Task { try await client.volume(.up) })
        try await expect(.timeout, slow)
        await client.close(); precondition(fake.counts == (1, 1, 1))

        let rejected = FakeAtvNative(); rejected.accept = false
        let rejectClient = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: rejected)
        try await expect(.rejected, Task { _ = try await rejectClient.generateIdentity() })
        await rejectClient.close(); precondition(rejected.counts == (1, 1, 1))

        let nativeError = FakeAtvNative(), errorClient = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: FakeAtvNative())
        await errorClient.close()
        let live = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: nativeError)
        let request = Task { _ = try await live.generateIdentity() }
        let id = try await command(nativeError, 0, "generate_identity")
        nativeError.push(["event": "error", "request_id": id, "kind": "bad_code", "message": "PRIVATE-SENTINEL"])
        try await expect(.native(.badCode), request)
        await live.close()
        var tiny = AtvClientLimits(); tiny.maximumEventBytes = 32
        let huge = FakeAtvNative(), limited = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: huge, limits: tiny)
        let pending = Task { _ = try await limited.generateIdentity() }
        _ = try await command(huge, 0, "generate_identity")
        huge.pushData(Data(repeating: 65, count: 33))
        try await expect(.overflow, pending)
        await limited.close(); precondition(huge.counts == (1, 1, 1))

        var commandLimit = AtvClientLimits(); commandLimit.maximumCommandBytes = 10
        let noSubmit = FakeAtvNative(), bounded = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: noSubmit, limits: commandLimit)
        try await expect(.overflow, Task { _ = try await bounded.generateIdentity() })
        precondition(noSubmit.counts.start == 0); await bounded.close()
    }
    @MainActor private static func cancellationAndClose() async throws {
        let fake = FakeAtvNative(), client = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: fake)
        let pending = Task { try await client.connect(host: "example.test", identity: identity, pinSHA256: pin) }
        let id = try await command(fake, 0, "connect")
        pending.cancel()
        try await expect(.cancelled, pending)
        // Closing while the poll call is active must wait for it to exit before free.
        async let a: Void = client.close()
        async let b: Void = client.close()
        _ = await (a, b)
        fake.push(["event": "connected", "request_id": id]); fake.push(state(id, 90))
        try await Task.sleep(nanoseconds: 10_000_000)
        precondition(client.state.phase == .closed && client.state.television == nil)
        precondition(fake.counts == (1, 1, 1))
        try await expect(.cancelled, Task { try await client.power() })

        try await expect(.cancelled, Task { try await client.connect(host: "late.test", identity: identity, pinSHA256: pin) })
        precondition(client.state.phase == .closed)
        let release = FakeAtvNative()
        var dropped: AtvRemoteClient? = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: release)
        weak let weakClient = dropped
        _ = try await connected(release, dropped!)
        dropped = nil
        try await wait { weakClient == nil && release.counts.free == 1 }
        precondition(release.counts == (1, 1, 1))
    }
    @MainActor private static func pairingReplacementAndProtocolFailure() async throws {
        let fake = FakeAtvNative(), client = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: fake)
        let pairing = Task { _ = try await client.startPairing(host: "example.test", identity: identity) }
        let old = try await command(fake, 0, "start_pairing")
        let cancel = Task { try await client.cancelPairing() }
        let cancelID = try await command(fake, 1, "cancel_pairing")
        try await expect(.superseded, pairing)
        fake.push(["event": "pairing_started", "request_id": old, "fingerprint": pin])
        fake.push(["event": "operation", "request_id": cancelID, "operation": "cancel_pairing", "ok": true])
        try await cancel.value
        try await expect(.notPairing, Task { _ = try await client.finishPairing(code: "A1B2C3") })
        let pending = Task { _ = try await client.generateIdentity() }
        let id = try await command(fake, 2, "generate_identity")
        fake.push(["event": "connected", "request_id": id]) // Correct ID, wrong event kind.
        try await expect(.malformedEvent, pending)
        await client.close(); precondition(fake.counts == (1, 1, 1))

        let closing = FakeAtvNative(), closeClient = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: closing)
        let connecting = Task { try await closeClient.connect(host: "example.test", identity: identity, pinSHA256: pin) }
        _ = try await command(closing, 0, "connect")
        await closeClient.close()
        try await expect(.closed, connecting)
        precondition(closing.counts == (1, 1, 1) && closeClient.state.phase == .closed)
    }
    private static func decodingBounds() throws {
        func fails(_ value: Any, expected: AtvClientError = .malformedEvent) throws {
            let data = try JSONSerialization.data(withJSONObject: value)
            do { _ = try AtvEvent.decode(data, maximumBytes: 1_048_576); throw CheckFailure.expectedFailure }
            catch let actual as AtvClientError { precondition(actual == expected) }
        }
        try fails(["event": "connected", "request_id": true])
        try fails(["event": "connected", "request_id": ""])
        try fails(["event": "state", "request_id": "x", "state": ["is_on": 1]])
        try fails(["event": "identity", "request_id": "x", "cert_pem": "", "key_pem": "private", "fingerprint": pin])
        try fails(["event": "operation", "request_id": "x", "operation": "key", "ok": false])
        let entries = Array(repeating: ["name": "x", "host": "h", "port": 6466, "addresses": [], "txt": []] as [String: Any], count: 257)
        try fails(["event": "devices", "request_id": "x", "devices": entries], expected: .overflow)
        do { _ = try AtvEvent.decode(Data([255]), maximumBytes: 10); throw CheckFailure.expectedFailure }
        catch let error as AtvClientError { precondition(error == .malformedEvent) }
        let safe = try AtvEvent.decode(Data(#"{"event":"error","request_id":"x","kind":"future","message":"PRIVATE-SENTINEL"}"#.utf8), maximumBytes: 1024)
        guard case .error(_, .native(.protocolError)) = safe else { throw CheckFailure.expectedFailure }
        precondition(!String(describing: identity).contains("SENTINEL"))
        precondition(!AtvClientError.native(.protocolError).localizedDescription.contains("SENTINEL"))
    }
    @MainActor private static func unavailable() async throws {
        for abi: UInt32 in [0, 2] {
            let fake = FakeAtvNative(); fake.abi = abi
            let client = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: fake)
            try await expect(abi == 0 ? .unavailable : .unsupportedABI, Task { _ = try await client.generateIdentity() })
            await client.close(); precondition(fake.counts == (0, 0, 0))
        }
        let failed = FakeAtvNative(); failed.canStart = false
        let failedClient = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" }, transport: failed)
        try await expect(.startFailed, Task { _ = try await failedClient.generateIdentity() })
        await failedClient.close(); precondition(failed.counts == (1, 0, 0))
#if !canImport(PlayBridgeCastCore)
        let stub = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" })
        try await expect(.unavailable, Task { _ = try await stub.generateIdentity() })
        await stub.close()
#endif
    }
#if ATV_NATIVE_SHIM
    @MainActor private static func nativeCBridge() async throws {
        pb_test_reset(1)
        let client = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" })
        let value = try await client.generateIdentity()
        precondition(value.certPem == "test-cert")
        await client.close()
        precondition((0..<4).allSatisfy { pb_test_count(UInt32($0)) == 1 })
        for (mode, expected) in [(UInt32(2), AtvClientError.overflow), (UInt32(3), AtvClientError.malformedEvent)] {
            pb_test_reset(mode)
            let invalid = AtvRemoteClient(phoneUUID: { "fixture-phone-uuid" })
            try await expect(expected, Task { _ = try await invalid.generateIdentity() })
            await invalid.close()
            precondition((0..<4).allSatisfy { pb_test_count(UInt32($0)) == 1 }, "Every returned C string is freed even when decoding fails")
        }
        print("PASS: real Swift/C bridge against in-memory shim: bounded copying, malformed UTF-8, every C string freed, cancel/free once")
    }
#endif

}
