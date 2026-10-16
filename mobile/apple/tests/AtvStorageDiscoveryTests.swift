import Foundation
import Security
import Darwin

private func expect(_ condition: @autoclosure () -> Bool, _ label: String, line: UInt = #line) {
    guard condition() else { fatalError("ATV storage/discovery check failed: \(label) at \(line)") }
}
private func fails(_ expected: AtvStorageError, _ body: () throws -> Void, line: UInt = #line) {
    do { try body(); fatalError("Expected storage failure at \(line)") }
    catch let error as AtvStorageError { expect(error == expected, "fixed storage error", line: line) }
    catch { fatalError("Unexpected error type at \(line)") }
}

/// No Security functions: dictionary calls are captured and all outcomes are scripted.
private final class FakeKeychain: AtvKeychainAccess {
    var data: Data?
    var readStatus: OSStatus?
    var updates: [OSStatus] = []
    var adds: [OSStatus] = []
    var updateCalls = 0
    var addCalls = 0
    var reads = 0
    func check(_ query: [String: Any]) {
        expect(query[kSecClass as String] as? String == kSecClassGenericPassword as String, "generic item")
        expect(query[kSecAttrService as String] as? String == "com.playbridge.atv-remote", "dedicated service")
        expect(query[kSecAttrAccount as String] as? String == "identity-and-tvs-v1", "single dedicated account")
        expect(query[kSecAttrSynchronizable as String] as? Bool == false, "not synchronizable")
        expect(query[kSecAttrAccessGroup as String] == nil, "no shared group")
        expect(query[kSecUseAuthenticationContext as String] == nil, "fake boundary does not instantiate authentication services")
    }
    func protection(_ attrs: [String: Any]) {
        expect(attrs[kSecAttrAccessible as String] as? String == kSecAttrAccessibleWhenUnlockedThisDeviceOnly as String, "device only, unlocked")
        expect(attrs[kSecAttrSynchronizable as String] as? Bool == false, "write non-sync")
        expect(attrs[kSecValueData as String] is Data, "one document write")
    }
    func read(_ query: [String: Any]) -> (OSStatus, Data?) {
        check(query); reads += 1
        expect(query[kSecReturnData as String] as? Bool == true, "return data")
        expect(query[kSecMatchLimit as String] as? String == kSecMatchLimitOne as String, "one item")
        return (readStatus ?? (data == nil ? errSecItemNotFound : errSecSuccess), data)
    }
    func update(_ query: [String: Any], attributes: [String: Any]) -> OSStatus {
        check(query); protection(attributes); updateCalls += 1
        let status = updates.isEmpty ? (data == nil ? errSecItemNotFound : errSecSuccess) : updates.removeFirst()
        if status == errSecSuccess { data = attributes[kSecValueData as String] as? Data }
        return status
    }
    func add(_ attributes: [String: Any]) -> OSStatus {
        check(attributes); protection(attributes); addCalls += 1
        expect(attributes[kSecUseAuthenticationContext as String] == nil, "no read option on add")
        let status = adds.isEmpty ? (data == nil ? errSecSuccess : errSecDuplicateItem) : adds.removeFirst()
        if status == errSecSuccess { data = attributes[kSecValueData as String] as? Data }
        return status
    }
}
private let identity = AtvIdentity(certPem: "fixture-cert", keyPem: "fixture-key", fingerprint: String(repeating: "a", count: 64))
private let otherIdentity = AtvIdentity(certPem: "other-cert", keyPem: "other-key", fingerprint: String(repeating: "b", count: 64))
private func record(_ pin: Character = "c", ids: Set<String> = ["playbridge:one"], mac: String? = "aa:bb", ip: String? = "192.0.2.1", name: String? = "TV") -> AtvTvRecord {
    .init(fingerprint: String(repeating: String(pin), count: 64), mac: mac, serverName: name, lastIp: ip, receiverIds: ids)
}

