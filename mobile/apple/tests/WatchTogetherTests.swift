import Foundation

final class PhoneProxyRegistration {
    let url: URL
    init(url: URL) { self.url = url }
}

final class PhoneSenderServices {
    static let shared = PhoneSenderServices()
    func register(
        url: String,
        headers: [String: String],
        contentType: String?,
        allowedPrivateOrigins: [String] = [],
        forLocalPlayback: Bool = false
    ) async throws -> PhoneProxyRegistration {
        PhoneProxyRegistration(url: URL(string: url)!)
    }
}

enum StreamProxySettingsStore {
    static func load() -> RemoteProxyConfiguration { .init() }
}

@MainActor
final class TestAlternativeEngine: PhoneAlternativePlaybackEngine {
    var onState: ((PhonePlaybackState) -> Void)?
    var onEnd: (() -> Void)?
    var onFailure: ((Int32) -> Void)?
    var networkIssue: PhonePlaybackNetworkIssue?
    var loads: [(URL, [String: String], Double, Bool)] = []
    var plays = 0
    var pauses = 0
    var closes = 0
    var seeks: [Double] = []
    var speeds: [Double] = []

    func configure(_ options: PhonePlayerOptions) {}
    func load(url: URL, headers: [String: String], resume: Double, autoplay: Bool) {
        loads.append((url, headers, resume, autoplay))
        onState?(PhonePlaybackState(position: resume, paused: !autoplay))
    }
    func play() { plays += 1 }
    func pause() { pauses += 1 }
    func seek(to seconds: Double) { seeks.append(seconds) }
    func setSpeed(_ speed: Double) { speeds.append(speed) }
    func selectAudio(_ id: Int?) {}
    func selectSubtitle(_ id: Int?) {}
    func close() { closes += 1 }
}

final class FakeTogetherSession: TogetherSessionProtocol {
    var hosted: [(relay: String, device: TogetherDevice)] = []
    var joined: [(invite: String, device: TogetherDevice)] = []
    var pairRegisters: [String] = []
    var pairAccepts: [(device: TogetherDevice, guest: Bool)] = []
    var pairCancels = 0
    var pairSends: [(pairing: String, target: String, id: String?)] = []
    var snapshots: [(positionMs: Int64, playing: Bool, buffering: Bool)] = []
    var loadeds: [(item: String, durationMs: Int64?)] = []
    var loadedItems: [TogetherItem] = []
    var clears = 0
    var playRequests: [TogetherItem] = []
    var plays = 0
    var pauses = 0
    var seeks: [Int64] = []
    var locks: [Bool] = []
    var policies: [TogetherPolicy] = []
    var replies: [(request: String, allowed: Bool, always: Bool)] = []
    var delegates: [(member: String, signKey: String)] = []
    var addOwns: [(member: String, boxKey: String)] = []
    var continueWithouts: [String] = []
    var shareCredentialCalls: [String] = []
    var screens: [Bool] = []
    var leaves = 0
    var closes = 0

    func host(relay: String, device: TogetherDevice) -> Bool { hosted.append((relay, device)); return true }
    func join(invite: String, device: TogetherDevice) -> Bool { joined.append((invite, device)); return true }
    func pairRegister(pairing: String) -> Bool { pairRegisters.append(pairing); return true }
    func pairAccept(device: TogetherDevice, guest: Bool) -> Bool { pairAccepts.append((device, guest)); return true }
    func pairCancel() -> Bool { pairCancels += 1; return true }
    func pairSend(pairing: String, target: String, id: String?) -> Bool { pairSends.append((pairing, target, id)); return true }
    func snapshot(positionMs: Int64, playing: Bool, buffering: Bool) -> Bool {
        snapshots.append((positionMs, playing, buffering)); return true
    }
    func loaded(item: String, durationMs: Int64?) -> Bool { loadeds.append((item, durationMs)); return true }
    func load(item: TogetherItem) -> Bool { loadedItems.append(item); return true }
    func clear() -> Bool { clears += 1; return true }
    func requestPlay(item: TogetherItem) -> Bool { playRequests.append(item); return true }
    func play() -> Bool { plays += 1; return true }
    func pause() -> Bool { pauses += 1; return true }
    func seek(positionMs: Int64) -> Bool { seeks.append(positionMs); return true }
    func lock(_ locked: Bool) -> Bool { locks.append(locked); return true }
    func policy(_ policy: TogetherPolicy) -> Bool { policies.append(policy); return true }
    func reply(request: String, allowed: Bool, always: Bool) -> Bool {
        replies.append((request, allowed, always)); return true
    }
    func delegate(member: String, signKey: String) -> Bool { delegates.append((member, signKey)); return true }
    func addOwn(member: String, boxKey: String) -> Bool { addOwns.append((member, boxKey)); return true }
    func continueWithout(member: String) -> Bool { continueWithouts.append(member); return true }
    func shareCredentials(member: String) -> Bool { shareCredentialCalls.append(member); return true }
    func setScreen(_ screen: Bool) -> Bool { screens.append(screen); return true }
    func leave() -> Bool { leaves += 1; return true }
    func close() { closes += 1 }
}