private func storageChecks() throws {
    let fake = FakeKeychain(), store: AtvCredentialStore
    store = AtvCredentialStore(keychain: fake)
    expect(store.records.isEmpty && store.identityOrNull() == nil && store.storageError == nil, "empty store")
    try store.saveIdentity(identity)
    expect(fake.addCalls == 1 && store.identityOrNull() == identity, "add identity")
    try store.saveVerifiedPairing(identity, record: record())
    try store.saveVerifiedPairing(identity, record: record("d", ids: ["playbridge:two"], mac: "cc:dd", ip: "192.0.2.2"))
    expect(store.records.count == 2, "two TVs")
    // Same pin adds a selected endpoint alias. Re-pairing replaces only that receiver's old pin.
    try store.upsert(record(ids: ["playbridge:alias"], mac: nil, ip: nil, name: nil))
    expect(store.records.first { $0.fingerprint == record().fingerprint }?.receiverIds == ["playbridge:one", "playbridge:alias"], "merge aliases")
    try store.saveVerifiedPairing(identity, record: record("e", ids: ["playbridge:alias"], mac: "ee:ff"))
    expect(store.records.count == 2 && !store.records.contains { $0.fingerprint == record().fingerprint }, "replace pin association")
    expect(store.records.last?.receiverIds == ["playbridge:one", "playbridge:alias"], "retain exact endpoint aliases")
    try store.updateLastIp(fingerprint: String(repeating: "E", count: 64), ip: "2001:db8::7")
    expect(store.records.last?.lastIp == "2001:db8::7", "durable DHCP update")
    let restored = AtvCredentialStore(keychain: fake)
    expect(restored.records == store.records && restored.identityOrNull() == identity, "reload document")
    let before = store.records, bytes = fake.data
    fake.updates = [errSecNotAvailable]
    fails(.unavailable) { try store.forget(fingerprint: record("e").fingerprint) }
    expect(store.records == before && fake.data == bytes && store.identityOrNull() == nil, "failed forget preserves durable and memory state; access failure blocks control")
    fake.updates = [errSecAuthFailed]
    fails(.unavailable) { try store.updateLastIp(fingerprint: record("e").fingerprint, ip: "192.0.2.99") }
    expect(store.records == before && fake.data == bytes, "failed last IP update")
    fake.updates = [errSecParam]
    fails(.writeFailed) { try store.saveVerifiedPairing(identity, record: record("f")) }
    expect(store.records == before && fake.data == bytes, "failed pairing upsert")
    fails(.invalidInput) { try store.saveIdentity(otherIdentity) }
    expect(fake.data == bytes, "identity cannot silently rotate other TVs")
    fails(.invalidInput) { try store.upsert(.init(fingerprint: "invalid", mac: nil, serverName: nil, lastIp: nil, receiverIds: [])) }
    try store.forget(fingerprint: record("e").fingerprint)
    expect(store.records.count == 1 && store.identityOrNull() == identity, "forget retains identity and other TV")
    try store.forget(fingerprint: record("d").fingerprint)
    expect(store.records.isEmpty && AtvCredentialStore(keychain: fake).identityOrNull() == identity, "forget last retains identity")
    expect(String(describing: identity) == "AtvIdentity(redacted)" && String(reflecting: record()) == "AtvTvRecord(redacted)", "redacted descriptions")

    // Read errors are not evidence of corruption, including while a healthy cache exists.
    try store.saveVerifiedPairing(identity, record: record())
    for status in [errSecInteractionNotAllowed, errSecAuthFailed, errSecNotAvailable, errSecMissingEntitlement] {
        fake.readStatus = status; store.reload()
        let error: AtvStorageError = status == errSecInteractionNotAllowed ? .locked : .unavailable
        expect(store.storageError == error && !store.recoveryRequired && store.identityOrNull() == nil, "locked/access unavailable")
        let writes = fake.updateCalls + fake.addCalls, healthy = fake.data
        fails(error) { try store.saveVerifiedPairing(otherIdentity, record: record("d")) }
        fails(error) { _ = try store.pairingIdentityOrNull() }
        fails(error) { try store.forget(fingerprint: record().fingerprint) }
        expect(fake.updateCalls + fake.addCalls == writes && fake.data == healthy, "blocked read never overwrites")
        fake.readStatus = nil; store.reload()
        expect(store.storageError == nil && store.identityOrNull() == identity, "unlock/retry restores healthy data")
    }
    fake.updates = [errSecInteractionNotAllowed]
    fails(.locked) { try store.forget(fingerprint: record().fingerprint) }
    expect(store.identityOrNull() == nil, "write lock revokes cached identity")
    store.reload()
    expect(store.identityOrNull() == identity, "write lock recovers by read")

    // Every status in update/add/retry must succeed before state is published.
    for (status, error): (OSStatus, AtvStorageError) in [(errSecNotAvailable, .unavailable), (errSecAuthFailed, .unavailable), (errSecParam, .writeFailed), (errSecDecode, .corrupt), (errSecInteractionNotAllowed, .locked)] {
        let f = FakeKeychain()
        let empty = AtvCredentialStore(keychain: f)
        f.adds = [status]
        fails(error) { try empty.saveIdentity(identity) }
        expect(f.data == nil && empty.identityOrNull() == nil, "failed add atomic")
    }
    let race = FakeKeychain(), raceStore = AtvCredentialStore(keychain: race)
    race.updates = [errSecItemNotFound, errSecParam]; race.adds = [errSecDuplicateItem]
    fails(.writeFailed) { try raceStore.saveIdentity(identity) }
    expect(race.updateCalls == 2 && raceStore.identityOrNull() == nil, "duplicate retry failure checked")
    race.updates = [errSecItemNotFound, errSecSuccess]; race.adds = [errSecDuplicateItem]
    try raceStore.saveIdentity(identity)
    expect(raceStore.identityOrNull() == identity, "duplicate retry success")

    // Corruption cannot be overwritten by ordinary writes or a failed verified repair.
    let corrupt = FakeKeychain()
    corrupt.data = Data("broken".utf8)
    let recovery = AtvCredentialStore(keychain: corrupt)
    expect(recovery.recoveryRequired && recovery.identityOrNull() == nil, "corrupt document")
    fails(.corrupt) { try recovery.saveIdentity(identity) }
    fails(.corrupt) { try recovery.upsert(record()) }
    fails(.corrupt) { try recovery.forget(fingerprint: record().fingerprint) }
    let corruptBytes = corrupt.data
    corrupt.updates = [errSecNotAvailable]
    fails(.unavailable) { try recovery.saveVerifiedPairing(identity, record: record()) }
    recovery.reload()
    expect(recovery.recoveryRequired && recovery.records.isEmpty && corrupt.data == corruptBytes, "failed repair leaves corrupt bytes")
    try recovery.saveVerifiedPairing(identity, record: record())
    expect(!recovery.recoveryRequired && recovery.records == [record(mac: "AA:BB")] && recovery.identityOrNull() == identity, "verified recovery")
    // Runtime unreadability can preserve other TVs only with the same verified install identity.
    try recovery.saveVerifiedPairing(identity, record: record("d", ids: ["playbridge:two"]))
    corrupt.readStatus = errSecDecode; recovery.reload()
    let pairingIdentity = try recovery.pairingIdentityOrNull()
    expect(recovery.identityOrNull() == nil && pairingIdentity == identity, "cached identity pairing only")
    try recovery.saveVerifiedPairing(identity, record: record("e"))
    expect(recovery.records.count == 2, "same identity recovery preserves other TV")
    try recovery.saveVerifiedPairing(otherIdentity, record: record("f"))
    corrupt.readStatus = nil
    expect(recovery.records.count == 1 && AtvCredentialStore(keychain: corrupt).identityOrNull() == otherIdentity, "new identity invalidates other TV pairings")

    // Reject duplicated credential ownership even if the JSON itself is well-formed.
    var duplicateDoc = try JSONSerialization.jsonObject(with: corrupt.data!) as! [String: Any]
    let original = (duplicateDoc["tvs"] as! [[String: Any]])[0]
    var duplicate = original
    duplicate["fingerprint"] = record("e").fingerprint
    duplicateDoc["tvs"] = [original, duplicate]
    corrupt.data = try JSONSerialization.data(withJSONObject: duplicateDoc)
    recovery.reload()
    expect(recovery.recoveryRequired && recovery.identityOrNull() == nil, "duplicate receiver ownership fails closed")
    duplicateDoc["tvs"] = [original, original]
    corrupt.data = try JSONSerialization.data(withJSONObject: duplicateDoc)
    recovery.reload()
    expect(recovery.recoveryRequired, "duplicate pin fails closed")
    for malformed in [Data(), Data("{}".utf8), Data("{\"version\":1,\"tvs\":[] ,\"identity\":{}}".utf8), Data(repeating: 0, count: 1_048_577)] {
        corrupt.data = malformed; recovery.reload()
        expect(recovery.recoveryRequired, "invalid document fail closed")
    }
    corrupt.data = Data("{\"version\":2}".utf8); recovery.reload()
    expect(recovery.storageError == .unsupportedVersion && !recovery.recoveryRequired, "future version distinct")
    fails(.unsupportedVersion) { try recovery.saveVerifiedPairing(identity, record: record()) }
    // Two separately created stores still serialize read/modify/write, without losing records.
    let shared = FakeKeychain(), first = AtvCredentialStore(keychain: shared), second = AtvCredentialStore(keychain: shared)
    try first.saveIdentity(identity)
    DispatchQueue.concurrentPerform(iterations: 20) { i in
        do { try (i % 2 == 0 ? first : second).upsert(record(Character(String(i % 6)), ids: ["playbridge:alias-\(i)"])) }
        catch { fatalError("Concurrent write failed") }
    }
    first.reload()
    expect(first.records.reduce(0) { $0 + $1.receiverIds.count } == 20, "serialized instances retain every alias")
}