@MainActor
final class FakeTvBridge: WatchTogetherTvBridge {
    var connectedPlayBridgeTvName: String? = "Living Room Apple TV"
    var connectedPlayBridgeTvIdentity: WatchTogetherConnectedTv? = WatchTogetherConnectedTv(
        name: "Living Room Apple TV",
        uuid: "uuid-tv-1",
        endpointKey: "192.168.1.10:8765"
    )
    var tvSupportsWatchTogether: Bool = true
    var nextResult: WatchTogetherTvJoinResult = WatchTogetherTvJoinResult(
        member: "tv-1", boxKey: "box-pub", signKey: "sign-pub"
    )
    var calls: [(invite: String, from: String)] = []

    func addTvToWatchTogether(invite: String, from: String) async throws -> WatchTogetherTvJoinResult {
        calls.append((invite, from))
        return nextResult
    }
}

private func parseJSON(_ raw: String?) -> [String: Any] {
    guard let raw, let data = raw.data(using: .utf8),
          let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
        fatalError("Invalid JSON: \(raw ?? "nil")")
    }
    return obj
}

@main
struct WatchTogetherTests {
    @MainActor
    static func main() async throws {
        testWatchTogetherLinks()
        testProtocolAndGuestJson()
        testMediaURLChecks()
        testReducerLifecycle()
        try await testPhoneTogetherSessionAndPlayer()
        try await testOwnTvMemberMatchAndAddVisibility()
        testMPVSeekStateTracking()
        testReconnectAndNoPersistence()
        testOpeningInviteLinksWhileInAParty()
        print("PASS: Phone Watch Together checks")
    }

    private static func testWatchTogetherLinks() {
        let room = "0123456789abcdef0123456789abcdef"
        precondition(WatchTogetherLinks.isInvite("https://party.playbridge.app/j/\(room)#k=abc&h=def"))
        precondition(WatchTogetherLinks.isInvite("https://example.com/together/j/\(room)#h=def&k=abc"))
        precondition(
            WatchTogetherLinks.invite("https://party.playbridge.app/j/\(room)?k=abc&h=def")
                == "https://party.playbridge.app/j/\(room)#k=abc&h=def"
        )
        precondition(!WatchTogetherLinks.isInvite("https://party.playbridge.app/j/\(room)"))
        precondition(!WatchTogetherLinks.isInvite("https://party.playbridge.app/j/\(room)#k=abc"))
        precondition(!WatchTogetherLinks.isInvite("https://party.playbridge.app/j/notaroom#k=abc&h=def"))
        precondition(!WatchTogetherLinks.isInvite("https://example.com/watch?v=1"))
        precondition(WatchTogetherLinks.roomId("https://party.playbridge.app/j/\(room)#k=abc&h=def") == room)
        precondition(WatchTogetherLinks.roomId("https://party.playbridge.app/j/\(room)?k=abc&h=def") == room)
        precondition(WatchTogetherLinks.roomId("https://party.playbridge.app/j/\(room)") == nil)
    }