private func discovered(_ host: String, mac: String? = nil, addresses: [String] = [], name: String = "Same TV") -> AtvDiscoveredDevice {
    .init(name: name, host: host, port: 9999, addresses: addresses.isEmpty ? [host] : addresses, txt: mac.map { [["BT", $0]] } ?? [])
}
private func matcherChecks() {
    func target(_ id: String = "one", _ addresses: [String] = ["192.0.2.1"], exo: Bool = false, cast: Bool = false) -> AtvReceiverTarget {
        .init(endpoint: "playbridge:" + id, name: "Same TV", addresses: addresses, androidTv: exo, googleCast: cast)
    }
    let a = discovered("192.0.2.1", mac: "aa:bb"), b = discovered("192.0.2.2", mac: "cc:dd")
    let ra = record(), rb = record("d", ids: ["playbridge:two"], mac: "cc:dd", ip: "192.0.2.2")
    let match = AtvDeviceMatcher.match
    expect(match(target(), [a,b], [ra,rb]).record == ra, "selected receiver only")
    expect(match(target("two", [b.host]), [a,b], [ra,rb]).record == rb, "second TV own pin")
    let unknown = match(target("unowned"), [a,b], [ra,rb])
    expect(unknown.record == nil && unknown.device == a, "same IP and name never transfer credentials")
    expect(match(target("unowned", ["192.0.2.9"]), [a,b], [ra,rb]).device == nil, "name not used")
    expect(!match(target("unowned", [], cast: true), [], []).available, "Cast hint does not grant ATV")
    expect(match(target("unowned", [], exo: true), [], []).available, "exo availability")
    let moved = discovered("192.0.2.8", mac: "  Aa:Bb  ")
    let relocated = match(target(), [b,moved], [ra])
    expect(relocated.record == ra && relocated.host == moved.host, "DHCP relocation by associated MAC")
    let conflict = discovered("192.0.2.1", mac: "cc:dd")
    expect(match(target(), [conflict], [ra]).host == nil && match(target(), [conflict], [ra]).device == nil, "IP MAC conflict blocks")
    expect(match(target(), [conflict,moved], [ra]).device == moved, "relocation wins over conflicting reused IP")
    expect(match(target(), [discovered(a.host)], [ra]).host == a.host, "unknown discovery MAC follows Android fallback")
    expect(match(target(), [discovered(a.host, mac: "  ")], [ra]).host == nil, "blank bt conflict matches Android")
    expect(match(target(), [b], [ra]).host == a.host, "selected addresses fallback before last IP")
    expect(match(target("one", []), [], [ra]).host == ra.lastIp, "record last IP fallback")
    let ambiguous = match(target(), [a], [ra,record("d")])
    expect(ambiguous.record == nil && ambiguous.device == nil && ambiguous.available && ambiguous.host == nil, "ambiguous association requires repair")
    let alias = record(ids: ["playbridge:one", "playbridge:alias"])
    expect(match(target("alias"), [a], [alias]).record == alias, "endpoint alias")
    let ipv6 = discovered("2001:db8::1", addresses: ["2001:DB8::1", "192.0.2.5"])
    expect(match(target("one", [" [2001:DB8::1] "]), [ipv6], []).device == ipv6, "bracketed IPv6 case normalization")
    expect(match(target("one", ["192.0.2.5"]), [ipv6], []).device == ipv6, "secondary IPv4")
    expect(match(target("one", ["fe80::1%en0"]), [discovered("fe80::1%en0")], []).device != nil, "IPv6 scope retained")
    expect(match(target("one", ["fe80::1%en1"]), [discovered("fe80::1%en0")], []).device == nil, "different scopes distinct")
    expect(match(target("one", ["2001:0db8::1"]), [ipv6], []).device == nil, "same textual normalization as Android")
    let native = AtvReceiverTarget(playBridge: PairedDevice(ip: "192.0.2.5", port: 9000, name: "TV", uuid: "uuid", players: ["EXO"]))
    expect(native.endpoint == "playbridge:uuid" && native.androidTv && !native.googleCast, "native receiver adapter")
    let legacy = AtvReceiverTarget(playBridge: PairedDevice(ip: "192.0.2.5", port: 9000, name: "TV"))
    expect(legacy.endpoint == "playbridge:192.0.2.5:9000", "endpoint fallback preserves PB port")
    expect(AtvDeviceMatcher.controlPort == 6466 && AtvDeviceMatcher.pairingPort == 6467 && a.port == 9999, "ATV fixed ports separate from advertised ports")
}

@MainActor private final class FakeBonjour: AtvBonjourDriver {
    var callback: (@MainActor (AtvBonjourEvent) -> Void)?
    var starts = 0, stops = 0
    func start(deliver: @escaping @MainActor (AtvBonjourEvent) -> Void) { starts += 1; callback = deliver }
    func stop() { stops += 1 } // Retain callback to simulate delayed native delivery.
    func send(_ event: AtvBonjourEvent) { callback?(event) }
}
@MainActor private func discoveryChecks() {
    let first = FakeBonjour(), second = FakeBonjour()
    var created = 0
    let browser = AtvBonjourBrowser { created += 1; return created == 1 ? first : second }
    expect(created == 0 && !browser.isScanning, "construction does no browsing")
    browser.start(); browser.start()
    expect(first.starts == 1 && created == 1, "idempotent start")
    let id = AtvBonjourServiceID(domain: "local.", type: AtvBonjourBrowser.serviceType, name: "TV")
    let other = AtvBonjourServiceID(domain: "other.", type: AtvBonjourBrowser.serviceType, name: "TV")
    let old = UUID(), new = UUID(), otherToken = UUID(), a = discovered("192.0.2.1"), b = discovered("192.0.2.2")
    first.send(.resolved(id, old, a))
    expect(browser.devices.isEmpty, "unsolicited resolve ignored")
    first.send(.found(id, old)); first.send(.resolved(id, old, a))
    first.send(.found(other, otherToken)); first.send(.resolved(other, otherToken, b))
    expect(browser.devices.count == 2, "same name across service identities retained")
    first.send(.found(id, new)); first.send(.resolved(id, old, a)); first.send(.removed(id, old))
    expect(browser.devices == [b], "obsolete resolver and removal fenced")
    first.send(.resolved(id, new, a))
    expect(browser.devices.count == 2, "replacement resolves")
    first.send(.removed(id, new)); first.send(.resolved(id, new, a))
    expect(browser.devices == [b], "removed service cannot reappear from late resolve")
    browser.stop(); browser.stop()
    expect(first.stops == 1 && browser.devices.isEmpty && !browser.isScanning, "idempotent stop clears")
    browser.start()
    first.send(.found(id, new)); first.send(.resolved(id, new, a)); first.send(.failed)
    expect(browser.isScanning && browser.devices.isEmpty && !browser.failed, "old scan generation fenced")
    second.send(.found(id, new)); second.send(.resolved(id, new, a))
    second.send(.failed)
    expect(browser.failed && !browser.isScanning && browser.devices.isEmpty && second.stops == 1, "search failure stops, no permission inference")
    second.send(.resolved(id, new, a))
    expect(browser.devices.isEmpty, "failed scan late resolve fenced")

    // Synthetic sockaddr data only: numeric decoding never needs DNS/sockets.
    var v4 = sockaddr_in(); v4.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); v4.sin_family = sa_family_t(AF_INET)
    expect(inet_pton(AF_INET, "192.0.2.7", &v4.sin_addr) == 1, "IPv4 fixture")
    var v6 = sockaddr_in6(); v6.sin6_len = UInt8(MemoryLayout<sockaddr_in6>.size); v6.sin6_family = sa_family_t(AF_INET6)
    expect(inet_pton(AF_INET6, "2001:db8::7", &v6.sin6_addr) == 1, "IPv6 fixture")
    let d4 = withUnsafeBytes(of: v4) { Data($0) }, d6 = withUnsafeBytes(of: v6) { Data($0) }
    let parsed = AtvNetServiceDriver.parse(name: "TV", port: 9999, addresses: [Data([0]),d4,d6,d4], txt: [["bt","aa:bb"]])
    expect(parsed?.addresses == ["192.0.2.7", "2001:db8::7"] && parsed?.bluetoothMac == "aa:bb" && parsed?.port == 9999, "all addresses, TXT, dedup and port")
    expect(AtvNetServiceDriver.parse(name: "TV", port: 0, addresses: [d4], txt: []) == nil, "invalid port")
    expect(AtvNetServiceDriver.parse(name: "TV", port: 6466, addresses: [d6.prefix(16)], txt: []) == nil, "truncated IPv6 rejected")
    expect(AtvNetServiceDriver.parse(name: "TV", port: 6466, addresses: [], txt: []) == nil, "empty resolution")
    v6.sin6_scope_id = 12345
    expect(inet_pton(AF_INET6, "fe80::7", &v6.sin6_addr) == 1, "link-local fixture")
    let scoped = AtvNetServiceDriver.parse(name: "TV", port: 6466, addresses: [withUnsafeBytes(of: v6) { Data($0) }], txt: [])
    expect(scoped?.host == "fe80::7%12345", "numeric scope preserved")
}

@main struct AtvStorageDiscoveryTests {
    @MainActor static func main() throws {
        try storageChecks()
        matcherChecks()
        discoveryChecks()
        print("ATV Keychain, receiver matcher and fake Bonjour checks passed (no Keychain or network access)")
    }
}