    private static func testProtocolAndGuestJson() {
        let boundary = StubTogetherBoundary()
        let client = try! TogetherClient(native: boundary, onEvent: { _ in })
        precondition(client.pairAccept(device: TogetherDevice(name: "iPhone", kind: .phone, screen: true), guest: true))
        let pairAcceptCmd = parseJSON(boundary.submitted.last)
        precondition(pairAcceptCmd["command"] as? String == "pair_accept")
        precondition(pairAcceptCmd["guest"] as? Bool == true)
        client.close()

        let joinMsg = parseJSON(WireProtocol.watchTogetherJoin(invite: "https://party.playbridge.app/j/r#k=1&h=2", from: "My iPhone"))
        precondition(joinMsg["type"] as? String == "watch_together_join")
        precondition(joinMsg["invite"] as? String == "https://party.playbridge.app/j/r#k=1&h=2")
        precondition(joinMsg["from"] as? String == "My iPhone")

        let limitEvent = TogetherJson.event(from: #"{"event":"stopped","room":"r1","reason":"room_limit","retry_after":120}"#)
        precondition(limitEvent == .stopped(roomId: "r1", reason: .roomLimit, retryAfterSecs: 120))
        precondition(TogetherJson.event(from: #"{"event":"unreachable"}"#) == .unreachable)
    }

    private static func testMediaURLChecks() {
        let pub = PartyMediaURL.evaluate("https://cdn.example.com/video.mp4")
        precondition(pub.normalizedURL == "https://cdn.example.com/video.mp4")
        precondition(pub.rejection == nil)
        precondition(!pub.isPrivateNetwork)

        let lan = PartyMediaURL.evaluate("http://192.168.1.42:8080/stream.m3u8")
        precondition(lan.rejection == nil)
        precondition(lan.isPrivateNetwork)

        let localFile = PartyMediaURL.evaluate("file:///var/mobile/video.mp4")
        precondition(localFile.normalizedURL == nil)
        precondition(localFile.rejection != nil)
    }

    private static func testReducerLifecycle() {
        var party = TogetherPartyState(
            roomId: "r1", invite: "https://party.playbridge.app/j/abc#k=1&h=2",
            relay: "https://party.playbridge.app", me: "m1", host: true, watching: true
        )
        party = PhoneTogetherReducer.reduce(party, event: .member(member: "m1", name: "Phone", kind: .phone, host: true), nowMs: 100)
        party = PhoneTogetherReducer.reduce(party, event: .member(member: "m2", name: "Friend", kind: .phone, host: false), nowMs: 100)
        party.own.insert("tv1")
        party = PhoneTogetherReducer.reduce(party, event: .connected(members: ["m1", "m2"]), nowMs: 120)
        precondition(party.connected && party.members.count == 2)

        let item = TogetherItem(id: "i1", title: "Movie", site: "example.com", source: .url("https://example.com/v.mp4"), startMs: 5_000)
        party = PhoneTogetherReducer.reduce(party, event: .video(item: item), nowMs: 200)
        party = PhoneTogetherReducer.reduce(party, event: .duration(120_000), nowMs: 220)
        party = PhoneTogetherReducer.reduce(party, event: .timeline(item: "i1", playing: true, positionMs: 10_000, rev: 1), nowMs: 1_000)
        precondition(party.positionNow(atMs: 3_500) == 12_500)

        party = PhoneTogetherReducer.reduce(party, event: .playRequested(request: "req1", from: "m2", title: "Clip", site: "site.test"), nowMs: 3_600)
        precondition(party.requests.count == 1)
        party = PhoneTogetherReducer.reduce(party, event: .requestExpired(request: "req1"), nowMs: 3_700)
        precondition(party.requests.isEmpty)

        // Ended for another item preserves active item; matching item clears it.
        party = PhoneTogetherReducer.reduce(party, event: .ended(item: "other"), nowMs: 4_000)
        precondition(party.item?.id == "i1")
        party = PhoneTogetherReducer.reduce(party, event: .ended(item: "i1"), nowMs: 4_100)
        precondition(party.item == nil && !party.playing)
    }

    @MainActor
    private static func testPhoneTogetherSessionAndPlayer() async throws {
        let suite = "PhoneTogetherTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        let session = FakeTogetherSession()
        var engines: [TestAlternativeEngine] = []
        var now: Int64 = 1_000

        let together = PhoneTogether(
            defaults: defaults,
            clockMs: { now },
            sessionFactory: { _ in session },
            playerSessionFactory: { routed, _, resume in
                let engine = TestAlternativeEngine()
                engines.append(engine)
                return PlaybackSession(
                    media: routed, route: .direct, initialResume: resume,
                    preferencesStore: defaults, alternativeFactory: { engine }
                ) { routed }
            }
        )

        // Default watching state follows connected TV presence.
        precondition(together.watchHereByDefault(connectedTvName: nil))
        precondition(!together.watchHereByDefault(connectedTvName: "Apple TV"))

        // Code-pair invite flow MUST accept as guest: true.
        together.showCode(watchHere: true)
        precondition(session.pairRegisters == [PhoneTogether.defaultPairingURL])
        together.handleEventForTesting(.pairingCode(code: "ABCDEF", qr: "q", qrSize: 1, qrModules: "1", expiresAtMs: 99_999))
        together.handleEventForTesting(.pairingInvite(from: "Host Phone", relay: "https://party.playbridge.app"))
        together.acceptInvite()
        precondition(session.pairAccepts.count == 1 && session.pairAccepts[0].guest)

        // Enter room as host with connected TV -> addOwn + delegate.
        together.handleEventForTesting(.room(
            roomId: "room-1", invite: "https://party.playbridge.app/j/0123456789abcdef0123456789abcdef#k=1&h=2",
            relay: "https://party.playbridge.app", member: "me-1", boxKey: "b", signKey: "s", host: true
        ))
        together.handleEventForTesting(.member(member: "me-1", name: "iPhone", kind: .phone, host: true))
        together.handleEventForTesting(.member(member: "friend-1", name: "Friend", kind: .phone, host: false))

        let tvBridge = FakeTvBridge()
        together.addConnectedTv(using: tvBridge)
        try await Task.sleep(nanoseconds: 50_000_000)
        precondition(session.addOwns.contains { $0.member == "tv-1" && $0.boxKey == "box-pub" })
        precondition(session.delegates.contains { $0.member == "tv-1" && $0.signKey == "sign-pub" })
        precondition(together.currentParty?.own.contains("tv-1") == true)

        // Play item with sign-in headers: offered ONLY to non-own peer, NOT to own TV.
        let sent = together.playInParty(
            url: "https://media.example.com/ep1.mp4",
            title: "Episode 1",
            headers: ["Cookie": "session=topsecret"]
        )
        precondition(sent)
        precondition(session.loadedItems.last?.headers["Cookie"] == "session=topsecret")

        let ep1 = TogetherItem(
            id: "item-1", title: "Episode 1", site: "media.example.com",
            source: .url("https://media.example.com/ep1.mp4"), startMs: 0
        )
        together.handleEventForTesting(.video(item: ep1))
        precondition(!together.canOfferShareSignIn(for: "me-1"))
        precondition(!together.canOfferShareSignIn(for: "tv-1"))
        precondition(together.canOfferShareSignIn(for: "friend-1"))
        together.shareSignIn(member: "friend-1")
        precondition(session.shareCredentialCalls == ["friend-1"])
        precondition(!together.canOfferShareSignIn(for: "friend-1"))

        // Player opens from .player(.load), reports loaded once ready, reuses session on replace, closes on EOF keeping watching=true.
        together.handleEventForTesting(.player(.load(item: ep1, headers: ["Cookie": "session=topsecret"], startMs: 12_000)))
        precondition(together.activePlayerItem?.itemID == "item-1")
        precondition(engines.count == 1 && engines[0].loads.last?.2 == 12.0 && engines[0].loads.last?.3 == false)

        engines[0].onState?(PhonePlaybackState(position: 12.0, duration: 90.0, paused: true, buffering: false, loaded: true))
        try await Task.sleep(nanoseconds: 30_000_000)
        precondition(session.loadeds.contains { $0.item == "item-1" && $0.durationMs == 90_000 })

        // User transport through PlaybackSession routes to party client, not local engine directly.
        together.activePlayerItem?.session.play()
        together.activePlayerItem?.session.pause()
        together.activePlayerItem?.session.seek(to: 45)
        precondition(session.plays == 1 && session.pauses == 1 && session.seeks == [45_000])

        // Replacement reuses the same PlaybackSession.
        let ep2 = TogetherItem(id: "item-2", title: "Episode 2", site: "media.example.com", source: .url("https://media.example.com/ep2.mp4"), startMs: 0)
        together.handleEventForTesting(.video(item: ep2))
        together.handleEventForTesting(.player(.load(item: ep2, headers: [:], startMs: 0)))
        precondition(engines.count == 1 && together.activePlayerItem?.itemID == "item-2")

        // EOF closes player without disabling watching!
        together.handleEventForTesting(.ended(item: "item-2"))
        precondition(together.activePlayerItem == nil)
        precondition(together.currentParty?.watching == true)
        together.userClosedPlayer()
        precondition(session.screens.isEmpty)

        // Manual Stop & user dismissal turning off Watch here.
        together.handleEventForTesting(.player(.load(item: ep1, headers: [:], startMs: 0)))
        precondition(together.activePlayerItem != nil)
        together.userClosedPlayer()
        precondition(session.screens == [false] && together.currentParty?.watching == false)

        now += 100
        _ = together.stopVideo()
        precondition(session.clears == 1)

        together.leave()
        precondition(session.leaves == 1 && together.state == .idle)
    }

    @MainActor
    private static func testOwnTvMemberMatchAndAddVisibility() async throws {
        let addedTv = WatchTogetherConnectedTv(
            name: "Living Room Apple TV",
            uuid: "uuid-tv-1",
            endpointKey: "192.168.1.10:8765"
        )
        let otherSameNameTv = WatchTogetherConnectedTv(
            name: "Living Room Apple TV",
            uuid: "uuid-tv-2",
            endpointKey: "192.168.1.11:8765"
        )
        let renamedSameUuid = WatchTogetherConnectedTv(
            name: "Bedroom Apple TV",
            uuid: "uuid-tv-1",
            endpointKey: "192.168.1.50:8765"
        )

        var party = TogetherPartyState(
            roomId: "room-tv",
            invite: "https://party.playbridge.app/j/0123456789abcdef0123456789abcdef#k=a&h=b",
            relay: "https://party.playbridge.app",
            me: "m-phone",
            host: true
        )
        party.own = ["m-tv-1"]
        party.members = [
            "m-tv-1": TogetherPartyMember(id: "m-tv-1", name: "Living Room Apple TV", kind: .tv, host: false),
            "m-guest-tv": TogetherPartyMember(id: "m-guest-tv", name: "Guest TV", kind: .tv, host: false),
        ]
        let lanIdentities = ["m-tv-1": addedTv]

        // Exact LAN match succeeds, duplicate-name TV does not collide, renamed TV with same UUID matches.
        precondition(PhoneTogether.isOwnTvMemberMatch(device: addedTv, party: party, ownTvIdentities: lanIdentities))
        precondition(!PhoneTogether.isOwnTvMemberMatch(device: otherSameNameTv, party: party, ownTvIdentities: lanIdentities))
        precondition(PhoneTogether.isOwnTvMemberMatch(device: renamedSameUuid, party: party, ownTvIdentities: lanIdentities))

        // UUID-less endpoint match distinguishes same-name TVs by ip:port.
        let endpointTv1 = WatchTogetherConnectedTv(name: "Living Room Apple TV", uuid: "", endpointKey: "192.168.1.10:8765")
        let endpointTv2 = WatchTogetherConnectedTv(name: "Living Room Apple TV", uuid: "", endpointKey: "192.168.1.11:8765")
        precondition(PhoneTogether.isOwnTvMemberMatch(device: endpointTv1, party: party, ownTvIdentities: lanIdentities))
        precondition(!PhoneTogether.isOwnTvMemberMatch(device: endpointTv2, party: party, ownTvIdentities: lanIdentities))

        // Guest TV sharing the name (not in party.own) does not match.
        let guestNamedTv = WatchTogetherConnectedTv(name: "Guest TV", uuid: "uuid-guest", endpointKey: "192.168.1.30:8765")
        precondition(!PhoneTogether.isOwnTvMemberMatch(device: guestNamedTv, party: party, ownTvIdentities: lanIdentities))

        // After memberLeft removes the TV, button becomes available again.
        let afterLeave = PhoneTogetherReducer.reduce(party, event: .memberLeft(member: "m-tv-1"), nowMs: 100)
        precondition(!PhoneTogether.isOwnTvMemberMatch(device: addedTv, party: afterLeave, ownTvIdentities: [:]))

        // Pairing-code fallback (in party.own with no LAN identity recorded) matches saved TV by name.
        let pairedTv = WatchTogetherConnectedTv(name: "Living Room Apple TV")
        var pairingParty = party
        pairingParty.own = ["m-paired-tv"]
        pairingParty.members = [
            "m-paired-tv": TogetherPartyMember(id: "m-paired-tv", name: "Living Room Apple TV", kind: .tv, host: false),
        ]
        precondition(PhoneTogether.isOwnTvMemberMatch(device: pairedTv, party: pairingParty, ownTvIdentities: [:]))
        precondition(!PhoneTogether.isOwnTvMemberMatch(
            device: WatchTogetherConnectedTv(name: "Other TV"),
            party: pairingParty,
            ownTvIdentities: [:]
        ))

        // End-to-end coordinator add -> hide -> memberLeft -> show again.
        let suite = "PhoneTogetherAddTv.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let together = PhoneTogether(defaults: defaults, sessionFactory: { _ in FakeTogetherSession() })
        together.handleEventForTesting(.room(
            roomId: "r-tv",
            invite: "https://party.playbridge.app/j/0123456789abcdef0123456789abcdef#k=a&h=b",
            relay: "https://party.playbridge.app",
            member: "me",
            boxKey: "b",
            signKey: "s",
            host: true
        ))
        let bridge = FakeTvBridge()
        precondition(!together.isConnectedTvInParty(using: bridge))
        together.addConnectedTv(using: bridge)
        try await Task.sleep(nanoseconds: 30_000_000)
        together.handleEventForTesting(.member(member: "tv-1", name: "Living Room Apple TV", kind: .tv, host: false))
        precondition(together.isConnectedTvInParty(using: bridge))

        // Duplicate-name TV still shows Add button.
        let duplicateBridge = FakeTvBridge()
        duplicateBridge.connectedPlayBridgeTvIdentity = otherSameNameTv
        precondition(!together.isConnectedTvInParty(using: duplicateBridge))

        // Once TV leaves, Add button returns.
        together.handleEventForTesting(.memberLeft(member: "tv-1"))
        precondition(!together.isConnectedTvInParty(using: bridge))

        // Connected desktop PlayBridge receiver (.desktop):
        // - Room starts with self in members (count == 1, .phone, host == true)
        // - Adding desktop hides the Add button and brings count to 2
        // - Defensive: memberLeft(me) never removes self
        // - Strict name fallback accepts .tv / .desktop in own, rejects .phone / .cli
        precondition(together.currentParty?.members.count == 1)
        precondition(together.currentParty?.members["me"]?.kind == .phone)
        precondition(together.currentParty?.members["me"]?.host == true)
        together.handleEventForTesting(.memberLeft(member: "me"))
        precondition(together.currentParty?.members["me"] != nil)

        let desktopIdentity = WatchTogetherConnectedTv(
            name: "Atuls-MacBook-Air.local",
            uuid: "uuid-desktop-1",
            endpointKey: "192.168.1.40:8765"
        )
        let desktopBridge = FakeTvBridge()
        desktopBridge.connectedPlayBridgeTvName = desktopIdentity.name
        desktopBridge.connectedPlayBridgeTvIdentity = desktopIdentity
        precondition(!together.isConnectedTvInParty(using: desktopBridge))
        together.addConnectedTv(using: desktopBridge)
        try await Task.sleep(nanoseconds: 30_000_000)
        // Before MemberInfo arrives, recorded identity already hides Add button.
        precondition(together.isConnectedTvInParty(using: desktopBridge))
        together.handleEventForTesting(.member(member: "tv-1", name: desktopIdentity.name, kind: .desktop, host: false))
        precondition(together.isConnectedTvInParty(using: desktopBridge))
        precondition(together.currentParty?.members.count == 2)
        let ordered = together.currentParty?.orderedMembers ?? []
        precondition(ordered.map(\.id) == ["me", "tv-1"])
        precondition(together.toastMessage == "Atuls-MacBook-Air.local joined the party")

        // After desktop leaves, Add button comes back and count drops back to 1.
        together.handleEventForTesting(.memberLeft(member: "tv-1"))
        precondition(!together.isConnectedTvInParty(using: desktopBridge))
        precondition(together.currentParty?.members.count == 1)

        // Pairing-code name fallback: accepts .desktop and .tv, rejects .phone and .cli even if in own.
        var fallbackParty = party
        fallbackParty.own = ["m-desk", "m-phone-own", "m-cli-own"]
        fallbackParty.members = [
            "m-desk": TogetherPartyMember(id: "m-desk", name: "Studio Mac", kind: .desktop, host: false),
            "m-phone-own": TogetherPartyMember(id: "m-phone-own", name: "Same Name Phone", kind: .phone, host: false),
            "m-cli-own": TogetherPartyMember(id: "m-cli-own", name: "Same Name CLI", kind: .cli, host: false),
        ]
        precondition(PhoneTogether.isOwnTvMemberMatch(
            device: WatchTogetherConnectedTv(name: "Studio Mac"),
            party: fallbackParty,
            ownTvIdentities: [:]
        ))
        precondition(!PhoneTogether.isOwnTvMemberMatch(
            device: WatchTogetherConnectedTv(name: "Same Name Phone"),
            party: fallbackParty,
            ownTvIdentities: [:]
        ))
        precondition(!PhoneTogether.isOwnTvMemberMatch(
            device: WatchTogetherConnectedTv(name: "Same Name CLI"),
            party: fallbackParty,
            ownTvIdentities: [:]
        ))

        // Add fallback label when connectedPlayBridgeTvName is nil never uses "TV".
        let namelessBridge = FakeTvBridge()
        namelessBridge.connectedPlayBridgeTvName = nil
        namelessBridge.connectedPlayBridgeTvIdentity = nil
        together.addConnectedTv(using: namelessBridge)
        try await Task.sleep(nanoseconds: 30_000_000)
        precondition(together.toastMessage == "That device joined the party")
    }

    private static func testMPVSeekStateTracking() {
        var seek = PhoneMPVSeekState()
        // Normal paused playback without an active seek never reports buffering (prevents sync deadlock).
        let pausedIdle = seek.sample(mpvSeeking: false, timePos: 12.0, paused: true, pausedForCache: true, now: 1.0)
        precondition(pausedIdle.position == 12.0 && pausedIdle.buffering == false)

        // Playing cache stall reports buffering.
        let playingStall = seek.sample(mpvSeeking: false, timePos: 12.0, paused: false, pausedForCache: true, now: 1.1)
        precondition(playingStall.position == 12.0 && playingStall.buffering == true)

        // When seek(to: 47.0) runs and mpv has stale time-pos=0 and paused-for-cache=false while seeking:
        // position stays at 47.0 and buffering stays true across >1s network wait so engine doesn't re-seek loop.
        seek.beginSeek(to: 47.0, now: 2.0)
        seek.markMPVSeekingEvent()
        let duringSlowSeek1 = seek.sample(mpvSeeking: true, timePos: 0.0, paused: true, pausedForCache: false, now: 3.2)
        precondition(duringSlowSeek1.position == 47.0 && duringSlowSeek1.buffering == true)
        let duringSlowSeek2 = seek.sample(mpvSeeking: true, timePos: 0.0, paused: false, pausedForCache: false, now: 4.5)
        precondition(duringSlowSeek2.position == 47.0 && duringSlowSeek2.buffering == true)

        // Once MPV_EVENT_PLAYBACK_RESTART fires, seek completes and live position + buffering resume.
        seek.finishSeek()
        let settled = seek.sample(mpvSeeking: false, timePos: 47.1, paused: false, pausedForCache: false, now: 4.6)
        precondition(settled.position == 47.1 && settled.buffering == false)

        // Guard: if mpv ignores seek (never enters seeking), target clears after 0.75s so buffering can't wedge.
        seek.beginSeek(to: 80.0, now: 5.0)
        let pendingBeforeTimeout = seek.sample(mpvSeeking: false, timePos: 48.0, paused: false, pausedForCache: false, now: 5.2)
        precondition(pendingBeforeTimeout.position == 80.0 && pendingBeforeTimeout.buffering == true)
        let expiredIgnored = seek.sample(mpvSeeking: false, timePos: 48.0, paused: false, pausedForCache: false, now: 5.9)
        precondition(expiredIgnored.position == 48.0 && expiredIgnored.buffering == false)

        // Guard: even if mpv's seeking property stays true indefinitely, maxSeekDurationSeconds caps buffering.
        seek.beginSeek(to: 120.0, now: 10.0)
        let midLongSeek = seek.sample(mpvSeeking: true, timePos: 48.0, paused: false, pausedForCache: false, now: 20.0)
        precondition(midLongSeek.position == 120.0 && midLongSeek.buffering == true)
        let cappedLongSeek = seek.sample(mpvSeeking: true, timePos: 48.0, paused: false, pausedForCache: false, now: 25.5)
        precondition(cappedLongSeek.position == 48.0 && cappedLongSeek.buffering == false)

        // Header sanitizer for mpv http-header-fields: strips Range/If-Range/User-Agent/Host/Sec-* while keeping Cookie & Referer.
        let filtered = PhoneMPVHTTPHeaders.filteredEntries(from: [
            "Referer": "https://standin.example/",
            "Cookie": "session=abc, def=1",
            "User-Agent": "CustomUA/1.0",
            "Range": "bytes=0-",
            "if-range": "\"etag\"",
            "Sec-Fetch-Mode": "no-cors",
            "Host": "cdn.example.com",
            "Accept-Encoding": "gzip",
        ])
        precondition(filtered == [
            "Cookie: session=abc, def=1",
            "Referer: https://standin.example/",
        ])
        precondition(PhoneMPVHTTPHeaders.userAgent(from: ["user-agent": "CustomUA/1.0"]) == "CustomUA/1.0")
        precondition(PhoneMPVHTTPHeaders.filteredEntries(from: ["Cookie": "a=1\r\nEvil: 1"]) == nil)

        // Merging item + command headers keeps non-secret item headers while letting command secrets win.
        let merged = PhoneTogether.mergedItemHeaders(
            itemHeaders: ["Referer": "https://standin.example/", "User-Agent": "Mozilla/5.0"],
            commandHeaders: ["Cookie": "session=secret", "referer": "https://override.example/"]
        )
        precondition(merged["Cookie"] == "session=secret")
        precondition(merged["User-Agent"] == "Mozilla/5.0")
        precondition(merged["referer"] == "https://override.example/")
        precondition(merged["Referer"] == nil)
    }

    @MainActor
    private static func testReconnectAndNoPersistence() {
        let suite = "PhoneTogetherReconnect.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        let session = FakeTogetherSession()
        let together = PhoneTogether(defaults: defaults, sessionFactory: { _ in session })
        let inviteURL = URL(string: "https://party.playbridge.app/j/0123456789abcdef0123456789abcdef?k=abc&h=def")!
        precondition(together.handleIncomingURL(inviteURL, connectedTvName: nil))
        precondition(together.pendingOpenScreen)
        together.consumePendingOpenScreen()
        precondition(!together.pendingOpenScreen)

        together.handleEventForTesting(.room(
            roomId: "room-bg", invite: "https://party.playbridge.app/j/0123456789abcdef0123456789abcdef#k=abc&h=def",
            relay: "https://party.playbridge.app", member: "me", boxKey: "b", signKey: "s", host: false
        ))
        together.handleEventForTesting(.connected(members: ["me"]))

        together.applicationEnteredBackground()
        together.handleEventForTesting(.disconnected)
        together.applicationBecameActive()
        precondition(together.currentParty?.connected == false)

        together.handleEventForTesting(.connected(members: ["me"]))
        precondition(together.currentParty?.connected == true)
        precondition(together.currentParty?.notice == "Reconnected to the watch party")

        // Unreachable marks reconnectFailed cleanly with Leave still available.
        together.handleEventForTesting(.unreachable)
        precondition(together.currentParty?.reconnectFailed == true)

        // App kill / new instance starts strictly idle with no persisted party or auto-rejoin.
        together.setRelayURL("https://custom.relay.example")
        let relaunched = PhoneTogether(defaults: defaults, sessionFactory: { _ in FakeTogetherSession() })
        precondition(relaunched.state == .idle)
        precondition(!relaunched.isInParty)
        precondition(relaunched.relayURL == "https://custom.relay.example")
    }

    @MainActor
    private static func testOpeningInviteLinksWhileInAParty() {
        let suite = "PhoneTogetherOpenInvite.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        let session = FakeTogetherSession()
        let together = PhoneTogether(defaults: defaults, sessionFactory: { _ in session })
        let room = "0123456789abcdef0123456789abcdef"
        let link = "https://party.playbridge.app/j/\(room)#k=abc&h=def"
        together.handleEventForTesting(.room(
            roomId: room, invite: link, relay: "https://party.playbridge.app",
            member: "me", boxKey: "b", signKey: "s", host: true
        ))
        together.handleEventForTesting(.connected(members: ["me"]))

        // This party's own link, in the form the invite page hands over: stays put, as host.
        let own = URL(string: "https://party.playbridge.app/j/\(room)?k=abc&h=def")!
        precondition(together.handleIncomingURL(own, connectedTvName: nil))
        precondition(session.joined.isEmpty)
        precondition(together.switchRequest == nil)
        precondition(together.currentParty?.host == true)

        // Another party's link asks first; staying keeps this party.
        let other = "https://party.playbridge.app/j/fedcba9876543210fedcba9876543210#k=xyz&h=uvw"
        precondition(together.handleIncomingURL(URL(string: other)!, connectedTvName: nil))
        precondition(together.switchRequest?.invite == other)
        precondition(session.joined.isEmpty)
        together.cancelSwitch()
        precondition(together.switchRequest == nil)
        precondition(together.currentParty?.roomId == room)

        // Confirming leaves this party and joins the other.
        precondition(together.handleIncomingURL(URL(string: other)!, connectedTvName: nil))
        together.confirmSwitch()
        precondition(together.switchRequest == nil)
        precondition(session.joined.map(\.0) == [other])
        precondition(together.state == .starting)
    }
}

private final class StubTogetherBoundary: TogetherNativeBoundary {
    var submitted: [String] = []
    var isAvailable: Bool { true }
    func abiVersion() -> UInt32 { TogetherClient.expectedAbiVersion }
    func start() -> OpaquePointer? { OpaquePointer(bitPattern: 0x101) }
    func submitJson(handle: OpaquePointer, commandJson: String) -> Bool {
        submitted.append(commandJson)
        return true
    }
    func nextEvent(handle: OpaquePointer, waitMs: UInt64) -> String? { nil }
    func cancel(handle: OpaquePointer) {}
    func free(handle: OpaquePointer) {}
}
